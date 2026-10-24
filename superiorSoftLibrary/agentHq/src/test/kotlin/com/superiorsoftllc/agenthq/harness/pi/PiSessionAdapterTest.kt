package com.superiorsoftllc.agenthq.harness.pi

import com.superiorsoftllc.agenthq.harness.CliHarness
import com.superiorsoftllc.agenthq.harness.HarnessRegistry
import com.superiorsoftllc.agenthq.provider.HarnessProvider
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionState
import com.superiorsoftllc.agenthq.session.host.LifecyclePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * BUG-M2-11d: Pi's persistent mode ([PiSessionAdapter], `--mode rpc`) driven by the daemon's session host through a real
 * [SessionManager]. The `pi` child is a fake that speaks the probed RPC protocol (docs/AGENTHQ-SESSION-HOST-PROBE.md):
 * a `prompt` line is acknowledged `response/prompt` then runs to `agent_settled`, `get_state` answers the session id,
 * `abort` settles the run `aborted:true` and only THEN answers `response/abort`, stdin EOF exits 0.
 */
class PiSessionAdapterTest {

    private class FakePi(val command: List<String>, private val listener: ProcessListener) : RunningProcess {
        val stdin: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
        @Volatile private var alive = true
        /** False = a run stays in flight until an abort settles it. */
        @Volatile var answerTurns = true
        /** True = the next run's last assistant message ends `stopReason: "error"` (pi still settles normally). */
        @Volatile var failNext = false
        /** Non-null = the next run first asks this dialog method and waits for its answer. */
        @Volatile var dialogNext: String? = null
        private val session = command.indexOf("--session").takeIf { it >= 0 }?.let { command[it + 1] } ?: "pi-ses-1"
        private var turns = 0

        override val isAlive: Boolean get() = alive
        override val pid: Long = 4949

        override fun writeInput(text: String) {
            check(alive) { "stdin closed" }
            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val record = Json.parseToJsonElement(line).jsonObject
                stdin += record
                val id = record["id"]?.jsonPrimitive?.content
                when (record["type"]!!.jsonPrimitive.content) {
                    "prompt" -> {
                        emit("""{"type":"response","command":"prompt","id":"$id","success":true,"data":{"disposition":"started"}}""")
                        if (answerTurns) Thread { run(++turns) }.start()
                    }
                    "get_state" -> emit("""{"type":"response","command":"get_state","id":"$id","success":true,"data":{"sessionId":"$session","sessionFile":"x.jsonl"}}""")
                    "abort" -> {
                        emit("""{"type":"agent_settled","aborted":true}""")
                        emit("""{"type":"response","command":"abort","id":"$id","success":true,"data":null}""")
                    }
                }
            }
        }

        private fun run(n: Int) {
            emit("""{"type":"extension_ui_request","id":"st-$n","method":"setStatus","statusKey":"dcp","statusText":"DCP: idle"}""")
            dialogNext?.let { method ->
                dialogNext = null
                emit("""{"type":"extension_ui_request","id":"dlg-$n","method":"$method","title":"Allow?"}""")
            }
            emit("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"reply $n"}}""")
            val stop = if (failNext) "\"error\",\"errorMessage\":\"API Error: 500\"" else "\"stop\""
            failNext = false
            emit("""{"type":"message_end","message":{"role":"assistant","stopReason":$stop,"usage":{"input":3,"output":2}}}""")
            emit("""{"type":"agent_end","messages":[]}""")
            emit("""{"type":"agent_settled","aborted":false}""")
        }

        private fun emit(line: String) { if (alive) listener.onStdout(line) }

        fun die(code: Int) {
            synchronized(this) { if (!alive) return; alive = false }
            listener.onExit(code)
        }

