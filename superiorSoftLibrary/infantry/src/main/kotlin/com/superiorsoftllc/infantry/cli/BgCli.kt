// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.AccessPolicy
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.session.BgJob
import com.superiorsoftllc.infantry.session.BgJobRequest
import com.superiorsoftllc.infantry.session.BgNotify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * `infantry bg run [--name <label>] [--keep] -- <cmd...>` (`--keep`: the job survives its session closing) and `bg list|status|logs|stop`: a background job the AGENT registers, so
 * the daemon knows the session is running without reading the OS process tree (which loses a detached job).
 *
 * `run` executes the command, tees its output to a log file, registers the job under `INFANTRY_SESSION_ID`, heartbeats
 * every [BEAT_MS] while the command lives and deregisters when it ends. A wrapper that dies without saying so is
 * dropped by the daemon (its pid is probed). Log files live in `<tmp>/infantry-bg/<id>.log`, the exit code in `<id>.exit`.
 *
 * BG-NOTIFY (ticket 245a7d31): `bg run --notify [--notify-on REGEX]... [--no-default-patterns] [--notify-tail N]
 * [--notify-timeout MIN] -- <cmd...>` DETACHES: it starts the wrapper as its own process (outside the harness's shell
 * tree, so ending the turn does not end the job), prints the job id and returns at once. The daemon then mails the
 * session when the job exits, when a pattern matches its output, or when it is still running after the timeout
 * ([com.superiorsoftllc.infantry.mailbox.BgJobNotifier]); the mail wakes an idle agent. Patterns: every `--notify-on`, plus
 * (unless `--no-default-patterns`) the newline-separated regexes of `INFANTRY_BG_NOTIFY_PATTERNS`, else [DEFAULT_PATTERNS].
 * `bg wait <id> [--timeout MIN]` is the blocking fallback for a harness mail cannot wake: it returns with the job's exit
 * code and last lines (124 when MIN ran out first).
 */
internal object BgCli {
    const val BEAT_MS = 15_000L
    private val json = Json { ignoreUnknownKeys = true }

