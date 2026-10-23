package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.Cli
import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.daemon.saveProfile
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import com.superiorsoftllc.agenthq.runner.StubScript
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.PipedReader
import java.io.PipedWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P11-A: `agenthq mcp serve` driven exactly as an MCP client drives it -- newline-delimited JSON-RPC written
 * to the process's stdin, answers read line by line from its stdout -- through the REAL [Cli.run] against a
 * REAL in-process daemon (stub provider). Only the pipes stand in for the OS process boundary.
 */
class McpServeTest {

    private val fixtures = mutableListOf<DaemonFixture>()
    private val servers = mutableListOf<Served>()

    @AfterTest
    fun stopAll() {
        servers.forEach { it.close() }
        fixtures.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
    }

    /** One running `mcp serve`: its stdin, its stdout, its stderr, and its exit code once stdin closes. */
    private class Served(private val stdin: PrintWriter, private val stdout: BufferedReader, val stderr: StringBuffer) {
        @Volatile var exit: Int? = null
        lateinit var worker: Thread
        private var nextId = 1

        fun sendRaw(line: String) {
            stdin.print(line + "\n")
            stdin.flush()
        }

        fun readMessage(): JsonObject {
            val line = stdout.readLine() ?: error("stdout closed; stderr=$stderr")
            return Json.parseToJsonElement(line).jsonObject
        }

