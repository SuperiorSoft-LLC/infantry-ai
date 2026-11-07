// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerEvent
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHub
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool
import java.util.concurrent.ConcurrentHashMap

/**
 * RUNNER-POOL-8 (docs/RUNNER-POOL.md 3.1 and 5): the orchestrator side of the runners' event outboxes, and what happens while a
 * runner is gone. [tick] runs after each pool heartbeat round:
 *
 * * **Replay, dedupe.** For every remote runner with sessions placed on it, `events(acked)` acknowledges what was applied and reads
 *   what the runner still keeps. An event is applied once, in seq order per session ([apply]); a seq at or below the last applied
 *   one is a duplicate and skipped; a seq further on than the next one is a gap (the runner's outbox overflowed) and logged. The
 *   acknowledgement rides on the next read, so an answer lost on the wire is answered again, never skipped.
 * * **Offline.** A runner whose heartbeat stopped answering ([RunnerHub.silentSince]) is offline: nothing is read from it, nothing
 *   is reaped (the orchestrator holds no record of a remote session, and the reaper only reaps records), and mail to its sessions
 *   stays queued in the mailbox ([MailRouter] retries a QUEUED delivery). When it answers again, its events replay and [reconnected]
 *   is told its sessions (the daemon pumps their queued mail).
 * * **Lost.** Silent with sessions on it for [lostAfterMs] (`limits.runners.lostAfterMin`): one attention item per outage
 *   ([attention]); with [restartElsewhere] on (`runners.onLost = restart-elsewhere`) each of its tickets starts again via placement on
 *   another runner ([restart]), and the session is no longer tracked on the lost runner.
 */
class RunnerWatch(
    private val pool: RunnerHub,
    private val lostAfterMs: () -> Long,
    private val restartElsewhere: () -> Boolean,
    /** Apply one new event of a session on [runner]. */
    private val apply: (runner: String, event: RunnerEvent) -> Unit,
    /** Runner [runner] answers again after it was offline; [sessions] are the ones placed on it. */
    private val reconnected: (runner: String, sessions: List<String>) -> Unit = { _, _ -> },
    /** Raise the lost-runner item: [sessions] on it, the [detail] line. */
    private val attention: (runner: String, sessions: List<String>, detail: String) -> Unit = { _, _, _ -> },
    /** The ticket session [sessionId] works on; null = none (a spawn, not restarted). */
    private val ticketOf: (sessionId: String) -> String? = { null },
    /** Start ticket [ticketId] again, not on [lostRunner]; null = started, else the refusal. */
    private val restart: (ticketId: String, lostRunner: String, problem: String) -> String? = { _, _, _ -> "no board" },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { println(it) },
) {
    /** session -> the highest seq applied. */
    private val applied = ConcurrentHashMap<String, Long>()
    /** Runners seen offline (with sessions on them) since their last good read. */
    private val offline = ConcurrentHashMap.newKeySet<String>()
    /** Runners whose current outage already raised the lost item. */
    private val alerted = ConcurrentHashMap.newKeySet<String>()

    /** One round over the remote runners that hold sessions placed by this orchestrator. */
    @Synchronized
    fun tick() {
        val now = clock()
        for (entry in pool.runners()) {
            val runner = entry.name
            if (runner == RunnerPool.LOCAL) continue
            val sessions = pool.sessionsOn(runner)
            if (sessions.isEmpty()) {
                offline -= runner
                alerted -= runner
                continue
            }
            val silent = pool.silentSince(runner)
            if (silent != null) {
                offline += runner
                if (now - silent >= lostAfterMs() && alerted.add(runner)) lost(runner, sessions, now - silent)
                continue
            }
            if (!read(runner, sessions)) {
                offline += runner
                continue
            }
            if (offline.remove(runner)) {
                alerted -= runner
                log("[infantry] runner '$runner' is back: events replayed, queued mail of ${sessions.size} session(s) delivered again")
                runCatching { reconnected(runner, sessions) }.onFailure { log("[infantry] runner '$runner' reconnect handling failed: ${it.message}") }
            }
        }
    }

    /** Whether runner [runner] is offline as this watch saw it last (silent, or its events read failed). */
    fun isOffline(runner: String): Boolean = runner in offline

    /** The highest seq applied for [sessionId] (0 = none). */
    fun appliedSeq(sessionId: String): Long = applied[sessionId] ?: 0L

    /** Acknowledge and read [runner]'s outbox; false = it did not answer. */
    private fun read(runner: String, sessions: List<String>): Boolean {
        val link = pool.link(runner) ?: return false
        val acked = sessions.associateWith { applied[it] ?: 0L }.filterValues { it > 0 }
        val reply = runCatching { link.events(acked) }.getOrElse { OrchestratorReply.Unavailable(it.message ?: "events failed") }
        val events = (reply as? OrchestratorReply.Ok)?.value ?: return false
        val mine = sessions.toSet()
        for (e in events.filter { it.sessionId in mine }.sortedWith(compareBy({ it.sessionId }, { it.seq }))) {
            val last = applied[e.sessionId] ?: 0L
            if (e.seq <= last) continue
            if (e.seq > last + 1) log("[infantry] runner '$runner': events ${last + 1}..${e.seq - 1} of session ${e.sessionId} were dropped by its outbox (limits.runners.outboxMax)")
            runCatching { apply(runner, e) }.onFailure { log("[infantry] runner '$runner': event ${e.kind} #${e.seq} of ${e.sessionId} not applied: ${it.message}") }
            applied[e.sessionId] = e.seq
        }
        return true
    }

    private fun lost(runner: String, sessions: List<String>, silentMs: Long) {
        val minutes = silentMs / 60_000L
        val restarting = restartElsewhere()
        val detail = buildString {
            append("Runner '").append(runner).append("' has been silent for ").append(minutes).append(" min with ")
            append(sessions.size).append(" worker session(s) on it; their tickets stay in progress.")
            if (restarting) append(" runners.onLost = restart-elsewhere: each ticket starts again on another runner from its last pushed branch; work not pushed from '")
                .append(runner).append("' is lost.")
            else append(" Bring it back, or set runners.onLost = restart-elsewhere (work not pushed from it is lost).")
        }
        runCatching { attention(runner, sessions, detail) }.onFailure { log("[infantry] runner '$runner' lost attention failed: ${it.message}") }
        log("[infantry] $detail")
        if (!restarting) return
        for (sessionId in sessions) {
            val ticket = ticketOf(sessionId) ?: continue
            val refusal = runCatching { restart(ticket, runner, "runner $runner lost (silent $minutes min)") }.getOrElse { it.message ?: "restart failed" }
            if (refusal == null) {
                pool.forget(sessionId)
                applied.remove(sessionId)
                log("[infantry] ticket $ticket restarted off lost runner '$runner'")
            } else {
                log("[infantry] ticket $ticket not restarted off lost runner '$runner': $refusal")
            }
        }
    }
}
