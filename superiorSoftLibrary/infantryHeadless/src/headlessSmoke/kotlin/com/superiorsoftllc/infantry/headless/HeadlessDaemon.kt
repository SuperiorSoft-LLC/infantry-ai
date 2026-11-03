package com.superiorsoftllc.infantry.headless

import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * One Infantry daemon, launched the way a droplet runs it: its own JVM, classpath = every jar in `:infantry`'s
 * installDist `lib` folder and nothing else, `-Djava.awt.headless=true`, every class it loads logged to [classLoadLog].
 *
 * Isolation (it must be safe beside the owner's daemon and other worktrees' suites):
 *  - state, home and the working folder are a fresh temp directory, deleted by [close]. `user.home` is redirected,
 *    so nothing can fall back to `~/.infantry`;
 *  - `INFANTRY_PORT` is a port that was free a moment ago, NOT strict: if something takes it first, the daemon falls
 *    back to a free one (PORT-1) and announces that, and the port is always read from the announcement. Not `0`:
 *    with 0 the daemon hands its sessions a mail base and `INFANTRY_URL` on port 0, unlike any real deployment.
 *    8787/8788 are never used;
 *  - the inherited environment is scrubbed of every `INFANTRY_*` and `GIT_*` variable before ours are set.
 *
 * The working folder is NOT a git checkout on purpose: with no `INFANTRY_REPO`, a ticket start over a git checkout
 * would cut a worktree from it. [assertWorkingFolderIsNotACheckout] reads the daemon's own banner to prove it.
 */