        override fun closeInput() { Thread { die(0) }.start() }
        override fun killTree() = die(137)
        override fun awaitExit(): Int = 0
    }

    /** The host's piped runner: counts every pi process spawned. */
    private class CountingLauncher : ProcessRunner {
        override val id = "counting"
        val processes = CopyOnWriteArrayList<FakePi>()
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess =
            FakePi(spec.command, listener).also { processes += it }
    }

    /** The one-shot path (the harness's own runner): one `pi --mode json` per reply, counted too. */
    private class OneShotRunner : ProcessRunner {
        override val id = "one-shot"
        val spawns = AtomicInteger()
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            spawns.incrementAndGet()
            return object : RunningProcess {
                @Volatile var alive = true
                override val isAlive get() = alive
                override fun writeInput(text: String) = Unit
                override fun closeInput() {
                    Thread {
                        listener.onStdout("""{"type":"session","id":"pi-oneshot"}""")
                        listener.onStdout("""{"type":"agent_settled","aborted":false}""")
                        alive = false
                        listener.onExit(0)
                    }.start()
                }
                override fun killTree() { alive = false }
                override fun awaitExit() = 0
            }
        }
    }

    private class Rig(keepAliveMinutes: Int) {
        val launcher = CountingLauncher()
        val oneShot = OneShotRunner()
        val events: MutableList<SessionEvent> = Collections.synchronizedList(mutableListOf())

        /** The real harness and adapter, with a launcher that needs no installed pi. */
        val provider = HarnessProvider(
            PiHarness(
                oneShot,
                { PiHarness.defaultSpec(it, launcher = { listOf("node", "pi.js") }) },
                sessionAdapter = PiSessionAdapter(launcher = { listOf("node", "pi.js") }),
            ),
        )
        val manager = SessionManager(
            provider, oneShot, JsonSessionStore(File.createTempFile("agenthq-pi", ".json").also { it.delete() }),
            killGraceMs = 500,
            lifecyclePolicy = { LifecyclePolicy(keepAliveMinutes) },
            lineRunner = launcher,
        ).also { m -> m.eventSink = { events += it } }

        val spawns: Int get() = launcher.processes.size + oneShot.spawns.get()

        fun await(what: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 10_000
            while (!condition()) {
                if (System.currentTimeMillis() > deadline) fail("never $what: ${synchronized(events) { events.toList() }}")
                Thread.sleep(10)
            }
        }

        fun awaitIdle(id: String) = await("idle") { manager.get(id)?.state == SessionState.IDLE }

        /** A copy: the host thread may still append while a test reads. */
        fun snapshot(): List<SessionEvent> = synchronized(events) { events.toList() }

        fun boots() = synchronized(events) { events.filterIsInstance<SessionEvent.Stderr>().map { it.line }.filter { "*" in it } }

        fun sent(p: FakePi, type: String) = synchronized(p.stdin) { p.stdin.toList() }.filter { it["type"]!!.jsonPrimitive.content == type }

        fun prompts(p: FakePi) = sent(p, "prompt").map { it["message"]!!.jsonPrimitive.content }
    }

    private val rigs = mutableListOf<Rig>()
    private fun rig(keepAliveMinutes: Int = 30) = Rig(keepAliveMinutes).also { rigs += it }

    @AfterTest
    fun tearDown() = rigs.forEach { it.manager.shutdown() }

    @Test
    fun twoPrompts_reuseOnePiProcess() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "m")
        rig.awaitIdle(session.id)
        rig.manager.resume(session.id, "second")
        rig.awaitIdle(session.id)

        assertEquals(1, rig.spawns, "two prompts in one pi session reuse ONE process")
        val pi = rig.launcher.processes.single()
        assertEquals(listOf("first", "second"), rig.prompts(pi))
        assertTrue(pi.isAlive, "the process outlives the turn")
        val argv = pi.command
        assertEquals("rpc", argv[argv.indexOf("--mode") + 1], "the persistent mode is --mode rpc: $argv")
        assertFalse("--session" in argv, "a fresh session resumes nothing: $argv")
        assertEquals("pi-ses-1", rig.manager.get(session.id)?.providerSessionId, "the id comes from get_state (rpc prints no session header)")
        assertEquals(listOf("[agenthq] * session started (pi · m)"), rig.boots(), "one chat line for the launch, none per turn")
        assertEquals(0, rig.snapshot().filterIsInstance<SessionEvent.ProcessExit>().size)
        assertTrue(rig.snapshot().filterIsInstance<SessionEvent.Exit>().none { it.processExited }, "a turn end is not a process exit")
    }

    @Test
    fun crash_restartsOnce_andResumesTheSessionId() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "m")
        rig.awaitIdle(session.id)
        rig.launcher.processes.single().die(1)
        rig.await("the crash reported") { rig.snapshot().filterIsInstance<SessionEvent.ProcessExit>().size == 1 }
        assertEquals(1, rig.launcher.processes.size, "no restart without a prompt")

        rig.manager.resume(session.id, "second")
        rig.await("the second turn answered") { rig.launcher.processes.size == 2 && rig.prompts(rig.launcher.processes[1]).size == 1 }
        rig.awaitIdle(session.id)

        assertEquals(2, rig.launcher.processes.size, "the next prompt restarts it ONCE")
        val argv = rig.launcher.processes[1].command
        assertEquals("pi-ses-1", argv[argv.indexOf("--session") + 1], "and resumes the session id: $argv")
        assertEquals(
            listOf("[agenthq] * session started (pi · m)", "[agenthq] * session restarted after a crash (pi · m)"),
            rig.boots(),
        )
    }

    @Test
    fun interrupt_sendsAbort_andKeepsTheProcess() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "m")
        rig.awaitIdle(session.id)
        val pi = rig.launcher.processes.single()
        pi.answerTurns = false
        rig.manager.resume(session.id, "a long scan")
        rig.await("the long prompt sent") { rig.prompts(pi).size == 2 }
        rig.manager.interrupt(session.id)
        rig.awaitIdle(session.id)

        assertEquals(1, rig.sent(pi, "abort").size, "the interrupt is pi's abort command")
        assertTrue(pi.isAlive, "and the process lives on")

        pi.answerTurns = true
        rig.manager.resume(session.id, "next")
        rig.await("the next turn answered") { rig.prompts(pi).size == 3 }
        rig.awaitIdle(session.id)
        assertEquals(1, rig.spawns, "still one process")
    }

    @Test
    fun errorStopReason_failsTheTurn_keepsTheProcess() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "m")
        rig.awaitIdle(session.id)
        val pi = rig.launcher.processes.single()
        pi.failNext = true
        rig.manager.resume(session.id, "second")
        rig.await("the failed turn settled") { rig.manager.get(session.id)?.state.let { it != null && it != SessionState.BUSY && rig.prompts(pi).size == 2 } }
        assertNotEquals(SessionState.IDLE, rig.manager.get(session.id)?.state, "a stopReason error is not a completed turn")
        assertTrue(pi.isAlive, "a failed turn keeps the process")
        assertEquals(1, rig.spawns)
    }

    @Test
    fun dialogRequest_isAnsweredCancelled_statusIsNot() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "m")
        rig.awaitIdle(session.id)
        val pi = rig.launcher.processes.single()
        pi.dialogNext = "confirm"
        rig.manager.resume(session.id, "second")
        rig.awaitIdle(session.id)

        val answers = rig.sent(pi, "extension_ui_response")
        assertEquals(listOf("dlg-2"), answers.map { it["id"]!!.jsonPrimitive.content }, "only the dialog is answered, never setStatus")
        assertEquals("true", answers.single()["cancelled"]!!.jsonPrimitive.content)
    }

    @Test
    fun closingTheSession_closesStdin_andTheProcessExits() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "m")
        rig.awaitIdle(session.id)
        val pi = rig.launcher.processes.single()
        rig.manager.kill(session.id)
        rig.await("the process gone") { !pi.isAlive }
        assertEquals(1, rig.spawns)
    }

    @Test
    fun keepAliveZero_runsPiOneShot() {
        val rig = rig(keepAliveMinutes = 0)
        val session = rig.manager.startNew("first", model = "m")
        rig.awaitIdle(session.id)
        rig.manager.resume(session.id, "second")
        rig.awaitIdle(session.id)
        assertEquals(0, rig.launcher.processes.size, "keep-alive off: no persistent pi")
        assertEquals(2, rig.oneShot.spawns.get(), "one `pi --mode json` per reply")
    }

    @Test
    fun fingerprint_modelChangeRestarts_resumeDoesNot() {
        val adapter = PiSessionAdapter(launcher = { error("never resolved for a fingerprint") })
        val base = RunRequest("p", File("w"), model = "m")
        assertEquals(adapter.fingerprint(base, emptyMap()), adapter.fingerprint(base.copy(prompt = "q", resumeSessionId = "s"), emptyMap()))
        assertNotEquals(adapter.fingerprint(base, emptyMap()), adapter.fingerprint(base.copy(model = "other"), emptyMap()))
        assertNotEquals(adapter.fingerprint(base, emptyMap()), adapter.fingerprint(base.copy(effort = "high"), emptyMap()))
        assertNotEquals(adapter.fingerprint(base, emptyMap()), adapter.fingerprint(base, mapOf("K" to "V")))
        assertNotEquals(adapter.fingerprint(base, emptyMap()), adapter.fingerprint(base.copy(workingDir = File("x")), emptyMap()))
    }

    @Test
    fun stdinLines_areTheProbedShapes() {
        assertEquals("""{"id":"p1","type":"prompt","message":"say \"hi\"\nnow"}""", PiSessionAdapter.promptLine("p1", "say \"hi\"\nnow"))
        assertEquals("""{"id":"a1","type":"abort"}""", PiSessionAdapter.abortLine("a1"))
        assertEquals("""{"id":"s1","type":"get_state"}""", PiSessionAdapter.stateLine("s1"))
        assertEquals("""{"type":"extension_ui_response","id":"d1","cancelled":true}""", PiSessionAdapter.dialogCancelLine("d1"))
    }

    /** The ServiceLoader's plugin offers the persistent adapter; a replaced runner (tests, a host) stays one-shot. */
    @Test
    fun servicePlugin_offersThePersistentAdapter_replacedRunnerDoesNot() {
        val registry = HarnessRegistry().also { it.register(PiHarnessPlugin()) }
        assertTrue((registry.resolve("pi") as CliHarness).sessionAdapter() is PiSessionAdapter)
        val replaced = HarnessRegistry().also { it.register(PiHarnessPlugin(OneShotRunner()) { ProcessSpec(listOf("pi-test")) }) }
        assertNull((replaced.resolve("pi") as CliHarness).sessionAdapter())
    }
}
