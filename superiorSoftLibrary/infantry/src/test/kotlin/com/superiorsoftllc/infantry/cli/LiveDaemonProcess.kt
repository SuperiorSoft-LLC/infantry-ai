package com.superiorsoftllc.infantry.cli

import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit

/**
 * A launch that did not end with a serving daemon.
 *
 * Named rather than a bare `error(...)` (which throws `IllegalStateException` and made the port retry
 * work by ACCIDENT) so the retry contract is explicit: `launchWithPortRetry` re-probes the port for
 * THIS exception and for nothing else. Any other failure -- a bad classpath, an unreadable state
 * directory, a daemon that exits on a configuration error -- surfaces on its first occurrence instead
 * of being burned through four more fresh ports. Extends `IllegalStateException`, so every existing
 * catcher is unaffected.
 */
internal class DaemonLaunchFailure(message: String) : IllegalStateException(message)

/**
 * A REAL Infantry daemon, in its OWN operating-system process, reachable on a REAL socket.
 *
 * The rest of the CLI suite boots the daemon IN-PROCESS through [com.superiorsoftllc.infantry.daemon.DaemonFixture].
 * That is a real Ktor server on a real WebSocket, but it shares the test JVM with the client: one
 * scheduler, one heap, one set of thread-local timings. A green in-process suite is therefore NOT
 * evidence about a daemon a user starts with `infantry daemon` and a CLI a user types as a second
 * process -- which is exactly the boundary the `agentsListsProfilesFromDaemon` flake lived on.
 *
 * This launches [com.superiorsoftllc.infantry.daemon.DaemonMainKt] as a child JVM (the same `main`
 * the installed binary runs, reading its configuration from the environment exactly as it does) and
 * runs [com.superiorsoftllc.infantry.cli.CliMainKt] as ANOTHER child JVM for each CLI invocation.
 * Nothing is faked: separate processes, a real TCP port, real SQLite on disk, real scheduling.
 *
 * Two rules keep the soak honest:
 *
 *  - The child environment is the operator's environment MINUS every `INFANTRY_*` variable, plus the
 *    handful this test sets. An inherited `INFANTRY_TOKEN`, `INFANTRY_URL` or `INFANTRY_REPO` would
 *    silently point the child at the operator's real daemon or make it refuse to start, so the soak
 *    would be measuring the wrong thing.
 *  - Child output is redirected to a log FILE, never to an unread pipe. A daemon whose stdout pipe
 *    fills up blocks on `println`, which looks exactly like a hang in the code under test.
 */
