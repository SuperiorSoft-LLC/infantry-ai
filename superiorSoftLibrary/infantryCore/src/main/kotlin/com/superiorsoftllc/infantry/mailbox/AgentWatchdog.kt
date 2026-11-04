// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.daemon.api.attention.AttentionKinds
import com.superiorsoftllc.infantry.daemon.api.attention.Attention
import com.superiorsoftllc.infantry.session.AgentWatch
import com.superiorsoftllc.infantry.session.ReportStatus
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState
import com.superiorsoftllc.infantry.session.WatchState
import com.superiorsoftllc.infantry.session.WatchdogConfig
import com.superiorsoftllc.infantry.session.isRunning
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * PIPELINE-WATCHDOG: tells "idle with nothing to do" from "idle but should be doing something".
 *
 * Per session it derives an [AgentWatch] (state, oldest unanswered item, last activity) from the mailbox and the session's
 * own record, stores it on the record so every client sees it, and for a STALLED or UNREPORTED agent walks an escalation
 * ladder: nudge mails (at most one per interval, capped), then ONE batched mail to the PM role (which wakes an idle PM
 * through the router's pump, the same path any mail takes), then one attention item for the owner. A QUIET agent (busy, but
 * silent) is only reported to the PM, once. Nothing happens to an agent with nothing pending. State lives in memory: a daemon
 * restart starts the ladder again. Per project it is opt-in ([isEnabledFor], default off in the daemon): an off project's
 * sessions are shown IDLE and nothing is sent or raised for them.
 *
 * A pending item is a mail addressed to the session (by id, agent id or role) since it was spawned, from anyone but
 * `system` or a `trigger:` sender (so a nudge never makes more work and never bounces), that is not answered and that the session has not mailed past.
 */
