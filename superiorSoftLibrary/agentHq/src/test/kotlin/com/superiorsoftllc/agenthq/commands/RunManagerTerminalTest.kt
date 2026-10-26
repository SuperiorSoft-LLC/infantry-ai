package com.superiorsoftllc.agenthq.commands

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.runner.TerminalListener
import com.superiorsoftllc.agenthq.runner.TerminalProcess
import com.superiorsoftllc.agenthq.runner.TerminalRunner
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RUN-CONSOLE (ticket 67f117b6, contract 1.134.0): a run through a terminal, over a fake pty. */
class RunManagerTerminalTest {
    private val repo: File = Files.createTempDirectory("agenthq-run-term").toFile()
    private val registry = CommandRegistry.parse("""[{"id":"build","name":"Build web","argv":["x"]}]""", repo)
    private val frames = CopyOnWriteArrayList<Envelope>()

    /** A pty the test drives by hand: [listener] is the run's, [size] / [typed] what the run asked of it. */
    private class FakeTerminal(private val failWith: Throwable? = null) : TerminalRunner {
        lateinit var listener: TerminalListener
        var size: Pair<Int, Int>? = null
        val typed = mutableListOf<String>()
        var alive = true
        override fun start(spec: ProcessSpec, cols: Int, rows: Int, listener: TerminalListener): TerminalProcess {
            failWith?.let { throw it }
            this.listener = listener
            size = cols to rows
            return object : TerminalProcess {
                override val isAlive get() = alive
                override val pid: Long = 42
                override fun write(bytes: ByteArray) { typed += String(bytes) }
                override fun resize(cols: Int, rows: Int) { size = cols to rows }
                override fun terminate() { alive = false; listener.onExit(130) }
                override fun killTree() { alive = false }
            }
        }
        fun out(text: String) = listener.onBytes(text.toByteArray())
    }

    private fun manager(t: TerminalRunner, history: Int = 20, scrollback: Int = 1 shl 20, demo: Boolean = false) = RunManager(
        { registry }, ScriptedRunner(StubScript(stdoutLines = listOf("piped line"))), { frames += it },
        terminal = t, limits = RunManager.RunLimits({ scrollback }, { history }), demoLock = { demo },
    )

    private fun start(m: RunManager, data: String = """{"commandId":"build"}"""): String {
        val s = m.start(data)
        assertEquals(Envelope.RUN, s.reply.type, s.reply.data)
        s.launch()
        return Json.decodeFromString(RunRecord.serializer(), s.reply.data).runId
    }

    private fun bytes() = frames.filter { it.type == Envelope.RUN_BYTES }.map { Json.decodeFromString(RunBytes.serializer(), it.data) }
    private fun lines() = frames.filter { it.type == Envelope.RUN_OUTPUT }.map { Json.decodeFromString(RunOutput.serializer(), it.data).line }
    private fun String.b64() = Base64.getEncoder().encodeToString(toByteArray())
    private fun RunBytes.text() = String(Base64.getDecoder().decode(data))

    @Test fun `terminal output goes out raw as run_bytes 1 to n and as plain run_output lines`() {
        val t = FakeTerminal()
        val m = manager(t)
        start(m)
        t.out("\u001b[32mloading 10%"); t.out("\rloading 100%\u001b[0m\n"); t.out("done\n")
        t.listener.onExit(0)
        assertEquals(listOf(1L, 2L, 3L), bytes().map { it.sequence })
        assertEquals("\u001b[32mloading 10%\rloading 100%\u001b[0m\ndone\n", bytes().joinToString("") { it.text() })
        assertEquals(listOf("loading 100%", "done"), lines())
        assertTrue(frames.last().type == Envelope.RUN_FINISHED && frames.last().data.contains("SUCCEEDED"))
    }

    @Test fun `the run record names the command and says it has a terminal, and RUNNING reaches every client`() {
        val t = FakeTerminal()
        start(manager(t))
        val running = frames.filter { it.type == Envelope.RUN }.map { Json.decodeFromString(RunRecord.serializer(), it.data) }.single()
        assertEquals("RUNNING", running.state)
        assertEquals("Build web", running.name)
        assertTrue(running.terminal)
        assertEquals(42L, running.pid)
    }

