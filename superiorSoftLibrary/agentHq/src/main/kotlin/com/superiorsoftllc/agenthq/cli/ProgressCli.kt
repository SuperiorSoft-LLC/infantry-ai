package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.session.ProgressReducer
import com.superiorsoftllc.agenthq.session.ProgressReport
import com.superiorsoftllc.agenthq.session.SessionProgress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Contract 1.117.0 (ticket-1371d44b): `agenthq progress ...`, `agenthq progress --watch` and `agenthq run -- <cmd>`.
 *
 * HARMLESS OUTSIDE INFANTRY AI, by construction: with no `AGENTHQ_SESSION_ID`, or a daemon that cannot be reached or
 * refuses, every report is dropped silently, every progress command exits 0 and prints nothing, and `--watch` / `run`
 * still copy the bytes through unchanged and keep the wrapped command's exit code. Reports go out on a background
 * thread, so a slow daemon never stalls the pipe. Only the parsed marker fields ever leave the machine's pipe; the
 * output text itself is never sent. `AGENTHQ_PROGRESS_DEBUG=1` prints why a report was dropped, on stderr.
 */
internal object ProgressCli {

    const val DEBUG_ENV = "AGENTHQ_PROGRESS_DEBUG"

    /**
     * Set to `1` on the command `agenthq run` wraps: its `##infantry` lines are already read, so a script that can also
     * call `agenthq progress` itself (scripts/agenthq-gate.sh) prints markers only and does not report twice.
     */
    const val PIPE_ENV = "AGENTHQ_PROGRESS_PIPE"

    /** How long a command waits, at the end, for its last reports to reach the daemon. */
    const val FLUSH_MS = 3_000L

    /** How often a streaming command sends a numeric update at most (a step / plan / done always goes out). */
    const val COALESCE_MS = 250L
    const val HEARTBEAT_MS = 5_000L

    val USAGE = """
        |agenthq progress <0-100> ["label"]            percent
        |agenthq progress plan "a" "b" ... | plan <N>   declare checkpoints (names, or a count) [--label x]
        |agenthq progress step ["name"]                 that step completed (no name: the current one); unplanned names are added
        |agenthq progress bytes <done> <total|?> ["label"]   a transfer (speed is derived)
        |agenthq progress count <done> <total|?> ["label"]   items done of a total
        |agenthq progress status "label"                indeterminate: a label and an elapsed timer, no number
        |agenthq progress estimate "label" (= status "label" --estimate)  indeterminate + "~N min left (estimated)" from this label's past runs (opt-in; prefer a measurement)
        |agenthq progress done ["label"] | fail ["reason"] | clear | show
        |agenthq progress --watch [--label x [--estimate]] [--reader gradle|junit|gate]...   stdin -> stdout unchanged, reading ##infantry markers
        |agenthq run [--label x] [--estimate] [--reader r]... -- <command...>    run it, output and exit code unchanged; done on 0, failed otherwise
        |Markers (any language, one line): ##infantry progress 40 compile | ##infantry plan a|b|c | ##infantry plan 4
        |  ##infantry step [name] | ##infantry bytes <done> <total> [label] | ##infantry count <done> <total> [label]
        |  ##infantry status [--estimate] <label> | ##infantry estimate <label> | ##infantry done [label] | ##infantry fail [reason] | ##infantry clear
        |Outside Infantry AI (no AGENTHQ_SESSION_ID, no daemon) every progress command does nothing and exits 0.
    """.trimMargin()

