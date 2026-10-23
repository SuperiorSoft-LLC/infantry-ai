package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.Cli
import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.PipedReader
import java.io.PipedWriter
import java.io.PrintWriter
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P11-D, the Phase 11 capstone: an MCP client orchestrates agents end to end through `agenthq mcp serve`.
 *
 * Everything is real except the model: a real in-process daemon ([DaemonFixture]) on a scripted provider, and the
 * REAL [Cli.run] serving MCP over its stdin/stdout seam (newline-delimited JSON-RPC 2.0, exactly what a child
 * process speaks), started in a project directory whose `.agenthq/mcp.json` supplies the default project and the
 * guardrails. Nested agents are simulated the way the daemon makes them: a second `mcp serve` running with
 * `AGENTHQ_SESSION_ID` set to the spawned session's id, sharing the lineage directory, so its depth comes from what
 * the parent server recorded -- not from a flag.
 *
 * docs/MCP.md "Orchestrating agents" walks through the same steps.
 */
class McpOrchestrationE2eTest {

    private val dirs = mutableListOf<File>()
    private val fixtures = mutableListOf<DaemonFixture>()
    private val servers = mutableListOf<Served>()

    @AfterTest
    fun cleanUp() {
        servers.forEach { it.close() }
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix).toFile().also { dirs += it }

    /** One MCP client session over a running `mcp serve`: writes requests to its stdin, reads answers from its stdout. */
    private class Served(private val stdin: PrintWriter, private val stdout: BufferedReader, val stderr: StringBuffer) {
        lateinit var worker: Thread
        private var nextId = 1

        fun request(method: String, params: String = "{}"): JsonObject {
            val id = nextId++
            stdin.print("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""" + "\n")
            stdin.flush()
            val line = stdout.readLine() ?: error("stdout closed; stderr=$stderr")
            val answer = Json.parseToJsonElement(line).jsonObject
            assertEquals("2.0", answer["jsonrpc"]!!.jsonPrimitive.content, "a JSON-RPC 2.0 answer: $answer")
            assertEquals(id, answer["id"]!!.jsonPrimitive.int, "answer must carry the request id: $answer")
            return answer
        }

        fun callTool(name: String, arguments: String = "{}"): JsonObject =
            request("tools/call", """{"name":"$name","arguments":$arguments}""")["result"]!!.jsonObject

        fun close() {
            runCatching { stdin.close() }
            worker.join(5_000)
        }
    }

    /** Starts `agenthq mcp serve` in [cwd] as the MCP server of [sessionId] (null = the top-level orchestrator). */
    private fun serve(fixture: DaemonFixture, cwd: File, lineage: File, sessionId: String? = null): Served {
        val toServer = PipedWriter()
        val serverIn = PipedReader(toServer, 1 shl 16)
        val fromServer = PipedReader(1 shl 16)
        val serverOut = PrintWriter(PipedWriter(fromServer))
        val stderr = StringBuffer()
        val served = Served(PrintWriter(toServer), BufferedReader(fromServer), stderr)
        val env = mapOf("AGENTHQ_SESSION_ID" to sessionId)
        val args = arrayOf("mcp", "serve", "--port", fixture.port.toString(), "--lineage-dir", lineage.path)
        served.worker = thread(name = "mcp-serve-e2e", isDaemon = true) {
            Cli.run(args, serverOut, stderr, { env[it] }, { serverIn }, cwd = cwd)
            serverOut.close()
        }
        servers += served
        return served
    }

    private fun initialize(served: Served): JsonObject =
        served.request(
            "initialize",
            """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"orchestrator","version":"0"}}""",
        )["result"]!!.jsonObject

    private fun text(result: JsonObject): String =
        result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

    private fun payload(result: JsonObject): JsonObject {
        assertEquals("false", result["isError"]!!.jsonPrimitive.content, "tool reported an error: ${text(result)}")
        return Json.parseToJsonElement(text(result)).jsonObject
    }

