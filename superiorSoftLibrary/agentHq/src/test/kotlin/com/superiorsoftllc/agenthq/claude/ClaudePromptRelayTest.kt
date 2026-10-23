package com.superiorsoftllc.agenthq.claude

import com.superiorsoftllc.agenthq.harness.ToolApproval
import com.superiorsoftllc.agenthq.prompts.PendingPrompts
import com.superiorsoftllc.agenthq.prompts.PromptAsker
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.PromptRequest
import com.superiorsoftllc.agenthq.protocol.PromptResponse
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.session.host.AdapterSink
import com.superiorsoftllc.agenthq.session.host.TurnEnd
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PROMPT-3: Claude's `can_use_tool` control requests (Tool approval `ask`) relayed through the PROMPT-1 [PendingPrompts].
 * The fake claude speaks the protocol probed live against claude 2.1.304: a `user` line makes it ask for each scripted
 * tool call in turn (`control_request` / `can_use_tool`), every `control_response` on stdin is recorded, and the turn's
 * `result` is printed once the last call is answered.
 */
class ClaudePromptRelayTest {

    private class Call(val tool: String, val input: String)

    private class FakeClaude(val command: List<String>, private val listener: ProcessListener, private val calls: List<Call>) : RunningProcess {
        val responses = LinkedBlockingQueue<JsonObject>()
        @Volatile private var alive = true
        private var next = 0
        override val isAlive get() = alive

        override fun writeInput(text: String) {
            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val record = Json.parseToJsonElement(line).jsonObject
                when (record["type"]!!.jsonPrimitive.content) {
                    "user" -> Thread { askNextOrFinish() }.start()
                    "control_response" -> {
                        responses += record
                        Thread { askNextOrFinish() }.start()
                    }
                }
            }
        }

        @Synchronized private fun askNextOrFinish() {
            if (next < calls.size) {
                val call = calls[next++]
                emit("""{"type":"control_request","request_id":"req-$next","request":{"subtype":"can_use_tool","tool_name":"${call.tool}","input":${call.input},"tool_use_id":"tu-$next"}}""")
            } else {
                emit("""{"type":"result","subtype":"success","is_error":false,"result":"done","session_id":"c1"}""")
            }
        }

