package com.superiorsoftllc.agenthq.opencode

import com.superiorsoftllc.agenthq.provider.OpenCodeProvider
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.session.host.AdapterSink
import com.superiorsoftllc.agenthq.session.host.OneShotAdapter
import com.superiorsoftllc.agenthq.session.host.TurnEnd
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An opencode turn that ends with no assistant output is a visible "no reply from <model>", never a plain success. */
class OpenCodeNoReplyTest {
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

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    private fun feed(turn: OpenCodeServeAdapter.Turn, vararg events: String) {
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
        val turn = OpenCodeServeAdapter.Turn(sink, "opencode-go/beam-free") { listOf("boom on stderr") }
        feed(turn, busy, idle)
        assertEquals(TurnEnd.FAILED, sink.end)
        assertNull(sink.exit)
        assertTrue(sink.reason!!.startsWith("no reply from opencode-go/beam-free"), sink.reason)
        assertTrue("boom on stderr" in sink.reason!!)
    }

    @Test
    fun `a stale idle after the prompt echo is not taken for an answer (regression for the empty turn)`() {
        val sink = Sink()
        val turn = OpenCodeServeAdapter.Turn(sink, "m/x")
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
        val turn = OpenCodeServeAdapter.Turn(sink, "m/x")
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
        val adapter = OneShotAdapter(OpenCodeProvider { listOf("opencode-go/beam-free") })
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