class AgentWatchdog(
    private val sessions: SessionManager,
    private val mailbox: Mailbox,
    private val send: (MailSendRequest) -> MailResult,
    private val attention: Attention,
    private val config: WatchdogConfig = WatchdogConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** The role that is told first. */
    private val pmRole: String = "pm",
    /** Per-project opt-in, read at every tick (a flip needs no restart). A project that is off gets no watchdog action at all. */
    private val isEnabledFor: (projectId: String?) -> Boolean = { true },
) {
    private class Episode(val key: String) {
        var nudges = 0
        var lastNudgeAt = 0L

        /** 0 = not told, [PM_PENDING] = queued in this tick's batch, otherwise when the PM was mailed. */
        var pmNotifiedAt = 0L
        var quietReported = false
        var ownerRaised = false
    }

    private val episodes = HashMap<String, Episode>()
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "infantry-agent-watchdog").also { it.isDaemon = true } }

    fun start() {
        if (!config.enabled) return
        executor.scheduleWithFixedDelay(
            { runCatching { tick() }.onFailure { System.err.println("[infantry] agent watchdog tick failed: ${it.message}") } },
            config.pollMs, config.pollMs, TimeUnit.MILLISECONDS,
        )
    }

    fun close() { executor.shutdownNow() }

    private data class Pending(val key: String, val since: Long, val summary: String)

    private data class Verdict(val session: SessionRecord, val watch: AgentWatch, val key: String?)

    @Synchronized
    fun tick() {
        val now = clock()
        val verdicts = sessions.list().map {
            if (isEnabledFor(it.projectId)) evaluate(it, now) else Verdict(it, AgentWatch(WatchState.IDLE.name, null, null, it.lastHeartbeat), null)
        }
        episodes.keys.retainAll(verdicts.filter { it.key != null }.map { it.session.id }.toSet())

        val toPm = ArrayList<String>()
        val updated = ArrayList<Verdict>()
        for (v in verdicts) {
            val state = WatchState.valueOf(v.watch.state)
            var watch = v.watch
            val ep = v.key?.let { key ->
                episodes[v.session.id]?.takeIf { it.key == key } ?: Episode(key).also { episodes[v.session.id] = it }
            }
            if (ep != null && (state == WatchState.STALLED || state == WatchState.UNREPORTED)) {
                val isPm = v.session.role.equals(pmRole, ignoreCase = true)
                if (ep.pmNotifiedAt == 0L && ep.nudges >= config.pmAfterNudges && now - ep.lastNudgeAt >= config.nudgeIntervalMs) {
                    if (isPm) {
                        ep.pmNotifiedAt = now // the PM cannot escalate to itself: straight to the owner after one more interval
                    } else {
                        ep.pmNotifiedAt = PM_PENDING
                        toPm += line(v.session, watch, now)
                    }
                }
                if (ep.nudges < config.nudgeCap && now - ep.lastNudgeAt >= config.nudgeIntervalMs && nudge(v.session, watch)) {
                    ep.nudges++
                    ep.lastNudgeAt = now
                }
                if (ep.pmNotifiedAt > 0L && !ep.ownerRaised && now - ep.pmNotifiedAt >= config.nudgeIntervalMs) {
                    ep.ownerRaised = true
                    attention.raise(
                        kind = AttentionKinds.STALLED,
                        ticketId = v.session.ticketId,
                        sessionId = v.session.id,
                        outcome = "watchdog_" + state.name.lowercase(),
                        detail = "Agent ${label(v.session)} is ${state.name} since " +
                            "${com.superiorsoftllc.infantry.DisplayZone.stamp(watch.pendingSince ?: watch.lastActivityAt)}: ${watch.pending ?: "no report mail"}; " +
                            "${ep.nudges} nudge(s) and the PM did not resolve it.",
                    )
                }
            } else if (ep != null && state == WatchState.QUIET && !ep.quietReported) {
                ep.quietReported = true
                toPm += line(v.session, watch, now)
            }
            if (ep != null) watch = watch.copy(nudges = ep.nudges)
            updated += v.copy(watch = watch)
        }
        if (toPm.isNotEmpty()) {
            val sent = send(
                MailSendRequest(
                    from = SYSTEM, to = pmRole, subject = PM_SUBJECT,
                    body = "Watchdog ${com.superiorsoftllc.infantry.DisplayZone.stamp(now)}: ${toPm.size} agent(s) need a look.\n" + toPm.joinToString("\n") { "- $it" },
                ),
            )
            val stamp = if (sent is MailResult.Sent) now else 0L
            for (ep in episodes.values) if (ep.pmNotifiedAt == PM_PENDING) ep.pmNotifiedAt = stamp
        }
        for (v in updated) sessions.setWatch(v.session.id, v.watch)
    }

    private fun label(s: SessionRecord) = "${s.role ?: "-"}/${s.agentId ?: s.id.take(8)}"

    private fun line(s: SessionRecord, w: AgentWatch, now: Long): String {
        val since = w.pendingSince ?: w.lastActivityAt
        return "${label(s)} ${w.state} for ${(now - since) / 60_000} min (last activity ${com.superiorsoftllc.infantry.DisplayZone.stamp(w.lastActivityAt)}): ${w.pending ?: "no item"}"
    }

    private fun nudge(s: SessionRecord, w: AgentWatch): Boolean {
        val since = w.pendingSince?.let { com.superiorsoftllc.infantry.DisplayZone.stamp(it) } ?: "your last run"
        val body = if (w.state == WatchState.UNREPORTED.name) {
            "You finished without telling anyone (pending since $since): ${w.pending}. Reply with status or finish it: mail your report to the role that started you."
        } else {
            "You have a pending item since $since: ${w.pending}. Reply with status or finish it."
        }
        return send(MailSendRequest(from = SYSTEM, to = s.id, subject = NUDGE_SUBJECT, body = body)) is MailResult.Sent
    }

    private fun evaluate(s: SessionRecord, now: Long): Verdict {
        val running = s.isRunning()
        val atRest = s.state == SessionState.IDLE
        val pending = if (running || atRest) pendingOf(s, now) else null
        fun watch(state: WatchState, p: Pending? = pending) = AgentWatch(state.name, p?.since, p?.summary, s.lastHeartbeat)
        if (s.report?.status == ReportStatus.UNREPORTED && !running) {
            val p = Pending(
                "unreported:${s.id}:${s.runCount}", s.lastHeartbeat,
                "finished without a report mail" + (s.ticketId?.let { " (ticket $it)" } ?: ""),
            )
            return Verdict(s, watch(WatchState.UNREPORTED, p), p.key)
        }
        if (running) {
            val state = if (now - s.lastHeartbeat >= config.quietMs) WatchState.QUIET else WatchState.WORKING
            return Verdict(s, watch(state), pending?.key ?: "run:${s.id}")
        }
        if (!atRest) return Verdict(s, AgentWatch(WatchState.IDLE.name, null, null, s.lastHeartbeat), null)
        if (pending != null) {
            val idleSince = maxOf(pending.since, s.lastHeartbeat)
            val state = if (now - idleSince >= config.stallMs) WatchState.STALLED else WatchState.WAITING
            return Verdict(s, watch(state), pending.key)
        }
        val blocked = mailbox.waiting().firstOrNull { it.session == s.id }
        if (blocked != null) {
            val p = Pending(blocked.messageId, blocked.since, "waiting for a reply from ${blocked.waitingOn}: ${blocked.subject}")
            return Verdict(s, watch(WatchState.WAITING, p), null)
        }
        return Verdict(s, watch(WatchState.IDLE), null)
    }

    private fun pendingOf(s: SessionRecord, now: Long): Pending? {
        val addresses = listOfNotNull(s.id, s.agentId, s.role).toSet()
        val sent = mailbox.outbox(s.id)
        val item = addresses.flatMap { mailbox.inbox(it) }.distinctBy { it.id }
            .filter { it.from != SYSTEM && !it.from.startsWith(TRIGGER_PREFIX) && it.sentAt >= s.spawnedAt && now - it.sentAt <= config.maxPendingAgeMs }
            .filter { it.state != MessageState.ANSWERED && it.state != MessageState.UNDELIVERABLE }
            .filter { m -> sent.none { it.sentAt > m.sentAt } }
            .minByOrNull { it.sentAt } ?: return null
        return Pending(item.id, item.sentAt, "mail from ${item.from}: ${item.subject.ifBlank { item.body.take(60) }}")
    }

    companion object {
        const val SYSTEM = "system"

        /** A trigger's mail cannot be answered (a reply bounces), so it is never a pending item. */
        const val TRIGGER_PREFIX = "trigger:"
        const val NUDGE_SUBJECT = "WATCHDOG nudge"
        const val PM_SUBJECT = "WATCHDOG stalled agents"
        private const val PM_PENDING = -1L
    }
}
