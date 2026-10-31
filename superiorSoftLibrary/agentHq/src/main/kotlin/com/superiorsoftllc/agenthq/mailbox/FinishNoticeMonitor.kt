package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.persistence.TicketRow
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import com.superiorsoftllc.agenthq.settings.LimitRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The daemon tells the starter of a ticket when its worker's run ends, so a worker that finishes (or dies) without
 * mailing never stalls the pipeline. Layer one of "PM must be told automatically"; layer two is the finish line in the brief.
 *
 * On [SessionEvent.Exit] of a session that runs a ticket with a `startedBy`, a kill too, naming who did it (`killedBy`) unless the ticket is done or it was routine housekeeping:
 *  - ONE mail from `system`, subject `[AUTOMATIC] ...`: ticket id and title, session, branch, last commit, exit code and
 *    the first lines of the worker's last output.
 *  - A FAILURE (killed, or a non-zero / unknown exit code) is marked `[AUTOMATIC FAILURE]`, collapsed to one per ticket
 *    per [REPEAT_WINDOW_MS].
 *  - A clean finish (ticket `done`) is SUPPRESSED when the worker already mailed the starter since the last notice: its
 *    own mail is richer, and a second one would only confuse.
 *  - Deduped per session run, so one finish gives one mail.
 *  - Ticket ef3356ae: a turn that ended with exit code 0 and the ticket still open is NOT a failure and sends nothing: the
 *    worker is at rest, waiting for its next instruction. It opens an IDLE PERIOD instead; when the worker has been at
 *    rest with no mail to or from it for [idleNoticeMs] (`limits.watchdog.idleNoticeMinutes`, 0 = never), [sweep] sends
 *    the starter ONE calm `[AUTOMATIC]` note that it is waiting. Mail either way (the starter's included) ends the period;
 *    further turns without mail do not start a new one, so the note goes out at most once per period.
 */
class FinishNoticeMonitor(
    private val sessions: SessionManager,
    private val mailbox: Mailbox,
    private val send: (MailSendRequest) -> MailResult,
    private val ticketOf: (String) -> TicketRow?,
    private val commitOf: (File) -> String? = ::gitHead,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long a worker may wait at rest, with no mail either way, before its starter is told; [Long.MAX_VALUE] = never. */
    private val idleNoticeMs: () -> Long = { LimitRegistry.minutesMs(LimitRegistry.IDLE_NOTICE_MINUTES.default, LimitRegistry.IDLE_NOTICE_MINUTES) },
    /** STALL-5: `limits.watch.exemptWhenTicketDone`; off = a non-zero exit after done is a failure notice as before. */
    private val exemptWhenDone: () -> Boolean = { true },
) {
    /** One stretch of a worker at rest with its ticket open: [since] is when it began (or its last sign of work). */
    private class IdlePeriod(@Volatile var since: Long) {
        @Volatile var noticed = false
    }

    private val tail = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val lastNoticed = ConcurrentHashMap<String, Int>()
    private val lastNoticeAt = ConcurrentHashMap<String, Long>()
    private val lastFailureNoticeAt = ConcurrentHashMap<String, Long>()
    private val idle = ConcurrentHashMap<String, IdlePeriod>()
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "agenthq-idle-notice").also { it.isDaemon = true } }

    /** The idle sweep; the setting is whole minutes, so a pass a minute is exact to its unit. */
    fun start() {
        executor.scheduleWithFixedDelay(
            { runCatching { sweep() }.onFailure { System.err.println("[agentHq] idle notice sweep failed: ${it.message}") } },
            1, 1, TimeUnit.MINUTES,
        )
    }

    fun close() { executor.shutdownNow() }

    fun onSessionEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.Stdout -> tail.computeIfAbsent(event.sessionId) { ArrayDeque() }.let { q ->
                synchronized(q) {
                    if (event.line.isNotBlank()) q.addLast(event.line.trim())
                    while (q.size > TAIL_LINES) q.removeFirst()
                }
            }
            is SessionEvent.Exit -> {
                // Ticket 7e9bed2a: a kill is noticed too (the ticket left open by it), saying who did it.
                runCatching { notice(event) }
                    .onFailure { System.err.println("[agentHq] finish notice failed for ${event.sessionId}: ${it.message}") }
            }
            else -> Unit
        }
    }

    private fun notice(event: SessionEvent.Exit) {
        val session = sessions.get(event.sessionId) ?: return
        // TICKET-HANDOVER: the handoff turn of a harness switch is not the worker finishing; its successor goes on.
        if (sessions.isHeld(session.id)) return
        val ticket = session.ticketId?.let { ticketOf(it) }?.takeIf { it.sessionId == session.id } ?: return
        val starter = ticket.startedBy ?: return
        val run = session.runCount
        if (lastNoticed.put(session.id, run) == run) return
        val killed = event.killOutcome != null
        if (killed && (ticket.status == TicketStatus.DONE || session.killedBy in QUIET_KILLERS)) return
        val done = ticket.status == TicketStatus.DONE
        // STALL-5: a non-zero exit after the ticket is done (or the final report was mailed) is a normal end, not a failure.
        if (!killed && event.code != 0 && exemptWhenDone() && (done || reportDelivered(mailbox, session, starter))) {
            println("[agentHq] finish notice: ${session.id} ended with exit code ${event.code} after its work was delivered; no failure notice")
            idle.remove(session.id)
            return
        }
        val failed = killed || event.code != 0
        if (!failed && !done) {
            // A clean end of turn with the ticket open: the worker is waiting, not failing. Same period while no mail moved.
            val now = clock()
            idle.compute(session.id) { _, p -> if (p != null && lastMailAt(session) < p.since) p else IdlePeriod(now) }
            return
        }
        idle.remove(session.id)
        val since = lastNoticeAt[session.id] ?: session.spawnedAt
        lastNoticeAt[session.id] = clock()
        val mailedStarter = mailbox.outbox(session.id).any { it.sentAt >= since && it.to == starter }
        if (!failed && mailedStarter) return
        if (failed) {
            val now = clock()
            val prev = lastFailureNoticeAt[ticket.id]
            if (prev != null && now - prev < REPEAT_WINDOW_MS) return
            lastFailureNoticeAt[ticket.id] = now
        }
        val lines = tail[session.id]?.let { q -> synchronized(q) { q.toList() } }.orEmpty()
        val branch = ticket.branch ?: session.worktreeBranch
        val commit = (session.worktreePath ?: session.repoRoot)?.let { commitOf(File(it)) }
        val reason = when {
            killed -> "killed by ${session.killedBy ?: "unknown"}${session.killReason?.let { " ($it)" }.orEmpty()}; the ticket is still ${ticket.status}"
            failed -> "exit code ${event.code ?: "unknown"}"
            else -> null
        }
        send(
            MailSendRequest(
                from = SYSTEM,
                to = starter,
                subject = (if (failed) "[AUTOMATIC FAILURE] " else "[AUTOMATIC] ") +
                    "ticket ${ticket.id} ${if (failed) "did not finish cleanly" else "finished"}: ${ticket.title.take(80)}",
                body = buildString {
                    append("Automatic notice from the daemon (the worker may also mail you its own report).\n")
                    reason?.let { append("FAILURE: $it\n") }
                    append("ticket: ${ticket.id} - ${ticket.title}\n")
                    append("status: ${ticket.status}\n")
                    append("worker session: ${session.id}\n")
                    append("branch: ${branch ?: "none"}\n")
                    append("last commit: ${commit ?: "unknown"}\n")
                    append("exit code: ${event.code ?: "unknown"}\n")
                    if (killed) append("killed by: ${session.killedBy ?: "unknown"}${session.killReason?.let { " - $it" }.orEmpty()}\n")
                    // TOKEN-1: a clean finish carries no output dump, and a raw JSON event line (thinking_tokens and the like) never does.
                    val readable = if (failed || killed) lines.filterNot { it.trimStart().startsWith("{") } else emptyList()
                    if (readable.isNotEmpty()) {
                        append("last output:\n")
                        readable.takeLast(OUTPUT_LINES).forEach { append("  ${it.take(240)}\n") }
                    }
                },
            ),
        )
    }

    /**
     * Sends the calm "waiting" note for every idle period that reached [idleNoticeMs] with no mail either way. A worker
     * that is working again (a background task, a `bg` job) pushes its period's start forward instead.
     */
    fun sweep() {
        val limit = idleNoticeMs()
        if (limit == Long.MAX_VALUE) return
        val now = clock()
        for ((id, period) in idle) {
            if (period.noticed) continue
            val session = sessions.get(id)
            val ticket = session?.ticketId?.let { ticketOf(it) }?.takeIf { it.sessionId == id && it.status != TicketStatus.DONE }
            val starter = ticket?.startedBy
            if (session == null || ticket == null || starter == null || session.state in ENDED_STATES) {
                idle.remove(id)
                continue
            }
            if (session.state != SessionState.IDLE || sessions.isHeld(id) || session.backgroundWork != null || session.bgJobs.isNotEmpty()) {
                period.since = now
                continue
            }
            val quietSince = maxOf(period.since, lastMailAt(session))
            if (now - quietSince < limit) continue
            period.noticed = true
            send(
                MailSendRequest(
                    from = SYSTEM,
                    to = starter,
                    subject = "[AUTOMATIC] ticket ${ticket.id} is waiting for instructions: ${ticket.title.take(80)}",
                    body = buildString {
                        append("Worker finished its turn and is waiting for instructions (ticket still open).\n")
                        append("No mail to or from it for ${(now - quietSince) / 60_000} min. Mail it to carry on; this note is not repeated until it has been mailed.\n")
                        append("ticket: ${ticket.id} - ${ticket.title}\n")
                        append("status: ${ticket.status}\n")
                        append("worker session: ${session.id}\n")
                        append("branch: ${ticket.branch ?: session.worktreeBranch ?: "none"}\n")
                    },
                ),
            )
        }
    }

    /** The newest mail the worker sent, or was sent by anyone but `system` (0 when none). */
    private fun lastMailAt(session: SessionRecord): Long {
        val sent = mailbox.outbox(session.id).maxOfOrNull { it.sentAt } ?: 0L
        val received = listOfNotNull(session.id, session.agentId).flatMap { mailbox.inbox(it) }
            .filter { it.from != SYSTEM }.maxOfOrNull { it.sentAt } ?: 0L
        return maxOf(sent, received)
    }

    companion object {
        const val SYSTEM = "system"

        /** STALL-5: the session's last mail to [starter] is newer than any mail it received from anyone but `system`. */
        fun reportDelivered(mailbox: Mailbox, session: SessionRecord, starter: String?): Boolean {
            if (starter == null) return false
            val sent = mailbox.outbox(session.id).filter { it.to == starter }.maxOfOrNull { it.sentAt } ?: return false
            val received = listOfNotNull(session.id, session.agentId).flatMap { mailbox.inbox(it) }
                .filter { it.from != SYSTEM }.maxOfOrNull { it.sentAt } ?: 0L
            return sent > received
        }
        /** Kills the daemon makes as routine housekeeping: no failure notice. */
        private val QUIET_KILLERS = setOf("done-worker reaper", "ticket start rollback")
        private const val TAIL_LINES = 12
        private const val OUTPUT_LINES = 6
        const val REPEAT_WINDOW_MS = 15 * 60_000L
        private val ENDED_STATES = setOf(SessionState.EXITED, SessionState.FAILED, SessionState.ORPHANED)

        /** The short sha of HEAD in [dir], or null when it is not a git checkout or git does not answer in 5 s. */
        fun gitHead(dir: File): String? = runCatching {
            val p = ProcessBuilder("git", "-C", dir.path, "rev-parse", "--short", "HEAD").redirectErrorStream(true).start()
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            p.inputStream.bufferedReader().readText().trim().takeIf { p.exitValue() == 0 && it.isNotEmpty() }
        }.getOrNull()
    }
}
