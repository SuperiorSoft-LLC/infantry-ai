package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.session.BgJob
import com.superiorsoftllc.agenthq.session.BgJobListener
import com.superiorsoftllc.agenthq.session.ProcInfo
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * BG-NOTIFY (ticket 245a7d31): the daemon watches every `agenthq bg run --notify` job and tells the session that owns it
 * by COMMS mail from `system`. Mail to a session at rest RESUMES it ([MailRouter]), so a worker starts its build, ends its
 * turn, and spends no tokens until the notice arrives: no `sleep`, no polling.
 *
 * STALL-1 (ticket 802c64d5) makes this the daemon's ABSTRACT WATCHER: no LLM, only timers, file length/mtime reads and pid
 * probes; a model turn is spent only when a notice is delivered. Mail is persisted and queued by the router while the
 * session is busy or compacting, so each notice is delivered exactly once, also after a daemon restart.
 *
 * TRIGGERS, one mail each:
 *  - EXIT (always): the wrapper's `done` carries the exit code, the last lines, the byte count (0 = the command printed
 *    nothing: "ended, no output", not an error), whether the pipe reached EOF and the children still holding it ([done]).
 *  - VANISHED: the job was dropped without a `done` (wrapper killed, `bg stop`, machine asleep, daemon restart) ([expired]);
 *    the runner's `<id>.exit` file is read when it is there, else the exit is reported unknown, tail read from the log.
 *  - EOF WHILE RUNNING: the command closed its output and still runs ([eof]): "output closed, still running", not done.
 *  - PATTERN: a new log line matched one of the job's regexes (Gradle `BUILD SUCCESSFUL|FAILED`, Kotlin `e: ` by
 *    default; the CLI resolves the list). The match waits [patternGraceMs] for the job to exit: an exit inside it sends ONE
 *    exit notice naming the match, so a Gradle run that prints its verdict and exits wakes the worker once, not twice.
 *  - QUIET: no new output line (the runner's own `[agenthq:` lines do not count) for [quietMs]; once per quiet stretch.
 *  - HEARTBEAT: still running after the job's timeout ([defaultTimeoutMs], 15 min), then after 2x, 4x that interval, up to
 *    [heartbeatCapMs]; independent of the log (frozen, vanished, or the watcher failing to read it): elapsed, watch state,
 *    last lines or "no output since", whether the process still exists. The first beat at the cap also tells the PM.
 *  - TREE JOB ENDED ([treeJobEnded]): a long process under the harness that the daemon did not launch is gone (layer 5).
 *
 * DELIVERY: an [BgJob.attached] job (a PATH shim, the caller's foreground) whose session still has its turn in flight
 * ([inTurn]) is NOT mailed: that turn saw the end on its own console. Its end is recorded ([lastEndedAt]) for the stall
 * detector, which catches a turn that stays BUSY and silent after it. Read on the session manager's scan pass ([scanned]).
 */
class BgJobNotifier(
    private val jobs: () -> List<Pair<String, BgJob>>,
    private val send: (MailSendRequest) -> MailResult,
    private val defaultTailLines: () -> Int,
    /** Default first heartbeat in ms; [Long.MAX_VALUE] = never. */
    private val defaultTimeoutMs: () -> Long,
    private val patternGraceMs: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
    /** No new output for this long = one QUIET notice; [Long.MAX_VALUE] = never. */
    private val quietMs: () -> Long = { 5 * 60_000L },
    /** The heartbeat interval stops growing here; the first beat at it tells the PM. */
    private val heartbeatCapMs: () -> Long = { 60 * 60_000L },
    /** Whether [sessionId] has a turn in flight right now. */
    private val inTurn: (sessionId: String) -> Boolean = { false },
    private val pidAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
    /** Who is told about [sessionId] on the PM ladder (its ticket's starter, else the `pm` role); null = nobody. */
    private val pmOf: (sessionId: String) -> String? = { null },
) : BgJobListener {
    private class Watch(val startedAt: Long) {
        var offset = 0L
        var partial = ""
        val matches = ArrayList<String>()
        var firstMatchAt = 0L
        var beatsSent = 0
        var nextBeatAt = 0L
        var interval = 0L
        var pmTold = false
        var lastOutputAt = startedAt
        var quietSent = false
        var readError: String? = null
        var errorSent = false
        var optOutSeen = false
    }

    private val watches = ConcurrentHashMap<String, Watch>()
    private val regexCache = ConcurrentHashMap<String, Regex?>()
    private val endedAt = ConcurrentHashMap<String, Long>()

    /** When a job of [sessionId] last ended (bg job or a process under its harness); the stall detector reads it. */
    fun lastEndedAt(sessionId: String): Long? = endedAt[sessionId]

    /** Seconds since [jobId] last printed a line of its own, null when it is not watched. */
    fun quietSeconds(jobId: String): Long? = watches[jobId]?.let { (clock() - it.lastOutputAt) / 1000 }

    private fun ended(sessionId: String) { endedAt[sessionId] = clock() }

    /** True when [job]'s notice should go out now (see DELIVERY above). */
    private fun deliverable(sessionId: String, job: BgJob): Boolean = !(job.attached && runCatching { inTurn(sessionId) }.getOrDefault(false))

    private fun deliver(sessionId: String, job: BgJob, req: MailSendRequest, meter: () -> Unit = WatchMeter::event) {
        if (!deliverable(sessionId, job)) return
        meter()
        send(req)
    }

    /**
     * The wrapper said its command ended with [exitCode]; [tail] = its last lines (null: read from the log). [bytes],
     * [eof] and [survivors] are the STALL-1 end facts (null from an older wrapper).
     */
    fun done(
        sessionId: String,
        job: BgJob,
        exitCode: Int?,
        tail: List<String>?,
        bytes: Long? = null,
        eof: Boolean? = null,
        survivors: List<String>? = null,
    ) {
        if (job.notify == null) return
        ended(sessionId)
        val w = watches.remove(job.id)
        val lines = tailOf(job, tail)
        val verdict = if (exitCode == null) "exit unknown" else "exit $exitCode"
        val facts = buildList {
            if (bytes == 0L) add("It ended with NO output (EOF, 0 bytes): nothing was printed, which is not an error by itself.")
            else if (bytes != null) add("Output: $bytes bytes" + if (eof == false) "." else ", complete (EOF).")
            if (eof == false) {
                add(
                    "Its output pipe is still open: a child it started outlives it" +
                        (survivors?.takeIf { it.isNotEmpty() }?.let { " (${it.joinToString("; ") { s -> s.take(100) }})" } ?: "") +
                        ". The command itself has ended; the exit code is its own.",
                )
            } else if (!survivors.isNullOrEmpty()) {
                add("Still running after it: ${survivors.joinToString("; ") { it.take(100) }}.")
            }
        }
        deliver(
            sessionId, job,
            mail(
                sessionId, job,
                subject = "[AUTOMATIC] bg job ${job.id} finished, $verdict${if (bytes == 0L) ", no output" else ""}: ${job.label.take(60)}",
                head = "Background job ${job.id} (${job.label}) finished after ${elapsed(job)}, $verdict." +
                    facts.joinToString("") { "\n$it" },
                matched = w?.matches.orEmpty(),
                lines = lines,
            ),
        )
    }

    override fun expired(sessionId: String, job: BgJob) {
        if (job.notify == null) return
        ended(sessionId)
        val w = watches.remove(job.id)
        // The runner writes `<id>.exit` right after its process wait returns: a runner lost later (or a daemon restart
        // that missed its `done`) still has the real exit code on disk.
        val code = job.logPath?.let { File(it.removeSuffix(".log") + ".exit") }?.takeIf { it.isFile }
            ?.let { runCatching { it.readText().trim().toInt() }.getOrNull() }
        val head = if (code != null) {
            "Background job ${job.id} (${job.label}) ended after ${elapsed(job)}, exit $code (read from its exit file; " +
                "its runner did not report it to the daemon, e.g. a daemon restart)."
        } else {
            "Background job ${job.id} (${job.label}) is gone after ${elapsed(job)}: its runner was stopped or died " +
                "before it reported an exit code (runner lost, exit unknown). Check `agenthq bg logs ${job.id}`."
        }
        deliver(
            sessionId, job,
            mail(
                sessionId, job,
                subject = if (code != null) "[AUTOMATIC] bg job ${job.id} finished, exit $code: ${job.label.take(60)}"
                else "[AUTOMATIC] bg job ${job.id} ended without an exit code: ${job.label.take(60)}",
                head = head,
                matched = w?.matches.orEmpty(),
                lines = tailOf(job, null),
            ),
        )
    }

    /** STALL-1: the command closed its output pipe and still runs: told once, never as done. */
    fun eof(sessionId: String, job: BgJob, bytes: Long?) {
        if (job.notify == null) return
        deliver(
            sessionId, job,
            mail(
                sessionId, job,
                subject = "[AUTOMATIC] bg job ${job.id} closed its output and is still running: ${job.label.take(60)}",
                head = "Background job ${job.id} (${job.label}) closed its output (EOF after ${bytes ?: "?"} bytes) after " +
                    "${elapsed(job)}, but its process is still running: it has NOT ended. You will be told when it exits.",
                matched = emptyList(),
                lines = tailOf(job, null),
            ),
        )
    }

    override fun treeJobEnded(sessionId: String, job: ProcInfo, endedAt: Long) {
        ended(sessionId)
        if (runCatching { inTurn(sessionId) }.getOrDefault(false)) return
        val ranFor = job.startedAt?.let { fmt(endedAt - it) } ?: "?"
        WatchMeter.event()
        send(
            MailSendRequest(
                from = FinishNoticeMonitor.SYSTEM,
                to = sessionId,
                subject = "[AUTOMATIC] a background process of yours ended: ${job.command.take(60)}",
                threadId = "tree:$sessionId:${job.pid}",
                body = "A process your harness started in the background ended after $ranFor (pid ${job.pid}):\n" +
                    "  ${job.command.take(400)}\n" +
                    "Its exit code is unknown: the daemon did not launch it. Read its output with your harness's own tool. " +
                    "Next time run long jobs as `agenthq run-watched -- <cmd>` to get the exit code and log tail here.\n",
            ),
        )
    }

    override fun scanned() {
        val now = clock()
        val live = jobs().filter { it.second.notify != null }
        watches.keys.retainAll(live.map { it.second.id }.toSet())
        for ((sid, job) in live) {
            val notify = job.notify ?: continue
            val w = watches.computeIfAbsent(job.id) { Watch(job.startedAt) }
            if (job.optOut != null && !w.optOutSeen) {
                w.optOutSeen = true
                optedOut(sid, job)
            }
            if (job.logPath != null) {
                val err = runCatching { readNew(job, w, notify.patterns.mapNotNull(::regex), now) }.exceptionOrNull()
                w.readError = err?.let { it.message ?: it.javaClass.simpleName }
            }
            if (w.matches.isNotEmpty() && now - w.firstMatchAt >= patternGraceMs()) {
                val matched = w.matches.toList()
                w.matches.clear()
                deliver(
                    sid, job,
                    mail(
                        sid, job,
                        subject = "[AUTOMATIC] bg job ${job.id} matched '${matched.first().take(60)}' (still running)",
                        head = "Background job ${job.id} (${job.label}) printed a line you asked to be told about and is still running after ${elapsed(job)}.",
                        matched = matched,
                        lines = tailOf(job, null),
                    ),
                )
            }
            val beat = heartbeatDue(w, notify.timeoutMinutes, now)
            // The watcher itself failing is reported at once, through the heartbeat (which does not need the log).
            if (beat || (w.readError != null && !w.errorSent)) {
                if (w.readError != null) w.errorSent = true
                heartbeat(sid, job, w, now)
                w.quietSent = true
            } else if (job.logPath != null && !w.quietSent && quietMs().let { it != Long.MAX_VALUE && it > 0 && now - w.lastOutputAt >= it }) {
                w.quietSent = true
                deliver(
                    sid, job,
                    mail(
                        sid, job,
                        subject = "[AUTOMATIC] bg job ${job.id} quiet for ${fmt(now - w.lastOutputAt)}: ${job.label.take(60)}",
                        head = "Background job ${job.id} (${job.label}) has printed nothing for ${fmt(now - w.lastOutputAt)} and is " +
                            (if (alive(job)) "still running" else "no longer running (its end report has not arrived yet)") +
                            " after ${elapsed(job)}. Decide: keep waiting (end your turn), investigate, or report to the PM.",
                        matched = emptyList(),
                        lines = tailOf(job, null),
                    ),
                )
            }
        }
    }

    /** Beats at T, then 2T and 4T later... (T = the job's timeout or [defaultTimeoutMs]); the interval stops at the cap. */
    private fun heartbeatDue(w: Watch, timeoutMinutes: Int?, now: Long): Boolean {
        val base = timeoutMinutes?.let { if (it <= 0) Long.MAX_VALUE else it * 60_000L } ?: defaultTimeoutMs()
        if (base == Long.MAX_VALUE || base <= 0) return false
        if (w.interval == 0L) { w.interval = base; w.nextBeatAt = w.startedAt + base }
        if (now < w.nextBeatAt) return false
        val cap = heartbeatCapMs().coerceAtLeast(base)
        w.interval = (w.interval * 2).coerceAtMost(cap)
        w.nextBeatAt = now + w.interval
        return true
    }

    private fun heartbeat(sid: String, job: BgJob, w: Watch, now: Long) {
        w.beatsSent++
        val lines = tailOf(job, null)
        val logState = when {
            w.readError != null -> "the watcher could not read the log (${w.readError})"
            job.logPath == null -> "no log (output not watched${job.optOut?.let { ": watch OFF, $it" } ?: ""})"
            !File(job.logPath).isFile -> "the log is gone (${job.logPath.takeLast(80)})"
            lines.isEmpty() || now - w.lastOutputAt >= 60_000L -> "no output since ${java.time.Instant.ofEpochMilli(w.lastOutputAt)}"
            else -> "last output ${fmt(now - w.lastOutputAt)} ago"
        }
        val proc = if (alive(job)) "its process is still running" else "its process is GONE (the end report has not arrived)"
        deliver(
            sid, job,
            mail(
                sid, job,
                subject = "[AUTOMATIC] bg job ${job.id} still running after ${elapsed(job)}: ${job.label.take(60)}",
                head = "Background job ${job.id} (${job.label}) is still running after ${elapsed(job)}; $logState; $proc. " +
                    "Decide: keep waiting (end your turn again; next status in ${fmt(w.interval)}), investigate " +
                    "(`agenthq bg stop ${job.id}` if it is hung), or report to the PM.",
                matched = emptyList(),
                lines = lines,
            ),
            WatchMeter::heartbeat,
        )
        val cap = heartbeatCapMs()
        if (!w.pmTold && w.interval >= cap) {
            w.pmTold = true
            pmOf(sid)?.let { pm ->
                send(
                    MailSendRequest(
                        from = FinishNoticeMonitor.SYSTEM, to = pm, threadId = "bg:${job.id}:pm",
                        subject = "[AUTOMATIC] worker ${sid.take(8)} has waited ${elapsed(job)} on bg job ${job.id} with no end",
                        body = "Session $sid has waited ${elapsed(job)} on `${job.label.take(80)}` ($logState; $proc). " +
                            "Status mails go on every ${fmt(cap)}.\n",
                    ),
                )
            }
        }
    }

    private fun optedOut(sid: String, job: BgJob) {
        WatchMeter.optOut()
        println("[agentHq] watch OFF for session $sid: ${job.label.take(80)} (reason: ${job.optOut})")
        pmOf(sid)?.let { pm ->
            send(
                MailSendRequest(
                    from = FinishNoticeMonitor.SYSTEM, to = pm, threadId = "optout:$sid",
                    subject = "[AUTOMATIC] watch OFF: ${sid.take(8)} runs `${job.label.take(50)}` unwatched",
                    body = "Session $sid opted out of the output watch for `${job.label.take(80)}`: ${job.optOut}\n",
                ),
            )
        }
    }

    private fun alive(job: BgJob): Boolean = runCatching { pidAlive(job.cmdPid ?: job.pid) }.getOrDefault(true)

    /** Reads the bytes appended to [job]'s log since the last pass and records the lines that match. */
    private fun readNew(job: BgJob, w: Watch, patterns: List<Regex>, now: Long) {
        val f = job.logPath?.let { File(it) }?.takeIf { it.isFile } ?: return
        val len = f.length()
        // Truncated or rotated (a new, shorter file at the same path): start again from its beginning.
        if (len < w.offset) { w.offset = 0L; w.partial = "" }
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
            if (!l.startsWith("[agenthq:")) {
                w.lastOutputAt = now
                w.quietSent = false
            }
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

    private fun elapsed(job: BgJob): String = fmt(clock() - job.startedAt)

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
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

/**
 * STALL-1: what the watcher, the heartbeat and the stall detector did, as COUNTS only. None of them ever calls a model:
 * they read files and pids and send mail; a token is spent only by the turn a delivered notice starts.
 */
object WatchMeter {
    private val events = java.util.concurrent.atomic.AtomicLong()
    private val heartbeats = java.util.concurrent.atomic.AtomicLong()
    private val optOuts = java.util.concurrent.atomic.AtomicLong()
    private val stalls = java.util.concurrent.atomic.AtomicLong()
    private val hookRewrites = java.util.concurrent.atomic.AtomicLong()
    private val hookOverrides = java.util.concurrent.atomic.AtomicLong()

    fun event() { events.incrementAndGet() }
    fun heartbeat() { heartbeats.incrementAndGet() }
    fun optOut() { optOuts.incrementAndGet() }
    fun stall() { stalls.incrementAndGet() }
    /** STALL-2: a harness hook routed a shell command through the runner. */
    fun hookRewrite() { hookRewrites.incrementAndGet() }
    /** STALL-2: another harness hook's rewrite replaced ours; the command ran unwatched. */
    fun hookOverride() { hookOverrides.incrementAndGet() }

    @kotlinx.serialization.Serializable
    data class Counts(
        val events: Long,
        val heartbeats: Long,
        val optOuts: Long,
        val stallNudges: Long,
        val hookRewrites: Long = 0,
        val hookOverrides: Long = 0,
        val note: String = NOTE,
    )

    const val NOTE = "watcher, heartbeat and stall detector use no model tokens"

    fun counts() = Counts(events.get(), heartbeats.get(), optOuts.get(), stalls.get(), hookRewrites.get(), hookOverrides.get())
}
