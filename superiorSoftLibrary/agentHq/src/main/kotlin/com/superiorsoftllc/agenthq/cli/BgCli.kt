package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.session.BgJob
import com.superiorsoftllc.agenthq.session.BgJobRequest
import com.superiorsoftllc.agenthq.session.BgNotify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * `agenthq bg run [--name <label>] [--keep] -- <cmd...>` (`--keep`: the job survives its session closing) and `bg list|status|logs|stop`: a background job the AGENT registers, so
 * the daemon knows the session is running without reading the OS process tree (which loses a detached job).
 *
 * `run` executes the command, tees its output to a log file, registers the job under `AGENTHQ_SESSION_ID`, heartbeats
 * every [BEAT_MS] while the command lives and deregisters when it ends. A wrapper that dies without saying so is
 * dropped by the daemon (its pid is probed). Log files live in `<tmp>/agenthq-bg/<id>.log`, the exit code in `<id>.exit`.
 *
 * BG-NOTIFY (ticket 245a7d31): `bg run --notify [--notify-on REGEX]... [--no-default-patterns] [--notify-tail N]
 * [--notify-timeout MIN] -- <cmd...>` DETACHES: it starts the wrapper as its own process (outside the harness's shell
 * tree, so ending the turn does not end the job), prints the job id and returns at once. The daemon then mails the
 * session when the job exits, when a pattern matches its output, or when it is still running after the timeout
 * ([com.superiorsoftllc.agenthq.mailbox.BgJobNotifier]); the mail wakes an idle agent. Patterns: every `--notify-on`, plus
 * (unless `--no-default-patterns`) the newline-separated regexes of `AGENTHQ_BG_NOTIFY_PATTERNS`, else [DEFAULT_PATTERNS].
 * `bg wait <id> [--timeout MIN]` is the blocking fallback for a harness mail cannot wake: it returns with the job's exit
 * code and last lines (124 when MIN ran out first).
 */
internal object BgCli {
    const val BEAT_MS = 15_000L
    private val json = Json { ignoreUnknownKeys = true }

    /** Built-in `--notify` patterns: Gradle's verdict and Kotlin compiler errors. Replaced by `AGENTHQ_BG_NOTIFY_PATTERNS`. */
    val DEFAULT_PATTERNS = listOf("BUILD SUCCESSFUL", "BUILD FAILED", "^e: ")
    const val PATTERNS_ENV = "AGENTHQ_BG_NOTIFY_PATTERNS"
    private const val MAIN_CLASS = "com.superiorsoftllc.agenthq.cli.CliMainKt"

    sealed interface Parsed {
        data class Run(
            val name: String?,
            val command: List<String>,
            val keep: Boolean = false,
            val notify: Boolean = false,
            val notifyOn: List<String> = emptyList(),
            val defaultPatterns: Boolean = true,
            val notifyTail: Int? = null,
            val notifyTimeout: Int? = null,
            /** Internal: set on the detached wrapper a `--notify` run starts, so both agree on the id. */
            val jobId: String? = null,
        ) : Parsed {
            /** The options again, as the detached wrapper's arguments (before `--`). */
            fun optionArgs(): List<String> = buildList {
                name?.let { add("--name"); add(it) }
                if (keep) add("--keep")
                if (notify) add("--notify")
                notifyOn.forEach { add("--notify-on"); add(it) }
                if (!defaultPatterns) add("--no-default-patterns")
                notifyTail?.let { add("--notify-tail"); add(it.toString()) }
                notifyTimeout?.let { add("--notify-timeout"); add(it.toString()) }
                jobId?.let { add("--job-id"); add(it) }
            }
        }
        data class Wait(val id: String, val timeoutMin: Long?) : Parsed
        data class Show(val verb: String, val id: String?) : Parsed
        data class Bad(val message: String) : Parsed
    }