        fun request(method: String, params: String = "{}"): JsonObject {
            val id = nextId++
            sendRaw("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")
            val answer = readMessage()
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

    private fun serve(vararg connection: String): Served {
        val toServer = PipedWriter()
        val serverIn = PipedReader(toServer, 1 shl 16)
        val fromServer = PipedReader(1 shl 16)
        val serverOut = PrintWriter(PipedWriter(fromServer))
        val stderr = StringBuffer()
        val served = Served(PrintWriter(toServer), BufferedReader(fromServer), stderr)
        served.worker = thread(name = "mcp-serve", isDaemon = true) {
            val code = Cli.run(arrayOf("mcp", "serve", *connection), serverOut, stderr, { null }, { serverIn })
            served.exit = code
            serverOut.close()
        }
        servers += served
        return served
    }

    private fun launch(): DaemonFixture =
        DaemonFixture.launch(StubScript.default(), null, Files.createTempDirectory("agenthq-mcp-test").toFile())
            .also { fixtures += it }

    private fun initialize(served: Served): JsonObject = served.request(
        "initialize",
        """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"0"}}""",
    ).also { served.sendRaw("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") }

    private fun payload(result: JsonObject): JsonObject {
        assertEquals(false, result["isError"]!!.jsonPrimitive.content.toBoolean(), "tool reported an error: $result")
        val content = result["content"]!!.jsonArray.single().jsonObject
        assertEquals("text", content["type"]!!.jsonPrimitive.content)
        return Json.parseToJsonElement(content["text"]!!.jsonPrimitive.content).jsonObject
    }

    /** A session spawned from a profile, run to completion on the stub: returns its id. */
    private fun seedSession(fixture: DaemonFixture): String {
        fixture.client.await(Envelope.READY)
        fixture.client.saveProfile("""{"id":"be-1","name":"Backend One","role":"backend"}""")
        fixture.client.saveProfile("""{"id":"qa-1","name":"QA One","role":"qa"}""")
        fixture.send(Envelope(Envelope.NEW, """{"agentId":"be-1"}"""))
        fixture.send(Envelope(Envelope.PROMPT, "say hello"))
        return assertNotNull(fixture.client.await(Envelope.FINISHED).sessionId, "finished frame names its session")
    }

    @Test
    fun initializeHandshakeAndToolsListOverPipes() {
        val fixture = launch()
        val served = serve("--port", fixture.port.toString())

        val init = initialize(served)
        val result = init["result"]!!.jsonObject
        assertEquals("2.0", init["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("agenthq", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(EventVocabulary.VERSION, result["serverInfo"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertTrue("tools" in result["capabilities"]!!.jsonObject, "must advertise the tools capability: $result")

        // The initialized notification is never answered: the very next line on stdout is the ping's answer.
        val ping = served.request("ping")
        assertEquals(JsonObject(emptyMap()), ping["result"], "ping answers an empty result: $ping")

        val tools = served.request("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf(
                "agenthq_list_sessions", "agenthq_get_session", "agenthq_pod_state", "agenthq_board",
                "agenthq_list_harnesses", // BUG-ASSIGN-1
                "agenthq_harness_status", "agenthq_harness_install", // HARNESS-INSTALL-1
                "agenthq_spawn", "agenthq_prompt", "agenthq_message", "agenthq_kill", "agenthq_session_remove", "agenthq_session_restart",
                "agenthq_ticket_create", "agenthq_ticket_update", "agenthq_ticket_get", // MCP-1
                "agenthq_ticket_start", // BUG-ASSIGN-1
                "agenthq_epic_base", // BASE-1c
                "agenthq_ticket_delete", // MCP-DEL-1
            "agenthq_ticket_move", // MCP-MOVE-1
                "agenthq_inbox", "agenthq_read", // MAIL-MCP-1
                "agenthq_pipeline", "agenthq_pipeline_record", // PIPE-1
                "agenthq_role_list", "agenthq_role_get", "agenthq_role_update", "agenthq_role_create", "agenthq_role_delete", "agenthq_project_config_get", "agenthq_project_config_set", // ROLE-EDIT-1, LANE-CAPS
                "agenthq_ask", // ASK-1
            ),
            tools.map { it["name"]!!.jsonPrimitive.content },
        )
        tools.forEach { tool ->
            val schema = tool["inputSchema"]!!.jsonObject
            assertEquals("object", schema["type"]!!.jsonPrimitive.content, "inputSchema of ${tool["name"]}")
            assertTrue("properties" in schema, "inputSchema of ${tool["name"]} lists its properties")
        }
        val getSchema = tools[1]["inputSchema"]!!.jsonObject
        assertEquals(listOf("sessionId"), getSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("integer", getSchema["properties"]!!.jsonObject["lines"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        // stdout is protocol only: the banner went to stderr.
        assertTrue(served.stderr.contains("agenthq mcp: serving"), "banner belongs on stderr: ${served.stderr}")

        served.close()
        assertEquals(0, served.exit, "EOF on stdin is a clean shutdown; stderr=${served.stderr}")
    }

    @Test
    fun eachReadOnlyToolAnswersFromTheDaemon() {
        val fixture = launch()
        val sessionId = seedSession(fixture)
        val served = serve("--port", fixture.port.toString())
        initialize(served)

        // agenthq_list_sessions: the six columns, with the profile's attribution.
        val sessions = payload(served.callTool("agenthq_list_sessions"))["sessions"]!!.jsonArray
        val row = sessions.single().jsonObject
        assertEquals(sessionId, row["id"]!!.jsonPrimitive.content)
        assertEquals(sessionId, row["sessionId"]!!.jsonPrimitive.content, "sessionId mirrors id: $row")
        assertEquals("be-1", row["agentId"]!!.jsonPrimitive.content)
        assertEquals("backend", row["role"]!!.jsonPrimitive.content)
        assertEquals(fixture.daemon.manager.get(sessionId)!!.provider, row["provider"]!!.jsonPrimitive.content)
        assertTrue("model" in row, "model is always present (null = provider default): $row")
        assertEquals(fixture.daemon.manager.get(sessionId)!!.state.name, row["status"]!!.jsonPrimitive.content)

        // agenthq_get_session: status plus the replayed transcript, which holds the stub's real text.
        val session = payload(served.callTool("agenthq_get_session", """{"sessionId":"${sessionId.take(8)}"}"""))
        assertEquals(sessionId, session["id"]!!.jsonPrimitive.content, "a unique prefix resolves: $session")
        assertEquals(sessionId, session["sessionId"]!!.jsonPrimitive.content, "sessionId mirrors id: $session")
        val transcript = session["transcript"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(
            transcript.any { it.contains("[stub] daemon is running without a provider CLI.") },
            "the transcript carries the run's text: $transcript",
        )
        assertTrue(transcript.any { it.startsWith("[finished]") }, "the transcript ends on the run's finish: $transcript")

        // Bounded: lines=1 returns exactly the last line and says it truncated.
        val one = payload(served.callTool("agenthq_get_session", """{"sessionId":"$sessionId","lines":1}"""))
        assertEquals(listOf(transcript.last()), one["transcript"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", one["transcriptTruncated"]!!.jsonPrimitive.content)

        // An unknown id is a tool error, not a protocol error and not a crash.
        val unknown = served.callTool("agenthq_get_session", """{"sessionId":"no-such-session"}""")
        assertEquals("true", unknown["isError"]!!.jsonPrimitive.content, "$unknown")

        // agenthq_pod_state: agents grouped by role, sessions by status, and the project roster on request.
        val pod = payload(served.callTool("agenthq_pod_state"))
        val agents = pod["agents"]!!.jsonObject
        assertEquals(2, agents["total"]!!.jsonPrimitive.int)
        val byRole = agents["byRole"]!!.jsonObject
        assertEquals(setOf("backend", "qa"), byRole.keys)
        assertEquals("be-1", (byRole["backend"] as JsonArray).single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(1, pod["sessions"]!!.jsonObject["total"]!!.jsonPrimitive.int)
        assertFalse("roster" in pod, "no roster unless a project is named: $pod")

        served.close()
    }

    @Test
    fun unknownMethodAndMalformedJsonAreJsonRpcErrors() {
        val fixture = launch()
        val served = serve("--port", fixture.port.toString())
        initialize(served)

        val unknown = served.request("resources/list")
        assertEquals(-32601, unknown["error"]!!.jsonObject["code"]!!.jsonPrimitive.int, "$unknown")

        served.sendRaw("""{"jsonrpc":"2.0","id":9,"method":""")
        val malformed = served.readMessage()
        assertEquals(-32700, malformed["error"]!!.jsonObject["code"]!!.jsonPrimitive.int, "$malformed")
        assertEquals("null", malformed["id"].toString(), "a parse error cannot know the id: $malformed")

        // The server survives both and keeps answering.
        assertEquals(JsonObject(emptyMap()), served.request("ping")["result"])
        served.close()
    }

    @Test
    fun aDaemonThatIsNotRunningIsAToolErrorWithTheFixedMessage() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val served = serve("--port", closedPort.toString())

        // The server itself starts and handshakes without a daemon...
        assertEquals("agenthq", initialize(served)["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        // ...and every tool reports the unreachable daemon as isError with one fixed message.
        for (tool in listOf("agenthq_list_sessions", "agenthq_pod_state")) {
            val result = served.callTool(tool)
            assertEquals("true", result["isError"]!!.jsonPrimitive.content, "$tool: $result")
            assertEquals(
                McpTools.DAEMON_UNREACHABLE,
                result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content,
            )
        }
        served.close()
        served.worker.join(TimeUnit.SECONDS.toMillis(5))
        assertEquals(0, served.exit)
    }
}
