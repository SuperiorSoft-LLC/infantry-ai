package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.runner.WatchNotices
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
 *    last lines or "no output since", whether the process still exists. The first beat at the cap is also told to the
 *    recipient rule's address (STALL-4: the ticket's starter, else the user; the text names no role).
 *  - TREE JOB ENDED ([treeJobEnded]): a long process under the harness that the daemon did not launch is gone (layer 5).
 *
 * WORDING (STALL-4): every text comes from [WatchNotices] (resource `watch/notices.json`, setting `watch.notices`).
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
    /** The heartbeat interval stops growing here; the first beat at it is also told to [recipientOf]. */
    private val heartbeatCapMs: () -> Long = { 60 * 60_000L },
    /** Whether [sessionId] has a turn in flight right now. */
    private val inTurn: (sessionId: String) -> Boolean = { false },
    private val pidAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
    /**
     * STALL-4: who else hears about [sessionId] (a long wait at the cap, an opt-out): the daemon's recipient rule
     * ([WatchNotices.ESCALATE_SETTING]), [WatchNotices.USER] = the user through the UI; null = nobody.
     */
    private val recipientOf: (sessionId: String) -> String? = { null },
    /** STALL-4: a notice line is cut to this many characters (`limits.watch.noticeLineChars`). */
    private val lineChars: () -> Int = { 200 },
    /** STALL-4: the most bytes one pass reads from one file (`limits.watch.readMaxKB`). */
    private val readMaxBytes: () -> Long = { 256 * 1024L },
    /** STALL-4: how far from a file's end a notice's tail is read (`limits.watch.tailReadKB`); also the longest partial line kept. */
    private val tailReadBytes: () -> Long = { 64 * 1024L },
    /** STALL-6: drops the still-queued notices of [threadId] to [to] (those whose subject is [subject], null = all); returns how many. */
    private val drop: (threadId: String, to: String, reason: String, subject: String?) -> Int = { _, _, _, _ -> 0 },
    /** STALL-6: `limits.watch.noticeDedupe`: handled / superseded / one-per-kind rules; false = every notice goes out. */
    private val dedupe: () -> Boolean = { true },
    /** STALL-6: how long ended / handled jobs are remembered (`limits.watch.handledKeepMinutes`). */
    private val keepMs: () -> Long = { 24 * 60 * 60_000L },
    /** BG-NATIVE-1: a job that printed a finished pattern and still runs after this long is ended (0 / MAX = hang-watch off). */
    private val hangGraceMs: () -> Long = { 30_000L },
    /** BG-NATIVE-1: ends [job]'s process tree; its normal finished note then fires. */
    private val endTree: (sessionId: String, job: BgJob) -> Unit = { _, _ -> },
) : BgJobListener {
    /** Where the watcher is in one watched file: only the bytes appended since are read. */
    private class Cursor {
        var offset = -1L
        var partial = ""
    }

    private class Watch(val startedAt: Long) {
        val cursors = HashMap<String, Cursor>()
        val matches = ArrayList<String>()
        var firstMatchAt = 0L
        var beatsSent = 0
        var nextBeatAt = 0L
        var interval = 0L
        var escalated = false
        var lastOutputAt = startedAt
        var quietSent = false
        var readError: String? = null
        var errorSent = false
        var optOutSeen = false
        var pausedSent = false
        var hangEnded = false
    }

    private val watches = ConcurrentHashMap<String, Watch>()
    private val regexCache = ConcurrentHashMap<String, Regex?>()
    private val endedAt = ConcurrentHashMap<String, Long>()

    /** STALL-6: a job that ended (its `done`/`expired` ran), kept to supersede its late notices. */
    private class Finished(val sessionId: String, val label: String, val endedAt: Long) { @Volatile var handled = false }

    private val finished = ConcurrentHashMap<String, Finished>()

    /** STALL-6: job id -> when its owner handled it (read its result after the end, or `bg ack`). */
    private val handled = ConcurrentHashMap<String, Long>()

    /** STALL-6: job id -> the one-per-job notice kinds already sent ([KIND_EOF], [KIND_END]). */
    private val sentKinds = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * STALL-6: [sessionId]'s session has seen [jobId]'s result (read its log/status after it ended, or `agenthq bg ack`):
     * its queued notices are dropped and no later one is sent. Returns how many queued notices were dropped.
     */
    fun ack(sessionId: String, jobId: String): Int {
        handled[jobId] = clock()
        finished[jobId]?.handled = true
        return runCatching { drop("bg:$jobId", sessionId, "handled: the job's result was already read", null) }.getOrDefault(0)
    }

    /** True when [jobId] was acked / read after its end, or superseded by a newer job. */
    fun isHandled(jobId: String): Boolean = handled.containsKey(jobId)

    /** When a job of [sessionId] last ended (bg job or a process under its harness); the stall detector reads it. */
    fun lastEndedAt(sessionId: String): Long? = endedAt[sessionId]

    /** Seconds since [jobId] last printed a line of its own, null when it is not watched. */
    fun quietSeconds(jobId: String): Long? = watches[jobId]?.let { (clock() - it.lastOutputAt) / 1000 }

    private fun ended(sessionId: String) { endedAt[sessionId] = clock() }

    /** True when [job]'s notice should go out now (see DELIVERY above). */
    private fun deliverable(sessionId: String, job: BgJob): Boolean = !(job.attached && runCatching { inTurn(sessionId) }.getOrDefault(false))

    private fun deliver(sessionId: String, job: BgJob, req: MailSendRequest, meter: () -> Unit = WatchMeter::event, kind: String? = null) {
        if (!deliverable(sessionId, job)) return
        if (dedupe()) {
            if (handled.containsKey(job.id)) return
            // One 'closed output' and one 'finished' notice per job; a 'closed output' notice after the end is stale.
            if (kind == KIND_EOF && finished.containsKey(job.id)) return
            if (kind != null && !sentKinds.computeIfAbsent(job.id) { ConcurrentHashMap.newKeySet() }.add(kind)) return
        }
        meter()
        send(req)
    }

    private fun t(key: String, job: BgJob, vararg values: Pair<String, Any?>): String =
        WatchNotices.text(key, "id" to job.id, "label" to job.label.take(60), "elapsed" to elapsed(job), *values)

    /**
     * The wrapper said its command ended with [exitCode]; [tail] = its last lines (null: read from the log). [bytes],
     * [eof] and [survivors] are the STALL-1 end facts, [marker] and [pipeNoFail] the STALL-4 ones (null from an older wrapper).
     */
    fun done(
        sessionId: String,
        job: BgJob,
        exitCode: Int?,
        tail: List<String>?,
        bytes: Long? = null,
        eof: Boolean? = null,
        survivors: List<String>? = null,
        marker: String? = null,
        pipeNoFail: Boolean? = null,
    ) {
        if (job.notify == null) return
        ended(sessionId)
        finish(sessionId, job)
        val w = watches.remove(job.id)
        val lines = tailOf(job, tail)
        val verdict = if (exitCode == null) "exit unknown" else "exit $exitCode"
        // STALL-6 (c): `cmd > out.txt; echo EXIT=$?` reports the echo's status, not the command's: say so, never a bare exit 0.
        val wrapperExit = wrapperExitOf(lines)?.takeIf { exitCode == null || it != exitCode.toString() }
        val facts = buildList {
            if (bytes == 0L) add(t("job.done.empty", job))
            else if (bytes != null) add(t("job.done.bytes", job, "bytes" to bytes, "complete" to if (eof == false) "." else ", complete (EOF)."))
            if (eof == false) {
                add(t("job.done.pipeOpen", job, "survivors" to (survivors?.takeIf { it.isNotEmpty() }?.let { " (${it.joinToString("; ") { s -> s.take(100) }})" } ?: "")))
            } else if (!survivors.isNullOrEmpty()) {
                add(t("job.done.survivors", job, "survivors" to survivors.joinToString("; ") { it.take(100) }))
            }
            if (marker != null) add(t("job.done.marker", job, "marker" to marker.take(lineChars())))
            if (pipeNoFail == true) add(t("job.done.pipe", job))
            if (wrapperExit != null) add(t("job.done.wrapperExit", job, "code" to (exitCode ?: "?"), "inner" to wrapperExit))
            else if (exitCode != null && WRAPPER_LABEL.containsMatchIn(job.label)) add(t("job.done.wrapperLabel", job))
        }
        deliver(
            sessionId, job,
            note(sessionId, job, t("job.done.subject", job, "verdict" to verdict + (if (marker != null) ", failure marker" else ""), "noOutput" to if (bytes == 0L) ", no output" else ""), verdict + (if (marker != null) " (failure marker: ${marker.take(lineChars())})" else "")),
            kind = KIND_END,
        )
    }

    override fun expired(sessionId: String, job: BgJob) {
        if (job.notify == null) return
        ended(sessionId)
        finish(sessionId, job)
        val w = watches.remove(job.id)
        // The runner writes `<id>.exit` right after its process wait returns: a runner lost later (or a daemon restart
        // that missed its `done`) still has the real exit code on disk.
        val code = job.logPath?.let { File(it.removeSuffix(".log") + ".exit") }?.takeIf { it.isFile }
            ?.let { runCatching { it.readText().trim().toInt() }.getOrNull() }
        deliver(
            sessionId, job,
            note(sessionId, job, if (code != null) t("job.vanished.subjectExit", job, "exit" to code) else t("job.vanished.subjectUnknown", job), if (code != null) "exit $code" else "exit unknown"),
            kind = KIND_END,
        )
    }

    /** STALL-6: [job] ended: remembered for superseding, and its queued 'closed output, still running' notice is now stale. */
    private fun finish(sessionId: String, job: BgJob) {
        finished[job.id] = Finished(sessionId, job.label, clock())
        if (dedupe()) runCatching { drop("bg:${job.id}", sessionId, "superseded: the job has finished", t("job.eof.subject", job)) }
    }

    /** The exit number a wrapper's last non-blank output line prints (`EXIT=1`), else null. */
    private fun wrapperExitOf(lines: List<String>): String? {
        val last = lines.lastOrNull { it.isNotBlank() } ?: return null
        val re = regex(WatchNotices.text("job.done.wrapperPattern")) ?: return null
        return re.find(last)?.groupValues?.getOrNull(1)
    }

    /** STALL-1: the command closed its output pipe and still runs: told once, never as done. */
    fun eof(sessionId: String, job: BgJob, bytes: Long?) {
        if (job.notify == null) return
        deliver(
            sessionId, job,
            mail(
                sessionId, job,
                subject = t("job.eof.subject", job),
                head = t("job.eof.head", job, "bytes" to (bytes ?: "?")),
                matched = emptyList(),
                lines = tailOf(job, null),
            ),
            kind = KIND_EOF,
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
                subject = WatchNotices.text("tree.ended.subject", "command" to job.command.take(60)),
                threadId = "tree:$sessionId:${job.pid}",
                body = WatchNotices.text("tree.ended.body", "ranFor" to ranFor, "pid" to job.pid, "commandFull" to job.command.take(400)) + "\n",
            ),
        )
    }

    override fun scanned() {
        val now = clock()
        val all = jobs()
        val live = all.filter { it.second.notify != null }
        watches.keys.retainAll(live.map { it.second.id }.toSet())
        if (dedupe()) supersede(all, now)
        for ((sid, job) in live) {
            val notify = job.notify ?: continue
            val w = watches.computeIfAbsent(job.id) { Watch(job.startedAt) }
            if (job.optOut != null && !w.optOutSeen) {
                w.optOutSeen = true
                optedOut(sid, job)
            }
            if (watched(job)) {
                val patterns = notify.patterns.mapNotNull(::regex)
                val err = runCatching {
                    job.logPath?.takeIf { job.logPaused == null }?.let { readNew(it, own = true, w, patterns, now) }
                    job.follow.forEach { readNew(it, own = false, w, patterns, now) }
                }.exceptionOrNull()
                w.readError = err?.let { it.message ?: it.javaClass.simpleName }
            }
            if (job.logPaused != null && !w.pausedSent) {
                w.pausedSent = true
                deliver(
                    sid, job,
                    mail(sid, job, subject = t("job.logPaused.subject", job), head = t("job.logPaused.head", job, "reason" to job.logPaused), matched = emptyList(), lines = emptyList()),
                )
            }
            // BG-NATIVE-1: a finished pattern is never mailed. If the job still runs after the grace, its tree is ended.
            if (w.matches.isNotEmpty()) {
                val grace = hangGraceMs()
                if (grace <= 0 || grace == Long.MAX_VALUE) w.matches.clear()
                else if (now - w.firstMatchAt >= grace && !w.hangEnded) {
                    w.hangEnded = true
                    if (alive(job)) {
                        println("[agentHq] hang-watch: ending job ${job.id} (session $sid): matched '${w.matches.first().take(80)}' and still running after ${fmt(now - w.firstMatchAt)}")
                        runCatching { endTree(sid, job) }
                    }
                    w.matches.clear()
                }
            }
            val beat = heartbeatDue(w, notify.timeoutMinutes, now)
            // The watcher itself failing is reported at once, through the heartbeat (which does not need the log).
            if (beat || (w.readError != null && !w.errorSent)) {
                if (w.readError != null) w.errorSent = true
                heartbeat(sid, job, w, now)
                w.quietSent = true
            } else if (watched(job) && job.logPaused == null && !w.quietSent &&
                quietMs().let { it != Long.MAX_VALUE && it > 0 && now - w.lastOutputAt >= it }
            ) {
                w.quietSent = true
                // STALL-4 item 2c: output redirected to a file the watch does not follow gets one neutral hint.
                val hint = job.redirects.filter { it !in job.follow }.firstOrNull()?.let { "\n" + t("job.quiet.redirectHint", job, "path" to it) } ?: ""
                deliver(
                    sid, job,
                    mail(
                        sid, job,
                        subject = t("job.quiet.subject", job, "quiet" to fmt(now - w.lastOutputAt)),
                        head = t(
                            "job.quiet.head", job, "quiet" to fmt(now - w.lastOutputAt),
                            "state" to WatchNotices.text(if (alive(job)) "job.quiet.running" else "job.quiet.gone"),
                        ) + hint,
                        matched = emptyList(),
                        lines = tailOf(job, null),
                    ),
                )
            }
        }
    }

    /**
     * STALL-6 (b): a job that ended is superseded when the same session started a newer job with the same label after the
     * end: its queued and future notices are dropped. Also forgets ended jobs older than [keepMs].
     */
    private fun supersede(all: List<Pair<String, BgJob>>, now: Long) {
        for ((id, f) in finished) {
            if (now - f.endedAt > keepMs()) { finished.remove(id); handled.remove(id); sentKinds.remove(id); continue }
            if (f.handled) continue
            if (all.any { (sid, j) -> sid == f.sessionId && j.id != id && j.label == f.label && j.startedAt >= f.endedAt }) {
                f.handled = true
                handled[id] = now
                runCatching { drop("bg:$id", f.sessionId, "superseded: a newer job with the same label started", null) }
            }
        }
    }

    /** True when the job has an output to watch: the runner's log, or files it follows. */
    private fun watched(job: BgJob) = job.logPath != null || job.follow.isNotEmpty()

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
        val primary = job.follow.firstOrNull() ?: job.logPath
        val logState = when {
            w.readError != null -> "the watcher could not read the log (${w.readError})"
            primary == null -> "no log (output not watched${job.optOut?.let { ": watch OFF, $it" } ?: ""})"
            !File(primary).isFile -> "the log is gone (${primary.takeLast(80)})"
            lines.isEmpty() || now - w.lastOutputAt >= 60_000L -> "no output since ${java.time.Instant.ofEpochMilli(w.lastOutputAt)}"
            else -> "last output ${fmt(now - w.lastOutputAt)} ago"
        }
        val proc = if (alive(job)) "its process is still running" else "its process is GONE (the end report has not arrived)"
        deliver(
            sid, job,
            mail(
                sid, job,
                subject = t("job.heartbeat.subject", job),
                head = t("job.heartbeat.head", job, "logState" to logState, "proc" to proc, "next" to fmt(w.interval)),
                matched = emptyList(),
                lines = lines,
            ),
            WatchMeter::heartbeat,
        )
        val cap = heartbeatCapMs()
        if (!w.escalated && w.interval >= cap) {
            w.escalated = true
            runCatching { recipientOf(sid) }.getOrNull()?.let { to ->
                send(
                    MailSendRequest(
                        from = FinishNoticeMonitor.SYSTEM, to = to, threadId = "bg:${job.id}:watch",
                        subject = t("escalate.longWait.subject", job, "session" to sid.take(8)),
                        body = t("escalate.longWait.body", job, "sessionFull" to sid, "label" to job.label.take(80), "logState" to logState, "proc" to proc, "cap" to fmt(cap)) + "\n",
                    ),
                )
            }
        }
    }

    private fun optedOut(sid: String, job: BgJob) {
        WatchMeter.optOut()
        println("[agentHq] watch OFF for session $sid: ${job.label.take(80)} (reason: ${job.optOut})")
        runCatching { recipientOf(sid) }.getOrNull()?.let { to ->
            send(
                MailSendRequest(
                    from = FinishNoticeMonitor.SYSTEM, to = to, threadId = "optout:$sid",
                    subject = t("escalate.optOut.subject", job, "session" to sid.take(8), "label" to job.label.take(50)),
                    body = t("escalate.optOut.body", job, "sessionFull" to sid, "label" to job.label.take(80), "reason" to job.optOut) + "\n",
                ),
            )
        }
    }

    private fun alive(job: BgJob): Boolean = runCatching { pidAlive(job.cmdPid ?: job.pid) }.getOrDefault(true)

    /**
     * Reads the bytes appended to [path] since the last pass and records the lines that match. STALL-4 item 4b: never a
     * copy of the file: at most [readMaxBytes] per pass (a bigger jump skips to its tail), a partial line kept to
     * [tailReadBytes] chars; a deleted file reads nothing, a shorter one (truncated, rotated) is read again from its start.
     * In the runner's own log ([own]) its `[agenthq:` lines are not output; in a followed file every byte is.
     */
    private fun readNew(path: String, own: Boolean, w: Watch, patterns: List<Regex>, now: Long) {
        val f = File(path).takeIf { it.isFile } ?: return
        val c = w.cursors.getOrPut(path) { Cursor() }
        val len = f.length()
        // A followed file that already had content: only what is appended from now on is new output.
        if (c.offset < 0) c.offset = if (own) 0L else len
        if (len < c.offset) { c.offset = 0L; c.partial = "" }
        if (len == c.offset) return
        var from = c.offset
        val maxRead = readMaxBytes().coerceAtLeast(1024L)
        if (len - from > maxRead) { from = len - maxRead; c.partial = "" }
        val buf = ByteArray((len - from).toInt())
        var got = 0
        RandomAccessFile(f, "r").use { r ->
            r.seek(from)
            while (got < buf.size) { val k = r.read(buf, got, buf.size - got); if (k < 0) break; got += k }
        }
        c.offset = from + got
        if (got == 0) return
        if (!own) { w.lastOutputAt = now; w.quietSent = false }
        val text = c.partial + String(buf, 0, got, Charsets.UTF_8)
        val parts = text.split('\n')
        c.partial = parts.last().takeLast(tailReadBytes().coerceIn(1024L, Int.MAX_VALUE.toLong()).toInt())
        for (line in parts.dropLast(1)) {
            val l = line.trimEnd('\r')
            if (own && !l.startsWith("[agenthq:")) {
                w.lastOutputAt = now
                w.quietSent = false
            }
            if (patterns.any { it.containsMatchIn(l) }) {
                if (w.matches.isEmpty()) w.firstMatchAt = now
                w.matches += l.take(lineChars())
            }
        }
    }

    private fun regex(p: String): Regex? = regexCache.computeIfAbsent(p) { runCatching { Regex(it) }.getOrNull() }

    /** [job]'s last lines: [given] (the wrapper's), else read from the END of its followed file or log, never the whole file. */
    private fun tailOf(job: BgJob, given: List<String>?): List<String> {
        val n = (job.notify?.tailLines ?: defaultTailLines()).coerceAtLeast(1)
        val lines = given ?: (job.follow.firstOrNull() ?: job.logPath)?.let { readTail(File(it), n, tailReadBytes()) }.orEmpty()
        return lines.takeLast(n)
    }

    private fun elapsed(job: BgJob): String = fmt(clock() - job.startedAt)

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s < 60) "${s}s" else "${s / 60}m${s % 60}s"
    }

    /** STALL-4 item 4c: a notice carries a bounded tail, each line cut to [lineChars]. */
    private fun cut(line: String): String { val max = lineChars().coerceAtLeast(20); return if (line.length <= max) line else line.take(max) + "…" }

    /** BG-NATIVE-1: the ONE-LINE finished note (id, exit, log path); no tail, no hints. The agent reads the log itself. */
    private fun note(sessionId: String, job: BgJob, subject: String, verdict: String) =
        MailSendRequest(
            from = FinishNoticeMonitor.SYSTEM, to = sessionId, subject = subject, threadId = "bg:${job.id}",
            body = "task finished: ${job.id} $verdict log ${job.logPath ?: "-"}\n",
        )

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
                    matched.takeLast(lines.size.coerceAtLeast(1)).forEach { append("  ").append(cut(it)).append('\n') }
                }
                job.logPath?.let { append("log: ").append(it).append('\n') }
                job.follow.forEach { append("following: ").append(it).append('\n') }
                if (lines.isNotEmpty()) {
                    append("last ${lines.size} lines:\n")
                    lines.forEach { append("  ").append(cut(it)).append('\n') }
                }
                if (dedupe()) append(WatchNotices.text("job.ackHint", "id" to job.id)).append('\n')
            },
        )

    companion object {
        private const val KIND_EOF = "eof"
        private const val KIND_END = "end"

        /** A command line that echoes `$?` itself: the watched exit code is the shell's, not the command's. */
        private val WRAPPER_LABEL = Regex("""echo\s+\S*\$\?""")

        /** The last [n] lines of [f], read from at most its last [maxBytes]; empty when it is gone or unreadable. */
        fun readTail(f: File, n: Int, maxBytes: Long): List<String> = runCatching {
            if (!f.isFile) return emptyList()
            RandomAccessFile(f, "r").use { r ->
                val len = r.length()
                val from = (len - maxBytes.coerceAtLeast(1024L)).coerceAtLeast(0L)
                val buf = ByteArray((len - from).toInt())
                r.seek(from)
                var got = 0
                while (got < buf.size) { val k = r.read(buf, got, buf.size - got); if (k < 0) break; got += k }
                val lines = String(buf, 0, got, Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }
                // A read that starts mid-file starts mid-line; a file ending in a newline leaves one empty last part.
                (if (from > 0) lines.drop(1) else lines).dropLastWhile { it.isEmpty() }.takeLast(n)
            }
        }.getOrDefault(emptyList())
    }
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