    private fun assertRefused(result: JsonObject, contains: String) {
        assertEquals("true", result["isError"]!!.jsonPrimitive.content, "expected a refusal: $result")
        assertTrue(text(result).contains(contains), "refusal must say '$contains': ${text(result)}")
    }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    @Test
    fun anAgentOrchestratesAnotherThroughMcpServe() {
        // The scripted provider: a resumable provider session, the prompt it received echoed back (so the transcript
        // proves what reached the agent), then a fixed reply.
        val script = StubScript(
            stdoutLines = listOf(
                """{"type":"step_start","sessionID":"scripted-provider-session"}""",
                """{"type":"text","text":"$REPLY"}""",
                """{"type":"step_finish"}""",
            ),
            echoPrompt = true,
        )
        // A paced runner (each scripted line ~RUN_PACE_MS apart) so a turn is still running when step 5 kills it.
        val fixture = DaemonFixture.launch(script, ScriptedRunner(script, RUN_PACE_MS), tempDir("agenthq-mcp-e2e-daemon"))
            .also { fixtures += it; it.client.await(Envelope.READY) }
        val manager = fixture.daemon.manager

        // The project the orchestrator runs in: .agenthq/mcp.json is the server's defaults (P11-C).
        val project = tempDir("agenthq-mcp-e2e-project")
        McpServeConfig.fileIn(project).apply { parentFile.mkdirs() }
            .writeText("""{"projectId":"$PROJECT","maxDepth":2,"maxSpawns":2}""")
        val lineage = tempDir("agenthq-mcp-e2e-lineage")
        val orchestrator = serve(fixture, project, lineage)

        // 1) initialize, then the 7 tools.
        val init = initialize(orchestrator)
        assertEquals("agenthq", init["serverInfo"]!!.jsonObject.str("name"), "$init")
        assertTrue(
            orchestrator.stderr.contains("depth 0/2, max 2 open spawns, kill own spawns only, project $PROJECT"),
            "the server took its defaults from .agenthq/mcp.json: ${orchestrator.stderr}",
        )
        val tools = orchestrator.request("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject.str("name") }.toSet()
        assertEquals(ALL_TOOLS, tools)

        // 2) a role-first spawn carrying a task; no projectId on the call, so it lands in mcp.json's project.
        val spawned = payload(orchestrator.callTool("agenthq_spawn", """{"role":"qa","task":"$TASK"}"""))
        val lead = spawned.str("sessionId")
        assertEquals("qa", spawned.str("role"))
        assertEquals(1, spawned["depth"]!!.jsonPrimitive.int)
        assertEquals("true", spawned.str("taskSent"))
        assertEquals(PROJECT, manager.get(lead)!!.projectId, "the spawn used the project from .agenthq/mcp.json")
        fixture.await(what = "the task's turn to finish") { manager.get(lead)!!.runCount >= 1 && !manager.get(lead)!!.live }

        // 3) the orchestrator sees it in the session list and in the pod state.
        val listed = payload(orchestrator.callTool("agenthq_list_sessions"))["sessions"]!!.jsonArray.map { it.jsonObject }
        assertEquals("qa", listed.single { it.str("id") == lead }.str("role"), "$listed")
        val pod = payload(orchestrator.callTool("agenthq_pod_state"))
        val podSessions = pod["sessions"]!!.jsonObject
        assertEquals(1, podSessions["total"]!!.jsonPrimitive.int, "$pod")
        val leadStatus = manager.get(lead)!!.state.name
        assertEquals(1, podSessions["byStatus"]!!.jsonObject[leadStatus]?.jsonPrimitive?.int, "the lead counts as $leadStatus: $pod")
        // The roster section is the configured project's (rows are agent PROFILES bound to it; a role-first spawn
        // binds none, so the project's roster is reported but carries no profile row for it).
        assertEquals(PROJECT, pod["roster"]!!.jsonObject.str("projectId"), "pod_state defaults to the configured project: $pod")

        // 4) mail it; the mailbox wakes the session with the message as its next turn, and the transcript shows it.
        val mail = payload(orchestrator.callTool("agenthq_message", """{"to":"$lead","body":"$MAIL_BODY"}"""))
        assertEquals(lead, mail.str("to"))
        assertEquals("user", mail.str("from"), "the top-level orchestrator mails as the human handle")
        fixture.await(what = "the mail's turn to finish") { manager.get(lead)!!.runCount >= 2 && !manager.get(lead)!!.live }
        val session = payload(orchestrator.callTool("agenthq_get_session", """{"sessionId":"$lead","lines":200}"""))
        val transcript = session["transcript"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(2, session["runCount"]!!.jsonPrimitive.int, "$session")
        assertTrue(transcript.any { it.contains(TASK) }, "the task reached the agent: $transcript")
        val mailAt = transcript.indexOfFirst { it.contains(MAIL_BODY) }
        assertTrue(mailAt >= 0, "the mailed body reached the agent: $transcript")
        assertTrue(
            transcript.drop(mailAt + 1).any { it.contains(REPLY) },
            "the scripted reply follows the mail in the transcript: $transcript",
        )

        // 6a) the depth guardrail, end to end through the lineage: the lead's own MCP server is at depth 1 (what the
        // orchestrator recorded), so it may spawn a worker at depth 2 = maxDepth -- and that worker may not spawn.
        val leadServer = serve(fixture, project, lineage, sessionId = lead)
        initialize(leadServer)
        assertTrue(leadServer.stderr.contains("depth 1/2"), "the lead's server read its depth from the lineage: ${leadServer.stderr}")
        val worker = payload(leadServer.callTool("agenthq_spawn", """{"role":"qa"}"""))
        assertEquals(2, worker["depth"]!!.jsonPrimitive.int)
        val workerId = worker.str("sessionId")
        val workerServer = serve(fixture, project, lineage, sessionId = workerId)
        initialize(workerServer)
        val before = manager.list().size
        assertRefused(workerServer.callTool("agenthq_spawn", """{"role":"qa"}"""), "depth limit")
        assertEquals(before, manager.list().size, "the refused spawn created nothing")

        // 6b) the concurrency guardrail: the orchestrator holds the lead, one more fills maxSpawns=2, the next is refused.
        val second = payload(orchestrator.callTool("agenthq_spawn", """{"role":"qa"}""")).str("sessionId")
        assertEquals(before + 1, manager.list().size)
        assertRefused(orchestrator.callTool("agenthq_spawn", """{"role":"qa"}"""), "concurrency limit")
        assertEquals(before + 1, manager.list().size, "the refused spawn created nothing")

        // 5) put the lead back to work, kill it mid-turn, and see it exit: the run is stopped, the session is no
        // longer live, and the slot it held is free again.
        val working = payload(orchestrator.callTool("agenthq_prompt", """{"sessionId":"$lead","prompt":"$LONG_TASK"}"""))
        assertEquals(lead, working.str("sessionId"))
        assertTrue(manager.get(lead)!!.live, "the lead is mid-turn when the kill arrives: ${manager.get(lead)}")
        val killed = payload(orchestrator.callTool("agenthq_kill", """{"sessionId":"$lead","force":true}"""))
        assertEquals(lead, killed.str("sessionId"))
        fixture.await(what = "the killed lead to exit") { !manager.get(lead)!!.live }
        val record = manager.get(lead)!!
        assertTrue(record.killOutcome != null, "the daemon recorded the kill: $record")
        val after = payload(orchestrator.callTool("agenthq_get_session", """{"sessionId":"$lead","lines":200}"""))
        assertEquals(record.state.name, after.str("status"), "get_session reports the exited state: $after")
        assertFalse(after.str("status") in setOf("STARTING", "BUSY", "WAITING_INPUT"), "the lead is not running: $after")
        val lines = after["transcript"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(3, after["runCount"]!!.jsonPrimitive.int, "task, mail, then the killed turn: $after")
        assertEquals(2, lines.count { it.contains(REPLY) }, "the killed turn never got to its scripted reply: $lines")
        val lastFinish = lines.last { it.startsWith("[finished]") }
        assertFalse(lastFinish.contains("SUCCEEDED"), "the killed turn did not finish normally: $lastFinish")
        payload(orchestrator.callTool("agenthq_spawn", """{"role":"qa"}"""))

        // Kill scope holds across servers: the orchestrator did not spawn the worker (the lead did), so it may not kill it.
        assertRefused(orchestrator.callTool("agenthq_kill", """{"sessionId":"$workerId"}"""), "not spawned by this MCP server")
        assertTrue(second.isNotBlank())
    }

    private companion object {
        const val PROJECT = "e2e-project"
        const val TASK = "triage the login bug"
        const val MAIL_BODY = "status report please"
        const val REPLY = "scripted reply: on it"
        const val LONG_TASK = "now run the full regression suite"
        const val RUN_PACE_MS = 400L
        val ALL_TOOLS = setOf(
            "agenthq_list_sessions", "agenthq_get_session", "agenthq_pod_state", "agenthq_board",
            "agenthq_spawn", "agenthq_prompt", "agenthq_message", "agenthq_kill", "agenthq_session_remove", "agenthq_session_restart",
            "agenthq_ticket_create", "agenthq_ticket_update", "agenthq_ticket_get",
            "agenthq_list_harnesses", "agenthq_ticket_start", // BUG-ASSIGN-1
            "agenthq_harness_status", "agenthq_harness_install", // HARNESS-INSTALL-1
            "agenthq_epic_base", // BASE-1c
            "agenthq_ticket_delete", // MCP-DEL-1
            "agenthq_ticket_move", // MCP-MOVE-1
            "agenthq_inbox", "agenthq_read", // MAIL-MCP-1
            "agenthq_ask", // ASK-1
            "agenthq_pipeline", "agenthq_pipeline_record", // PIPE-1
        )
    }
}
