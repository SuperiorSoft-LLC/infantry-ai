package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.runner.WatchNotices
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import com.superiorsoftllc.agenthq.session.isBusy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * STALL-1 (ticket 802c64d5) item 5: the daemon's STALL DETECTOR, a timer with no LLM. It nudges a ticket worker (one mail,
 * which wakes a session at rest) and sends the recipient rule's address one line (STALL-4: the ticket's starter, else the
 * user; every text is a [WatchNotices] template that names no role), never kills or restarts it, in these states:
 *  - IDLE: the ticket is In Progress, the session is at rest for [stallMs] with no job running (no bg job, no process
 *    under its harness), and it is not waiting deliberately ([deliberateWait]: an open owner question, a mail it is
 *    waiting on an answer to).
 *  - BUSY BUT SILENT: a turn is in flight, a job of the session ended ([lastJobEndedAt]: a bg job's end, or a process
 *    under its harness gone) after the session's last sign of life, and nothing happened for [stallMs] since. The nudge
 *    mail queues until that turn ends (a turn in flight cannot be written into); the line goes out at once.
 *  - BUSY IN THE FOREGROUND (STALL-3): a turn is in flight with no tool progress for [foregroundStallMs] and no watched job
 *    of the session running, even though no job ended: a long or hung foreground call (`find /` over everything, a build
 *    the hooks could not rewrite). Same nudge and line, keyed by the last sign of life.
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
    /** STALL-4: who gets the one line (the daemon's recipient rule, [WatchNotices.USER] = the user); null = nobody. */
    private val recipientOf: (SessionRecord) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    /** STALL-3: `limits.watch.foregroundStallMinutes` (10); [Long.MAX_VALUE] = never. */
    private val foregroundStallMs: () -> Long = { Long.MAX_VALUE },
    /** STALL-5: `limits.watch.exemptWhenTicketDone` (1); off = a done ticket or a delivered final report is watched like any other. */
    private val exemptWhenDone: () -> Boolean = { true },
    /** STALL-5: the ticket is done. */
    private val ticketDone: (ticketId: String) -> Boolean = { false },
    /** STALL-5: the session mailed its final report (its last mail to the starter is newer than anything anyone sent it). */
    private val reportDelivered: (SessionRecord) -> Boolean = { false },
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
        val exempt = exemptWhenDone()
        val active = runCatching { ticketInProgress(ticket) || (!exempt && ticketDone(ticket)) }.getOrDefault(false)
        if (!active) return null
        if (exempt && runCatching { reportDelivered(s) }.getOrDefault(false)) return null
        val silentFor = now - s.lastHeartbeat
        if (!s.isBusy()) {
            if (s.state != SessionState.IDLE || s.bgJobs.isNotEmpty() || s.backgroundWork != null) return null
            if (silentFor < stall || runCatching { deliberateWait(s) }.getOrDefault(true)) return null
            val key = "idle:${s.lastHeartbeat}"
            if (told.put(s.id, key) != key) {
                val v = vars(s, ticket, silentFor)
                nudge(
                    s, key,
                    subject = WatchNotices.text("stall.idle.subject", *v),
                    body = WatchNotices.text("stall.idle.body", *v),
                    line = WatchNotices.text("stall.idle.line", *v),
                )
            }
            return key
        }
        val ended = lastJobEndedAt(s.id)?.takeIf { it > s.lastHeartbeat } ?: return foreground(s, ticket, now, silentFor)
        if (now - ended < stall || silentFor < stall) return null
        val key = "busy:$ended"
        if (told.put(s.id, key) != key) {
            val v = vars(s, ticket, silentFor) + ("ago" to fmt(now - ended))
            nudge(
                s, key,
                subject = WatchNotices.text("stall.busy.subject", *v),
                body = WatchNotices.text("stall.busy.body", *v),
                line = WatchNotices.text("stall.busy.line", *v),
            )
        }
        return key
    }

    /** STALL-3: BUSY with no tool progress and no watched job for [foregroundStallMs]: a long or hung foreground call. */
    private fun foreground(s: SessionRecord, ticket: String, now: Long, silentFor: Long): String? {
        val limit = foregroundStallMs()
        if (limit == Long.MAX_VALUE || limit <= 0 || silentFor < limit || s.bgJobs.isNotEmpty()) return null
        val key = "busy-fg:${s.lastHeartbeat}"
        if (told.put(s.id, key) != key) {
            val v = vars(s, ticket, silentFor)
            nudge(
                s, key,
                subject = WatchNotices.text("stall.foreground.subject", *v),
                body = WatchNotices.text("stall.foreground.body", *v),
                line = WatchNotices.text("stall.foreground.line", *v),
            )
        }
        return key
    }

    private fun vars(s: SessionRecord, ticket: String, silentFor: Long): Array<Pair<String, Any?>> =
        arrayOf("session" to s.id.take(8), "role" to (s.role ?: "?"), "ticket" to ticket, "silent" to fmt(silentFor))

    private fun nudge(s: SessionRecord, key: String, subject: String, body: String, line: String) {
        WatchMeter.stall()
        send(MailSendRequest(from = FinishNoticeMonitor.SYSTEM, to = s.id, subject = subject, threadId = "stall:${s.id}", body = body + "\n"))
        runCatching { recipientOf(s) }.getOrNull()?.takeIf { it != s.id }?.let { to ->
            send(MailSendRequest(from = FinishNoticeMonitor.SYSTEM, to = to, subject = "[AUTOMATIC] $line".take(200), threadId = "stall:${s.id}:watch", body = line + "\n"))
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
