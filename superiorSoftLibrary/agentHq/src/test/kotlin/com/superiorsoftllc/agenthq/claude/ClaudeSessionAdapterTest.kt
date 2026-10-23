package com.superiorsoftllc.agenthq.claude

import com.superiorsoftllc.agenthq.harness.HarnessRegistry
import com.superiorsoftllc.agenthq.harness.claude.ClaudeHarnessPlugin
import com.superiorsoftllc.agenthq.harness.CliHarness
import com.superiorsoftllc.agenthq.provider.AgentProvider
import com.superiorsoftllc.agenthq.provider.ClaudeCodeProvider
import com.superiorsoftllc.agenthq.provider.ProviderFactory
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.provider.providerconfig.ClaudeCatalog
import com.superiorsoftllc.agenthq.protocol.EventNormalizer
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * BUG-M2-11c: Claude Code's persistent mode ([ClaudeSessionAdapter]) driven by the daemon's session host through a real
 * [SessionManager]. The `claude` child is a fake that speaks the probed stream-json protocol: a `user` line on stdin is
 * a turn (answered `system/init`, `assistant`, `result`), a `control_request` interrupt ends the turn with
 * `error_during_execution`, stdin EOF exits 0.
 */
class ClaudeSessionAdapterTest {

    private class FakeClaude(val command: List<String>, private val listener: ProcessListener) : RunningProcess {
        val stdin: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
        @Volatile private var alive = true
        /** False = a turn stays in flight until an interrupt ends it. Read when the turn ARRIVES: a later flip is for a later turn. */
        @Volatile var answerTurns = true
        /** True = the next turn's `result` is an error the CLI reports. */
        @Volatile var failNext = false
        /** True = claude's `result` for an interrupt is only printed when the next `user` line arrives (a slow stop). */
        @Volatile var holdInterruptResult = false
        @Volatile private var heldResult: String? = null
        private val session = command.indexOf("--resume").takeIf { it >= 0 }?.let { command[it + 1] } ?: "claude-ses-1"
        private var turns = 0
        /**
         * BUG-FLAKE-5: claude's ONE stdout. Lines come out in the order their causes went in on stdin, as from the real
         * CLI; a thread per line let a later line's output overtake an earlier one's under load.
         */
        private val stdout = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-claude-stdout").also { it.isDaemon = true } }

        override val isAlive: Boolean get() = alive
        override val pid: Long = 5151

        override fun writeInput(text: String) {
            check(alive) { "stdin closed" }
            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val record = Json.parseToJsonElement(line).jsonObject
                stdin += record
                when (record["type"]!!.jsonPrimitive.content) {
                    "user" -> {
                        val held = heldResult.also { heldResult = null }
                        val reply = if (answerTurns) ++turns else null
                        stdout.execute {
                            held?.let(::emit)
                            reply?.let(::answer)
                        }
                    }
                    "control_request" -> {
                        val result = """{"type":"result","subtype":"error_during_execution","is_error":true,"session_id":"$session"}"""
                        val hold = holdInterruptResult
                        if (hold) heldResult = result
                        stdout.execute {
                            emit("""{"type":"control_response","response":{"subtype":"success","request_id":"${record["request_id"]!!.jsonPrimitive.content}"}}""")
                            if (!hold) emit(result)
                        }
                    }
                }
            }
        }

        private fun answer(n: Int) {
            emit("""{"type":"system","subtype":"init","session_id":"$session","model":"m"}""")
            emit("""{"type":"assistant","message":{"content":[{"type":"text","text":"reply $n"}]},"session_id":"$session"}""")
            if (failNext) {
                failNext = false
                emit("""{"type":"result","subtype":"success","is_error":true,"result":"API Error: 500","session_id":"$session"}""")
            } else {
                emit("""{"type":"result","subtype":"success","is_error":false,"result":"reply $n","session_id":"$session"}""")
            }
        }

        private fun emit(line: String) { if (alive) listener.onStdout(line) }

        fun die(code: Int) {
            synchronized(this) { if (!alive) return; alive = false }
            stdout.shutdown()
            listener.onExit(code)
        }

