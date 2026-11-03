package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.session.BgJobRequest
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
            "childHolds", "childHoldsLong" -> {
                ProcessBuilder(javaCmd(), "-cp", System.getProperty("java.class.path"), StallFixture::class.java.name, "sleep", if (args.first() == "childHoldsLong") "40000" else "8000")
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
            // STALL-2: starts a child that does NOT hold the pipe and exits at once, orphaning it (a parent-pid walk loses it).
            "orphan" -> {
                ProcessBuilder(javaCmd(), "-cp", System.getProperty("java.class.path"), StallFixture::class.java.name, "sleep", args[1])
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                println("parent gone")
                System.out.flush()
                Runtime.getRuntime().halt(0)
            }
            // STALL-2: leaves a "build daemon" behind (its command line names GradleDaemon): never waited on.
            "leavesDaemon" -> {
                ProcessBuilder(javaCmd(), "-cp", System.getProperty("java.class.path"), StallFixture::class.java.name, "sleep", "15000", "GradleDaemon")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                Runtime.getRuntime().halt(0)
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
        assertTrue(log.contains("[infantry:start pid="), log)
        assertTrue(log.contains("[infantry:done exit=3 ") && log.contains("bytes=0]"), log)
    }

    @Test
    fun ii_aFinishedCommandWhoseChildKeepsStdoutOpen_isDoneWhenTheWholeTreeEnded() {
        // STALL-2 item 2: done = the whole attached tree ended (Job Object / subreaper), so the child's 8 s are waited.
        val t0 = System.currentTimeMillis()
        val code = run("childHolds")
        val took = System.currentTimeMillis() - t0
        assertEquals(0, code)
        assertTrue(took >= 6_500, "done waits for the child that is still part of the tree (took $took ms)")
        val d = done()
        assertEquals(0, d.exitCode)
        assertEquals(true, d.eof, "the child ended, so the pipe closed")
        val log = logOf()
        assertTrue(log.contains("waiting for 1 process(es) of its tree"), log)
        assertTrue(log.contains("[infantry:note tree ended"), log)
    }

    @Test
    fun ii_withTheTreeWaitCapped_theChildKeepingStdoutOpen_hasNoEOF_butIsStillReportedDone() {
        val t0 = System.currentTimeMillis()
        // STALL-5: a 40 s child, so the loose clock bound is not the child's own life (runner + JVM start-up + 1 s cap + 2 s EOF grace take ~8.5 s on a busy host).
        val code = run("childHoldsLong", env = mapOf(BgCli.TREE_WAIT_ENV to "1"))
        val took = System.currentTimeMillis() - t0
        assertEquals(0, code)
        // The proof is that the child is still ALIVE at done (a named survivor below); the clock only bounds it loosely.
        assertTrue(took < 30_000, "a capped tree wait must not wait for the child's 40 s (took $took ms)")
        assertTrue(logOf().contains("tree wait capped at 1s: 1 still running"), logOf())
        val d = done()
        assertEquals(0, d.exitCode)
        assertEquals(false, d.eof, "the child still holds the pipe")
        assertTrue(d.survivors.orEmpty().isNotEmpty(), "the child is named: ${d.survivors}")
        assertTrue(logOf().contains("output still open after exit"), logOf())
        // The surviving child is ours to clean up.
        d.survivors.orEmpty().mapNotNull { it.substringBefore(' ').toLongOrNull() }.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
    }

    @Test
    fun anOrphanedGrandchild_isStillPartOfTheTree_andDoneWaitsForIt() {
        // The parent exits at once and its child is re-parented: only the Job Object (Windows) / subreaper (Linux) keeps it.
        val t0 = System.currentTimeMillis()
        assertEquals(0, run("orphan", "4000"))
        val took = System.currentTimeMillis() - t0
        val log = logOf()
        assertTrue(took >= 3_000, "done waited for the orphan (took $took ms): $log")
        assertTrue(log.contains("(job object)") || log.contains("(subreaper)"), "the OS tree, not the snapshot: $log")
        assertTrue(log.contains("[infantry:note tree ended"), log)
    }

    @Test
    fun aBuildDaemonLeftBehind_isNeverWaitedOn() {
        val t0 = System.currentTimeMillis()
        assertEquals(0, run("leavesDaemon"))
        val took = System.currentTimeMillis() - t0
        assertTrue(took < 8_000, "the daemon's 15 s are not waited (took $took ms): ${logOf()}")
        assertFalse(logOf().contains("waiting for"), logOf())
        // Ours to clean up: the fake daemon.
        ProcessHandle.allProcesses().filter { it.info().commandLine().orElse("").contains("GradleDaemon") && it.info().commandLine().orElse("").contains(StallFixture::class.java.name) }
            .forEach { it.destroyForcibly() }
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
        assertTrue(logOf().contains("[infantry:eof bytes="), logOf())
        assertEquals(0, done().exitCode)
    }

    @Test
    fun aSilentLongCommand_getsStillRunningLines_withNothingDoneByTheWorker() {
        run("slowSilent", "2600", env = mapOf(BgCli.BEAT_LINE_ENV to "1"))
        val log = logOf()
        assertTrue(log.contains("[infantry:running elapsed="), log)
        assertTrue(log.lines().first().startsWith("[infantry:start"), log)
        assertTrue(log.trimEnd().lines().last().startsWith("[infantry:done exit=0"), log)
    }

    @Test
    fun aKilledCommand_stillGetsItsEndWithTheRealNonZeroCode() {
        val code = run("sleep", "20000", onStart = { p -> Thread { Thread.sleep(800); p.destroyForcibly() }.start() })
        assertTrue(code != 0, "killed = non-zero ($code)")
        assertEquals(code, done().exitCode)
        assertTrue(logOf().contains("[infantry:done exit=$code"), logOf())
    }

    @Test
    fun gradleGetsAPlainConsole_andTheShimDirIsSkipped() {
        assertEquals(listOf("gradlew.bat", "--console=plain", "test"), BgCli.withProfileArgs(listOf("gradlew.bat", "test")))
        assertEquals(listOf("/x/gradle", "--console=rich"), BgCli.withProfileArgs(listOf("/x/gradle", "--console=rich")))
        assertEquals(listOf("npm", "test"), BgCli.withProfileArgs(listOf("npm", "test")))
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
        com.superiorsoftllc.infantry.runner.WatchShims.install(dir, listOf("C:\\bin\\infantry.cmd"), windows = true)
        val cmd = File(dir, "gradle.cmd").readText()
        assertTrue(cmd.contains("call C:\\bin\\infantry.cmd bg run --attached --shim gradle -- gradle %*"), cmd)
        assertTrue(File(dir, "npm").readText().contains("'/c/bin/infantry.cmd' 'bg' 'run' '--attached' '--shim' 'npm' '--' 'npm' \"\$@\""))
        val env = com.superiorsoftllc.infantry.runner.WatchShims.sessionEnv(dir, "/usr/bin", forced = true)
        assertTrue(env.getValue("PATH").startsWith(dir.absolutePath))
        assertEquals(mapOf("INFANTRY_WATCH" to "off"), com.superiorsoftllc.infantry.runner.WatchShims.sessionEnv(dir, "/usr/bin", forced = false))
    }
}
