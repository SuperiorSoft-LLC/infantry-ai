package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.daemon.StateDirLock
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * BUG-INSTALL-LOCK-1: the INSTALLED entry ([main] in AppMain.kt, installed profile) takes the state-dir lock, so a
 * second launch on the same state dir (a double-click on the Start-menu shortcut) never starts a second daemon on the
 * same `infantry.db`. It refuses with one line naming the holder and exits 2, as the daemon's own `main` does.
 *
 * Both launches are real JVMs on `AppMainKt` with `-Dinfantry.installed=true`, headless, a temp state dir
 * (`INFANTRY_INSTALL_STATE_DIR`), a temp `user.home` and an OS-assigned port. Never `~/.infantry`, never 8787/8788.
 */
class InstalledStateDirLockTest {
    private val root: File = Files.createTempDirectory("infantry-install-lock").toFile()
    private val stateDir = File(root, "state")
    private val home = File(root, "home").apply { mkdirs() }
    private val logsDir = File(stateDir, InstallLogs.DIR_NAME)

    private fun launch(name: String, port: Int): Pair<Process, File> {
        val console = File(root, "$name.console.log")
        val argFile = File(root, "$name.args").apply {
            writeText(
                listOf(
                    "-Dinfantry.installed=true",
                    "-Djava.awt.headless=true",
                    "\"-Duser.home=${home.absolutePath.replace("\\", "\\\\")}\"",
                    "-cp",
                    "\"${System.getProperty("java.class.path").replace("\\", "\\\\")}\"",
                ).joinToString("\n"),
            )
        }
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val builder = ProcessBuilder(java, "@${argFile.absolutePath}", "com.superiorsoftllc.infantry.ui.AppMainKt")
        builder.environment().apply {
            keys.filter { it.startsWith("INFANTRY_", ignoreCase = true) || it.startsWith("INFANTRY_", ignoreCase = true) }.forEach(::remove)
            put(InstallProfile.STATE_DIR_ENV, stateDir.absolutePath)
            put(InstallProfile.PORT_ENV, port.toString())
            put("INFANTRY_PROVIDER", "stub")
            put("INFANTRY_HEADLESS", "true")
            put("INFANTRY_LIMITS_LIVE", "false")
        }
        // stdin stays an open pipe: the headless daemon runs until it is closed.
        return builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(console)).start() to console
    }

    /** Every port a `daemon provider=... at ws://host:PORT/ws` line names, across every log this state dir holds. */
    private fun daemonPorts(vararg consoles: File): Set<Int> {
        val files = (logsDir.listFiles()?.toList() ?: emptyList()) + consoles
        return files.flatMap { f ->
            runCatching { f.readLines() }.getOrDefault(emptyList()).mapNotNull { DAEMON_LINE.find(it)?.groupValues?.get(1)?.toInt() }
        }.toSet()
    }

    /** Everything the holder wrote: its console plus every log in this state dir (the installed app logs to a file). */
    private fun allText(vararg consoles: File): String =
        ((logsDir.listFiles()?.toList() ?: emptyList()) + consoles).joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }

    private fun stop(process: Process) {
        runCatching { process.outputStream.close() }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        }
    }

    @Test fun aSecondInstalledLaunchOnTheSameStateDirRefusesAndStartsNoDaemon() {
        val port = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val (first, firstConsole) = launch("first", port)
        var second: Process? = null
        try {
            val deadline = System.currentTimeMillis() + 90_000
            while (daemonPorts(firstConsole).isEmpty()) {
                if (!first.isAlive) fail("the first launch exited ${first.exitValue()} before its daemon came up: ${firstConsole.readText()}")
                if (System.currentTimeMillis() > deadline) fail("the first launch's daemon never came up: ${firstConsole.readText()}")
                Thread.sleep(200)
            }
            val firstPort = daemonPorts(firstConsole).single()

            val (b, secondConsole) = launch("second", port)
            second = b
            val secondDeadline = System.currentTimeMillis() + 90_000
            while (b.isAlive) {
                val ports = daemonPorts(firstConsole, secondConsole)
                if (ports.size > 1) {
                    fail("the second installed launch started a SECOND daemon on the same state dir ${stateDir.path} (ports $ports)")
                }
                if (System.currentTimeMillis() > secondDeadline) fail("the second launch neither refused nor started a daemon")
                Thread.sleep(200)
            }
            val said = secondConsole.readText()
            assertEquals(2, b.exitValue(), "the refused launch exits 2: $said")
            assertTrue("is in use by another daemon (pid ${first.pid()}, port $firstPort)" in said, "the refusal names the holder: $said")
            // INSTALL-REFOCUS-1: the refused launch asks the holder to show its window, over the holder's own daemon
            // port (named in daemon.lock), and says so. The headless holder has no window to show: it logs the request
            // and answers that, and the headless second launch has no dialog to fall back to, so it says that too.
            val holderSaid = allText(firstConsole)
            assertTrue(
                "[infantry-install] activation requested by a second launch" in holderSaid,
                "the holder received the refused launch's activation request: $holderSaid",
            )
            assertTrue(
                "[infantry-install] asked the running Infantry AI (port $firstPort) to show its window" in said,
                "the refused launch says it signalled the holder: $said",
            )
            assertTrue(first.isAlive, "the first launch keeps running")
            assertEquals(setOf(firstPort), daemonPorts(firstConsole, secondConsole), "only one daemon ever ran")
            assertEquals(
                StateDirLock.Holder(first.pid(), firstPort),
                StateDirLock.read(File(stateDir, StateDirLock.FILE_NAME)),
                "the installed launch holds the state-dir lock and records the port it bound",
            )
        } finally {
            second?.let(::stop)
            stop(first)
            root.deleteRecursively()
        }
    }

    private companion object {
        val DAEMON_LINE = Regex("""\[infantry-ui] daemon provider=\S+ at ws://[^:]+:(\d+)/ws""")
    }
}