    /** `progress <args>`. Never fails the caller: a usage error is reported on stderr, and still exits 0 outside a session. */
    fun progress(
        args: List<String>,
        stdin: InputStream,
        stdout: OutputStream,
        err: Appendable,
        getenv: (String) -> String?,
        open: () -> FrameStream,
    ): Int {
        if (args.firstOrNull() == "--help" || args.firstOrNull() == "-h") {
            stdout.write((USAGE + "\n").toByteArray())
            stdout.flush()
            return 0
        }
        val reporter = ProgressReporter(session(getenv), open, err, debug(getenv))
        if (args.firstOrNull() == "--watch") {
            val opts = parseStreamOptions(args.drop(1), allowCommand = false)
            if (opts.bad != null) {
                err.appendLine("error: progress --watch: ${opts.bad}")
                return 0.also { pump(stdin, stdout, null, reporter, emptyList()) }
            }
            opts.label?.let { reporter.submit(ProgressReport("status", label = it, estimate = if (opts.estimate) true else null)) }
            pump(stdin, stdout, null, reporter, opts.readers)
            reporter.close(FLUSH_MS)
            return 0
        }
        val report = parseCommand(args)
        if (report == null) {
            err.appendLine("error: progress: unknown form '${args.joinToString(" ").take(80)}'\n$USAGE")
            return if (reporter.enabled) 2 else 0
        }
        if (report.op == "get") {
            val current = reporter.query()
            if (current != null) {
                stdout.write((ProgressReducer.line(current, System.currentTimeMillis()) + "\n").toByteArray())
                stdout.flush()
            }
            reporter.close(0)
            return 0
        }
        reporter.submit(report)
        reporter.close(FLUSH_MS)
        return 0
    }

    /** `run [--label x] [--reader r]... -- <command...>`: the child's exit code, or 127 when it cannot be started. */
    fun run(
        args: List<String>,
        stdout: OutputStream,
        stderr: OutputStream,
        err: Appendable,
        getenv: (String) -> String?,
        open: () -> FrameStream,
    ): Int {
        val opts = parseStreamOptions(args, allowCommand = true)
        if (opts.bad != null || opts.command.isEmpty()) {
            err.appendLine("error: run: ${opts.bad ?: "expects a command after --, e.g. agenthq run --label gate -- ./gradlew test"}")
            return 2
        }
        val child = try {
            ProcessBuilder(opts.command).redirectInput(ProcessBuilder.Redirect.INHERIT)
                .also { it.environment()[PIPE_ENV] = "1" }
                .start()
        } catch (e: Exception) {
            err.appendLine("agenthq run: cannot run '${opts.command.first()}': ${e.message}")
            return 127
        }
        val reporter = ProgressReporter(session(getenv), open, err, debug(getenv))
        reporter.submit(
            ProgressReport(
                "status", label = opts.label ?: opts.command.joinToString(" ").take(ProgressReducer.MAX_LABEL),
                estimate = if (opts.estimate) true else null,
            ),
        )
        val scanner = LineScanner(reporter, opts.readers)
        val errPump = Thread { pump(child.errorStream, stderr, scanner, reporter, opts.readers) }.apply { isDaemon = true; start() }
        pump(child.inputStream, stdout, scanner, reporter, opts.readers)
        val code = child.waitFor()
        errPump.join(2_000)
        reporter.submit(if (code == 0) ProgressReport("done") else ProgressReport("fail", label = "exit $code"))
        reporter.close(FLUSH_MS)
        return code
    }

    private fun session(getenv: (String) -> String?): String? = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
    private fun debug(getenv: (String) -> String?): Boolean = getenv(DEBUG_ENV)?.trim() == "1"

    class StreamOptions(val label: String?, val readers: List<ProgressReader>, val command: List<String>, val bad: String?, val estimate: Boolean = false)

