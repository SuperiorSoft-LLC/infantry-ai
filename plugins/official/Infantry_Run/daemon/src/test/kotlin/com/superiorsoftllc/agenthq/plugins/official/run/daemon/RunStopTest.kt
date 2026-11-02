package com.superiorsoftllc.agenthq.plugins.official.run.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.runner.PipedProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** IN-APP BUILD T4: run_stop. A stop is KILLED_*, never FAILED, and there is still exactly one run_finished. */
class RunStopTest {
    private val repo: File = Files.createTempDirectory("agenthq-stop-test").toFile()
    private val frames = CopyOnWriteArrayList<Envelope>()
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun registry(argv: String, grace: Long) =
        CommandRegistry.parse("""[{"id":"build","argv":$argv,"graceMillis":$grace}]""", repo)

    private fun finishedFrames() = frames.filter { it.type == Envelope.RUN_FINISHED }
        .map { Json.decodeFromString(RunFinished.serializer(), it.data) }

    private fun awaitFinished(): RunFinished {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (finishedFrames().isEmpty() && System.nanoTime() < end) Thread.sleep(10)
        Thread.sleep(100) // a duplicate would land here
        assertEquals(1, finishedFrames().size, "exactly one run_finished")
        return finishedFrames().single()
    }

    private fun startRun(m: RunManager): String {
        val s = m.start("""{"commandId":"build"}""")
        assertEquals(Envelope.RUN, s.reply.type, s.reply.data)
        s.launch()
        return Json.decodeFromString(RunRecord.serializer(), s.reply.data).runId
    }

    /** A child that ignores nothing: it exits as soon as terminate() is called. */
    private class PoliteRunner : ProcessRunner {
        override val id = "polite"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess = object : RunningProcess {
            private val done = CountDownLatch(1)
            @Volatile private var alive = true
            override val isAlive get() = alive
            override fun writeInput(text: String) {}
            override fun closeInput() {}
            override fun terminate() { alive = false; Thread { listener.onExit(143); done.countDown() }.start() }
            override fun killTree() { alive = false }
            override fun awaitExit(): Int { done.await(); return 143 }
        }
    }

    @Test fun `stop of a child that exits on request is KILLED_GRACEFUL not FAILED`() {
        val m = RunManager(registry("""["x"]""", 5000), PoliteRunner(), { frames += it })
        val runId = startRun(m)
        assertEquals(null, m.stop(runId))
        val f = awaitFinished()
        assertEquals("KILLED_GRACEFUL", f.outcome)
    }

    @Test fun `stop of a child that ignores the request is KILLED_FORCED after the grace window`() {
        val m = RunManager(registry("""["x"]""", 150), ScriptedRunner(StubScript(stdoutLines = listOf("a")), 60_000), { frames += it })
        val runId = startRun(m)
        val t0 = System.nanoTime()
        assertEquals(null, m.stop(runId))
        val f = awaitFinished()
        assertEquals("KILLED_FORCED", f.outcome)
        assertTrue(f.exitCode == null || f.exitCode != 0)
        assertTrue(System.nanoTime() - t0 >= 100_000_000L, "waited for the grace window")
    }

    @Test fun `stop force skips the grace window`() {
        val m = RunManager(registry("""["x"]""", 60_000), ScriptedRunner(StubScript(stdoutLines = listOf("a")), 60_000), { frames += it })
        val runId = startRun(m)
        assertEquals(null, m.stop("$runId force"))
        assertEquals("KILLED_FORCED", awaitFinished().outcome)
    }

    @Test fun `a bad or unknown stop answers an error and a second stop adds no second run_finished`() {
        val m = RunManager(registry("""["x"]""", 100), ScriptedRunner(StubScript(stdoutLines = listOf("a")), 60_000), { frames += it })
        assertEquals(Envelope.ERROR, m.stop("")!!.type)
        assertEquals(Envelope.ERROR, m.stop("nope")!!.type)
        val runId = startRun(m)
        assertEquals(Envelope.ERROR, m.stop("$runId bogus")!!.type)
        assertEquals(null, m.stop("$runId force"))
        m.stop("$runId force")
        awaitFinished()
        assertNotNull(m.stop(runId)) // finished: no run in flight any more
    }

    @Test fun `a real grandchild process dies with the tree`() {
        val argv = if (windows) """["cmd.exe","/c","ping -n 60 127.0.0.1"]""" else """["sh","-c","sleep 60; true"]"""
        val m = RunManager(registry(argv, 200), PipedProcessRunner(), { frames += it })
        val runId = startRun(m)
        var grand: ProcessHandle? = null
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (grand == null && System.nanoTime() < end) {
            grand = ProcessHandle.current().descendants()
                .filter { it.info().command().orElse("").let { c -> c.endsWith("PING.EXE", true) || c.endsWith("sleep") } }
                .findFirst().orElse(null)
            if (grand == null) Thread.sleep(50)
        }
        assertNotNull(grand, "grandchild process started")
        try {
            assertEquals(null, m.stop("$runId force"))
            val f = awaitFinished()
            assertTrue(f.outcome.startsWith("KILLED_"), f.outcome)
            assertNotEquals("FAILED", f.outcome)
            val dead = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (grand.isAlive && System.nanoTime() < dead) Thread.sleep(50)
            assertTrue(!grand.isAlive, "grandchild survived the tree kill")
        } finally {
            grand.destroyForcibly()
        }
    }

    @Test fun `graceful and forced taskkill both carry the tree flag`() {
        val graceful = com.superiorsoftllc.agenthq.runner.ProcessTreeKiller.taskkillArgs(42, force = false)
        val forced = com.superiorsoftllc.agenthq.runner.ProcessTreeKiller.taskkillArgs(42, force = true)
        assertTrue("/T" in graceful, "graceful taskkill must pass /T: $graceful")
        assertTrue("/F" !in graceful, "graceful taskkill must not force: $graceful")
        assertTrue("/T" in forced && "/F" in forced, "forced taskkill must pass /T /F: $forced")
    }
}
