package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.session.BgJobRequest
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The commands the STALL-1 runner tests run, as a real separate JVM (so pipes, children and exit codes are the OS's). */
object StallFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            // (i) prints nothing at all and exits 3.
            "silent" -> System.exit(3)
            // (ii) starts a child that INHERITS stdout and outlives it, prints one line, exits 0.
            "childHolds" -> {
                ProcessBuilder(javaCmd(), "-cp", System.getProperty("java.class.path"), StallFixture::class.java.name, "sleep", "8000")
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT).start()
                println("parent done")
                System.out.flush()
                Thread.sleep(600)
                Runtime.getRuntime().halt(0)
            }
            // (iii) closes its output and keeps running 3 s.
            "closeAndRun" -> {
                println("bye output")
                System.out.flush()
                System.out.close()
                System.err.close()
                Thread.sleep(3_000)
                System.exit(0)
            }
            "sleep" -> Thread.sleep(args[1].toLong())
            "slowSilent" -> { Thread.sleep(args[1].toLong()); System.exit(0) }
        }
    }

    fun javaCmd(): String = ProcessHandle.current().info().command().orElse("java")
}

/**
 * STALL-1 (ticket 802c64d5): the definitive-end cases. The END is the runner's process wait, never the pipe's EOF:
 * (i) a silent command that exits = EOF at once, 0 bytes, its real exit code; (ii) a finished command whose child holds
 * the pipe = no EOF, still reported done with the right code; (iii) a command that closes its output and keeps running =
 * an `eof` event while alive, not a done. Plus the always-there START / still-running / END lines and a killed command.
 */
class StallRunnerTest {
    private val reqs = CopyOnWriteArrayList<BgJobRequest>()
    private val cwd = File(".").absoluteFile

    private fun fixture(vararg a: String) =
        listOf(StallFixture.javaCmd(), "-cp", System.getProperty("java.class.path"), StallFixture::class.java.name) + a

