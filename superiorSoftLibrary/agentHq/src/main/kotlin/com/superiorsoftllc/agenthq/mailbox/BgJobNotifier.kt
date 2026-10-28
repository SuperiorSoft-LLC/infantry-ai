package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.session.BgJob
import com.superiorsoftllc.agenthq.session.BgJobListener
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * BG-NOTIFY (ticket 245a7d31): the daemon watches every `agenthq bg run --notify` job and tells the session that owns it
 * by COMMS mail from `system`. Mail to a session at rest RESUMES it ([MailRouter]), so a worker starts its build, ends its
 * turn, and spends no tokens until the notice arrives: no `sleep`, no polling.
 *
 * TRIGGERS, one mail each:
 *  - EXIT (always): the wrapper's `done` carries the exit code and the last lines ([done]).
 *  - VANISHED: the job was dropped without a `done` (wrapper killed, `bg stop`, machine asleep) ([expired]); exit unknown,
 *    tail read from the log.
 *  - PATTERN: a new log line matched one of the job's regexes (Gradle `BUILD SUCCESSFUL|FAILED`, Kotlin `e: ` by
 *    default; the CLI resolves the list). The match waits [patternGraceMs] for the job to exit: an exit inside it sends ONE
 *    exit notice naming the match, so a Gradle run that prints its verdict and exits wakes the worker once, not twice.
 *  - TIMEOUT: still running after the job's timeout, again every such period, with the age of its last output, so a
 *    hung job is visible instead of polled.
 *
 * Read on the session manager's scan pass ([scanned]); only the new bytes of a job's log are read.
 */
