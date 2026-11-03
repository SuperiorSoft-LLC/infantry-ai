package com.superiorsoftllc.infantry.cli.mcp

import com.superiorsoftllc.infantry.cli.Cli
import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runner.ScriptedRunner
import com.superiorsoftllc.infantry.runner.StubScript
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
 * P11-D, the Phase 11 capstone: an MCP client orchestrates agents end to end through `infantry mcp serve`.
 *
 * Everything is real except the model: a real in-process daemon ([DaemonFixture]) on a scripted provider, and the
 * REAL [Cli.run] serving MCP over its stdin/stdout seam (newline-delimited JSON-RPC 2.0, exactly what a child
 * process speaks), started in a project directory whose `.infantry/mcp.json` supplies the default project and the
 * guardrails. Nested agents are simulated the way the daemon makes them: a second `mcp serve` running with
 * `INFANTRY_SESSION_ID` set to the spawned session's id, so its depth comes from the daemon's record of who spawned it
 * (RUNTIME-LOAD-7: the host spawn service) -- not from a flag or a file.
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

    /** Starts `infantry mcp serve` in [cwd] as the MCP server of [sessionId] (null = the top-level orchestrator). */
    private fun serve(fixture: DaemonFixture, cwd: File, sessionId: String? = null): Served {
        val toServer = PipedWriter()
        val serverIn = PipedReader(toServer, 1 shl 16)
        val fromServer = PipedReader(1 shl 16)
        val serverOut = PrintWriter(PipedWriter(fromServer))
        val stderr = StringBuffer()
        val served = Served(PrintWriter(toServer), BufferedReader(fromServer), stderr)
        val env = mapOf("INFANTRY_SESSION_ID" to sessionId)
        val args = arrayOf("mcp", "serve", "--port", fixture.port.toString())
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
        // RUNTIME-LOAD-5: the session and board reads are Infantry_Agents' / Infantry_Board's daemon tools.
        val fixture = DaemonFixture.launch(
            script, ScriptedRunner(script, RUN_PACE_MS), tempDir("infantry-mcp-e2e-daemon"),
            pluginsRoot = com.superiorsoftllc.infantry.daemon.TestDaemonPlugins.boardRoot("official/Infantry_Agents", "official/Infantry_Chat", "official/Infantry_Git"),
            podSpawnDepth = 2,
        )
            .also { fixtures += it; it.client.await(Envelope.READY) }
        val manager = fixture.daemon.manager

        // The project the orchestrator runs in: .infantry/mcp.json is the server's defaults (P11-C).
        val project = tempDir("infantry-mcp-e2e-project")
        McpServeConfig.fileIn(project).apply { parentFile.mkdirs() }
            .writeText("""{"projectId":"$PROJECT","maxDepth":2,"maxSpawns":2}""")
        val orchestrator = serve(fixture, project)

        // 1) initialize, then the 7 tools.
        val init = initialize(orchestrator)
        assertEquals("infantry", init["serverInfo"]!!.jsonObject.str("name"), "$init")
        assertTrue(
            orchestrator.stderr.contains("depth 0/2, max 2 open spawns, project $PROJECT"),
            "the server took its defaults from .infantry/mcp.json: ${orchestrator.stderr}",
        )
        val tools = orchestrator.request("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject.str("name") }.toSet()
        assertTrue(tools.containsAll(ALL_TOOLS), "every tool the orchestration uses is listed (core and plugin halves): $tools")

        // 2) a role-first spawn carrying a task; no projectId on the call, so it lands in mcp.json's project.
        val spawned = payload(orchestrator.callTool("infantry_spawn", """{"role":"qa","task":"$TASK"}"""))
        val lead = spawned.str("sessionId")
        assertEquals("qa", spawned.str("role"))
        assertEquals(1, spawned["depth"]!!.jsonPrimitive.int)
        assertEquals("true", spawned.str("taskSent"))
        assertEquals(PROJECT, manager.get(lead)!!.projectId, "the spawn used the project from .infantry/mcp.json")
        fixture.await(what = "the task's turn to finish") { manager.get(lead)!!.runCount >= 1 && !manager.get(lead)!!.live }

        // 3) the orchestrator sees it in the session list and in the pod state.
        val listed = payload(orchestrator.callTool("infantry_list_sessions"))["sessions"]!!.jsonArray.map { it.jsonObject }
        assertEquals("qa", listed.single { it.str("id") == lead }.str("role"), "$listed")
        val pod = payload(orchestrator.callTool("infantry_pod_state"))
        val podSessions = pod["sessions"]!!.jsonObject
        assertEquals(1, podSessions["total"]!!.jsonPrimitive.int, "$pod")
        val leadStatus = manager.get(lead)!!.state.name
        assertEquals(1, podSessions["byStatus"]!!.jsonObject[leadStatus]?.jsonPrimitive?.int, "the lead counts as $leadStatus: $pod")
        // The roster section is the configured project's (rows are agent PROFILES bound to it; a role-first spawn
        // binds none, so the project's roster is reported but carries no profile row for it).
        assertEquals(PROJECT, pod["roster"]!!.jsonObject.str("projectId"), "pod_state defaults to the configured project: $pod")

        // 4) mail it; the mailbox wakes the session with the message as its next turn, and the transcript shows it.
        val mail = payload(orchestrator.callTool("infantry_message", """{"to":"$lead","body":"$MAIL_BODY"}"""))
        assertEquals(lead, mail.str("to"))
        assertEquals("user", mail.str("from"), "the top-level orchestrator mails as the human handle")
        fixture.await(what = "the mail's turn to finish") { manager.get(lead)!!.runCount >= 2 && !manager.get(lead)!!.live }
        val session = payload(orchestrator.callTool("infantry_get_session", """{"sessionId":"$lead","lines":200}"""))
        val transcript = session["transcript"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(2, session["runCount"]!!.jsonPrimitive.int, "$session")
        assertTrue(transcript.any { it.contains(TASK) }, "the task reached the agent: $transcript")
        val mailAt = transcript.indexOfFirst { it.contains(MAIL_BODY) }
        assertTrue(mailAt >= 0, "the mailed body reached the agent: $transcript")
        assertTrue(
            transcript.drop(mailAt + 1).any { it.contains(REPLY) },
            "the scripted reply follows the mail in the transcript: $transcript",
        )

        // 6a) the depth guardrail, end to end through the daemon's own record of who spawned whom: the lead was spawned by the
        // orchestrator (an owner connection, no session), so it is a root; its worker is at depth 1, the worker's own helper at depth 2
        // = maxDepth -- and that helper may not spawn.
        val leadServer = serve(fixture, project, sessionId = lead)
        initialize(leadServer)
        val worker = payload(leadServer.callTool("infantry_spawn", """{"role":"qa"}"""))
        assertEquals(1, worker["depth"]!!.jsonPrimitive.int)
        val workerId = worker.str("sessionId")
        assertEquals(lead, manager.get(workerId)!!.spawnerSessionId, "the daemon recorded the lead as the worker's spawner")
        val workerServer = serve(fixture, project, sessionId = workerId)
        initialize(workerServer)
        val helper = payload(workerServer.callTool("infantry_spawn", """{"role":"qa"}"""))
        assertEquals(2, helper["depth"]!!.jsonPrimitive.int)
        val helperServer = serve(fixture, project, sessionId = helper.str("sessionId"))
        initialize(helperServer)
        val before = manager.list().size
        assertRefused(helperServer.callTool("infantry_spawn", """{"role":"qa"}"""), "depth limit")
        assertEquals(before, manager.list().size, "the refused spawn created nothing")

        // 6b) the concurrency guardrail: the orchestrator holds the lead, one more fills maxSpawns=2, the next is refused.
        // CONTEXT-LIMIT-1: a spawn carries a context limit (recorded on the session); a typo is refused with nothing created.
        assertRefused(orchestrator.callTool("infantry_spawn", """{"role":"qa","contextLimit":"200"}"""), "out of range")
        assertEquals(before, manager.list().size, "the refused spawn created nothing")
        val spawnedWithLimit = payload(orchestrator.callTool("infantry_spawn", """{"role":"qa","contextLimit":"300k"}"""))
        assertEquals(300_000, spawnedWithLimit["contextLimit"]!!.jsonPrimitive.int)
        val second = spawnedWithLimit.str("sessionId")
        assertEquals(300_000, manager.get(second)!!.contextLimit)
        assertEquals(before + 1, manager.list().size)
        assertRefused(orchestrator.callTool("infantry_spawn", """{"role":"qa"}"""), "concurrency limit")
        assertEquals(before + 1, manager.list().size, "the refused spawn created nothing")

        // 5) put the lead back to work, kill it mid-turn, and see it exit: the run is stopped, the session is no
        // longer live, and the slot it held is free again.
        val working = payload(orchestrator.callTool("infantry_prompt", """{"sessionId":"$lead","prompt":"$LONG_TASK"}"""))
        assertEquals(lead, working.str("sessionId"))
        assertTrue(manager.get(lead)!!.live, "the lead is mid-turn when the kill arrives: ${manager.get(lead)}")
        val killed = payload(orchestrator.callTool("infantry_kill", """{"sessionId":"$lead","force":true}"""))
        assertEquals(lead, killed.str("sessionId"))
        fixture.await(what = "the killed lead to exit") { !manager.get(lead)!!.live }
        val record = manager.get(lead)!!
        assertTrue(record.killOutcome != null, "the daemon recorded the kill: $record")
        val after = payload(orchestrator.callTool("infantry_get_session", """{"sessionId":"$lead","lines":200}"""))
        assertEquals(record.state.name, after.str("status"), "get_session reports the exited state: $after")
        assertFalse(after.str("status") in setOf("STARTING", "BUSY", "WAITING_INPUT"), "the lead is not running: $after")
        val lines = after["transcript"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(3, after["runCount"]!!.jsonPrimitive.int, "task, mail, then the killed turn: $after")
        assertEquals(2, lines.count { it.contains(REPLY) }, "the killed turn never got to its scripted reply: $lines")
        val lastFinish = lines.last { it.startsWith("[finished]") }
        assertFalse(lastFinish.contains("SUCCEEDED"), "the killed turn did not finish normally: $lastFinish")
        payload(orchestrator.callTool("infantry_spawn", """{"role":"qa"}"""))

        // Kill scope (1.114.0) is by role and project, not by MCP process: the orchestrator is the owner path (no
        // session id), which may kill any session; the scope refusals are covered by SessionKillScopeTest.
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
            "infantry_list_sessions", "infantry_get_session", "infantry_pod_state", "infantry_board", // RUNTIME-LOAD-5: plugin tools
            "infantry_spawn", "infantry_prompt", "infantry_message", "infantry_kill", "infantry_session_remove", "infantry_session_restart",
            "infantry_ticket_start", // BUG-ASSIGN-1 (infantry_list_harnesses: Infantry_Agents, RUNTIME-LOAD-2; roles, ask, project config: Infantry_Roles / _Attention / _Settings, RUNTIME-LOAD-3)
            "infantry_inbox", "infantry_read", // MAIL-MCP-1
            "infantry_watch_file", // STALL-4
            "infantry_pipeline", "infantry_pipeline_record", // PIPE-1
        )
    }
}