    /** [args] are the words after `bg`. Everything after the first `--` is the command, untouched. */
    fun parse(args: List<String>): Parsed {
        val verb = args.firstOrNull() ?: return Parsed.Bad("bg expects run, list, status, logs, stop or wait")
        val rest = args.drop(1)
        return when (verb) {
            "run" -> {
                val split = rest.indexOf("--")
                val opts = if (split >= 0) rest.take(split) else rest
                val command = if (split >= 0) rest.drop(split + 1) else emptyList()
                var name: String? = null
                var keep = false
                var notify = false
                val notifyOn = ArrayList<String>()
                var defaults = true
                var tail: Int? = null
                var timeout: Int? = null
                var jobId: String? = null
                var i = 0
                while (i < opts.size) {
                    when (opts[i]) {
                        "--name" -> name = opts.getOrNull(++i)?.takeIf { it.isNotBlank() } ?: return Parsed.Bad("--name expects a label")
                        "--keep" -> keep = true
                        "--notify" -> notify = true
                        "--notify-on" -> {
                            val re = opts.getOrNull(++i)?.takeIf { it.isNotEmpty() } ?: return Parsed.Bad("--notify-on expects a regex")
                            val bad = runCatching { Regex(re) }.exceptionOrNull()
                            if (bad != null) return Parsed.Bad("--notify-on: bad regex '$re' (${bad.message})")
                            notifyOn += re
                            notify = true
                        }
                        "--no-default-patterns" -> defaults = false
                        "--notify-tail" -> tail = opts.getOrNull(++i)?.toIntOrNull()?.takeIf { it > 0 }
                            ?: return Parsed.Bad("--notify-tail expects a positive number of lines")
                        "--notify-timeout" -> timeout = opts.getOrNull(++i)?.toIntOrNull()?.takeIf { it >= 0 }
                            ?: return Parsed.Bad("--notify-timeout expects minutes (0 = never)")
                        "--job-id" -> jobId = opts.getOrNull(++i)?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,40}")) }
                            ?: return Parsed.Bad("--job-id expects an id")
                        else -> return Parsed.Bad("bg run: unknown option '${opts[i]}' (put the command after --)")
                    }
                    i++
                }
                if (command.isEmpty()) Parsed.Bad("bg run expects a command after --, e.g. agenthq bg run --name gate -- gradlew test")
                else Parsed.Run(name, command, keep, notify, notifyOn, defaults, tail, timeout, jobId)
            }
            "wait" -> {
                val id = rest.firstOrNull()?.takeIf { !it.startsWith("--") } ?: return Parsed.Bad("bg wait expects a job id")
                when {
                    rest.size == 1 -> Parsed.Wait(id, null)
                    rest.size == 3 && rest[1] == "--timeout" ->
                        rest[2].toLongOrNull()?.takeIf { it > 0 }?.let { Parsed.Wait(id, it) } ?: Parsed.Bad("--timeout expects minutes")
                    else -> Parsed.Bad("bg wait expects: <id> [--timeout MIN]")
                }
            }
            "list" -> if (rest.isEmpty()) Parsed.Show("list", null) else Parsed.Bad("bg list takes no arguments")
            "status", "logs", "stop" ->
                if (rest.size == 1) Parsed.Show(verb, rest[0]) else Parsed.Bad("bg $verb expects one job id")
            else -> Parsed.Bad("unknown bg command '$verb' (run, list, status, logs, stop, wait)")
        }
    }

    fun logDir(): File = File(System.getProperty("java.io.tmpdir"), "agenthq-bg")

    fun main(
        args: List<String>,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
        /** Global options (`--port N`) the detached `--notify` wrapper must be started with too. */
        globals: List<String> = emptyList(),
        open: () -> FrameStream,
    ): Int {
        val parsed = parse(args)
        if (parsed is Parsed.Bad) {
            err.appendLine("error: ${parsed.message}")
            return 2
        }
        val session = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
        return try {
            when (parsed) {
                is Parsed.Run ->
                    if (parsed.notify && parsed.jobId == null) detach(parsed, session, globals, getenv, out, err)
                    else run(parsed, session, getenv, out, err, open)
                is Parsed.Wait -> waitFor(parsed, session, out, err, open)
                is Parsed.Show -> show(parsed, session, out, err, open)
                is Parsed.Bad -> 2
            }
        } catch (e: Exception) {
            err.appendLine("error: ${e.message}")
            1
        }
    }

    private fun send(stream: FrameStream, session: String?, req: BgJobRequest): List<BgJob> = runBlocking {
        val answer = stream.request(
            Envelope(Envelope.BG_JOB, json.encodeToString(BgJobRequest.serializer(), req), session),
            answerType = Envelope.BG_JOB,
            what = "bg_job",
            idempotent = true,
        )
        json.decodeFromString(ListSerializer(BgJob.serializer()), answer.data)
    }

    /** The patterns a `--notify` job is watched for: its own, plus the env list (or the built-in ones). */
    fun patternsFor(p: Parsed.Run, getenv: (String) -> String?): List<String> {
        val defaults = if (!p.defaultPatterns) emptyList()
        else getenv(PATTERNS_ENV)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() && runCatching { Regex(it) }.isSuccess }
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_PATTERNS
        return (p.notifyOn + defaults).distinct()
    }

    /**
     * `--notify`: start this same CLI as a detached wrapper (`bg run --job-id <id> ...`) and return. The wrapper's parent is
     * this short-lived process only, so once it exits the harness's shell tree no longer holds the job. On Windows `javaw`
     * is used when present, so no console window opens for it (issue I2).
     */
    private fun detach(p: Parsed.Run, session: String?, globals: List<String>, getenv: (String) -> String?, out: Appendable, err: Appendable): Int {
        if (session == null) {
            err.appendLine("error: --notify needs AGENTHQ_SESSION_ID (the session the notice is mailed to); run it from an agent session")
            return 2
        }
        val id = UUID.randomUUID().toString().take(8)
        val dir = logDir().also { it.mkdirs() }
        val javaCmd = ProcessHandle.current().info().command().orElse("java").let { j ->
            val f = File(j)
            val w = File(f.parentFile ?: File("."), "javaw.exe")
            if (f.name.equals("java.exe", ignoreCase = true) && w.isFile) w.path else j
        }
        val args = listOf(javaCmd, "-cp", System.getProperty("java.class.path"), MAIN_CLASS) + globals +
            listOf("bg", "run") + p.copy(jobId = id).optionArgs() + listOf("--") + p.command
        val nul = File(if (System.getProperty("os.name").lowercase().contains("win")) "NUL" else "/dev/null")
        ProcessBuilder(args)
            .redirectInput(ProcessBuilder.Redirect.from(nul))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(File(dir, "$id.wrapper.log"))
            .start()
        out.appendLine("bg job $id started detached: ${p.name ?: p.command.joinToString(" ").take(80)}")
        out.appendLine("log: ${File(dir, "$id.log").absolutePath}")
        out.appendLine(
            "You will get a COMMS mail when it exits (or prints ${patternsFor(p, getenv).joinToString(", ") { "/$it/" }}, " +
                "or runs past its timeout).",
        )
        out.appendLine("End your turn now; do not sleep or poll. (Harness not woken by mail? `agenthq bg wait $id`.)")
        return 0
    }

    private fun run(p: Parsed.Run, session: String?, getenv: (String) -> String?, out: Appendable, err: Appendable, open: () -> FrameStream): Int {
        val id = p.jobId ?: UUID.randomUUID().toString().take(8)
        val log = File(logDir().also { it.mkdirs() }, "$id.log")
        val exitFile = File(logDir(), "$id.exit").also { it.delete() }
        val label = p.name ?: p.command.joinToString(" ").take(80)
        val notify = if (!p.notify) null else BgNotify(p.notifyTail, patternsFor(p, getenv), p.notifyTimeout)
        val child = try {
            ProcessBuilder(p.command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            // `bg wait` must not block forever on a command that never started.
            runCatching { log.writeText("bg run: cannot start ${p.command.joinToString(" ")}: ${e.message}" + System.lineSeparator()) }
            runCatching { exitFile.writeText("127") }
            throw e
        }
        val job = BgJob(id, label, ProcessHandle.current().pid(), System.currentTimeMillis(), logPath = log.absolutePath, keep = p.keep, notify = notify)
        // No session (run outside an agent) or no daemon: the command still runs; only the flag is missing.
        val stream = if (session == null) null else try {
            open()
        } catch (e: Exception) {
            err.appendLine("warning: daemon unreachable, running unregistered (${e.message})")
            null
        }
        fun call(req: BgJobRequest) {
            if (stream == null) return
            try { send(stream, session, req) } catch (e: Exception) { err.appendLine("warning: bg_job: ${e.message}") }
        }
        if (session == null) err.appendLine("warning: AGENTHQ_SESSION_ID is not set, running unregistered")
        out.appendLine("bg job $id: $label (log ${log.absolutePath})")
        call(BgJobRequest("beat", job))
        val beat = Thread {
            try {
                while (true) {
                    Thread.sleep(BEAT_MS)
                    call(BgJobRequest("beat", job))
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
        val keepLines = p.notifyTail ?: 200
        val tail = ArrayDeque<String>()
        child.inputStream.bufferedReader().use { r ->
            log.bufferedWriter().use { w ->
                r.forEachLine { line ->
                    w.appendLine(line)
                    w.flush()
                    out.appendLine(line)
                    tail.addLast(line)
                    if (tail.size > keepLines) tail.removeFirst()
                }
            }
        }
        val code = child.waitFor()
        beat.interrupt()
        runCatching { exitFile.writeText(code.toString()) }
        call(BgJobRequest("done", jobId = id, exitCode = code, tail = tail.toList()))
        stream?.close()
        out.appendLine("bg job $id exited $code")
        return code
    }

    /**
     * `bg wait <id>`: block until the job's exit file appears (the wrapper writes it as the command ends), then print its
     * last lines and return its exit code. The fallback for a harness a mail cannot wake; it costs no model tokens while it
     * blocks. A wrapper that died without an exit file ends the wait with 1; [Parsed.Wait.timeoutMin] running out, 124.
     */
    private fun waitFor(p: Parsed.Wait, session: String?, out: Appendable, err: Appendable, open: () -> FrameStream): Int {
        val exitFile = File(logDir(), "${p.id}.exit")
        val log = File(logDir(), "${p.id}.log")
        fun lookup(): Long? = runCatching {
            val s = open()
            try { send(s, session, BgJobRequest("list")) } finally { s.close() }
        }.getOrNull()?.firstOrNull { it.id == p.id }?.pid
        var pid = lookup()
        // A `--notify` run returns before its detached wrapper has started and registered: give it a few seconds.
        var tries = 0
        while (pid == null && !exitFile.isFile && tries++ < 10) {
            Thread.sleep(1_000)
            pid = lookup()
        }
        if (pid == null && !exitFile.isFile && !log.isFile) {
            err.appendLine("error: no bg job '${p.id}'")
            return 1
        }
        val deadline = p.timeoutMin?.let { System.currentTimeMillis() + it * 60_000L }
        while (!exitFile.isFile) {
            val alive = pid != null && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            if (!alive) {
                Thread.sleep(1_000)
                if (exitFile.isFile) break
                logsOf(log, out, err, 20)
                err.appendLine("bg job ${p.id} ended without an exit code (its wrapper was stopped or died)")
                return 1
            }
            if (deadline != null && System.currentTimeMillis() > deadline) {
                logsOf(log, out, err, 20)
                out.appendLine("bg job ${p.id} still running after ${p.timeoutMin} min; run `agenthq bg wait ${p.id}` again")
                return 124
            }
            Thread.sleep(2_000)
        }
        val code = exitFile.readText().trim().toIntOrNull() ?: 1
        logsOf(log, out, err, 20)
        out.appendLine("bg job ${p.id} exited $code")
        return code
    }

    private fun show(p: Parsed.Show, session: String?, out: Appendable, err: Appendable, open: () -> FrameStream): Int {
        val stream = try {
            open()
        } catch (e: Exception) {
            err.appendLine("error: Infantry AI daemon not running (${e.message ?: e.javaClass.simpleName}) -- start it with `infantry daemon`")
            return 2
        }
        try {
            val jobs = send(stream, session, BgJobRequest("list"))
            if (p.verb == "list") {
                if (jobs.isEmpty()) out.appendLine("No background jobs.")
                for (j in jobs) out.appendLine("${j.id} | pid ${j.pid} | ${j.label} | ${(System.currentTimeMillis() - j.startedAt) / 1000}s" + (if (j.notify != null) " | notify" else ""))
                return 0
            }
            val job = jobs.firstOrNull { it.id == p.id }
            if (job == null) {
                // A finished job's log stays at its well-known path, so `logs` still answers.
                if (p.verb == "logs") return logsOf(File(logDir(), p.id + ".log"), out, err)
                err.appendLine("error: no running bg job '${p.id}'")
                return 1
            }
            return when (p.verb) {
                "status" -> {
                    out.appendLine("${job.id} running | pid ${job.pid} | ${job.label} | ${(System.currentTimeMillis() - job.startedAt) / 1000}s | log ${job.logPath}")
                    0
                }
                "logs" -> logsOf(File(job.logPath ?: return 1), out, err)
                else -> {
                    // stop: end the wrapper's process tree; the wrapper's exit deregisters the job.
                    val handle = ProcessHandle.of(job.pid).orElse(null)
                    if (handle == null) {
                        out.appendLine("job ${job.id} is already gone")
                    } else {
                        handle.descendants().forEach { it.destroyForcibly() }
                        handle.destroyForcibly()
                        out.appendLine("stopped job ${job.id}")
                    }
                    0
                }
            }
        } finally {
            stream.close()
        }
    }

    private fun logsOf(f: File, out: Appendable, err: Appendable, lines: Int = 200): Int {
        if (!f.isFile) {
            err.appendLine("error: no log at ${f.absolutePath}")
            return 1
        }
        f.readLines().takeLast(lines).forEach { out.appendLine(it) }
        return 0
    }
}
