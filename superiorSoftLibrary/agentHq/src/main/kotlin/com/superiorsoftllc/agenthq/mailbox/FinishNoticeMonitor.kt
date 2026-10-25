package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.persistence.TicketRow
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The daemon tells the starter of a ticket when its worker's run ends, so a worker that finishes (or dies) without
 * mailing never stalls the pipeline. Layer one of "PM must be told automatically"; layer two is the finish line in the brief.
 *
 * On [SessionEvent.Exit] of a session that runs a ticket with a `startedBy`, a kill too, naming who did it (`killedBy`) unless the ticket is done or it was routine housekeeping:
 *  - ONE mail from `system`, subject `[AUTOMATIC] ...`: ticket id and title, session, branch, last commit, exit code and
 *    the first lines of the worker's last output.
 *  - A FAILURE (exit code not 0, or the ticket not in `done`) is marked `[AUTOMATIC FAILURE]` and always sent.
 *  - A clean finish is SUPPRESSED when the worker already mailed the starter since the last notice: its own mail is
 *    richer, and a second one would only confuse.
 *  - Deduped per session run, so one finish gives one mail.
 *  - A turn that ended with exit code 0 and the ticket still open is NOT a failure when the worker is only waiting: a
 *    background process or `bg` job is running, it mailed the starter since the last notice (a plan awaiting a go), or
 *    the daemon itself started under [RESUME_GRACE_MS] ago (sessions resumed after a restart). Non-zero exits always notify.
 *  - Failure notices are collapsed to one per ticket per [REPEAT_WINDOW_MS].
 */
class FinishNoticeMonitor(
    private val sessions: SessionManager,
    private val mailbox: Mailbox,
    private val send: (MailSendRequest) -> MailResult,
    private val ticketOf: (String) -> TicketRow?,
    private val commitOf: (File) -> String? = ::gitHead,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val tail = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val lastNoticed = ConcurrentHashMap<String, Int>()
    private val lastNoticeAt = ConcurrentHashMap<String, Long>()
    private val lastFailureNoticeAt = ConcurrentHashMap<String, Long>()
    private val startedAt = clock()

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
        val failed = killed || (event.code ?: 0) != 0
        val done = ticket.status == TicketStatus.DONE
        val failure = failed || !done
        val since = lastNoticeAt[session.id] ?: session.spawnedAt
        lastNoticeAt[session.id] = clock()
        val mailedStarter = mailbox.outbox(session.id).any { it.sentAt >= since && it.to == starter }
        if (!failure && mailedStarter) return
        if (failure && !failed) {
            // A clean exit with the ticket still open: only a worker that is not waiting on anything is a failure.
            val waiting = session.backgroundWork != null || session.bgJobs.isNotEmpty() ||
                mailedStarter || clock() - startedAt < RESUME_GRACE_MS
            if (waiting) return
        }
        if (failure) {
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
            failed -> "exit code ${event.code}"
            !done -> "ended with the ticket still ${ticket.status}"
            else -> null
        }
        send(
            MailSendRequest(
                from = SYSTEM,
                to = starter,
                subject = (if (failure) "[AUTOMATIC FAILURE] " else "[AUTOMATIC] ") +
                    "ticket ${ticket.id} ${if (failure) "did not finish cleanly" else "finished"}: ${ticket.title.take(80)}",
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
                    if (lines.isNotEmpty()) {
                        append("last output:\n")
                        lines.takeLast(OUTPUT_LINES).forEach { append("  ${it.take(240)}\n") }
                    }
                },
            ),
        )
    }

    companion object {
        const val SYSTEM = "system"
        /** Kills the daemon makes as routine housekeeping: no failure notice. */
        private val QUIET_KILLERS = setOf("done-worker reaper", "ticket start rollback")
        private const val TAIL_LINES = 12
        private const val OUTPUT_LINES = 6
        const val RESUME_GRACE_MS = 5 * 60_000L
        const val REPEAT_WINDOW_MS = 15 * 60_000L

        /** The short sha of HEAD in [dir], or null when it is not a git checkout or git does not answer in 5 s. */
        fun gitHead(dir: File): String? = runCatching {
            val p = ProcessBuilder("git", "-C", dir.path, "rev-parse", "--short", "HEAD").redirectErrorStream(true).start()
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            p.inputStream.bufferedReader().readText().trim().takeIf { p.exitValue() == 0 && it.isNotEmpty() }
        }.getOrNull()
    }
}