        private fun emit(line: String) { if (alive) listener.onStdout(line) }
        override fun closeInput() { alive = false; listener.onExit(0) }
        override fun killTree() { alive = false; listener.onExit(137) }
        override fun awaitExit() = 0
    }

    private class Runner(private val calls: List<Call>) : ProcessRunner {
        override val id = "fake-claude"
        val processes = CopyOnWriteArrayList<FakeClaude>()
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess =
            FakeClaude(spec.command, listener, calls).also { processes += it }
    }

    private class Sink(override val prompts: PromptAsker) : AdapterSink {
        override val sessionId = "ses-1"
        val ends = LinkedBlockingQueue<TurnEnd>()
        val output = CopyOnWriteArrayList<String>()
        override fun output(line: String) { output += line }
        override fun diagnostic(line: String) = Unit
        override fun detail(text: String) = Unit
        override fun providerSession(id: String) = Unit
        override fun turnEnded(end: TurnEnd, exitCode: Int?, reason: String?) { ends += end }
    }

    /** A real PendingPrompts whose `prompt_request` frames land in [requests], answered by the test as a client would. */
    private class Prompts(timeoutMs: Long = 10_000) {
        val requests = LinkedBlockingQueue<PromptRequest>()
        val pending = PendingPrompts(timeoutMillis = { timeoutMs }).also { p ->
            p.sink = { env ->
                if (env.type == Envelope.PROMPT_REQUEST) requests += Json.decodeFromString(PromptRequest.serializer(), env.data)
            }
        }

        fun next(): PromptRequest = assertNotNull(requests.poll(5, TimeUnit.SECONDS), "no prompt_request arrived")

        fun answer(request: PromptRequest, decision: String? = null, answers: List<String> = emptyList(), other: String? = null) {
            pending.respond(PromptResponse(request.promptId, decision, answers, other), "client:1")
        }
    }

    private val write = Call("Write", """{"file_path":"C:\\tmp\\x.txt","content":"hello"}""")
    private val question = Call(
        "AskUserQuestion",
        """{"questions":[{"question":"Which color?","header":"Color","options":[{"label":"Red","description":"warm"},{"label":"Blue","description":"cool"}],"multiSelect":false}]}""",
    )

    private fun run(calls: List<Call>, prompts: Prompts, approval: ToolApproval = ToolApproval.ASK): Triple<FakeClaude, Sink, ClaudeSessionAdapter> {
        val adapter = ClaudeSessionAdapter(executable = { "claude" })
        val runner = Runner(calls)
        val sink = Sink(prompts.pending)
        val process = adapter.launch(RunRequest("go", toolApproval = approval), emptyMap(), runner, sink)
        adapter.sendTurn(process, RunRequest("go", toolApproval = approval))
        return Triple(runner.processes.single(), sink, adapter)
    }

    private fun FakeClaude.response(): JsonObject {
        val r = assertNotNull(responses.poll(5, TimeUnit.SECONDS), "claude got no control_response")
        return r["response"]!!.jsonObject
    }

    private fun JsonObject.behavior() = this["response"]!!.jsonObject["behavior"]!!.jsonPrimitive.content

    @Test
    fun `ask mode adds the stdio prompt tool and keeps the agenthq allow`() {
        val (claude) = run(emptyList(), Prompts())
        val argv = claude.command
        assertEquals("stdio", argv[argv.indexOf("--permission-prompt-tool") + 1])
        assertEquals("Bash(agenthq:*),PowerShell(agenthq:*),mcp__agenthq__*", argv[argv.indexOf("--allowedTools") + 1])
    }

    @Test
    fun `other modes are unchanged - no prompt tool`() {
        for (mode in listOf(ToolApproval.DENY, ToolApproval.ALLOW_EDITS, ToolApproval.ALLOW_ALL)) {
            val (claude) = run(emptyList(), Prompts(), mode)
            assertFalse("--permission-prompt-tool" in claude.command, "$mode")
        }
    }

    @Test
    fun `approval allow round trip returns allow with the input unchanged`() {
        val prompts = Prompts()
        val (claude, sink) = run(listOf(write), prompts)
        val request = prompts.next()
        assertEquals("ses-1", request.sessionId)
        assertEquals("Write", request.tool)
        assertEquals("C:\\tmp\\x.txt", request.input)
        prompts.answer(request, decision = "allow_once")
        val response = claude.response()
        assertEquals("req-1", response["request_id"]!!.jsonPrimitive.content)
        assertEquals("allow", response.behavior())
        val updated = response["response"]!!.jsonObject["updatedInput"]!!.jsonObject
        assertEquals("hello", updated["content"]!!.jsonPrimitive.content)
        assertEquals(TurnEnd.COMPLETED, sink.ends.poll(5, TimeUnit.SECONDS))
        assertTrue(sink.output.none { "can_use_tool" in it }, "the control_request is the adapter's, not a chat line")
    }

    @Test
    fun `approval deny round trip returns deny with a message`() {
        val prompts = Prompts()
        val (claude, sink) = run(listOf(write), prompts)
        prompts.answer(prompts.next(), decision = "deny")
        val response = claude.response()
        assertEquals("deny", response.behavior())
        assertTrue(response["response"]!!.jsonObject["message"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals(TurnEnd.COMPLETED, sink.ends.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `AskUserQuestion becomes a question prompt and the answer reaches claude in updatedInput`() {
        val prompts = Prompts()
        val (claude) = run(listOf(question), prompts)
        val request = prompts.next()
        assertEquals("question", request.kind)
        assertEquals("Which color?", request.question)
        assertEquals(listOf("Red", "Blue"), request.options.map { it.label })
        assertEquals("cool", request.options[1].description)
        assertTrue(request.allowOther)
        prompts.answer(request, answers = listOf("Blue"))
        val response = claude.response()
        assertEquals("allow", response.behavior())
        val updated = response["response"]!!.jsonObject["updatedInput"]!!.jsonObject
        assertEquals("Blue", updated["answers"]!!.jsonObject["Which color?"]!!.jsonPrimitive.content)
        assertTrue("questions" in updated, "the original input is kept beside the answers")
    }

    @Test
    fun `allow_session suppresses the second prompt for the same tool`() {
        val prompts = Prompts()
        val (claude, sink) = run(listOf(write, write), prompts)
        prompts.answer(prompts.next(), decision = "allow_session")
        assertEquals("allow", claude.response().behavior())
        assertEquals("allow", claude.response().behavior())
        assertNull(prompts.requests.poll(300, TimeUnit.MILLISECONDS), "the second Write was prompted again")
        assertEquals(TurnEnd.COMPLETED, sink.ends.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `an unanswered prompt times out and the deny reaches claude`() {
        val prompts = Prompts(timeoutMs = 200)
        val (claude, sink) = run(listOf(write), prompts)
        prompts.next()
        assertEquals("deny", claude.response().behavior())
        assertEquals(TurnEnd.COMPLETED, sink.ends.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `an unanswered question times out as a deny`() {
        val prompts = Prompts(timeoutMs = 200)
        val (claude) = run(listOf(question), prompts)
        prompts.next()
        assertEquals("deny", claude.response().behavior())
    }
}