class BgJobNotifier(
    private val jobs: () -> List<Pair<String, BgJob>>,
    private val send: (MailSendRequest) -> MailResult,
    private val defaultTailLines: () -> Int,
    /** Default timeout in ms; [Long.MAX_VALUE] = never. */
    private val defaultTimeoutMs: () -> Long,
    private val patternGraceMs: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
) : BgJobListener {
    private class Watch {
        var offset = 0L
        var partial = ""
        val matches = ArrayList<String>()
        var firstMatchAt = 0L
        var timeoutsSent = 0
    }

    private val watches = ConcurrentHashMap<String, Watch>()
    private val regexCache = ConcurrentHashMap<String, Regex?>()

    /** The wrapper said its command ended with [exitCode]; [tail] = its last lines (null: read from the log). */
    fun done(sessionId: String, job: BgJob, exitCode: Int?, tail: List<String>?) {
        if (job.notify == null) return
        val w = watches.remove(job.id)
        val lines = tailOf(job, tail)
        val verdict = if (exitCode == null) "exit unknown" else "exit $exitCode"
        send(
            mail(
                sessionId, job,
                subject = "[AUTOMATIC] bg job ${job.id} finished, $verdict: ${job.label.take(60)}",
                head = "Background job ${job.id} (${job.label}) finished after ${elapsed(job)}, $verdict.",
                matched = w?.matches.orEmpty(),
                lines = lines,
            ),
        )
    }

    override fun expired(sessionId: String, job: BgJob) {
        if (job.notify == null) return
        val w = watches.remove(job.id)
        send(
            mail(
                sessionId, job,
                subject = "[AUTOMATIC] bg job ${job.id} ended without an exit code: ${job.label.take(60)}",
                head = "Background job ${job.id} (${job.label}) is gone after ${elapsed(job)}: its wrapper was stopped or died " +
                    "before it reported an exit code. Check `agenthq bg logs ${job.id}`.",
                matched = w?.matches.orEmpty(),
                lines = tailOf(job, null),
            ),
        )
    }

    override fun scanned() {
        val now = clock()
        val live = jobs().filter { it.second.notify != null }
        watches.keys.retainAll(live.map { it.second.id }.toSet())
        for ((sid, job) in live) {
            val notify = job.notify ?: continue
            val w = watches.computeIfAbsent(job.id) { Watch() }
            if (notify.patterns.isNotEmpty()) readNew(job, w, notify.patterns.mapNotNull(::regex), now)
            if (w.matches.isNotEmpty() && now - w.firstMatchAt >= patternGraceMs()) {
                val matched = w.matches.toList()
                w.matches.clear()
                send(
                    mail(
                        sid, job,
                        subject = "[AUTOMATIC] bg job ${job.id} matched '${matched.first().take(60)}' (still running)",
                        head = "Background job ${job.id} (${job.label}) printed a line you asked to be told about and is still running after ${elapsed(job)}.",
                        matched = matched,
                        lines = tailOf(job, null),
                    ),
                )
            }
            val timeout = notify.timeoutMinutes?.let { if (it <= 0) Long.MAX_VALUE else it * 60_000L } ?: defaultTimeoutMs()
            if (timeout != Long.MAX_VALUE && timeout > 0) {
                val due = (now - job.startedAt) / timeout
                if (due > w.timeoutsSent) {
                    w.timeoutsSent = due.toInt()
                    val quiet = job.logPath?.let { File(it) }?.takeIf { it.isFile }?.let { (now - it.lastModified()) / 1000 }
                    send(
                        mail(
                            sid, job,
                            subject = "[AUTOMATIC] bg job ${job.id} still running after ${elapsed(job)}: ${job.label.take(60)}",
                            head = "Background job ${job.id} (${job.label}) is still running after ${elapsed(job)}" +
                                (quiet?.let { "; its last output was ${it}s ago" } ?: "") +
                                ". If it is hung, `agenthq bg stop ${job.id}`; otherwise end your turn again and wait for the next notice.",
                            matched = emptyList(),
                            lines = tailOf(job, null),
                        ),
                    )
                }
            }
        }
    }

    /** Reads the bytes appended to [job]'s log since the last pass and records the lines that match. */
    private fun readNew(job: BgJob, w: Watch, patterns: List<Regex>, now: Long) {
        val f = job.logPath?.let { File(it) }?.takeIf { it.isFile } ?: return
        val len = f.length()
        if (len < w.offset) w.offset = 0L
        if (len == w.offset) return
        val bytes = RandomAccessFile(f, "r").use { r ->
            r.seek(w.offset)
            ByteArray((len - w.offset).toInt()).also { r.readFully(it) }
        }
        w.offset = len
        val text = w.partial + String(bytes, Charsets.UTF_8)
        val parts = text.split('\n')
        w.partial = parts.last()
        for (line in parts.dropLast(1)) {
            val l = line.trimEnd('\r')
            if (patterns.any { it.containsMatchIn(l) }) {
                if (w.matches.isEmpty()) w.firstMatchAt = now
                w.matches += l
            }
        }
    }

    private fun regex(p: String): Regex? = regexCache.computeIfAbsent(p) { runCatching { Regex(it) }.getOrNull() }

    private fun tailOf(job: BgJob, given: List<String>?): List<String> {
        val n = (job.notify?.tailLines ?: defaultTailLines()).coerceAtLeast(1)
        val lines = given ?: job.logPath?.let { File(it) }?.takeIf { it.isFile }?.let { f ->
            runCatching { f.readLines() }.getOrNull()
        }.orEmpty()
        return lines.takeLast(n)
    }

    private fun elapsed(job: BgJob): String {
        val s = ((clock() - job.startedAt) / 1000).coerceAtLeast(0)
        return if (s < 60) "${s}s" else "${s / 60}m${s % 60}s"
    }

    private fun mail(sessionId: String, job: BgJob, subject: String, head: String, matched: List<String>, lines: List<String>) =
        MailSendRequest(
            from = FinishNoticeMonitor.SYSTEM,
            to = sessionId,
            subject = subject,
            threadId = "bg:${job.id}",
            body = buildString {
                append(head).append('\n')
                if (matched.isNotEmpty()) {
                    append("matched:\n")
                    matched.takeLast(lines.size.coerceAtLeast(1)).forEach { append("  ").append(it.take(400)).append('\n') }
                }
                job.logPath?.let { append("log: ").append(it).append('\n') }
                if (lines.isNotEmpty()) {
                    append("last ${lines.size} lines:\n")
                    lines.forEach { append("  ").append(it.take(400)).append('\n') }
                }
            },
        )
}
