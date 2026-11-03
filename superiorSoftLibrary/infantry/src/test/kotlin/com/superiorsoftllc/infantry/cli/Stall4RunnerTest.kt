// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.session.BgJobRequest
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The commands the STALL-4 runner tests run, as a real separate JVM. */
object Stall4Fixture {
    /** `lines <exit> <line>...`: prints each line, exits with the code (words after a `>` are ignored: a fake redirect). */
    @JvmStatic
    fun main(args: Array<String>) {
        args.drop(1).takeWhile { it != ">" }.forEach { println(it) }
        System.out.flush()
        System.exit(args[0].toInt())
    }
}

/**
 * STALL-4 (ticket 14ac9383), the runner side: an exit 0 with a failure marker in the output, a pipe without pipefail, a
 * redirect followed or only named, the free-space floor, and `watch-file`'s two ends (the pid exited; the file idle).
 */
class Stall4RunnerTest {
    private val reqs = CopyOnWriteArrayList<BgJobRequest>()
    private val cwd = File(".").absoluteFile

    private fun fixture(vararg a: String) =
        listOf(StallFixture.javaCmd(), "-cp", System.getProperty("java.class.path"), Stall4Fixture::class.java.name) + a

    private fun run(
        p: BgCli.Parsed.Run,
        env: Map<String, String> = emptyMap(),
        out: StringBuilder = StringBuilder(),
        freeSpace: (File) -> Long = { Long.MAX_VALUE },
    ): Int = BgCli.runJob(
        p, "sess-1", { k -> env[k] ?: System.getenv(k) }, out, StringBuilder(), cwd,
        spawn = { cmd -> ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true).start() },
        freeSpace = freeSpace,
    ) { BgCli.Reporter({ reqs += it }, {}) }

    private fun done() = reqs.last { it.op == "done" }
    private fun firstJob() = reqs.first { it.op == "beat" }.job!!
    private fun logOf(): String = File(firstJob().logPath!!).readText()

    @Test
    fun exit0WithAFailureMarkerInTheOutput_isSaidInTheDoneLineAndTheReport() {
        assertEquals(0, run(BgCli.Parsed.Run("g", fixture("0", "Running tests", "BUILD FAILED in 3s"), notify = true)))
        assertEquals("BUILD FAILED in 3s", done().marker)
        val end = logOf().trimEnd().lines().last()
        assertTrue(end.startsWith("[infantry:done exit=0 ") && end.contains("warning=\"exit=0 but output contains failure marker: BUILD FAILED in 3s\""), end)
    }

    @Test
    fun aNonZeroExitIsTrustedAsIs_noMarkerWarning() {
        assertEquals(1, run(BgCli.Parsed.Run("g", fixture("1", "BUILD FAILED"), notify = true)))
        assertNull(done().marker)
        assertTrue(logOf().trimEnd().lines().last().endsWith("]") && !logOf().contains("warning="), logOf())
    }

    @Test
    fun aRedirectIsNamedButNotFollowedByDefault_andFollowedWhenTheSettingIsOn() {
        val target = File(Files.createTempDirectory("s4").toFile(), "x.log").absolutePath
        val cmd = fixture("0", "hi", ">", target)
        run(BgCli.Parsed.Run("g", cmd, notify = true))
        assertEquals(listOf(target), firstJob().redirects)
        assertEquals(emptyList(), firstJob().follow)
        reqs.clear()
        run(BgCli.Parsed.Run("g", cmd, notify = true), env = mapOf(BgCli.FOLLOW_REDIRECTS_ENV to "1"))
        assertEquals(listOf(target), firstJob().follow)
        assertEquals(emptyList(), firstJob().redirects)
        reqs.clear()
        val explicit = File(target).resolveSibling("y.log").absolutePath
        run(BgCli.Parsed.Run("g", fixture("0", "hi"), notify = true, follow = listOf(explicit)))
        assertEquals(listOf(explicit), firstJob().follow)
    }

    @Test
    fun belowTheFreeSpaceFloorTheLogPauses_theCommandStillRunsAndStreams() {
        val out = StringBuilder()
        assertEquals(0, run(BgCli.Parsed.Run("g", fixture("0", "still streamed"), notify = true), out = out, freeSpace = { 1L }))
        assertTrue(out.contains("still streamed"), out.toString())
        assertTrue(out.contains("log file paused"), out.toString())
        val paused = reqs.filter { it.op == "beat" }.mapNotNull { it.job?.logPaused }
        assertEquals(1, paused.distinct().size, "told the daemon once: $paused")
        assertTrue(!File(firstJob().logPath!!).readText().contains("still streamed"))
    }

    @Test
    fun aPipeInAShellCommandLineWithoutPipefailIsFlagged() {
        // No bash on this host, or only WSL's launcher (System32\bash.exe), which may have no distro: nothing to test.
        val bash = BgCli.resolveCommand(listOf("bash"), cwd, System::getenv)?.takeIf { !it.first().contains("System32", ignoreCase = true) } ?: return
        run(BgCli.Parsed.Run("g", listOf(bash.first(), "-c", "echo hi | cat"), notify = true))
        assertEquals(true, done().pipeNoFail)
        assertTrue(logOf().contains("warning=\"pipe without pipefail"), logOf())
        reqs.clear()
        // A hooked bash script gets pipefail prepended: `false | cat` then exits 1, not cat's 0.
        val script = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("false | cat".toByteArray())
        assertEquals(1, run(BgCli.Parsed.Run("g", emptyList(), notify = true, shell = bash.first(), scriptB64 = script)))
        assertNull(done().pipeNoFail)
    }

    @Test
    fun watchFile_isDoneWhenItsPidExits_orWhenTheFileStopsGrowing() {
        val f = File.createTempFile("s4w", ".log").apply { deleteOnExit(); writeText("start\n") }
        var alive = true
        val t = Thread {
            Thread.sleep(400); f.appendText("BUILD SUCCESSFUL\n"); Thread.sleep(200); alive = false
        }.apply { start() }
        BgCli.watchFile(BgCli.Parsed.WatchFile(f.absolutePath, untilPid = 4242L, jobId = "w1"), "sess-1", { null }, StringBuilder(),
            pidAlive = { alive }, pollMs = 50) { BgCli.Reporter({ reqs += it }, {}) }
        t.join()
        val beat = firstJob()
        assertEquals(listOf(f.absolutePath), beat.follow)
        assertNull(beat.logPath, "watch-file has no log of its own: the file is never copied")
        assertEquals(4242L, beat.cmdPid)
        val d = done()
        assertEquals("w1", d.jobId)
        assertEquals(listOf("start", "BUILD SUCCESSFUL"), d.tail)
        assertEquals("BUILD SUCCESSFUL\n".length.toLong(), d.bytes)

        reqs.clear()
        val t0 = System.currentTimeMillis()
        BgCli.watchFile(BgCli.Parsed.WatchFile(f.absolutePath, idleSeconds = 1, jobId = "w2"), "sess-1", { null }, StringBuilder(), pollMs = 50) {
            BgCli.Reporter({ reqs += it }, {})
        }
        assertTrue(System.currentTimeMillis() - t0 in 900..5_000, "done after ~1 s idle")
        assertNotNull(reqs.lastOrNull { it.op == "done" && it.jobId == "w2" })
    }

    @Test
    fun watchFileParses() {
        assertEquals(
            BgCli.Parsed.WatchFile("x.log", untilPid = 12, idleSeconds = 30, name = "n"),
            BgCli.parse(listOf("watch-file", "x.log", "--until-pid", "12", "--idle-seconds", "30", "--name", "n")),
        )
        assertTrue(BgCli.parse(listOf("watch-file")) is BgCli.Parsed.Bad)
        assertTrue(BgCli.parse(listOf("watch-file", "x", "--until-pid", "no")) is BgCli.Parsed.Bad)
        assertEquals(listOf("a.log"), (BgCli.parse(listOf("run", "--follow", "a.log", "--", "gradle")) as BgCli.Parsed.Run).follow)
    }
}
