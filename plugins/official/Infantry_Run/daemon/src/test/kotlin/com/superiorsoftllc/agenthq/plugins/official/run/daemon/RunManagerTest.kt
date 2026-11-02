package com.superiorsoftllc.agenthq.plugins.official.run.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
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
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunManagerTest {
    private val repo: File = Files.createTempDirectory("agenthq-run-test").toFile()
    private val registry = CommandRegistry.parse("""[{"id":"build","argv":["x","y"]}]""", repo)
    private val frames = CopyOnWriteArrayList<Envelope>()

    private fun manager(script: StubScript, delay: Long = 0) =
        RunManager(registry, ScriptedRunner(script, delay), { frames += it })

    private fun awaitFinished() {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (frames.none { it.type == Envelope.RUN_FINISHED } && System.nanoTime() < end) Thread.sleep(5)
        Thread.sleep(50) // a duplicate would land here
    }

    private fun outputs() = frames.filter { it.type == Envelope.RUN_OUTPUT }
        .map { Json.decodeFromString(RunOutput.serializer(), it.data) }

    private fun runOf(script: StubScript): RunManager {
        val m = manager(script)
        val s = m.start("""{"commandId":"build"}""")
        assertEquals(Envelope.RUN, s.reply.type)
        s.launch(); awaitFinished()
        return m
    }

    @Test fun `sequence runs 1 to n with no gap or repeat`() {
        runOf(StubScript(stdoutLines = (1..5).map { "o$it" }, stderrLines = listOf("e1", "e2")))
        assertEquals((1L..7L).toList(), outputs().map { it.sequence })
        assertEquals(listOf("stdout", "stderr"), outputs().map { it.stream }.distinct())
    }

    @Test fun `exactly one run_finished per run`() {
        runOf(StubScript(stdoutLines = listOf("a"), exitCode = 0))
        val fin = frames.filter { it.type == Envelope.RUN_FINISHED }
        assertEquals(1, fin.size)
        assertTrue(fin[0].data.contains("SUCCEEDED"), fin[0].data)
    }

    @Test fun `non zero exit is FAILED with the code`() {
        runOf(StubScript(exitCode = 3))
        val f = Json.decodeFromString(RunFinished.serializer(), frames.single { it.type == Envelope.RUN_FINISHED }.data)
        assertEquals("FAILED", f.outcome); assertEquals(3, f.exitCode)
    }

    @Test fun `unknown id and a second run in flight answer error`() {
        val m = manager(StubScript(), 0)
        assertEquals(Envelope.ERROR, m.start("""{"commandId":"nope"}""").reply.type)
        assertEquals(Envelope.ERROR, m.start("not json").reply.type)
        val first = m.start("""{"commandId":"build"}""")
        assertEquals(Envelope.RUN, first.reply.type)
        assertEquals(Envelope.ERROR, m.start("""{"commandId":"build"}""").reply.type)
        first.launch(); awaitFinished()
        assertEquals(Envelope.RUN, m.start("""{"commandId":"build"}""").reply.type) // free again after finish
    }

    @Test fun `carriage return and newline both break lines`() {
        runOf(StubScript(stdoutLines = listOf("a\rb\nc\r\nd")))
        assertEquals(listOf("a", "b", "c", "d"), outputs().map { it.line })
    }

    @Test fun `a line over 8 KB is cut and flagged`() {
        runOf(StubScript(stdoutLines = listOf("x".repeat(9000), "ok")))
        val o = outputs()
        assertEquals(8192, o[0].line.length); assertTrue(o[0].truncated)
        assertTrue(!o[1].truncated)
    }

    @Test fun `output is capped at 10000 lines then one truncation line`() {
        runOf(StubScript(stdoutLines = (1..10_500).map { "l$it" }))
        val o = outputs()
        assertEquals(10_001, o.size)
        assertEquals("[output truncated]", o.last().line)
        assertEquals("l10000", o[9_999].line)
        assertEquals((1L..10_001L).toList(), o.map { it.sequence })
        assertEquals(1, frames.count { it.type == Envelope.RUN_FINISHED })
    }

    private val secret = "SECRET-ARGV-TOKEN-42"
    private val secretRegistry = CommandRegistry.parse("""[{"id":"build","argv":["x","$secret"]}]""", repo)

    @Test fun `spawn failure is FAILED with null exit and no argv in any frame`() {
        val throwing = object : ProcessRunner {
            override val id = "throwing"
            override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess =
                throw IllegalStateException("cannot start ${spec.command}")
        }
        val m = RunManager(secretRegistry, throwing, { frames += it })
        val s = m.start("""{"commandId":"build"}""")
        s.launch(); awaitFinished()
        val f = Json.decodeFromString(RunFinished.serializer(), frames.single { it.type == Envelope.RUN_FINISHED }.data)
        assertEquals("FAILED", f.outcome); assertEquals(null, f.exitCode)
        m.start("""{"commandId":"nope"}""").let { frames += it.reply } // an ERROR frame must not echo argv either
        for (e in frames + s.reply) assertTrue(!e.data.contains(secret), "argv leaked into ${e.type}: ${e.data}")
    }

    @Test fun `a normal run never puts argv in a frame`() {
        val m = RunManager(secretRegistry, ScriptedRunner(StubScript(stdoutLines = listOf("a"), stderrLines = listOf("b")), 0), { frames += it })
        val s = m.start("""{"commandId":"build"}""")
        s.launch(); awaitFinished()
        assertTrue(frames.size >= 3)
        for (e in frames + s.reply) assertTrue(!e.data.contains(secret), "argv leaked into ${e.type}: ${e.data}")
    }
}