    fun parseStreamOptions(args: List<String>, allowCommand: Boolean): StreamOptions {
        val split = args.indexOf("--")
        val opts = if (split >= 0) args.take(split) else args
        val command = if (split >= 0) args.drop(split + 1) else emptyList()
        if (!allowCommand && split >= 0) return StreamOptions(null, emptyList(), emptyList(), "takes no command (use agenthq run -- <cmd>)")
        var label: String? = null
        var estimate = false
        val readers = mutableListOf<ProgressReader>()
        var i = 0
        while (i < opts.size) {
            when (opts[i]) {
                "--label" -> label = opts.getOrNull(++i) ?: return StreamOptions(null, emptyList(), command, "--label expects a label")
                // PROGRESS-S5: opt-in only; the label is the key past runs are remembered by.
                "--estimate" -> estimate = true
                "--reader" -> {
                    val name = opts.getOrNull(++i)
                    readers += ProgressReaders.byName(name) ?: return StreamOptions(
                        null, emptyList(), command, "--reader expects one of ${ProgressReaders.NAMES.joinToString(", ")}",
                    )
                }
                else -> return StreamOptions(null, emptyList(), command, "unknown option '${opts[i].take(40)}'" + if (allowCommand) " (put the command after --)" else "")
            }
            i++
        }
        if (estimate && !allowCommand && label == null) return StreamOptions(null, emptyList(), command, "--estimate needs --label (past runs are remembered by it)")
        return StreamOptions(label, readers, command, null, estimate)
    }

    /** The CLI words after `progress` as one report; null = not a form this command knows. */
    fun parseCommand(args: List<String>): ProgressReport? {
        val verb = args.firstOrNull() ?: return null
        val rest = args.drop(1)
        verb.removeSuffix("%").toDoubleOrNull()?.let { return ProgressReport("percent", percent = it, label = rest.joinToString(" ").ifBlank { null }) }
        return when (verb) {
            "plan" -> {
                var label: String? = null
                var count: Int? = null
                val names = mutableListOf<String>()
                var i = 0
                while (i < rest.size) {
                    when (rest[i]) {
                        "--label" -> label = rest.getOrNull(++i) ?: return null
                        "--count" -> count = rest.getOrNull(++i)?.toIntOrNull() ?: return null
                        else -> names += rest[i]
                    }
                    i++
                }
                if (count == null && names.size == 1) names.single().toIntOrNull()?.let { count = it; names.clear() }
                if (names.isEmpty() && (count ?: 0) <= 0) null else ProgressReport("plan", steps = names, count = count, label = label)
            }
            "step" -> ProgressReport("step", step = rest.joinToString(" ").ifBlank { null })
            "bytes", "count" -> amounts(verb, rest)
            "status", "start" -> {
                val estimate = "--estimate" in rest
                val label = rest.filter { it != "--estimate" }.joinToString(" ").ifBlank { null }
                if (estimate && label == null) null else ProgressReport("status", label = label, estimate = if (estimate) true else null)
            }
            "estimate" -> rest.joinToString(" ").ifBlank { null }?.let { ProgressReport("status", label = it, estimate = true) }
            "done" -> ProgressReport("done", label = rest.joinToString(" ").ifBlank { null })
            "fail" -> ProgressReport("fail", label = rest.joinToString(" ").ifBlank { null })
            "clear" -> if (rest.isEmpty()) ProgressReport("clear") else null
            "show", "get" -> if (rest.isEmpty()) ProgressReport("get") else null
            else -> null
        }
    }

    internal fun amounts(op: String, rest: List<String>): ProgressReport? {
        val done = rest.getOrNull(0)?.toLongOrNull() ?: return null
        val totalWord = rest.getOrNull(1) ?: return ProgressReport(op, done = done)
        val total = if (totalWord == "?") null else totalWord.toLongOrNull() ?: return null
        return ProgressReport(op, done = done, total = total, label = rest.drop(2).joinToString(" ").ifBlank { null })
    }

    /**
     * Copies [input] to [output] byte for byte (flushing every chunk, so a live log stays live), while a [LineScanner]
     * looks at each complete line. A closed [output] (the reader went away) stops the copy, never the scan.
     */
    private fun pump(input: InputStream, output: OutputStream, shared: LineScanner?, reporter: ProgressReporter, readers: List<ProgressReader>) {
        val scanner = shared ?: LineScanner(reporter, readers)
        val buffer = ByteArray(8192)
        val line = LineBuffer()
        var writable = true
        while (true) {
            val n = try { input.read(buffer) } catch (_: Exception) { -1 }
            if (n < 0) break
            if (writable) {
                try {
                    output.write(buffer, 0, n)
                    output.flush()
                } catch (_: Exception) {
                    writable = false
                }
            }
            line.feed(buffer, n) { scanner.line(it) }
        }
        line.finish { scanner.line(it) }
    }

