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
    private class FakeTerminal(private val failWith: Throwable? = null, private val writeGate: java.util.concurrent.CountDownLatch? = null) : TerminalRunner {
        lateinit var listener: TerminalListener
        var size: Pair<Int, Int>? = null
        val typed = CopyOnWriteArrayList<String>()
        /** SEC-FIX-1: the thread each write ran on (it must never be the caller of run_input). */
        val writers = CopyOnWriteArrayList<Thread>()
        var alive = true
        override fun start(spec: ProcessSpec, cols: Int, rows: Int, listener: TerminalListener): TerminalProcess {
            failWith?.let { throw it }
            this.listener = listener
            size = cols to rows
            return object : TerminalProcess {
                override val isAlive get() = alive
                override val pid: Long = 42
                override fun write(bytes: ByteArray) { writers += Thread.currentThread(); writeGate?.await(); typed += String(bytes) }
                override fun resize(cols: Int, rows: Int) { size = cols to rows }
                override fun terminate() { alive = false; listener.onExit(130) }
                override fun killTree() { alive = false }
            }
        }
        fun out(text: String) = listener.onBytes(text.toByteArray())
    }

    private var clock = 1_000_000L

    private fun manager(
        t: TerminalRunner, history: Int = 20, scrollback: Int = 1 shl 20, demo: Boolean = false,
        rate: Int = 0, maxFrame: Int = 1 shl 14, queue: Int = 1 shl 18, maxCols: Int = 1000, maxRows: Int = 1000,
    ) = RunManager(
        { registry }, ScriptedRunner(StubScript(stdoutLines = listOf("piped line"))), { frames += it }, now = { clock },
        terminal = t,
        limits = RunManager.RunLimits(
            scrollbackBytes = { scrollback }, history = { history }, inputBytesPerSecond = { rate }, inputMaxFrameBytes = { maxFrame },
            inputQueueBytes = { queue }, maxCols = { maxCols }, maxRows = { maxRows },
        ),
        restricted = { demo },
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
        awaitTyped(t, listOf("y\r"))
        assertTrue(t.writers.none { it === Thread.currentThread() }, "the pty was written on the run_input caller's thread")
    }

    private fun awaitTyped(t: FakeTerminal, expected: List<String>) {
        val end = System.nanoTime() + 5_000_000_000L
        while (t.typed.toList() != expected && System.nanoTime() < end) Thread.sleep(5)
        assertEquals(expected, t.typed.toList())
    }

    private fun input(m: RunManager, id: String, text: String) = m.input("""{"runId":"$id","data":"${text.b64()}"}""")

    @Test fun `SEC-FIX-1 a run_input over limits_run_inputMaxFrameBytes is refused whole`() {
        val t = FakeTerminal()
        val m = manager(t, maxFrame = 4)
        val id = start(m, """{"commandId":"build","interactive":true}""")
        val refused = input(m, id, "12345")
        assertTrue(refused!!.data.contains("at most 4 bytes") && refused.data.contains("limits.run.inputMaxFrameBytes"), refused.data)
        // A frame far over the cap is refused on its length, before it is decoded.
        assertEquals(Envelope.ERROR, input(m, id, "x".repeat(100_000))?.type)
        assertNull(input(m, id, "1234"))
        awaitTyped(t, listOf("1234"))
    }

    @Test fun `SEC-FIX-1 input over limits_run_inputBytesPerSecond is refused until the allowance refills`() {
        val t = FakeTerminal()
        val m = manager(t, rate = 10)
        val id = start(m, """{"commandId":"build","interactive":true}""")
        assertNull(input(m, id, "0123456789"))
        val refused = input(m, id, "a")
        assertTrue(refused!!.data.contains("limits.run.inputBytesPerSecond"), refused.data)
        clock += 100 // 100 ms at 10 bytes/s = 1 byte back
        assertNull(input(m, id, "b"))
        assertEquals(Envelope.ERROR, input(m, id, "c")?.type)
        awaitTyped(t, listOf("0123456789", "b"))
    }

    @Test fun `SEC-FIX-1 a stuck pty never blocks run_input, and the waiting input is bounded by limits_run_inputQueueBytes`() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val t = FakeTerminal(writeGate = gate)
        val m = manager(t, queue = 8)
        val id = start(m, """{"commandId":"build","interactive":true}""")
        val began = System.nanoTime()
        assertNull(input(m, id, "aaaa"))
        assertNull(input(m, id, "bbbb"))
        val refused = input(m, id, "c")
        assertTrue(System.nanoTime() - began < 2_000_000_000L, "run_input waited for the pty")
        assertTrue(refused!!.data.contains("not keeping up") && refused.data.contains("limits.run.inputQueueBytes"), refused.data)
        gate.countDown()
        awaitTyped(t, listOf("aaaa", "bbbb"))
        assertNull(input(m, id, "c"))
        awaitTyped(t, listOf("aaaa", "bbbb", "c"))
    }

    @Test fun `SEC-FIX-1 cols and rows are clamped to limits_run_maxCols and maxRows at start and on resize`() {
        val t = FakeTerminal()
        val m = manager(t, maxCols = 1000, maxRows = 500)
        val id = start(m, """{"commandId":"build","cols":100000,"rows":100000}""")
        assertEquals(1000 to 500, t.size)
        assertNull(m.resize("""{"runId":"$id","cols":100000,"rows":3}"""))
        assertEquals(1000 to 3, t.size)
    }

    @Test fun `a restricted policy refuses an interactive start and any input`() {
        val m = manager(FakeTerminal(), demo = true)
        assertEquals(Envelope.ERROR, m.start("""{"commandId":"build","interactive":true}""").reply.type)
        val id = start(m) // a plain run still starts
        assertTrue(m.input("""{"runId":"$id","data":"${"x".b64()}"}""")!!.data.contains("restricted policy"))
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