    @Test fun `attach replays the kept scrollback with the sequence it covers, after the run too`() {
        val t = FakeTerminal()
        val m = manager(t, scrollback = 1024)
        val id = start(m)
        t.out("one\n"); t.out("two\n")
        t.listener.onExit(1)
        val replay = Json.decodeFromString(RunBytes.serializer(), m.attach("""{"runId":"$id"}""").data)
        assertTrue(replay.replay)
        assertEquals(2L, replay.sequence)
        assertEquals("one\ntwo\n", replay.text())
        assertEquals(Envelope.ERROR, m.attach("""{"runId":"nope"}""").type)
    }

    @Test fun `start sizes the pty and resize reaches it`() {
        val t = FakeTerminal()
        val m = manager(t)
        val id = start(m, """{"commandId":"build","cols":100,"rows":40}""")
        assertEquals(100 to 40, t.size)
        assertNull(m.resize("""{"runId":"$id","cols":132,"rows":50}"""))
        assertEquals(132 to 50, t.size)
        assertEquals(Envelope.ERROR, m.resize("""{"runId":"$id","cols":0,"rows":50}""")?.type)
    }

    @Test fun `input is refused unless the run was started interactive`() {
        val t = FakeTerminal()
        val m = manager(t)
        val id = start(m)
        val refused = m.input("""{"runId":"$id","data":"${"y\r".b64()}"}""")
        assertTrue(refused!!.data.contains("not started interactive"), refused.data)
        assertTrue(t.typed.isEmpty())
    }

    @Test fun `an interactive run takes keys`() {
        val t = FakeTerminal()
        val m = manager(t)
        val id = start(m, """{"commandId":"build","interactive":true}""")
        assertNull(m.input("""{"runId":"$id","data":"${"y\r".b64()}"}"""))
        assertEquals(listOf("y\r"), t.typed)
    }

    @Test fun `the demo lock refuses an interactive start and any input`() {
        val m = manager(FakeTerminal(), demo = true)
        assertEquals(Envelope.ERROR, m.start("""{"commandId":"build","interactive":true}""").reply.type)
        val id = start(m) // a plain run still starts
        assertTrue(m.input("""{"runId":"$id","data":"${"x".b64()}"}""")!!.data.contains("demo lock"))
    }

    @Test fun `run_list returns the kept runs newest first and forgets the oldest past the history cap`() {
        val t = FakeTerminal()
        val m = manager(t, history = 2)
        val ids = (1..3).map { code -> start(m).also { t.listener.onExit(code) } }
        val runs = Json.decodeFromString(ListSerializer(RunSummary.serializer()), m.list().data)
        assertEquals(listOf(ids[2], ids[1]), runs.map { it.runId })
        assertEquals(listOf("FINISHED", "FINISHED"), runs.map { it.state })
        assertEquals(listOf(3, 2), runs.map { it.exitCode })
        assertEquals("Build web", runs[0].name)
        assertEquals(Envelope.ERROR, m.attach("""{"runId":"${ids[0]}"}""").type)
    }

    @Test fun `no pty on this host falls back to the pipes`() {
        val m = manager(FakeTerminal(failWith = UnsatisfiedLinkError("no libpty")))
        start(m)
        val end = System.nanoTime() + 5_000_000_000L
        while (frames.none { it.type == Envelope.RUN_FINISHED } && System.nanoTime() < end) Thread.sleep(5)
        assertEquals(listOf("piped line"), lines())
        assertTrue(bytes().isEmpty())
        val summary = Json.decodeFromString(ListSerializer(RunSummary.serializer()), m.list().data).single()
        assertFalse(summary.terminal)
    }

    @Test fun `stop on a terminal run ends it as KILLED_GRACEFUL`() {
        val t = FakeTerminal()
        val m = manager(t)
        val id = start(m)
        assertNull(m.stop(id))
        val end = System.nanoTime() + 5_000_000_000L
        while (frames.none { it.type == Envelope.RUN_FINISHED } && System.nanoTime() < end) Thread.sleep(5)
        val f = Json.decodeFromString(RunFinished.serializer(), frames.single { it.type == Envelope.RUN_FINISHED }.data)
        assertEquals("KILLED_GRACEFUL", f.outcome)
    }
}