        override fun closeInput() { Thread { die(0) }.start() }
        override fun killTree() = die(137)
        override fun awaitExit(): Int = 0
    }

    /** The host's piped runner: counts every claude process spawned. */
    private class CountingLauncher : ProcessRunner {
        override val id = "counting"
        val processes = CopyOnWriteArrayList<FakeClaude>()
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess =
            FakeClaude(spec.command, listener).also { processes += it }
    }

    /** The one-shot path (the provider's configured runner): one `claude -p` per reply, counted too. */
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
                        listener.onStdout("""{"type":"result","subtype":"success","is_error":false,"session_id":"claude-oneshot"}""")
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
        private val real = ClaudeCodeProvider(
            { ClaudeCatalog(emptyList(), null, false, emptyList()) },
            ClaudeSessionAdapter(executable = { "claude.exe" }, requestId = { "req-1" }),
        )
        /** The real provider and adapter, with a one-shot argv that needs no installed claude.exe. */
        val provider = object : AgentProvider by real {
            override fun spec(request: RunRequest) = ClaudeCommand.spec(request.workingDir, request.resumeSessionId, request.model, "claude.exe")
            override fun defaultModel() = "m"
        }
        val manager = SessionManager(
            provider, oneShot, JsonSessionStore(File.createTempFile("agenthq-claude", ".json").also { it.delete() }),
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

        fun boots() = synchronized(events) { events.filterIsInstance<SessionEvent.Stderr>().map { it.line }.filter { "↻" in it } }

        fun prompts(p: FakeClaude) = synchronized(p.stdin) { p.stdin.toList() }
            .filter { it["type"]!!.jsonPrimitive.content == "user" }
            .map { it["message"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content }
    }

    private val rigs = mutableListOf<Rig>()
    private fun rig(keepAliveMinutes: Int = 30) = Rig(keepAliveMinutes).also { rigs += it }

    @AfterTest
    fun tearDown() = rigs.forEach { it.manager.shutdown() }

    @Test
    fun twoPrompts_reuseOneClaudeProcess() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "m")
        rig.awaitIdle(session.id)
        rig.manager.resume(session.id, "second")
        rig.awaitIdle(session.id)

        assertEquals(1, rig.spawns, "two prompts in one claude session reuse ONE process")
        val claude = rig.launcher.processes.single()
        assertEquals(listOf("first", "second"), rig.prompts(claude))
        assertTrue(claude.isAlive, "the process outlives the turn")
        val argv = claude.command.joinToString(" ")
        assertTrue("-p --input-format stream-json --output-format stream-json --verbose" in argv, argv)
        assertFalse("--resume" in argv, "a fresh session resumes nothing: $argv")
        assertEquals("claude-ses-1", rig.manager.get(session.id)?.providerSessionId)
        assertEquals(listOf("[agenthq] ↻ session started (claude · m)"), rig.boots(), "one chat line for the launch, none per turn")
        assertEquals(0, rig.events.filterIsInstance<SessionEvent.ProcessExit>().size)
        assertTrue(rig.events.filterIsInstance<SessionEvent.Exit>().none { it.processExited }, "a turn end is not a process exit")
    }

    @Test
    fun crash_restartsOnce_andResumesTheSessionId() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "m")
        rig.awaitIdle(session.id)
        rig.launcher.processes.single().die(1)
        rig.await("the crash reported") { rig.events.filterIsInstance<SessionEvent.ProcessExit>().size == 1 }
        assertEquals(1, rig.launcher.processes.size, "no restart without a prompt")

        rig.manager.resume(session.id, "second")
        rig.await("the second turn answered") { rig.launcher.processes.size == 2 && rig.prompts(rig.launcher.processes[1]).size == 1 }
        rig.awaitIdle(session.id)

        assertEquals(2, rig.launcher.processes.size, "the next prompt restarts it ONCE")
        val argv = rig.launcher.processes[1].command
        assertEquals("claude-ses-1", argv[argv.indexOf("--resume") + 1], "and resumes the session id: $argv")
        assertEquals(
            listOf("[agenthq] ↻ session started (claude · m)", "[agenthq] ↻ session restarted after a crash (claude · m)"),
            rig.boots(),
        )
        assertEquals("claude-ses-1", rig.manager.get(session.id)?.providerSessionId)
    }

    @Test
    fun interrupt_sendsControlRequest_andKeepsTheProcess() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "m")
        rig.awaitIdle(session.id)
        val claude = rig.launcher.processes.single()
        claude.answerTurns = false
        rig.manager.resume(session.id, "a long scan")
        rig.manager.interrupt(session.id)
        rig.awaitIdle(session.id)

        val control = rig.prompts(claude).size to synchronized(claude.stdin) { claude.stdin.last() }
        assertEquals("control_request", control.second["type"]!!.jsonPrimitive.content, "the interrupt is claude's control_request")
        assertEquals("interrupt", control.second["request"]!!.jsonObject["subtype"]!!.jsonPrimitive.content)
        assertTrue(claude.isAlive, "and the process lives on")

        claude.answerTurns = true
        rig.manager.resume(session.id, "next")
        rig.await("the next turn answered") { rig.prompts(claude).size == 3 }
        rig.awaitIdle(session.id)
        assertEquals(1, rig.spawns, "still one process")
    }

    /**
     * BUG-M2-11c-FLAKE: the manager settles an interrupt IDLE at once, so the owner's next prompt can reach claude before
     * claude prints the interrupted turn's `result`. That late result belongs to the interrupted turn: it must neither end
     * the new turn (FAILED, its real result then dropped) nor surface as an ERROR, and the abort grace must not kill the
     * process now serving the new turn.
     */
    @Test
    fun nextPrompt_beforeTheInterruptResult_isNotEndedByIt() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "m")
        rig.awaitIdle(session.id)
        val claude = rig.launcher.processes.single()
        claude.answerTurns = false
        claude.holdInterruptResult = true
        rig.manager.resume(session.id, "a long scan")
        rig.manager.interrupt(session.id)
        rig.awaitIdle(session.id)

        claude.answerTurns = true
        rig.manager.resume(session.id, "next")
        rig.await("the next turn's own result") {
            synchronized(rig.events) { rig.events.filterIsInstance<SessionEvent.Stdout>().any { "\"result\":\"reply 2\"" in it.line } }
        }
        rig.awaitIdle(session.id)
        assertEquals(emptyList(), errorFrames(rig), "the interrupted turn's late result is not the next turn's ERROR")

        Thread.sleep(5_300) // past the host's abort grace (5 s): the superseded interrupt must not kill the process
        assertTrue(claude.isAlive, "the process serving the next turn lives on")
        assertEquals(SessionState.IDLE, rig.manager.get(session.id)?.state)
        assertEquals(1, rig.spawns, "still one process")
    }

    @Test
    fun errorResult_failsTheTurn_keepsTheProcess() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "m")
        rig.awaitIdle(session.id)
        val claude = rig.launcher.processes.single()
        claude.failNext = true
        rig.manager.resume(session.id, "second")
        rig.await("the failed turn settled") { rig.manager.get(session.id)?.state.let { it != null && it != SessionState.BUSY && rig.prompts(claude).size == 2 } }
        assertNotEquals(SessionState.IDLE, rig.manager.get(session.id)?.state, "an is_error result is not a completed turn")
        assertTrue(claude.isAlive, "a failed turn keeps the process")
        assertEquals(1, rig.spawns)
    }

    /** The ERROR frames the daemon would derive from the session's stdout (same mapping as DaemonMain). */
    private fun errorFrames(rig: Rig) = synchronized(rig.events) { rig.events.filterIsInstance<SessionEvent.Stdout>().toList() }
        .flatMap { EventNormalizer.normalise(it.line, afterInterrupt = it.afterInterrupt) }
        .filterIsInstance<EventNormalizer.Line.Failure>()

    @Test
    fun interrupt_emitsNoErrorFrame_andEndsInterrupted() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "m")
        rig.awaitIdle(session.id)
        val claude = rig.launcher.processes.single()
        claude.answerTurns = false
        rig.manager.resume(session.id, "a long scan")
        rig.manager.interrupt(session.id)
        rig.await("claude's interrupt result") {
            synchronized(rig.events) { rig.events.filterIsInstance<SessionEvent.Stdout>().any { "error_during_execution" in it.line } }
        }
        rig.awaitIdle(session.id)

        val result = synchronized(rig.events) { rig.events.filterIsInstance<SessionEvent.Stdout>().single { "error_during_execution" in it.line } }
        assertTrue(result.afterInterrupt, "the result line is tagged as part of the interrupted turn")
        assertEquals(emptyList(), errorFrames(rig), "an owner interrupt is not an ERROR")
        assertEquals(SessionState.IDLE, rig.manager.get(session.id)?.state, "the turn ended INTERRUPTED: the session is at rest, not FAILED")
    }

    @Test
    fun realErrorResult_withoutInterrupt_stillEmitsError() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "m")
        rig.awaitIdle(session.id)
        val claude = rig.launcher.processes.single()
        claude.failNext = true
        rig.manager.resume(session.id, "second")
        rig.await("the error result") { errorFrames(rig).isNotEmpty() }
        assertEquals(1, errorFrames(rig).size, "a real is_error result still produces ERROR")
    }

    @Test
    fun closingTheSession_closesStdin_andTheProcessExits() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "m")
        rig.awaitIdle(session.id)
        val claude = rig.launcher.processes.single()
        rig.manager.kill(session.id)
        rig.await("the process gone") { !claude.isAlive }
        assertEquals(1, rig.spawns)
    }

    @Test
    fun keepAliveZero_runsClaudeOneShot() {
        val rig = rig(keepAliveMinutes = 0)
        val session = rig.manager.startNew("first", model = "m")
        rig.awaitIdle(session.id)
        rig.manager.resume(session.id, "second")
        rig.awaitIdle(session.id)
        assertEquals(0, rig.launcher.processes.size, "keep-alive off: no persistent claude")
        assertEquals(2, rig.oneShot.spawns.get(), "one `claude -p` per reply")
    }

    @Test
    fun fingerprint_modelChangeRestarts_resumeDoesNot() {
        val adapter = ClaudeSessionAdapter(executable = { error("never resolved for a fingerprint") })
        val base = RunRequest("p", File("w"), model = "m")
        assertEquals(adapter.fingerprint(base, emptyMap()), adapter.fingerprint(base.copy(prompt = "q", resumeSessionId = "s"), emptyMap()))
        assertNotEquals(adapter.fingerprint(base, emptyMap()), adapter.fingerprint(base.copy(model = "other"), emptyMap()))
        assertNotEquals(adapter.fingerprint(base, emptyMap()), adapter.fingerprint(base.copy(effort = "high"), emptyMap()))
        assertNotEquals(adapter.fingerprint(base, emptyMap()), adapter.fingerprint(base, mapOf("K" to "V")))
        assertNotEquals(adapter.fingerprint(base, emptyMap()), adapter.fingerprint(base.copy(workingDir = File("x")), emptyMap()))
    }

    @Test
    fun stdinLines_areTheProbedShapes_andARepeatedInitIsNoEvent() {
        assertEquals(
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"say \"hi\"\nnow"}]}}""",
            ClaudeSessionAdapter.userLine("say \"hi\"\nnow"),
        )
        assertEquals(
            """{"type":"control_request","request_id":"r1","request":{"subtype":"interrupt"}}""",
            ClaudeSessionAdapter.interruptLine("r1"),
        )
        // system/init arrives on every turn of a kept process; the daemon's normaliser must not announce anything for it.
        assertEquals(emptyList(), EventNormalizer.normalise("""{"type":"system","subtype":"init","session_id":"s"}"""))
    }

    /** Both entry points to claude return the persistent adapter, so behaviour never depends on which one resolved the id. */
    @Test
    fun bothClaudeEntryPoints_offerThePersistentAdapter() {
        assertTrue(ProviderFactory.fromFlag("claude").sessionAdapter() is ClaudeSessionAdapter)
        val registry = HarnessRegistry().also { it.register(ClaudeHarnessPlugin()) }
        val harness = registry.resolve("claude") as CliHarness
        assertNotNull(harness.sessionAdapter() as? ClaudeSessionAdapter)
    }
}
