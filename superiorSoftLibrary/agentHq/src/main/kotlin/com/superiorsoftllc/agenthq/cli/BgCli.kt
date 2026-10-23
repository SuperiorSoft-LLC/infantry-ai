package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.session.BgJob
import com.superiorsoftllc.agenthq.session.BgJobRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * `agenthq bg run [--name <label>] -- <cmd...>` and `bg list|status|logs|stop`: a background job the AGENT registers, so
 * the daemon knows the session is running without reading the OS process tree (which loses a detached job).
 *
 * `run` executes the command, tees its output to a log file, registers the job under `AGENTHQ_SESSION_ID`, heartbeats
 * every [BEAT_MS] while the command lives and deregisters when it ends. A wrapper that dies without saying so is
 * dropped by the daemon (its pid is probed). Log files live in `<tmp>/agenthq-bg/<id>.log`.
 */
internal object BgCli {
    const val BEAT_MS = 15_000L
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Parsed {
        data class Run(val name: String?, val command: List<String>) : Parsed
        data class Show(val verb: String, val id: String?) : Parsed
        data class Bad(val message: String) : Parsed
    }

    /** [args] are the words after `bg`. Everything after the first `--` is the command, untouched. */
    fun parse(args: List<String>): Parsed {
        val verb = args.firstOrNull() ?: return Parsed.Bad("bg expects run, list, status, logs or stop")
        val rest = args.drop(1)
        return when (verb) {
            "run" -> {
                val split = rest.indexOf("--")
                val opts = if (split >= 0) rest.take(split) else rest
                val command = if (split >= 0) rest.drop(split + 1) else emptyList()
                var name: String? = null
                var i = 0
                while (i < opts.size) {
                    when (opts[i]) {
                        "--name" -> name = opts.getOrNull(++i)?.takeIf { it.isNotBlank() } ?: return Parsed.Bad("--name expects a label")
                        else -> return Parsed.Bad("bg run: unknown option '${opts[i]}' (put the command after --)")
                    }
                    i++
                }
                if (command.isEmpty()) Parsed.Bad("bg run expects a command after --, e.g. agenthq bg run --name gate -- gradlew test")
                else Parsed.Run(name, command)
            }
            "list" -> if (rest.isEmpty()) Parsed.Show("list", null) else Parsed.Bad("bg list takes no arguments")
            "status", "logs", "stop" ->
                if (rest.size == 1) Parsed.Show(verb, rest[0]) else Parsed.Bad("bg $verb expects one job id")
            else -> Parsed.Bad("unknown bg command '$verb' (run, list, status, logs, stop)")
        }
    }

    fun logDir(): File = File(System.getProperty("java.io.tmpdir"), "agenthq-bg")

    fun main(
        args: List<String>,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
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
                is Parsed.Run -> run(parsed, session, out, err, open)
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

    private fun run(p: Parsed.Run, session: String?, out: Appendable, err: Appendable, open: () -> FrameStream): Int {
        val id = UUID.randomUUID().toString().take(8)
        val log = File(logDir().also { it.mkdirs() }, "$id.log")
        val label = p.name ?: p.command.joinToString(" ").take(80)
        val child = ProcessBuilder(p.command).redirectErrorStream(true).start()
        val job = BgJob(id, label, ProcessHandle.current().pid(), System.currentTimeMillis(), logPath = log.absolutePath)
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
        child.inputStream.bufferedReader().use { r ->
            log.bufferedWriter().use { w ->
                r.forEachLine { line ->
                    w.appendLine(line)
                    w.flush()
                    out.appendLine(line)
                }
            }
        }
        val code = child.waitFor()
        beat.interrupt()
        call(BgJobRequest("done", jobId = id))
        stream?.close()
        out.appendLine("bg job $id exited $code")
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
                for (j in jobs) out.appendLine("${j.id} | pid ${j.pid} | ${j.label} | ${(System.currentTimeMillis() - j.startedAt) / 1000}s")
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

    private fun logsOf(f: File, out: Appendable, err: Appendable): Int {
        if (!f.isFile) {
            err.appendLine("error: no log at ${f.absolutePath}")
            return 1
        }
        f.readLines().takeLast(200).forEach { out.appendLine(it) }
        return 0
    }
}