    /** Built-in `--notify` patterns: every tool profile's `notifyOn` (STALL-3). Replaced by `INFANTRY_BG_NOTIFY_PATTERNS`. */
    val DEFAULT_PATTERNS: List<String> get() = com.superiorsoftllc.infantry.runner.ToolProfiles.notifyPatterns()
    const val PATTERNS_ENV = "INFANTRY_BG_NOTIFY_PATTERNS"
    private const val MAIN_CLASS = "com.superiorsoftllc.infantry.cli.CliMainKt"

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
            /** STALL-1: `--attached`: watched (`--notify`) but run in the caller's foreground, not detached. */
            val attached: Boolean = false,
            /** STALL-1: `--shim <name>`: started by the PATH shim for [command]'s argv0; the shim dir is skipped when resolving. */
            val shim: String? = null,
            /**
             * STALL-2: `--hook <harness>`: a harness hook rewrote the worker's shell command into this run
             * ([com.superiorsoftllc.infantry.runner.WatchRewrite]); the runner's own lines go to stderr, like a shim's.
             */
            val hook: String? = null,
            /** STALL-2: `--shell <exe> --script-b64 <b64>`: the command is this script, run by that shell (no `--` part). */
            val shell: String? = null,
            val scriptB64: String? = null,
            /** STALL-4: `--follow <path>` (repeatable): growth of these files counts as the job's output. */
            val follow: List<String> = emptyList(),
        ) : Parsed {
            /** The script a hook passed, decoded; null for a plain `-- <cmd...>` run. */
            val script: String? get() = scriptB64?.let { com.superiorsoftllc.infantry.runner.WatchRewrite.decode(it) }

            /** What the START line and the label show: the script itself, not `bash <file>`. */
            val shown: String get() = script?.replace(Regex("\\s+"), " ") ?: command.joinToString(" ")

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
                if (attached) add("--attached")
                shim?.let { add("--shim"); add(it) }
                hook?.let { add("--hook"); add(it) }
                shell?.let { add("--shell"); add(it) }
                scriptB64?.let { add("--script-b64"); add(it) }
                follow.forEach { add("--follow"); add(it) }
            }
        }
        /**
         * STALL-4: `infantry watch-file <path> [--until-pid N] [--idle-seconds S] [--name L] [--notify-on RE]...`: watch a
         * file another process writes, with the quiet / heartbeat / pattern / done notices of a watched job.
         */
        data class WatchFile(
            val path: String,
            val untilPid: Long? = null,
            val idleSeconds: Long? = null,
            val name: String? = null,
            val notifyOn: List<String> = emptyList(),
            /** Internal: set on the detached wrapper. */
            val jobId: String? = null,
        ) : Parsed {
            fun optionArgs(): List<String> = buildList {
                add(path)
                untilPid?.let { add("--until-pid"); add(it.toString()) }
                idleSeconds?.let { add("--idle-seconds"); add(it.toString()) }
                name?.let { add("--name"); add(it) }
                notifyOn.forEach { add("--notify-on"); add(it) }
                jobId?.let { add("--job-id"); add(it) }
            }
        }
        /** STALL-1: `infantry unwatched --reason <text> -- <cmd...>`: the explicit opt-out; [reason] is required. */
        data class Unwatched(val reason: String, val command: List<String>) : Parsed
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
                var attached = false
                var shim: String? = null
                var hook: String? = null
                var shell: String? = null
                var scriptB64: String? = null
                val follow = ArrayList<String>()
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
                        "--attached" -> { attached = true; notify = true }
                        "--shim" -> shim = opts.getOrNull(++i)?.takeIf { it.isNotBlank() } ?: return Parsed.Bad("--shim expects a program name")
                        "--hook" -> hook = opts.getOrNull(++i)?.takeIf { it.matches(Regex("[a-z0-9-]{1,20}")) } ?: return Parsed.Bad("--hook expects a harness id")
                        "--shell" -> shell = opts.getOrNull(++i) ?: return Parsed.Bad("--shell expects a shell executable")
                        "--script-b64" -> scriptB64 = opts.getOrNull(++i)?.takeIf { s ->
                            runCatching { com.superiorsoftllc.infantry.runner.WatchRewrite.decode(s) }.isSuccess
                        } ?: return Parsed.Bad("--script-b64 expects a base64url script")
                        "--follow" -> follow += opts.getOrNull(++i)?.takeIf { it.isNotBlank() } ?: return Parsed.Bad("--follow expects a file path")
                        else -> return Parsed.Bad("bg run: unknown option '${opts[i]}' (put the command after --)")
                    }
                    i++
                }
                when {
                    scriptB64 != null && command.isNotEmpty() -> Parsed.Bad("bg run: --script-b64 and a command after -- are exclusive")
                    scriptB64 == null && command.isEmpty() -> Parsed.Bad("bg run expects a command after --, e.g. infantry bg run --name tests -- <your build or test command>")
                    else -> Parsed.Run(name, command, keep, notify, notifyOn, defaults, tail, timeout, jobId, attached, shim, hook, shell, scriptB64, follow)
                }
            }
            "unwatched" -> {
                val split = rest.indexOf("--")
                val opts = if (split >= 0) rest.take(split) else rest
                val command = if (split >= 0) rest.drop(split + 1) else emptyList()
                val reason = if (opts.size == 2 && opts[0] == "--reason") opts[1].trim() else ""
                when {
                    reason.isEmpty() -> Parsed.Bad("unwatched needs a reason: infantry unwatched --reason \"<why>\" -- <cmd...>")
                    command.isEmpty() -> Parsed.Bad("unwatched expects a command after --")
                    else -> Parsed.Unwatched(reason, command)
                }
            }
            "watch-file" -> {
                val path = rest.firstOrNull()?.takeIf { !it.startsWith("--") }
                    ?: return Parsed.Bad("watch-file expects a file path: infantry watch-file <path> [--until-pid N] [--idle-seconds S]")
                var untilPid: Long? = null
                var idle: Long? = null
                var name: String? = null
                var jobId: String? = null
                val notifyOn = ArrayList<String>()
                var i = 1
                while (i < rest.size) {
                    when (rest[i]) {
                        "--until-pid" -> untilPid = rest.getOrNull(++i)?.toLongOrNull()?.takeIf { it > 0 } ?: return Parsed.Bad("--until-pid expects a process id")
                        "--idle-seconds" -> idle = rest.getOrNull(++i)?.toLongOrNull()?.takeIf { it > 0 } ?: return Parsed.Bad("--idle-seconds expects a positive number of seconds")
                        "--name" -> name = rest.getOrNull(++i)?.takeIf { it.isNotBlank() } ?: return Parsed.Bad("--name expects a label")
                        "--notify-on" -> {
                            val re = rest.getOrNull(++i)?.takeIf { it.isNotEmpty() } ?: return Parsed.Bad("--notify-on expects a regex")
                            runCatching { Regex(re) }.exceptionOrNull()?.let { return Parsed.Bad("--notify-on: bad regex '$re' (${it.message})") }
                            notifyOn += re
                        }
                        "--job-id" -> jobId = rest.getOrNull(++i)?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,40}")) } ?: return Parsed.Bad("--job-id expects an id")
                        else -> return Parsed.Bad("watch-file: unknown option '${rest[i]}'")
                    }
                    i++
                }
                Parsed.WatchFile(path, untilPid, idle, name, notifyOn, jobId)
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
            "status", "logs", "stop", "ack" ->
                if (rest.size == 1) Parsed.Show(verb, rest[0]) else Parsed.Bad("bg $verb expects one job id")
            else -> Parsed.Bad("unknown bg command '$verb' (run, list, status, logs, stop, wait, ack, watch-file)")
        }
    }

    fun logDir(): File = File(System.getProperty("java.io.tmpdir"), "infantry-bg")

    private fun hostIsWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    /**
     * BG-RESOLVE (ticket bbb6120c): [cmd] with argv[0] turned into the absolute path of the program that will run, or
     * null when there is none. Windows' CreateProcess appends only `.exe` and does not find `.cmd`/`.bat` shims on PATH
     * (`infantry` is `infantry.cmd`), nor a program in the cwd, so `bg run -- gradlew.bat` and `bg run -- infantry run ...`
     * failed with CreateProcess error=2. Here:
     *  - argv[0] with a path separator: taken against [cwd]; on Windows each PATHEXT extension is tried when it has none.
     *  - a bare name on Windows: [cwd] first (cmd.exe semantics), then every PATH dir, each with the PATHEXT extensions.
     *  - a bare name elsewhere: the PATH dirs, for an executable file.
     * The JDK starts a `.cmd`/`.bat` given by its absolute path itself (through cmd.exe, with its own strict quoting), so
     * the resolved path is passed as is; no hand-rolled `cmd.exe /c` quoting.
     */
    fun resolveCommand(
        cmd: List<String>,
        cwd: File,
        getenv: (String) -> String?,
        windows: Boolean = hostIsWindows(),
    ): List<String>? {
        val argv0 = cmd.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val exts = if (!windows) emptyList()
        else (getenv("PATHEXT")?.takeIf { it.isNotBlank() } ?: ".COM;.EXE;.BAT;.CMD")
            .split(';').map { it.trim() }.filter { it.startsWith(".") }
        fun hasExt(name: String) = exts.any { name.endsWith(it, ignoreCase = true) }
        /** The file [f] names, or the first of [f] + each PATHEXT extension (lower case first: case-sensitive test FS). */
        fun probe(f: File): File? {
            if (!windows) return f.takeIf { it.isFile && it.canExecute() }
            if (hasExt(f.name) && f.isFile) return f
            for (e in exts) {
                File(f.path + e.lowercase()).takeIf { it.isFile }?.let { return it }
                File(f.path + e).takeIf { it.isFile }?.let { return it }
            }
            return null
        }
        val given = File(argv0)
        val hasSep = argv0.contains('/') || (windows && argv0.contains('\\')) || given.isAbsolute
        val found = if (hasSep) {
            probe(if (given.isAbsolute) given else File(cwd, argv0))
        } else {
            val path = (getenv("PATH") ?: if (windows) getenv("Path") else null).orEmpty()
            val dirs = path.split(File.pathSeparatorChar).map { it.trim().trim('"') }.filter { it.isNotEmpty() }.map(::File)
            (if (windows) listOf(cwd) + dirs else dirs).firstNotNullOfOrNull { probe(File(it, argv0)) }
        } ?: return null
        return listOf(found.absoluteFile.normalize().path) + cmd.drop(1)
    }

    /**
     * STALL-2: the shell a hooked script runs with: [given] (the harness shell's own path, `$BASH` / PowerShell's `$PID`
     * process) when it resolves, else the same family from PATH (`pwsh`, `powershell` / `bash`, `sh`).
     */
    fun resolveShell(given: String?, cwd: File, getenv: (String) -> String?): String? {
        val g = given?.trim()?.trim('"')?.takeIf { it.isNotEmpty() && !it.contains('$') }
        val family = g?.let { File(it).name.lowercase().substringBefore('.') }
        val fallbacks = if (family == "pwsh" || family == "powershell") listOf("pwsh", "powershell") else if (family == "cmd") listOf("cmd") else listOf("bash", "sh")
        return (listOfNotNull(g) + fallbacks).firstNotNullOfOrNull { resolveCommand(listOf(it), cwd, getenv)?.first() }
    }

    /**
     * STALL-2: the argv that runs [p]'s hooked script, or null when no shell resolves. PowerShell gets it as
     * `-EncodedCommand` (UTF-16LE base64: no quoting layer between); bash/sh/zsh and cmd.exe get a script file next to the
     * job's log, so neither the JDK's Windows argument quoting nor cmd.exe's ever touches the text.
     */
    internal fun scriptCommand(p: Parsed.Run, id: String, cwd: File, getenv: (String) -> String?): List<String>? {
        val script = p.script ?: return null
        val shell = resolveShell(p.shell, cwd, getenv) ?: return null
        val dir = logDir().also { it.mkdirs() }
        return when (File(shell).name.lowercase().substringBefore('.')) {
            "pwsh", "powershell" -> {
                val body = script + "\nif (\$null -ne \$LASTEXITCODE) { exit \$LASTEXITCODE }\n"
                listOf(shell, "-NoProfile", "-NonInteractive", "-EncodedCommand", java.util.Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_16LE)))
            }
            "cmd" -> {
                val f = File(dir, "$id.cmd").apply { writeText("@echo off\r\n" + script.replace("\r\n", "\n").replace("\n", "\r\n") + "\r\n") }
                listOf(shell, "/d", "/c", f.absolutePath)
            }
            else -> {
                // STALL-4 item 3: `a | tail` exits with a's failure, not tail's 0 (a shell without pipefail skips the line).
                val pipefail = if (PIPE.containsMatchIn(script)) "(set -o pipefail) 2>/dev/null && set -o pipefail\n" else ""
                val f = File(dir, "$id.sh").apply { writeText(pipefail + script + "\n") }
                listOf(shell, f.absolutePath.replace('\\', '/'))
            }
        }
    }

    /** First line of a start failure in a job's log; [detach] reads it back to tell "never started" from "ran fast". */
    private const val CANNOT_START = "bg run: cannot start"

    /** The daemon side of a job: send a request; close when done. A seam, so tests can record what a wrapper reports. */
    internal class Reporter(val send: (BgJobRequest) -> Unit, val close: () -> Unit)

    fun main(
        args: List<String>,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
        /** Global options (`--port N`) the detached `--notify` wrapper must be started with too. */
        globals: List<String> = emptyList(),
        open: () -> FrameStream,
    ): Int {
        // STALL-3: the tool profiles the daemon handed this session (its settings merged over the shipped defaults).
        com.superiorsoftllc.infantry.runner.ToolProfiles.active = com.superiorsoftllc.infantry.runner.ToolProfiles.fromEnv(getenv).orEmpty()
        val parsed = parse(args)
        if (parsed is Parsed.Bad) {
            err.appendLine("error: ${parsed.message}")
            return 2
        }
        val session = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
        val cwd = File(System.getProperty("user.dir"))
        // STALL-1: a shim call inside a watched command (npm running npm), or with the watch off, runs the real program.
        if (parsed is Parsed.Run && parsed.shim != null &&
            (!getenv(WATCHED_ENV).isNullOrBlank() || getenv(WATCH_ENV)?.trim()?.lowercase() == "off" || session == null)
        ) {
            val command = resolveCommand(parsed.command, cwd, withoutShimDir(getenv)) ?: run {
                err.appendLine("error: cannot find '${parsed.command.first()}' on PATH (outside the watch shims)")
                return 127
            }
            return try {
                ProcessBuilder(command).directory(cwd).inheritIO().start().waitFor()
            } catch (e: Exception) {
                err.appendLine("error: ${e.message}")
                127
            }
        }
        return try {
            when (parsed) {
                is Parsed.Run ->
                    if (parsed.notify && parsed.jobId == null && !parsed.attached) detach(parsed, session, globals, getenv, out, err, cwd)
                    else runJob(parsed, session, getenv, out, err, cwd) {
                        val s = open()
                        Reporter({ req -> send(s, session, req) }, { s.close() })
                    }
                is Parsed.Unwatched -> unwatched(parsed, session, getenv, err, cwd) {
                    val s = open()
                    Reporter({ req -> send(s, session, req) }, { s.close() })
                }
                is Parsed.WatchFile ->
                    if (parsed.jobId == null) detachWatchFile(parsed, session, globals, out, err, cwd)
                    else watchFile(parsed, session, getenv, err) {
                        val s = open()
                        Reporter({ req -> send(s, session, req) }, { s.close() })
                    }
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
    private fun detach(
        p: Parsed.Run,
        session: String?,
        globals: List<String>,
        getenv: (String) -> String?,
        out: Appendable,
        err: Appendable,
        cwd: File,
    ): Int {
        if (session == null) {
            err.appendLine("error: --notify needs INFANTRY_SESSION_ID (the session the notice is mailed to); run it from an agent session")
            return 2
        }
        // Resolve before going detached: the caller is still in its turn and can read the error (ticket bbb6120c).
        // STALL-2: a hooked script is rebuilt by the wrapper from its options; only its shell is checked here.
        val command = if (p.scriptB64 != null) {
            if (resolveShell(p.shell, cwd, getenv) == null) {
                err.appendLine("error: bg run: no shell found for the hooked command (${p.shell ?: "none given"}); job NOT started")
                return 127
            }
            emptyList()
        } else resolveCommand(p.command, cwd, getenv) ?: run {
            err.appendLine("error: bg run: cannot find '${p.command.first()}' on PATH or in ${cwd.absolutePath}; job NOT started")
            return 127
        }
        val id = UUID.randomUUID().toString().take(8)
        val dir = logDir().also { it.mkdirs() }
        val wrapperLog = File(dir, "$id.wrapper.log")
        val log = File(dir, "$id.log")
        val wrapper = try {
            startWrapper(listOf("bg", "run") + globals + p.copy(jobId = id).optionArgs() + (if (command.isEmpty()) emptyList() else listOf("--") + command), wrapperLog)
        } catch (e: Exception) {
            err.appendLine("error: bg run: cannot start the bg wrapper: ${e.message}; job NOT started")
            return 127
        }
        // Safety net: a wrapper that is already gone, or whose command could not start, never becomes a job to wait on.
        if (wrapper.waitFor(1_500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            val failedStart = runCatching { log.readLines().firstOrNull()?.startsWith(CANNOT_START) == true }.getOrDefault(false)
            val exitWritten = File(dir, "$id.exit").isFile
            if (failedStart || !exitWritten) {
                runCatching { log.readLines().takeLast(20).forEach { err.appendLine(it) } }
                runCatching { wrapperLog.readLines().takeLast(20).forEach { err.appendLine(it) } }
                err.appendLine("error: bg run: the command did not start; job NOT started")
                return wrapper.exitValue().takeIf { it != 0 } ?: 127
            }
        }
        out.appendLine("bg job $id started detached: ${p.name ?: p.shown.take(80)}")
        out.appendLine("log: ${File(dir, "$id.log").absolutePath}")
        out.appendLine(
            "You will get a COMMS mail when it exits (or prints ${patternsFor(p, getenv).joinToString(", ") { "/$it/" }}, " +
                "or runs past its timeout).",
        )
        out.appendLine("End your turn now; do not sleep or poll. (Harness not woken by mail? `infantry bg wait $id`.)")
        return 0
    }

    /** This same CLI started as a detached wrapper with [cliArgs]: no console (javaw on Windows, issue I2), stderr to [wrapperLog]. */
    private fun startWrapper(cliArgs: List<String>, wrapperLog: File): Process {
        val javaCmd = ProcessHandle.current().info().command().orElse("java").let { j ->
            val f = File(j)
            val w = File(f.parentFile ?: File("."), "javaw.exe")
            if (f.name.equals("java.exe", ignoreCase = true) && w.isFile) w.path else j
        }
        val nul = File(if (hostIsWindows()) "NUL" else "/dev/null")
        return ProcessBuilder(listOf(javaCmd, "-cp", System.getProperty("java.class.path"), MAIN_CLASS) + cliArgs)
            .redirectInput(ProcessBuilder.Redirect.from(nul))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(wrapperLog)
            .start()
    }

    /**
     * STALL-4 item 2a, `infantry watch-file <path>`: start the detached watcher and return. The file is never copied: the
     * daemon reads only what is appended ([com.superiorsoftllc.infantry.mailbox.BgJobNotifier]), so the caller ends its
     * turn and spends no tokens until a notice (quiet, heartbeat, pattern, done) arrives.
     */
    private fun detachWatchFile(p: Parsed.WatchFile, session: String?, globals: List<String>, out: Appendable, err: Appendable, cwd: File): Int {
        if (session == null) {
            err.appendLine("error: watch-file needs INFANTRY_SESSION_ID (the session the notices are mailed to); run it from an agent session")
            return 2
        }
        val path = File(p.path).let { if (it.isAbsolute) it else File(cwd, p.path) }.absoluteFile.normalize().path
        val id = UUID.randomUUID().toString().take(8)
        val dir = logDir().also { it.mkdirs() }
        try {
            startWrapper(listOf("bg", "watch-file") + globals + p.copy(path = path, jobId = id).optionArgs(), File(dir, "$id.wrapper.log"))
        } catch (e: Exception) {
            err.appendLine("error: watch-file: cannot start the watcher: ${e.message}")
            return 127
        }
        out.appendLine("watching $path as bg job $id" + (p.untilPid?.let { " until pid $it exits" } ?: ""))
        out.appendLine("You will get a COMMS mail when it goes quiet, matches a pattern, or is done. End your turn now; do not sleep or poll.")
        return 0
    }

    /**
     * STALL-4: the detached `watch-file` wrapper: registers the job (no log of its own, [BgJob.follow] = the file) and
     * beats until it is done: [Parsed.WatchFile.untilPid] exited, or, with no pid, the file has not grown for the idle time
     * ([IDLE_ENV], `limits.watch.fileIdleSeconds`). A deleted or rotated file is not an error: its size is just read again.
     */
    internal fun watchFile(
        p: Parsed.WatchFile,
        session: String?,
        getenv: (String) -> String?,
        err: Appendable,
        pidAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
        pollMs: Long = 1_000L,
        connect: () -> Reporter,
    ): Int {
        val id = p.jobId ?: UUID.randomUUID().toString().take(8)
        val file = File(p.path)
        val idleMs = (p.idleSeconds ?: getenv(IDLE_ENV)?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: 120L) * 1000L
        val notify = BgNotify(patterns = (p.notifyOn + com.superiorsoftllc.infantry.runner.ToolProfiles.notifyPatterns()).distinct())
        val job = BgJob(
            id, p.name ?: "watch ${file.name}".take(80), ProcessHandle.current().pid(), System.currentTimeMillis(),
            notify = notify, cmdPid = p.untilPid, follow = listOf(file.path), idleSeconds = idleMs / 1000,
        )
        val reporter = if (session == null) null else runCatching(connect).getOrElse { err.appendLine("warning: daemon unreachable (${it.message})"); null }
        fun call(req: BgJobRequest) { runCatching { reporter?.send?.invoke(req) }.onFailure { err.appendLine("warning: bg_job: ${it.message}") } }
        call(BgJobRequest("beat", job))
        val started = System.currentTimeMillis()
        var lastLen = if (file.isFile) file.length() else -1L
        var lastGrowth = started
        var grown = 0L
        var nextBeat = started + BEAT_MS
        while (true) {
            Thread.sleep(pollMs)
            val now = System.currentTimeMillis()
            val len = if (file.isFile) file.length() else -1L
            if (len != lastLen) {
                if (len > lastLen && lastLen >= 0) grown += len - lastLen else if (len > 0) grown += len
                lastLen = len
                lastGrowth = now
            }
            val done = p.untilPid?.let { !runCatching { pidAlive(it) }.getOrDefault(false) } ?: (now - lastGrowth >= idleMs)
            if (done) break
            if (now >= nextBeat) { call(BgJobRequest("beat", job)); nextBeat = now + BEAT_MS }
        }
        val tail = com.superiorsoftllc.infantry.mailbox.BgJobNotifier.readTail(file, 200, envLong(getenv, TAIL_READ_ENV, 64) * 1024L)
        call(BgJobRequest("done", jobId = id, exitCode = null, tail = tail, bytes = grown, eof = true))
        runCatching { reporter?.close?.invoke() }
        return 0
    }

    /** STALL-1: the env a watched command's children get, so a nested shim call runs the real program directly. */
    const val WATCHED_ENV = "INFANTRY_WATCHED"
    /** STALL-1: the PATH dir of the shims; skipped when a shim resolves the real program. */
    const val SHIM_DIR_ENV = "INFANTRY_SHIM_DIR"
    /** STALL-1: `off` = the session's shims run the real program unwatched (the owner's `limits.watch.forced` = 0). */
    const val WATCH_ENV = "INFANTRY_WATCH"
    /** STALL-1: seconds between the runner's `still running` lines (default 60; tests set it short). */
    const val BEAT_LINE_ENV = "INFANTRY_WATCH_BEAT_SECONDS"
    /** STALL-2: seconds the runner waits for a command's tree after its exit (`limits.watch.treeWaitMinutes`; 0 = none). */
    const val TREE_WAIT_ENV = "INFANTRY_WATCH_TREE_WAIT_SECONDS"
    const val DEFAULT_TREE_WAIT_SECONDS = 1800L
    /** STALL-4: the runner's space limits (`limits.watch.*`), handed to every session by the daemon. */
    const val LOG_MAX_ENV = "INFANTRY_WATCH_LOG_MAX_MB"
    const val LOG_TOTAL_ENV = "INFANTRY_WATCH_LOG_TOTAL_MB"
    const val KEEP_JOBS_ENV = "INFANTRY_WATCH_LOG_KEEP_JOBS"
    const val KEEP_DAYS_ENV = "INFANTRY_WATCH_LOG_KEEP_DAYS"
    const val MIN_FREE_ENV = "INFANTRY_WATCH_MIN_FREE_MB"
    const val IDLE_ENV = "INFANTRY_WATCH_FILE_IDLE_SECONDS"
    const val SPACE_CHECK_ENV = "INFANTRY_WATCH_SPACE_CHECK_SECONDS"
    const val TAIL_READ_ENV = "INFANTRY_WATCH_TAIL_READ_KB"
    /** STALL-4: `1` = follow the files a command line redirects its output to (`watch.followRedirects`; default off). */
    const val FOLLOW_REDIRECTS_ENV = "INFANTRY_WATCH_FOLLOW_REDIRECTS"

    /** STALL-4: the env [runJob] / [watchFile] read their limits from. */
    fun watchLimitEnv(
        logMaxMb: Long, logTotalMb: Long, keepJobs: Long, keepDays: Long, minFreeMb: Long, fileIdleSeconds: Long, followRedirects: Boolean,
        spaceCheckSeconds: Long = 5, tailReadKb: Long = 64,
    ): Map<String, String> =
        mapOf(
            SPACE_CHECK_ENV to spaceCheckSeconds.toString(), TAIL_READ_ENV to tailReadKb.toString(),
            LOG_MAX_ENV to logMaxMb.toString(), LOG_TOTAL_ENV to logTotalMb.toString(), KEEP_JOBS_ENV to keepJobs.toString(),
            KEEP_DAYS_ENV to keepDays.toString(), MIN_FREE_ENV to minFreeMb.toString(), IDLE_ENV to fileIdleSeconds.toString(),
            FOLLOW_REDIRECTS_ENV to if (followRedirects) "1" else "0",
        )

    private fun envLong(getenv: (String) -> String?, key: String, default: Long): Long = getenv(key)?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: default

    /** STALL-4 item 3: a single `|` (not `||`) outside quotes is taken as a pipe. */
    private val PIPE = Regex("""(?<!\|)\|(?!\|)""")

    /** STALL-4 item 3: the shells a `pipefail` line is prepended for (their exit code is then the pipeline's first failure). */
    private val PIPEFAIL_SHELLS = setOf("bash", "zsh", "ksh", "sh")

    private fun describe(h: ProcessHandle) = "${h.pid()} ${com.superiorsoftllc.infantry.runner.ProcessTree.commandLine(h).ifEmpty { "?" }.take(80)}"

    /** How long the runner waits for the output pipe's EOF after the command exited before it reports anyway. */
    const val EOF_GRACE_MS = 2_000L

    /** Children a command may leave behind on purpose; named, never waited on (every tool profile's `daemons`, STALL-3). */
    val DETACHED_DAEMON: Regex get() = com.superiorsoftllc.infantry.runner.ToolProfiles.daemonRegex()

    /** The END sentinel the runner writes after the process wait returns: `[infantry:done exit=0 elapsed=1m2s bytes=0]`. */
    fun endLine(exit: Int?, elapsedMs: Long, bytes: Long, warnings: List<String> = emptyList()): String =
        "[infantry:done exit=${exit ?: "unknown"} elapsed=${fmtElapsed(elapsedMs)} bytes=$bytes" +
            warnings.joinToString("") { " warning=\"${it.replace('"', '\'')}\"" } + "]"

    fun fmtElapsed(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s < 60) "${s}s" else "${s / 60}m${s % 60}s"
    }

    /** The command's tool-profile `args` (a plain console: rich output hides progress from a log) unless the caller chose. */
    fun withProfileArgs(cmd: List<String>): List<String> = com.superiorsoftllc.infantry.runner.ToolProfiles.withArgs(cmd)

    /** [getenv] without the shim dir on PATH, so a shim never resolves to itself. */
    fun withoutShimDir(getenv: (String) -> String?): (String) -> String? {
        val dir = getenv(SHIM_DIR_ENV)?.takeIf { it.isNotBlank() } ?: return getenv
        val norm = { d: String -> File(d.trim().trim('"')).absoluteFile.normalize().path.lowercase() }
        val skip = norm(dir)
        return { k ->
            val v = getenv(k)
            if ((k == "PATH" || k == "Path") && v != null) {
                v.split(File.pathSeparatorChar).filter { it.isNotBlank() && norm(it) != skip }.joinToString(File.pathSeparator)
            } else v
        }
    }

    /** Counts the bytes read through it: the runner's `bytes=` (0 = the command printed nothing). */
    private class CountingStream(input: java.io.InputStream) : java.io.FilterInputStream(input) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    /**
     * Run [p]'s command in the foreground (or as the detached `--notify` wrapper): tee its output to the log, register it
     * through [connect] and report its end. A command that cannot start still writes the log line and the `.exit` file
     * (127), and, when there is a session, registers the job and reports `done` 127 so the `--notify` mail goes out:
     * a detached wrapper's caller is gone and would otherwise idle forever (ticket bbb6120c).
     *
     * STALL-1 (ticket 802c64d5), the definitive end: the command's END is the return of the PROCESS WAIT ([Process.waitFor];
     * the runner is its parent and gets the real exit code), never the EOF of its output pipe. The pipe is read on its own
     * thread: EOF there means only that every writer closed it. So (i) a silent command that exits gives EOF with 0 bytes
     * at once and is reported `bytes=0`; (ii) a command whose child inherited the pipe and outlives it never gives EOF, and
     * is still reported done [EOF_GRACE_MS] after its exit, naming the child; (iii) a command that closes its output and keeps
     * running gives EOF while alive: an `eof` event, not a done. The log ALWAYS has a START line, a `still running` line
     * every [BEAT_LINE_ENV] seconds (60) and the END sentinel [endLine], so even a silent command has output.
     */
    internal fun runJob(
        p: Parsed.Run,
        session: String?,
        getenv: (String) -> String?,
        out: Appendable,
        err: Appendable,
        cwd: File,
        spawn: ((List<String>) -> Process)? = null,
        /** STALL-4: free bytes on the log's disk (tests inject a low value). */
        freeSpace: (File) -> Long = { f -> (f.absoluteFile.parentFile ?: f.absoluteFile).usableSpace },
        connect: () -> Reporter,
    ): Int {
        val id = p.jobId ?: UUID.randomUUID().toString().take(8)
        // STALL-4 item 4a: old job logs go first (finished ones past the count / total, any past the age).
        runCatching {
            com.superiorsoftllc.infantry.runner.LogPrune.prune(
                logDir(), envLong(getenv, KEEP_JOBS_ENV, 50), envLong(getenv, KEEP_DAYS_ENV, 7),
                envLong(getenv, LOG_TOTAL_ENV, 500) * com.superiorsoftllc.infantry.runner.CappedLog.MB, setOf(id),
            )
        }
        val log = File(logDir().also { it.mkdirs() }, "$id.log")
        val exitFile = File(logDir(), "$id.exit").also { it.delete() }
        val label = p.name ?: p.shown.take(80)
        val notify = if (!p.notify) null else BgNotify(p.notifyTail, patternsFor(p, getenv), p.notifyTimeout)
        val beatLineMs = (getenv(BEAT_LINE_ENV)?.toLongOrNull()?.takeIf { it > 0 } ?: 60L) * 1000L
        val started = System.currentTimeMillis()
        // STALL-4 item 2b: files the output goes to: `--follow` always; a redirect in the command line only when
        // `watch.followRedirects` is on, else it is named in the quiet notice's hint (the default watches the output buffer).
        val redirected = runCatching { com.superiorsoftllc.infantry.runner.ToolProfiles.redirectTargets(p.shown, cwd) }.getOrDefault(emptyList())
        val followAll = getenv(FOLLOW_REDIRECTS_ENV)?.trim() == "1"
        val followed = (p.follow.map { f -> File(f).let { if (it.isAbsolute) it else File(cwd, f) }.absoluteFile.normalize().path } +
            (if (followAll) redirected else emptyList())).distinct()
        var job = BgJob(
            id, label, ProcessHandle.current().pid(), started, logPath = log.absolutePath, keep = p.keep, notify = notify, attached = p.attached,
            follow = followed, redirects = redirected.filter { it !in followed },
        )
        // STALL-4 item 3: a pipe whose exit code is the last command's (a shell given on the command line, or cmd / PowerShell).
        val shellName = (p.shell ?: p.command.firstOrNull())?.let { com.superiorsoftllc.infantry.runner.ToolProfiles.baseName(it) }
        val pipeNoFail = PIPE.containsMatchIn(p.script ?: p.command.drop(1).joinToString(" ")) &&
            (if (p.script != null) shellName !in PIPEFAIL_SHELLS else (shellName in PIPEFAIL_SHELLS + setOf("cmd", "pwsh", "powershell")) && p.command.none { "pipefail" in it })
        val markers = com.superiorsoftllc.infantry.runner.ToolProfiles.failureMarkers()
        var marker: String? = null
        // No session (run outside an agent) or no daemon: the command still runs; only the flag is missing.
        var reporter: Reporter? = null
        var connected = false
        val callLock = Any()
        fun call(req: BgJobRequest): Unit = synchronized(callLock) {
            if (session == null) return
            if (!connected) {
                connected = true
                reporter = try {
                    connect()
                } catch (e: Exception) {
                    err.appendLine("warning: daemon unreachable, running unregistered (${e.message})")
                    null
                }
            }
            val r = reporter ?: return
            try { r.send(req) } catch (e: Exception) { err.appendLine("warning: bg_job: ${e.message}") }
        }
        fun close() = runCatching { reporter?.close?.invoke() }
        val resolveEnv = if (p.shim != null) withoutShimDir(getenv) else getenv
        val doSpawn = spawn ?: { cmd: List<String> ->
            ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true).also { b ->
                b.environment()[WATCHED_ENV] = id
                if (p.attached) b.redirectInput(ProcessBuilder.Redirect.INHERIT)
            }.start()
        }
        com.superiorsoftllc.infantry.runner.ProcessTree.prepare() // STALL-2: Linux subreaper, before the command starts
        val child = try {
            val command = if (p.scriptB64 != null) {
                scriptCommand(p, id, cwd, resolveEnv) ?: throw java.io.IOException("no shell found for the hooked command (${p.shell ?: "none given"})")
            } else {
                resolveCommand(p.command, cwd, resolveEnv)
                    ?: throw java.io.IOException("Cannot run program \"${p.command.first()}\": not found on PATH or in ${cwd.absolutePath}")
            }
            doSpawn(withProfileArgs(command))
        } catch (e: Exception) {
            // `bg wait` must not block forever on a command that never started, and a `--notify` caller must get its mail.
            val line = "$CANNOT_START ${p.shown}: ${e.message}"
            runCatching { log.writeText(line + System.lineSeparator()) }
            runCatching { exitFile.writeText("127") }
            err.appendLine("error: $line; job NOT started")
            call(BgJobRequest("beat", job))
            call(BgJobRequest("done", jobId = id, exitCode = 127, tail = listOf(line), bytes = 0L, eof = true))
            close()
            return 127
        }
        job = job.copy(cmdPid = child.pid())
        // STALL-2 item 2: the command's whole tree (Windows Job Object / Linux subreaper), so done = every member ended.
        val tree = runCatching { com.superiorsoftllc.infantry.runner.ProcessTree.attach(child, DETACHED_DAEMON) }.getOrNull()
        if (session == null) err.appendLine("warning: INFANTRY_SESSION_ID is not set, running unregistered")
        // A shim call's harness should see only the command's own output on stdout; the runner's own lines go to stderr.
        val own: Appendable = if (p.shim != null || p.hook != null) err else out
        own.appendLine("bg job $id: $label (log ${log.absolutePath})")
        // STALL-4 item 4: the log is capped (head cut, tail kept) and paused below the free-space floor.
        val writer = com.superiorsoftllc.infantry.runner.CappedLog(
            log, envLong(getenv, LOG_MAX_ENV, 50) * com.superiorsoftllc.infantry.runner.CappedLog.MB,
            envLong(getenv, MIN_FREE_ENV, 200) * com.superiorsoftllc.infantry.runner.CappedLog.MB, freeSpace,
            spaceCheckMs = envLong(getenv, SPACE_CHECK_ENV, 5).coerceAtLeast(1) * 1000L,
        )
        var pauseTold = false
        val keepLines = p.notifyTail ?: 200
        val tail = ArrayDeque<String>()
        val ioLock = Any()
        var ended = false
        var paused: String? = null
        fun emit(line: String, runner: Boolean) {
            synchronized(ioLock) {
                writer.appendLine(line)
                (if (runner) own else out).appendLine(line)
                if (!runner) {
                    tail.addLast(line)
                    if (tail.size > keepLines) tail.removeFirst()
                    if (marker == null) markers.firstOrNull { it.containsMatchIn(line) }?.let { marker = line.trim().take(200) }
                }
                if (writer.pausedReason != null && !pauseTold) {
                    pauseTold = true
                    paused = writer.pausedReason
                    own.appendLine(com.superiorsoftllc.infantry.runner.WatchNotices.text("runner.logPaused", "reason" to writer.pausedReason))
                }
            }
            // Told once, outside the io lock: the daemon stops its quiet notices for the job and says why, neutrally.
            paused?.let { reason -> paused = null; job = job.copy(logPaused = reason); call(BgJobRequest("beat", job)) }
        }
        emit("[infantry:start pid=${child.pid()} at=${java.time.Instant.ofEpochMilli(started)} cmd=${p.shown.take(80)}${p.hook?.let { " via=$it-hook" } ?: ""}]", runner = true)
        call(BgJobRequest("beat", job))
        // Runner stopped gracefully (Ctrl-C, SIGTERM): still leave an END, with the exit unknown.
        val hook = Thread {
            synchronized(ioLock) {
                if (!ended) runCatching {
                    writer.appendLine("[infantry:done exit=unknown elapsed=${fmtElapsed(System.currentTimeMillis() - started)} reason=runner stopped]")
                    writer.close()
                }
            }
        }
        runCatching { Runtime.getRuntime().addShutdownHook(hook) }
        val counting = CountingStream(child.inputStream)
        val reader = Thread {
            runCatching { counting.bufferedReader().forEachLine { emit(it, runner = false) } }
            if (child.isAlive) {
                emit("[infantry:eof bytes=${counting.count} process=running]", runner = true)
                call(BgJobRequest("eof", jobId = id, bytes = counting.count))
            }
        }.apply { isDaemon = true; name = "infantry-bg-reader"; start() }
        // Children are snapshotted while the command lives: once it has exited, its tree can no longer be asked for.
        val lastKids = java.util.concurrent.atomic.AtomicReference<List<ProcessHandle>>(emptyList())
        // HARNESS-GAP G16: the daemon's `stop` needs the wrapper's start instant and the tree's members (pid + start).
        job = job.copy(pidStart = ProcessHandle.current().info().startInstant().map { it.toEpochMilli() }.orElse(null))
        val beat = Thread {
            try {
                var nextBeat = System.currentTimeMillis() + BEAT_MS
                var nextLine = started + beatLineMs
                while (true) {
                    // Often at first: a short-lived command may start its child and exit within a second.
                    Thread.sleep(if (System.currentTimeMillis() - started < 5_000) 200 else 1_000)
                    runCatching { child.descendants().toList().takeIf { it.isNotEmpty() || !child.isAlive }?.let(lastKids::set) }
                    runCatching {
                        val seen = (listOf(child.toHandle()) + lastKids.get()).filter { it.isAlive }.map { com.superiorsoftllc.infantry.session.BgMember(it.pid(), it.info().startInstant().map { s -> s.toEpochMilli() }.orElse(null)) }
                        val merged = (job.members + seen).distinctBy { it.pid }.filter { m -> ProcessHandle.of(m.pid).map { it.isAlive }.orElse(false) }
                        if (merged != job.members) job = job.copy(members = merged)
                    }
                    runCatching { tree?.alive() } // the snapshot form learns members only while it can still see them
                    val now = System.currentTimeMillis()
                    if (now >= nextLine) {
                        emit("[infantry:running elapsed=${fmtElapsed(now - started)}]", runner = true)
                        nextLine += beatLineMs
                    }
                    if (now >= nextBeat) {
                        call(BgJobRequest("beat", job))
                        nextBeat = now + BEAT_MS
                    }
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
        runCatching { lastKids.set(child.descendants().toList()) }
        val code = child.waitFor()
        // STALL-2 item 2: the command exited; done waits for the rest of its tree (build daemons excluded), up to the cap.
        // The beat keeps running meanwhile, so the log still says `running` and the daemon still sees the job alive.
        val treeWaitMs = (getenv(TREE_WAIT_ENV)?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: DEFAULT_TREE_WAIT_SECONDS) * 1000L
        var waiting = if (treeWaitMs > 0) runCatching { tree?.alive().orEmpty() }.getOrDefault(emptyList()) else emptyList()
        if (waiting.isNotEmpty()) {
            emit("[infantry:note command exited $code; waiting for ${waiting.size} process(es) of its tree (${tree?.kind}): ${describe(waiting.first())}]", runner = true)
            val until = System.currentTimeMillis() + treeWaitMs
            while (waiting.isNotEmpty() && System.currentTimeMillis() < until) {
                Thread.sleep(500)
                waiting = runCatching { tree?.alive().orEmpty() }.getOrDefault(emptyList())
            }
            emit(
                if (waiting.isEmpty()) "[infantry:note tree ended ${fmtElapsed(System.currentTimeMillis() - started)} after start]"
                else "[infantry:note tree wait capped at ${fmtElapsed(treeWaitMs)}: ${waiting.size} still running: ${describe(waiting.first())}]",
                runner = true,
            )
        }
        runCatching { tree?.close() }
        beat.interrupt()
        reader.join(EOF_GRACE_MS)
        val eof = !reader.isAlive
        // Plus any live process still naming the command as its parent (started after the last snapshot).
        val orphans = runCatching {
            ProcessHandle.allProcesses().filter { h -> h.isAlive && h.parent().map { it.pid() == child.pid() }.orElse(false) }.toList()
        }.getOrDefault(emptyList())
        // `waiting`: what the tree wait was capped on (on Linux an orphan is the runner's child by then, not the command's).
        val survivors = (lastKids.get() + orphans + waiting).distinctBy { it.pid() }.filter { it.isAlive }
            .filterNot { DETACHED_DAEMON.containsMatchIn(com.superiorsoftllc.infantry.runner.ProcessTree.commandLine(it)) }
            .map(::describe)
        val bytes = counting.count
        runCatching { exitFile.writeText(code.toString()) }
        if (!eof) {
            emit("[infantry:note output still open after exit: ${survivors.size} child process(es) hold it${survivors.firstOrNull()?.let { ": $it" } ?: ""}]", runner = true)
        }
        val foundMarker = marker?.takeIf { code == 0 }
        val warnings = listOfNotNull(
            foundMarker?.let { com.superiorsoftllc.infantry.runner.WatchNotices.text("runner.marker", "marker" to it) },
            if (pipeNoFail) com.superiorsoftllc.infantry.runner.WatchNotices.text("runner.pipe") else null,
        )
        emit(endLine(code, System.currentTimeMillis() - started, bytes, warnings), runner = true)
        synchronized(ioLock) { ended = true; runCatching { writer.close() } }
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        val lines = synchronized(ioLock) { tail.toList() }
        call(BgJobRequest("done", jobId = id, exitCode = code, tail = lines, bytes = bytes, eof = eof, survivors = survivors, marker = foundMarker, pipeNoFail = pipeNoFail.takeIf { it }))
        close()
        own.appendLine("bg job $id exited $code")
        return code
    }

    /**
     * STALL-1 `infantry unwatched --reason <text> -- <cmd...>`: the explicit opt-out. The command runs with the caller's own
     * stdio (no log, no tee), but it is still REGISTERED with its reason, so the daemon logs and counts the opt-out, shows
     * `watch OFF: <reason>`, tells the PM once, and the heartbeat and stall detector still cover it.
     */
    internal fun unwatched(
        p: Parsed.Unwatched,
        session: String?,
        getenv: (String) -> String?,
        err: Appendable,
        cwd: File,
        spawn: (List<String>) -> Process = { ProcessBuilder(it).directory(cwd).inheritIO().start() },
        connect: () -> Reporter,
    ): Int {
        val command = resolveCommand(p.command, cwd, withoutShimDir(getenv)) ?: run {
            err.appendLine("error: unwatched: cannot find '${p.command.first()}' on PATH or in ${cwd.absolutePath}")
            return 127
        }
        val id = UUID.randomUUID().toString().take(8)
        val job = BgJob(
            id, p.command.joinToString(" ").take(80), ProcessHandle.current().pid(), System.currentTimeMillis(),
            notify = BgNotify(), attached = true, optOut = p.reason.take(200),
        )
        val reporter = if (session == null) null else runCatching(connect).getOrNull()
        fun call(req: BgJobRequest) { runCatching { reporter?.send?.invoke(req) } }
        call(BgJobRequest("beat", job))
        val child = spawn(command)
        val beat = Thread {
            try {
                while (true) { Thread.sleep(BEAT_MS); call(BgJobRequest("beat", job.copy(cmdPid = child.pid()))) }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
        val code = child.waitFor()
        beat.interrupt()
        call(BgJobRequest("done", jobId = id, exitCode = code))
        runCatching { reporter?.close?.invoke() }
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
                out.appendLine("bg job ${p.id} still running after ${p.timeoutMin} min; run `infantry bg wait ${p.id}` again")
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
            if (p.verb == "ack") {
                // STALL-6: "I have seen this job's result": the daemon drops its queued and future notices.
                runCatching { send(stream, session, BgJobRequest("ack", jobId = p.id)) }.onFailure { err.appendLine("error: ack failed (${it.message})"); return 1 }
                out.appendLine("acked ${p.id}: its queued and future notices are dropped")
                return 0
            }
            if (job == null) {
                // A finished job's result was read here (log or status): STALL-6 marks it handled, so a notice still queued
                // for it is dropped. A running job is not acked by a read; its end is still news.
                if (p.verb != "stop") runCatching { send(stream, session, BgJobRequest("ack", jobId = p.id)) }
                // A finished job's log stays at its well-known path, so `logs` still answers.
                if (p.verb == "logs") return logsOf(File(logDir(), p.id + ".log"), out, err)
                // SCRIPT-HOOK-3 (BG-04): status still answers for a finished job: its exit code is on disk next to the log.
                if (p.verb == "status") {
                    val log = File(logDir(), p.id + ".log")
                    val code = File(logDir(), p.id + ".exit").takeIf { it.isFile }?.let { runCatching { it.readText().trim().toInt() }.getOrNull() }
                    if (code != null || log.isFile) {
                        out.appendLine("${p.id} finished | exit ${code ?: "unknown"} | log ${log.absolutePath}")
                        return 0
                    }
                }
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
                    // HARNESS-GAP G16: the daemon owns the recorded tree and checks every pid's start time; the wrapper's exit deregisters the job.
                    val viaDaemon = runCatching { send(stream, session, BgJobRequest("stop", jobId = job.id)) }
                    if (viaDaemon.isSuccess) {
                        out.appendLine("stopped job ${job.id}")
                    } else {
                        // An older daemon without the op: end the wrapper's live tree here (still guarded by the job's start time).
                        val handle = ProcessHandle.of(job.pid).orElse(null)
                        val start = handle?.info()?.startInstant()?.map { it.toEpochMilli() }?.orElse(null)
                        if (handle == null || (start != null && start > job.startedAt)) {
                            out.appendLine("job ${job.id} is already gone")
                        } else {
                            handle.descendants().forEach { it.destroyForcibly() }
                            handle.destroyForcibly()
                            out.appendLine("stopped job ${job.id}")
                        }
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