internal class LiveDaemonProcess private constructor(
    private val process: Process,
    val port: Int,
    private val stateDir: File,
    private val log: File,
) {

    /** The daemon's own stdout+stderr, for a failure message that says what the daemon said. */
    fun logTail(lines: Int = 40): String =
        runCatching { log.readLines().takeLast(lines).joinToString("\n") }.getOrDefault("<no log>")

    private fun javaBin(): String {
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
        return File(File(System.getProperty("java.home"), "bin"), "java$exe").absolutePath
    }

    /**
     * One REAL `infantry agents` invocation: its own process, its own socket, its own exit code.
     *
     * @return the exit code plus what it printed, so a failure can quote the actual table.
     */
    fun cli(vararg args: String, timeoutSeconds: Long = 60): CliResult {
        val out = File.createTempFile("infantry-cli-out", ".txt")
        val err = File.createTempFile("infantry-cli-err", ".txt")
        val command = listOf(javaBin(), "-cp", classpath(), CLI_MAIN, *args, "--port", port.toString())
        val child = ProcessBuilder(command)
            .apply { childEnvironment(this) }
            .redirectOutput(out)
            .redirectError(err)
            .start()
        val finished = child.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            child.destroyForcibly()
            error("the `infantry ${args.joinToString(" ")}` process did not exit within ${timeoutSeconds}s")
        }
        val result = CliResult(child.exitValue(), out.readText(), err.readText())
        out.delete()
        err.delete()
        return result
    }

    /** Stop the daemon: ask politely, then insist. Safe to call twice. */
    fun stop() {
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    /** Delete the temp state directory. Safe after [stop]. */
    fun deleteState() {
        runCatching { stateDir.deleteRecursively() }
    }

    companion object {
        private const val DAEMON_MAIN = "com.superiorsoftllc.infantry.daemon.DaemonMainKt"
        private const val CLI_MAIN = "com.superiorsoftllc.infantry.cli.CliMainKt"

        /**
         * The classpath to hand a child JVM.
         *
         * NOT `java.class.path`: Gradle's test worker loads the test through its own classloader and
         * leaves that property NULL, so a child launched with it gets `-cp null` and dies with a bare
         * `NullPointerException` from `ProcessBuilder`. The URLs of the classloader that loaded this
         * class ARE the runtime classpath, so they are read from there, and the property is used only
         * when it is actually set (a plain `java -cp ... Test` run, an IDE).
         */
        private fun classpath(): String {
            System.getProperty("java.class.path")
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }

            val urls = generateSequence(LiveDaemonProcess::class.java.classLoader) { it.parent }
                .filterIsInstance<URLClassLoader>()
                .flatMap { it.urLs.asSequence() }
                .distinct()
                .mapNotNull { it.toFileOrNull() }
                .distinct()
                .map { it.absolutePath }
                .toList()
            check(urls.isNotEmpty()) {
                "cannot work out a classpath for the child process: java.class.path is unset and the " +
                    "test classloader exposes no URLs (${LiveDaemonProcess::class.java.classLoader})"
            }
            return urls.joinToString(File.pathSeparator)
        }

            /**
             * The file behind a `file:` classloader URL, with its percent-escapes decoded.
             *
             * `URLClassLoader` hands out `file:/C:/Program%20Files/...`, which is neither a usable path
             * nor a path a child JVM can resolve; decoded, and without the leading slash Windows adds
             * in front of the drive letter, it is.
             */
            private fun URL.toFileOrNull(): File? {
                if (protocol != "file") return null
                val path = URLDecoder.decode(path, Charsets.UTF_8)
                return File(if (path.startsWith("/")) path.substring(1) else path)
            }


        /** How long the daemon may take to answer `/health` before the launch is called a failure. */
        private const val BOOT_TIMEOUT_SECONDS = 60L

        /**
         * How many ports to try before giving up. A collision needs another process to take exactly the
         * probed port inside a sub-millisecond window, so this is generous; it exists to stop an
         * unbounded loop, not to paper over a real problem.
         */
        private const val PORT_PROBE_ATTEMPTS = 5

        /** How the daemon reports losing its port to another process, across platforms and JDKs. */
        private val BIND_FAILURE_MARKERS = listOf(
            "address already in use",
            "bindexception",
            "only one usage of each socket address",
            "address in use",
        )

        /**
         * A port nothing is listening on.
         *
         * Racy in principle -- something else could take it between the probe and the bind. Rather
         * than sleep through the race, [launch] re-probes and retries when THIS attempt's own log
         * shows it lost the port. The failure stays legible either way: a real bind problem surfaces
         * with the daemon's own log attached rather than a silent wrong target.
         */
        private fun freePort(): Int = ServerSocket(0).use { it.localPort }

        /**
         * The operator's environment with every `INFANTRY_*` setting removed.
         *
         * Two entries are dropped as well, and both are Windows quirks rather than choices:
         * variables whose NAME contains `=` (the hidden per-drive `=C:` entries Windows keeps) and
         * any entry with no value. `ProcessBuilder.start` serialises the environment as `NAME=VALUE`
         * lines, and either shape makes it throw a bare `NullPointerException` with no message --
         * which looks like a harness bug and costs the same twenty minutes every time.
         */
        private fun cleanEnvironment(): MutableMap<String, String> =
            System.getenv()
                .filterKeys { !it.startsWith("INFANTRY_", ignoreCase = true) && !it.contains('=') }
                .mapNotNull { (key, value) -> value?.let { key to it } }
                .toMap()
                .toMutableMap()

        /**
         * A child environment: the operator's own, minus every `INFANTRY_*` setting, plus whatever the
         * caller adds.
         *
         * The `INFANTRY_*` entries are REMOVED rather than overwritten, because the daemon reads far
         * more of them than any test sets (`INFANTRY_TOKEN`, `INFANTRY_REPO`, `INFANTRY_PROVIDERS`,
         * `INFANTRY_URL`, the budget flags). An inherited one would point the child at the operator's
         * real daemon, make it refuse to start, or spend a budget the test never meant to spend --
         * and the soak would then be measuring the wrong system while looking green.
         *
         * The inherited map is edited IN PLACE rather than cleared and refilled: on Windows,
         * `ProcessBuilder` serialises the environment itself, and replacing the map wholesale on a
         * Gradle test worker's environment fails inside `start()` with a bare `NullPointerException`
         * and no message at all.
         */
        private fun childEnvironment(builder: ProcessBuilder): MutableMap<String, String> =
            builder.environment().also { env ->
                env.keys.filter { it.startsWith("INFANTRY_", ignoreCase = true) }.forEach { env.remove(it) }
            }

        /**
         * Start the daemon on [port] over the state directory [stateDir], and return once `/health`
         * answers 200 -- so every caller starts against a daemon that is really serving.
         *
         * @param dbFile the SQLite file the daemon opens; a restart test passes the same one twice.
         */
        fun launch(
            stateDir: File,
            port: Int? = null,
            dbFile: File = File(stateDir, "infantry.db"),
        ): LiveDaemonProcess =
            launchWithPortRetry(log = daemonLog(stateDir), fixedPort = port) { chosen ->
                launchOnce(stateDir, chosen, dbFile)
            }

        /** The daemon's own log. ONE file per state directory, APPENDED to by every launch attempt. */
        private fun daemonLog(stateDir: File): File = File(stateDir, "daemon.log")

        /**
         * [launch]'s retry decision, lifted out of it so the policy can be driven without a daemon.
         *
         * `fixedPort` null means "choose one": probe for a free port and race the daemon onto it. An
         * explicit port is a restart onto the port the previous daemon already proved, so it is never
         * re-chosen -- retrying that would silently move the restart target.
         *
         * [startAttempt] is handed the port to use and must return once the daemon is serving. It runs
         * again ONLY when it fails with a [DaemonLaunchFailure] whose own slice of the log shows the
         * port was taken; the collision is then resolved by asking the OS for a different port. That
         * is a retry, not a sleep: nothing is waited out. Any other exception type, and any
         * [DaemonLaunchFailure] the log does not attribute to the port, surfaces immediately.
         */
        internal fun <T> launchWithPortRetry(
            log: File,
            fixedPort: Int?,
            freshPort: () -> Int = ::freePort,
            maxAttempts: Int = PORT_PROBE_ATTEMPTS,
            startAttempt: (Int) -> T,
        ): T {
            var attempt = 0
            while (true) {
                // The log is opened APPEND (see [launchOnce]), so it accumulates across attempts and
                // one read of it describes every attempt so far. Remember where it ENDS before this
                // attempt can write a byte, and classify this attempt from what follows that point
                // only: a bind failure an EARLIER attempt recorded says nothing about why THIS one
                // failed. Taken inside the loop, never once before it, for the same reason.
                val alreadyLogged = log.length()
                val chosen = fixedPort ?: freshPort()
                try {
                    return startAttempt(chosen)
                } catch (bootFailure: DaemonLaunchFailure) {
                    // Deliberate, not incidental (DEFECT 2): the exception TYPE decides whether a
                    // retry is on the table, and the log only decides whether the port was the
                    // reason. Anything else is a harness bug and must not cost four more ports.
                    if (fixedPort != null || !portWasTaken(appendedTo(log, alreadyLogged))) {
                        throw bootFailure
                    }
                    if (++attempt >= maxAttempts) throw bootFailure
                }
            }
        }

        /**
         * The text appended to [log] after byte [fromOffset]: this attempt's own output, and nothing
         * an earlier attempt left behind.
         *
         * Read as BYTES from that offset and decoded, rather than as a character index into the whole
         * file: [File.length] counts bytes and a daemon log can carry multi-byte output, so a
         * character index would drift into the previous attempt's text. A log that cannot be read at
         * all yields "", which reads as "no collision" and fails fast -- the safe direction, since
         * the alternative is burning four more ports on a failure nothing can explain.
         */
        internal fun appendedTo(log: File, fromOffset: Long): String {
            if (!log.isFile) return ""
            return runCatching {
                RandomAccessFile(log, "r").use { file ->
                    val length = file.length()
                    if (fromOffset >= length) {
                        ""
                    } else {
                        file.seek(fromOffset)
                        val appended = ByteArray((length - fromOffset).toInt())
                        file.readFully(appended)
                        String(appended, Charsets.UTF_8)
                    }
                }
            }.getOrDefault("")
        }

        private fun launchOnce(stateDir: File, port: Int, dbFile: File): LiveDaemonProcess {
            stateDir.mkdirs()
            val log = File(stateDir, "daemon.log")
            val child = ProcessBuilder(
                listOf(javaCommand(), "-cp", classpath(), DAEMON_MAIN),
            )
                .apply {
                    val env = childEnvironment(this)
                    env["INFANTRY_PORT"] = port.toString()
                    env["INFANTRY_HOST"] = "127.0.0.1"
                    // The stub provider needs no provider CLI installed, so this runs anywhere.
                    env["INFANTRY_PROVIDER"] = "stub"
                    env["INFANTRY_STATE_DIR"] = stateDir.absolutePath
                    env["INFANTRY_DB"] = dbFile.absolutePath
                }
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .redirectErrorStream(true)
                .start()
            val daemon = LiveDaemonProcess(child, port, stateDir, log)
            daemon.awaitHealth()
            return daemon
        }

        /**
         * Whether [attemptLog] -- what ONE attempt appended to the daemon log -- shows it lost the
         * port to another process, rather than failing for any other reason. Only this case is safe
         * to retry with a fresh port.
         *
         * The caller hands it a SLICE, never the whole file. The log is opened APPEND, so a whole-file
         * read carries an earlier attempt's bind failure into this attempt's classification and
         * retries a failure that had nothing to do with the port.
         */
        private fun portWasTaken(attemptLog: String): Boolean =
            BIND_FAILURE_MARKERS.any { attemptLog.contains(it, ignoreCase = true) }

        private fun javaCommand(): String {
            val exe = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
            return File(File(System.getProperty("java.home"), "bin"), "java$exe").absolutePath
        }

        /** Poll `/health` until the daemon answers, or fail with its own log attached. */
        private fun LiveDaemonProcess.awaitHealth() {
            val http = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/health")).GET().build()
            val deadline = System.currentTimeMillis() + BOOT_TIMEOUT_SECONDS * 1000
            var lastFailure = "never answered"
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive) {
                    throw DaemonLaunchFailure(
                        "the daemon process exited with ${process.exitValue()} before it served /health:\n${logTail()}",
                    )
                }
                val attempt = runCatching {
                    http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
                }
                if (attempt.getOrNull() == 200) return
                lastFailure = attempt.exceptionOrNull()?.toString() ?: "status ${attempt.getOrNull()}"
                Thread.sleep(100)
            }
            stop()
            throw DaemonLaunchFailure(
                "the daemon never answered /health within ${BOOT_TIMEOUT_SECONDS}s ($lastFailure):\n${logTail()}",
            )
        }
    }
}

/**
 * One `infantry agents` process: its exit code, its stdout and its stderr.
 *
 * [names] are the profile names of the printed rows, in printed order, parsed off the `ID | NAME |
 * ROLE | MODEL` table the command prints. Parsed rather than substring-matched so a listing that
 * prints the right words in the wrong place cannot pass.
 */
internal class CliResult(val exit: Int, val out: String, val err: String) {

    val names: List<String> by lazy {
        val lines = out.lines().filter { it.isNotBlank() }
        val start = lines.indexOfFirst { it.startsWith("ID |") }
        if (start < 0) emptyList()
        else lines.drop(start + 1).map { row ->
            val columns = row.split('|')
            check(columns.size >= 3) { "unreadable agents row '$row' in:\n$out" }
            columns[1].trim()
        }
    }

    override fun toString(): String = "exit=$exit\n--- stdout ---\n$out--- stderr ---\n$err"
}