    /** Splits bytes into lines (LF; a trailing CR is dropped). A line longer than [MAX] is skipped, never buffered whole. */
    private class LineBuffer {
        private val bytes = ByteArrayOutputStream()
        private var overflow = false

        fun feed(buffer: ByteArray, n: Int, onLine: (String) -> Unit) {
            var start = 0
            for (i in 0 until n) {
                if (buffer[i] == '\n'.code.toByte()) {
                    append(buffer, start, i - start)
                    emit(onLine)
                    start = i + 1
                }
            }
            append(buffer, start, n - start)
        }

        fun finish(onLine: (String) -> Unit) {
            if (bytes.size() > 0 || overflow) emit(onLine)
        }

        private fun append(buffer: ByteArray, from: Int, len: Int) {
            if (len <= 0 || overflow) return
            if (bytes.size() + len > MAX) {
                overflow = true
                bytes.reset()
            } else {
                bytes.write(buffer, from, len)
            }
        }

        private fun emit(onLine: (String) -> Unit) {
            if (!overflow) onLine(bytes.toString(Charsets.UTF_8).removeSuffix("\r"))
            bytes.reset()
            overflow = false
        }

        companion object {
            const val MAX = 16 * 1024
        }
    }

    /** Turns lines into reports: a marker always, else each opted-in reader. Thread-safe (stdout and stderr share one). */
    private class LineScanner(private val reporter: ProgressReporter, private val readers: List<ProgressReader>) {
        private var lastSent = 0L

        @Synchronized
        fun line(text: String) {
            if (!reporter.enabled) return
            val now = System.currentTimeMillis()
            val marker = ProgressMarkers.parse(text)
            if (marker != null) {
                reporter.submit(marker)
                lastSent = now
                return
            }
            var sent = false
            for (r in readers) r.read(text, reporter.current).forEach { reporter.submit(it); sent = true }
            if (sent) lastSent = now
            // 1.124.0: a job whose report did not change still shows it is alive ("last output Ns ago").
            else if (now - lastSent >= ProgressCli.HEARTBEAT_MS && reporter.current?.state == SessionProgress.RUNNING) {
                reporter.submit(ProgressReport("output"))
                lastSent = now
            }
        }
    }
}

/**
 * The marker grammar: one line, optional leading whitespace, the prefix `##infantry`, a verb and its words. Anything
 * that does not parse is ignored (null): a marker can never break the program that prints it.
 *
 * ```
 * ##infantry progress <0-100>[%] [label...]
 * ##infantry plan <N> [label...]            ##infantry plan <name>|<name>|...
 * ##infantry step [name...]                 (that step completed; no name = the current one)
 * ##infantry bytes <done> <total|?> [label...]
 * ##infantry count <done> <total|?> [label...]
 * ##infantry status <label...>              (indeterminate)
 * ##infantry estimate <label...>            (indeterminate, opt-in estimate from past runs of the label)
 * ##infantry done [label...] | ##infantry fail [reason...] | ##infantry clear
 * ```
 */
internal object ProgressMarkers {
    const val PREFIX = "##infantry"