    private fun run(vararg a: String, env: Map<String, String> = emptyMap(), onStart: (Process) -> Unit = {}): Int {
        val p = BgCli.Parsed.Run("fixture ${a.first()}", fixture(*a), notify = true)
        return BgCli.runJob(
            p, "sess-1", { k -> env[k] ?: System.getenv(k) }, StringBuilder(), StringBuilder(), cwd,
            spawn = { cmd -> ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true).start().also(onStart) },
        ) { BgCli.Reporter({ reqs += it }, {}) }
    }

    private fun done() = reqs.last { it.op == "done" }
    private fun logOf(): String = File(reqs.first { it.op == "beat" }.job!!.logPath!!).readText()

    @Test
    fun i_aSilentCommandThatExits_isEndedWithNoOutput_EOF0Bytes_realExitCode() {
        val code = run("silent")
        assertEquals(3, code)
        val d = done()
        assertEquals(3, d.exitCode)
        assertEquals(0L, d.bytes)
        assertEquals(true, d.eof, "an exited silent command closes its pipe: EOF at once")
        val log = logOf()
        assertTrue(log.contains("[agenthq:start pid="), log)
        assertTrue(log.contains("[agenthq:done exit=3 ") && log.contains("bytes=0]"), log)
    }

    @Test
    fun ii_aFinishedCommandWhoseChildKeepsStdoutOpen_hasNoEOF_butIsStillReportedDone() {
        val t0 = System.currentTimeMillis()
        val code = run("childHolds")
        val took = System.currentTimeMillis() - t0
        assertEquals(0, code)
        assertTrue(took < 7_000, "done must not wait for the child's 8 s (took $took ms)")
        val d = done()
        assertEquals(0, d.exitCode)
        assertEquals(false, d.eof, "the child still holds the pipe")
        assertTrue(d.survivors.orEmpty().isNotEmpty(), "the child is named: ${d.survivors}")
        assertTrue(logOf().contains("output still open after exit"), logOf())
        // The surviving child is ours to clean up.
        d.survivors.orEmpty().mapNotNull { it.substringBefore(' ').toLongOrNull() }.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
    }

    @Test
    fun iii_aCommandThatClosesStdoutAndKeepsRunning_givesEOFWhileAlive_notDone() {
        val code = run("closeAndRun")
        assertEquals(0, code)
        val ops = reqs.map { it.op }
        val eofAt = ops.indexOf("eof")
        assertTrue(eofAt >= 0, "an eof event while the process lives: $ops")
        assertTrue(eofAt < ops.lastIndexOf("done"), "eof comes before the done: $ops")
        assertTrue((reqs[eofAt].bytes ?: 0) > 0)
        assertTrue(logOf().contains("[agenthq:eof bytes="), logOf())
        assertEquals(0, done().exitCode)
    }

    @Test
    fun aSilentLongCommand_getsStillRunningLines_withNothingDoneByTheWorker() {
        run("slowSilent", "2600", env = mapOf(BgCli.BEAT_LINE_ENV to "1"))
        val log = logOf()
        assertTrue(log.contains("[agenthq:running elapsed="), log)
        assertTrue(log.lines().first().startsWith("[agenthq:start"), log)
        assertTrue(log.trimEnd().lines().last().startsWith("[agenthq:done exit=0"), log)
    }

    @Test
    fun aKilledCommand_stillGetsItsEndWithTheRealNonZeroCode() {
        val code = run("sleep", "20000", onStart = { p -> Thread { Thread.sleep(800); p.destroyForcibly() }.start() })
        assertTrue(code != 0, "killed = non-zero ($code)")
        assertEquals(code, done().exitCode)
        assertTrue(logOf().contains("[agenthq:done exit=$code"), logOf())
    }

    @Test
    fun gradleGetsAPlainConsole_andTheShimDirIsSkipped() {
        assertEquals(listOf("gradlew.bat", "--console=plain", "test"), BgCli.withPlainConsole(listOf("gradlew.bat", "test")))
        assertEquals(listOf("/x/gradle", "--console=rich"), BgCli.withPlainConsole(listOf("/x/gradle", "--console=rich")))
        assertEquals(listOf("npm", "test"), BgCli.withPlainConsole(listOf("npm", "test")))
        val sep = File.pathSeparator
        val shim = File("shimdir").absolutePath
        val env = mapOf("PATH" to "$shim${sep}/usr/bin", BgCli.SHIM_DIR_ENV to shim)
        assertEquals("/usr/bin", BgCli.withoutShimDir { env[it] }("PATH"))
    }

    @Test
    fun unwatchedNeedsAReason() {
        assertTrue(BgCli.parse(listOf("unwatched", "--", "npm", "test")) is BgCli.Parsed.Bad)
        assertTrue(BgCli.parse(listOf("unwatched", "--reason", " ", "--", "npm")) is BgCli.Parsed.Bad)
        val ok = BgCli.parse(listOf("unwatched", "--reason", "interactive prompt", "--", "npm", "init"))
        assertEquals(BgCli.Parsed.Unwatched("interactive prompt", listOf("npm", "init")), ok)
    }

    @Test
    fun anOptOutIsStillRegisteredWithItsReason_soHeartbeatAndStallStillApply() {
        val code = BgCli.unwatched(
            BgCli.Parsed.Unwatched("needs a tty", fixture("silent")), "sess-1", System::getenv, StringBuilder(), cwd,
            spawn = { ProcessBuilder(it).start() },
        ) { BgCli.Reporter({ reqs += it }, {}) }
        assertEquals(3, code)
        val beat = assertNotNull(reqs.first { it.op == "beat" }.job)
        assertEquals("needs a tty", beat.optOut)
        assertNotNull(beat.notify, "watched by the heartbeat")
        assertFalse(beat.logPath != null, "no log for an opted-out command")
        assertEquals(3, done().exitCode)
    }

    @Test
    fun theShimsAreWrittenAndPutFirstOnPath() {
        val dir = kotlin.io.path.createTempDirectory("shims").toFile()
        com.superiorsoftllc.agenthq.runner.WatchShims.install(dir, listOf("C:\\bin\\agenthq.cmd"), windows = true)
        val cmd = File(dir, "gradle.cmd").readText()
        assertTrue(cmd.contains("call C:\\bin\\agenthq.cmd bg run --attached --shim gradle -- gradle %*"), cmd)
        assertTrue(File(dir, "npm").readText().contains("'/c/bin/agenthq.cmd' 'bg' 'run' '--attached' '--shim' 'npm' '--' 'npm' \"\$@\""))
        val env = com.superiorsoftllc.agenthq.runner.WatchShims.sessionEnv(dir, "/usr/bin", forced = true)
        assertTrue(env.getValue("PATH").startsWith(dir.absolutePath))
        assertEquals(mapOf("AGENTHQ_WATCH" to "off"), com.superiorsoftllc.agenthq.runner.WatchShims.sessionEnv(dir, "/usr/bin", forced = false))
    }
}