class HeadlessDaemon private constructor(
    val process: Process,
    val runDir: File,
    private val tempRoot: File,
    val stdout: File,
    val stderr: File,
    val classLoadLog: File,
) : AutoCloseable {

    /** What the daemon has printed so far. */
    fun out(): String = stdout.readTextOrEmpty()

    /** What the daemon has printed to stderr so far. */
    fun err(): String = stderr.readTextOrEmpty()

    /**
     * Wait until the daemon announces `listening on ws://<host>:<port>/ws` and return the port it bound. Fails with
     * both streams when the process exits first or never announces.
     */
    fun awaitListening(timeoutMs: Long = START_TIMEOUT_MS): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            LISTENING.find(out())?.let { return it.groupValues[2].toInt() }
            if (!process.isAlive) {
                throw AssertionError("the daemon exited (${process.exitValue()}) before listening.\n${streams()}")
            }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("the daemon did not announce a listening port within ${timeoutMs}ms.\n${streams()}")
    }

    /** True once the daemon has printed its `listening on` line: it bound a socket and accepts connections. */
    fun announcedListening(): Boolean = LISTENING.containsMatchIn(out())

    /** The startup banner proves sessions and ticket starts run in a plain folder, never in a git checkout. */
    fun assertWorkingFolderIsNotACheckout() {
        val line = out().lineSequence().firstOrNull { it.contains("worktree repo=") }
            ?: throw AssertionError("the daemon printed no worktree banner.\n${streams()}")
        if (!line.contains("not a git checkout")) {
            throw AssertionError("the daemon's working folder must not be a git checkout (a ticket start would cut a worktree from it): $line")
        }
    }

    /** Every class name the daemon JVM loaded, read from `-Xlog:class+load`. */
    fun loadedClasses(): List<String> =
        classLoadLog.readTextOrEmpty().lineSequence().mapNotNull { CLASS_LOADED.find(it)?.groupValues?.get(1) }.toList()

    fun streams(): String = "--- daemon stdout ---\n${out().takeLast(4000)}\n--- daemon stderr ---\n${err().takeLast(4000)}"

    /** Stop the daemon (and anything it spawned) and delete its temp state. The evidence in [runDir] is kept. */
    override fun close() {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroy()
        if (!process.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        // Windows releases the state dir's lock and log handles a moment after the process ends.
        repeat(20) {
            if (tempRoot.deleteRecursively()) return
            Thread.sleep(POLL_MS)
        }
    }

    companion object {
        const val DAEMON_MAIN = "com.superiorsoftllc.infantry.daemon.DaemonMainKt"

        /** The bind a droplet uses: every interface. Non-loopback, so the daemon must demand a token. */
        const val WILDCARD = "0.0.0.0"

        /** The owner's daemon (runDaemon) and the desktop preview (runRealDaemon). Never used here. */
        val OWNER_PORTS = setOf(8787, 8788)

        private const val START_TIMEOUT_MS = 90_000L
        private const val STOP_TIMEOUT_MS = 10_000L
        private const val POLL_MS = 50L

        private val LISTENING = Regex("""listening on ws://(\S+):(\d+)/ws""")

        /** `[0.012s][info][class,load] java.lang.Object source: shared objects file` -> `java.lang.Object`. */
        private val CLASS_LOADED = Regex("""\]\s(\S+)\s+source:""")

        /** `:infantry`'s installDist, passed by the Gradle task. */
        val install: File by lazy { File(requiredProperty("headlessSmoke.install")) }

        private val reportDir: File by lazy { File(requiredProperty("headlessSmoke.reportDir")) }

        /**
         * Launch the installed daemon bound to [host], with [token] (or none), the stub provider and temp state.
         * Its stdout, stderr and class-load log go to `<reportDir>/<scenario>/`, emptied first.
         */
        fun launch(scenario: String, host: String, token: String?): HeadlessDaemon {
            val lib = File(install, "lib")
            check(lib.isDirectory) { "no installed daemon at $lib: run :superiorSoftLibrary:infantry:installDist" }
            val runDir = File(reportDir, scenario).apply { deleteRecursively(); mkdirs() }
            val tempRoot = Files.createTempDirectory("infantry-headless-").toFile()
            val state = File(tempRoot, "state").apply { mkdirs() }
            val home = File(tempRoot, "home").apply { mkdirs() }
            val work = File(tempRoot, "work").apply { mkdirs() }
            val stdout = File(runDir, "daemon.out")
            val stderr = File(runDir, "daemon.err")
            val classLoadLog = File(runDir, "classload.log")

            val builder = ProcessBuilder(
                javaExecutable(),
                "-Xlog:class+load:file=${classLoadLog.absolutePath}",
                "-Djava.awt.headless=true",
                "-Duser.home=${home.absolutePath}",
                "-cp", lib.absolutePath + File.separator + "*",
                DAEMON_MAIN,
            )
                .directory(work)
                .redirectOutput(stdout)
                .redirectError(stderr)
            val env = builder.environment()
            env.keys.removeIf { it.uppercase().startsWith("INFANTRY_") || it.uppercase().startsWith("GIT_") }
            env["INFANTRY_HOST"] = host
            env["INFANTRY_PORT"] = freePort().toString()
            env["INFANTRY_PROVIDER"] = "stub"
            env["INFANTRY_STATE_DIR"] = state.absolutePath
            env["INFANTRY_HOME"] = state.absolutePath
            env["INFANTRY_LIMITS_LIVE"] = "false"
            if (token != null) env["INFANTRY_TOKEN"] = token
            return HeadlessDaemon(builder.start(), runDir, tempRoot, stdout, stderr, classLoadLog)
        }

        /** A port nothing listens on right now, never one of [OWNER_PORTS]. */
        private fun freePort(): Int {
            repeat(10) {
                val port = ServerSocket(0).use { it.localPort }
                if (port !in OWNER_PORTS) return port
            }
            error("the OS kept handing out the owner's ports")
        }

        /** The JVM running this test (the module's toolchain), so the daemon runs on the same Java. */
        private fun javaExecutable(): String =
            ProcessHandle.current().info().command().orElseGet {
                val exe = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
                File(File(System.getProperty("java.home"), "bin"), exe).absolutePath
            }

        /**
         * The address a client on another machine would dial: this machine's LAN IPv4 when the daemon answers on it,
         * else loopback (a host with no LAN, or a firewall that drops traffic to its own LAN address). The wildcard
         * bind is non-loopback either way; the address only shows the path is not loopback-only.
         */
        fun clientHost(port: Int): String = lanIpv4s().firstOrNull { answers(it, port) } ?: "127.0.0.1"

        private fun lanIpv4s(): List<String> {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            return Collections.list(interfaces)
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
                .sortedByDescending { it.isSiteLocalAddress }
                .map { it.hostAddress }
        }

        private fun answers(address: String, port: Int): Boolean = runCatching {
            Socket().use { it.connect(InetSocketAddress(address, port), 3_000) }
            true
        }.getOrDefault(false)

        private fun requiredProperty(name: String): String =
            System.getProperty(name)?.takeIf { it.isNotBlank() }
                ?: error("system property $name is not set: run this through the headlessSmoke Gradle task")

        private fun File.readTextOrEmpty(): String = if (isFile) readText() else ""
    }
}
