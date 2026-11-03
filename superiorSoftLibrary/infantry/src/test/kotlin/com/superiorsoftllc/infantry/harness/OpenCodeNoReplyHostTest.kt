package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.harness.api.SessionOutput
import com.superiorsoftllc.infantry.harness.api.SessionTurnEnd
import com.superiorsoftllc.infantry.plugins.official.opencode.harness.OpenCodeServeSession
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.session.host.AdapterSink
import com.superiorsoftllc.infantry.session.host.OneShotAdapter
import com.superiorsoftllc.infantry.session.host.TurnEnd
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An opencode turn that ends with no assistant output is a visible "no reply from <model>", never a plain success. */
class OpenCodeNoReplyHostTest {
    /** The plugin session's output, recorded into a [Sink]. */
    private class TurnOut(private val sink: Sink) : SessionOutput {
        override fun write(text: String) = true
        override fun closeInput() {}
        override fun detail(text: String) {}
        override fun providerSession(id: String) {}
        override fun output(line: String) { sink.output(line) }
        override fun turnEnded(end: SessionTurnEnd, exitCode: Int?, reason: String?) = sink.turnEnded(TurnEnd.valueOf(end.name), exitCode, reason)
    }

    private class Sink : AdapterSink {
        val lines = mutableListOf<String>()
        var end: TurnEnd? = null
        var exit: Int? = -99
        var reason: String? = null
        override fun output(line: String) { lines += line }
        override fun diagnostic(line: String) {}
        override fun detail(text: String) {}
        override fun providerSession(id: String) {}
        override fun turnEnded(end: TurnEnd, exitCode: Int?, reason: String?) { this.end = end; exit = exitCode; this.reason = reason }
    }

    /** H8b-3: the one-shot path serves the opencode id too (a session with keepAlive 0); a bare provider with that id is what the adapter needs. */
    private object OpenCodeProviderStub : com.superiorsoftllc.infantry.harness.AgentProvider {
        override val id = "opencode"
        override fun spec(request: RunRequest) = ProcessSpec(listOf("opencode", "run"), request.workingDir)
        override fun defaultModel() = "opencode-go/beam-free"
    }

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    private fun feed(turn: OpenCodeServeSession.Turn, vararg events: String) {
        for (e in events) {
            val o = obj(e)
            val props = o["properties"] as JsonObject
            val part = props["part"] as? JsonObject
            turn.onEvent((o["type"] as kotlinx.serialization.json.JsonPrimitive).content, props, part, "ses_1")
        }
    }

    private val userMsg = """{"type":"message.updated","properties":{"sessionID":"ses_1","info":{"id":"msg_u","role":"user"}}}"""
    private val userPart = """{"type":"message.part.updated","properties":{"sessionID":"ses_1","part":{"id":"p0","messageID":"msg_u","sessionID":"ses_1","type":"text","time":{"start":1,"end":2}}}}"""
    private val idle = """{"type":"session.idle","properties":{"sessionID":"ses_1"}}"""
    private val answer = """{"type":"message.part.updated","properties":{"sessionID":"ses_1","part":{"id":"p1","messageID":"msg_a","sessionID":"ses_1","type":"text","text":"hi","time":{"start":1,"end":2}}}}"""
    private val busy = """{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"busy"}}}"""

    @Test
    fun `serve turn that goes idle with no text is a no-reply failure naming the model`() {
        val sink = Sink()
        val turn = OpenCodeServeSession.Turn(TurnOut(sink), "opencode-go/beam-free") { listOf("boom on stderr") }
        feed(turn, busy, idle)
        assertEquals(TurnEnd.FAILED, sink.end)
        assertNull(sink.exit)
        assertTrue(sink.reason!!.startsWith("no reply from opencode-go/beam-free"), sink.reason)
        assertTrue("boom on stderr" in sink.reason!!)
    }

    @Test
    fun `a stale idle after the prompt echo is not taken for an answer (regression for the empty turn)`() {
        val sink = Sink()
        val turn = OpenCodeServeSession.Turn(TurnOut(sink), "m/x")
        // The user's own part arrives, then a late idle from an aborted turn: no busy was seen, so the turn stays open.
        feed(turn, userMsg, userPart, idle)
        assertNull(sink.end)
        feed(turn, busy, answer, idle)
        assertEquals(TurnEnd.COMPLETED, sink.end)
        assertEquals(0, sink.exit)
    }

    @Test
    fun `serve turn with text completes`() {
        val sink = Sink()
        val turn = OpenCodeServeSession.Turn(TurnOut(sink), "m/x")
        feed(turn, busy, answer, idle)
        assertEquals(TurnEnd.COMPLETED, sink.end)
    }

    private class ScriptedRunner(private val stdout: List<String>, private val stderr: List<String> = emptyList()) : ProcessRunner {
        override val id = "scripted"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess = object : RunningProcess {
            override val isAlive = false
            override fun writeInput(text: String) {}
            override fun closeInput() {
                stderr.forEach(listener::onStderr)
                stdout.forEach(listener::onStdout)
                listener.onExit(0)
            }
            override fun killTree() {}
            override fun awaitExit() = 0
        }
    }

    private fun oneShot(runner: ScriptedRunner, model: String? = "opencode-go/beam-free"): Sink {
        val adapter = OneShotAdapter(OpenCodeProviderStub)
        val sink = Sink()
        val handle = adapter.launch(RunRequest(prompt = "hello", model = model), emptyMap(), runner, sink)
        adapter.sendTurn(handle, RunRequest(prompt = "hello", model = model))
        return sink
    }

    @Test
    fun `opencode run that exits 0 with no text is a no-reply state`() {
        val sink = oneShot(ScriptedRunner(listOf("""{"type":"step_start","part":{}}""", """{"type":"step_finish","part":{}}"""), listOf("warn: x")))
        assertEquals(TurnEnd.FAILED, sink.end)
        assertTrue(sink.reason!!.startsWith("no reply from opencode-go/beam-free"), sink.reason)
        assertTrue("warn: x" in sink.reason!!)
    }

    @Test
    fun `opencode run that exits 0 with text still completes`() {
        val sink = oneShot(ScriptedRunner(listOf("""{"type":"text","part":{"type":"text","text":"hi"}}""")))
        assertEquals(TurnEnd.COMPLETED, sink.end)
        assertEquals(0, sink.exit)
    }
}