    fun parse(line: String): ProgressReport? {
        val t = line.trim()
        if (!t.startsWith(PREFIX) || t.length == PREFIX.length || !t[PREFIX.length].isWhitespace()) return null
        val words = t.substring(PREFIX.length).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val verb = words.firstOrNull() ?: return null
        val rest = words.drop(1)
        val tail = rest.joinToString(" ").ifBlank { null }
        return when (verb) {
            "progress" -> rest.firstOrNull()?.removeSuffix("%")?.toDoubleOrNull()?.takeIf { it.isFinite() }
                ?.let { ProgressReport("percent", percent = it, label = rest.drop(1).joinToString(" ").ifBlank { null }) }
            "plan" -> {
                val n = rest.firstOrNull()?.toIntOrNull()
                if (n != null) {
                    if (n > 0) ProgressReport("plan", count = n, label = rest.drop(1).joinToString(" ").ifBlank { null }) else null
                } else {
                    val names = (tail ?: return null).split('|').map { it.trim() }.filter { it.isNotEmpty() }
                    if (names.isEmpty()) null else ProgressReport("plan", steps = names)
                }
            }
            "step" -> ProgressReport("step", step = tail)
            "bytes", "count" -> ProgressCli.amounts(verb, rest)
            "status" -> rest.filter { it != "--estimate" }.joinToString(" ").ifBlank { null }
                ?.let { ProgressReport("status", label = it, estimate = if ("--estimate" in rest) true else null) }
            "estimate" -> tail?.let { ProgressReport("status", label = it, estimate = true) }
            // 1.124.0: ##infantry eta <seconds> [label...]  (an indeterminate step the worker expects to take that long)
            "eta" -> rest.firstOrNull()?.toLongOrNull()?.takeIf { it > 0 }?.let {
                ProgressReport("status", label = rest.drop(1).joinToString(" ").ifBlank { null }, estimateMs = it * 1000)
            }
            "done" -> ProgressReport("done", label = tail)
            "fail" -> ProgressReport("fail", label = tail)
            "clear" -> ProgressReport("clear")
            else -> null
        }
    }
}

/** An opt-in reader for one tool's own output (`--reader <name>`); it never guesses for a tool it was not asked for. */
internal fun interface ProgressReader {
    /** The reports [line] stands for, given what the job shows now ([current], the CLI's own mirror). */
    fun read(line: String, current: SessionProgress?): List<ProgressReport>
}

internal object ProgressReaders {
    val NAMES = listOf("gradle", "junit", "gate")

    fun byName(name: String?): ProgressReader? = when (name) {
        "gradle" -> gradle()
        "junit" -> junit()
        "gate" -> gate()
        else -> null
    }

    private val GRADLE_TASK = Regex("""^> Task (:\S+)""")

    /** Gradle (`--console=plain`): the task now running, as the label, while the job is not measured another way. */
    fun gradle() = ProgressReader { line, current ->
        val task = GRADLE_TASK.find(line)?.groupValues?.get(1) ?: return@ProgressReader emptyList()
        if (current != null && current.mode != "indeterminate") emptyList()
        else listOf(ProgressReport("status", label = "gradle $task"))
    }

    private val TEST_EVENT = Regex("""^\S.* > .+ (PASSED|FAILED|SKIPPED)\s*$""")
    private val TESTS_COMPLETED = Regex("""(\d+) tests? completed""")

    /** Test runs: each `Class > test PASSED|FAILED|SKIPPED` line counts one; `N tests completed` sets the count. */
    fun junit(): ProgressReader {
        var seen = 0L
        return ProgressReader { line, _ ->
            TESTS_COMPLETED.find(line)?.let { m ->
                seen = m.groupValues[1].toLong()
                return@ProgressReader listOf(ProgressReport("count", done = seen, label = "tests"))
            }
            if (!TEST_EVENT.matches(line)) return@ProgressReader emptyList()
            seen++
            listOf(ProgressReport("count", done = seen, label = "tests"))
        }
    }

    private val GATE_STAGE = Regex("""stage (\d+) of (\d+)[:\s-]*(.*)$""")

    /** Our gate script's `stage k of n [name]`: a plan of n checkpoints, k-1 of them complete. */
    fun gate() = ProgressReader { line, current ->
        val m = GATE_STAGE.find(line) ?: return@ProgressReader emptyList()
        val k = m.groupValues[1].toInt()
        val n = m.groupValues[2].toInt()
        if (n <= 0 || k <= 0) return@ProgressReader emptyList()
        buildList {
            val planned = current?.mode == "steps" && current.stepsTotal == n && current.state == SessionProgress.RUNNING
            if (!planned) add(ProgressReport("plan", count = n, label = m.groupValues[3].trim().ifBlank { null }))
            val done = if (planned) current!!.stepsDone ?: 0 else 0
            repeat((k - 1 - done).coerceAtLeast(0)) { add(ProgressReport("step")) }
        }
    }
}

