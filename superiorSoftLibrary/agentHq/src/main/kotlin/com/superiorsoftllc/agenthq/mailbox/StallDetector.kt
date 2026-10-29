package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import com.superiorsoftllc.agenthq.session.isBusy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * STALL-1 (ticket 802c64d5) item 5: the daemon's STALL DETECTOR, a timer with no LLM. It nudges a ticket worker (one mail,
 * which wakes a session at rest) and sends its PM one line, never kills or restarts it, in two states:
 *  - IDLE: the ticket is In Progress, the session is at rest for [stallMs] with no job running (no bg job, no process
 *    under its harness), and it is not waiting deliberately ([deliberateWait]: an open owner question, a mail it is
 *    waiting on an answer to).
 *  - BUSY BUT SILENT: a turn is in flight, a job of the session ended ([lastJobEndedAt]: a bg job's end, or a process
 *    under its harness gone) after the session's last sign of life, and nothing happened for [stallMs] since. The nudge
 *    mail queues until that turn ends (a turn in flight cannot be written into); the PM line goes out at once.
 * Each stretch is told once (keyed by the last sign of life). It also writes every session's watch label
 * ([SessionManager.setWatchLabel]): `watching <job> (quiet 2m)`, `watch OFF: <reason>`, `busy, silent 7m`, `stalled`,
 * `idle`; a session with no ticket and no job gets none.
 */
class StallDetector(
    private val sessions: SessionManager,
    private val send: (MailSendRequest) -> MailResult,
    private val ticketInProgress: (ticketId: String) -> Boolean,
    private val deliberateWait: (SessionRecord) -> Boolean,
    private val lastJobEndedAt: (sessionId: String) -> Long?,
    private val quietSeconds: (jobId: String) -> Long?,
    /** `limits.watch.stallMinutes` (5); [Long.MAX_VALUE] = never. */
    private val stallMs: () -> Long,
    /** Who gets the one line: the ticket's starter, else the `pm` role. */
    private val pmOf: (SessionRecord) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val told = ConcurrentHashMap<String, String>()
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "agenthq-stall-detector").also { it.isDaemon = true } }

    fun start(periodMs: Long = 30_000L) {
        executor.scheduleWithFixedDelay(
            { runCatching { tick() }.onFailure { System.err.println("[agentHq] stall detector tick failed: ${it.message}") } },
            periodMs, periodMs, TimeUnit.MILLISECONDS,
        )
    }

    fun close() { executor.shutdownNow() }

    @Synchronized
    fun tick() {
        val now = clock()
        val all = sessions.list()
        told.keys.retainAll(all.map { it.id }.toSet())
        val stall = stallMs()
        for (s in all) {
            val stalledKey = if (stall == Long.MAX_VALUE || stall <= 0) null else check(s, now, stall)
            runCatching { sessions.setWatchLabel(s.id, label(s, now, stalledKey)) }
        }
    }

    /** The key of the stall [s] is in now (told once per key), or null when it is not stalled. */
    private fun check(s: SessionRecord, now: Long, stall: Long): String? {
        val ticket = s.ticketId ?: return null
        if (!runCatching { ticketInProgress(ticket) }.getOrDefault(false)) return null
        val silentFor = now - s.lastHeartbeat
        if (!s.isBusy()) {
            if (s.state != SessionState.IDLE || s.bgJobs.isNotEmpty() || s.backgroundWork != null) return null
            if (silentFor < stall || runCatching { deliberateWait(s) }.getOrDefault(true)) return null
            val key = "idle:${s.lastHeartbeat}"
            if (told.put(s.id, key) != key) {
                nudge(
                    s, key,
                    subject = "[AUTOMATIC] stall check: idle ${fmt(silentFor)} with ticket $ticket In Progress",
                    body = "Your ticket $ticket is In Progress, you have been at rest for ${fmt(silentFor)} and no job of yours is " +
                        "running. Continue the work; if you are blocked, mail the PM what blocks you; if you are done, move the " +
                        "ticket and report. (Waiting on a reply? Then this was sent in error: say so to the PM.)",
                    pmLine = "worker ${s.id.take(8)} (${s.role ?: "?"}) idle ${fmt(silentFor)} on ticket $ticket with no job running; nudged.",
                )
            }
            return key
        }
        val ended = lastJobEndedAt(s.id) ?: return null
        if (ended <= s.lastHeartbeat || now - ended < stall || silentFor < stall) return null
        val key = "busy:$ended"
        if (told.put(s.id, key) != key) {
            nudge(
                s, key,
                subject = "[AUTOMATIC] stall check: your job ended ${fmt(now - ended)} ago and you have been silent since",
                body = "A job you started ended ${fmt(now - ended)} ago (see the job notice in this mailbox), and your turn has shown " +
                    "no tool call or output since. Do not wait on it any longer: read its result and continue, or report to the PM.",
                pmLine = "worker ${s.id.take(8)} (${s.role ?: "?"}) is BUSY but silent ${fmt(silentFor)}; its job ended ${fmt(now - ended)} ago. " +
                    "Nudge queued until its turn ends; interrupt it if it stays stuck.",
            )
        }
        return key
    }

    private fun nudge(s: SessionRecord, key: String, subject: String, body: String, pmLine: String) {
        WatchMeter.stall()
        send(MailSendRequest(from = FinishNoticeMonitor.SYSTEM, to = s.id, subject = subject, threadId = "stall:${s.id}", body = body + "\n"))
        pmOf(s)?.let { pm ->
            send(MailSendRequest(from = FinishNoticeMonitor.SYSTEM, to = pm, subject = "[AUTOMATIC] $pmLine".take(200), threadId = "stall:${s.id}:pm", body = pmLine + "\n"))
        }
        println("[agentHq] stall detector: ${s.id} $key")
    }

    private fun label(s: SessionRecord, now: Long, stalledKey: String?): String? {
        s.bgJobs.firstOrNull { it.optOut != null }?.let { return "watch OFF: ${it.optOut!!.take(60)}" }
        s.bgJobs.firstOrNull()?.let { j ->
            val quiet = quietSeconds(j.id)?.takeIf { it >= 60 }?.let { " (quiet ${it / 60}m)" } ?: ""
            return "watching ${j.label.take(40)}$quiet"
        }
        if (stalledKey != null && told[s.id] == stalledKey) return "stalled"
        if (s.ticketId == null) return null
        val silent = now - s.lastHeartbeat
        return when {
            s.isBusy() && silent >= 60_000L -> "busy, silent ${silent / 60_000}m"
            s.isBusy() -> null
            s.state == SessionState.IDLE -> "idle"
            else -> null
        }
    }

    private fun fmt(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return if (m < 1) "${ms / 1000}s" else "${m}m"
    }
}