/**
 * Sends reports to the daemon on one background thread, in order, collapsing a run of numeric updates (`percent`,
 * `bytes`, `count`) to the newest so a fast stream sends at most one every [ProgressCli.COALESCE_MS]. Keeps a local
 * mirror of the value ([current], the same reducer the daemon runs) for the readers. The first connection failure or
 * refusal disables it for the rest of the process: outside Infantry AI it costs one failed connect at most.
 */
internal class ProgressReporter(
    private val session: String?,
    private val open: () -> FrameStream,
    private val err: Appendable,
    private val debug: Boolean,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val lock = Object()
    private val pending = ArrayDeque<ProgressReport>()
    private var closing = false
    @Volatile private var dead = session == null
    private var stream: FrameStream? = null
    private var worker: Thread? = null

    val enabled: Boolean get() = !dead

    /** The CLI's mirror of the session's progress, as this process reported it. */
    @Volatile var current: SessionProgress? = null
        private set

    fun submit(report: ProgressReport) {
        if (dead) return
        current = runCatching { ProgressReducer.apply(current, report, System.currentTimeMillis()) }.getOrElse { return }
        synchronized(lock) {
            val last = pending.lastOrNull()
            if (last != null && last.op == report.op && report.op in NUMERIC) pending.removeLast()
            pending.addLast(report)
            lock.notifyAll()
            if (worker == null) worker = Thread(::drain, "agenthq-progress").apply { isDaemon = true; start() }
        }
    }

    /** `progress show`: the daemon's current value for the session, or null (no session, no daemon, nothing reported). */
    fun query(): SessionProgress? {
        if (dead) return null
        return try {
            val answer = send(ProgressReport("get")) ?: return null
            if (answer.trim() == "null") null else json.decodeFromString(SessionProgress.serializer(), answer)
        } catch (e: Exception) {
            drop(e)
            null
        }
    }

    /** Waits up to [timeoutMs] for the queued reports to go out, then closes the connection. Never throws. */
    fun close(timeoutMs: Long) {
        synchronized(lock) {
            closing = true
            lock.notifyAll()
        }
        worker?.join(timeoutMs.coerceAtLeast(1))
        runCatching { stream?.close() }
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                while (pending.isEmpty() && !closing) lock.wait()
                if (pending.isEmpty()) return
                pending.removeFirst()
            }
            if (dead) continue
            try {
                send(next)
            } catch (e: Exception) {
                drop(e)
            }
            if (next.op in NUMERIC && !synchronized(lock) { closing }) Thread.sleep(ProgressCli.COALESCE_MS)
        }
    }

    private fun send(report: ProgressReport): String? = runBlocking {
        val s = stream ?: open().also { stream = it }
        s.request(
            Envelope(Envelope.PROGRESS, json.encodeToString(ProgressReport.serializer(), report), session),
            answerType = Envelope.PROGRESS,
            what = "progress",
            idempotent = false,
            timeoutMs = 2_000,
        ).data
    }

    private fun drop(e: Exception) {
        dead = true
        if (debug) synchronized(err) { err.appendLine("agenthq progress: report dropped (${e.message ?: e::class.simpleName})") }
    }

    companion object {
        val NUMERIC = setOf("percent", "bytes", "count", "output")
    }
}

/** An [OutputStream] over an [Appendable] (a test's StringBuilder): bytes are decoded as UTF-8 per chunk. */
internal class AppendableOutputStream(private val target: Appendable) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    override fun write(b: ByteArray, off: Int, len: Int) {
        synchronized(target) { target.append(String(b, off, len, Charsets.UTF_8)) }
    }
}

/** Swallows what a connection would print, so a progress command stays silent outside Infantry AI. */
internal object DiscardAppendable : Appendable {
    override fun append(csq: CharSequence?): Appendable = this
    override fun append(csq: CharSequence?, start: Int, end: Int): Appendable = this
    override fun append(c: Char): Appendable = this
}
