package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.Cli
import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.harness.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.StubScript
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
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
 * MCP-1: `agenthq mcp serve` creates, updates and reads tickets with role / provider / model and a full multi-line
 * description (BUG-E2E-1, BUG-CLI-1), driven over piped stdio through the REAL [Cli.run] against a REAL in-process
 * daemon. The `claude` provider is a fake that LISTS its models, so an unlisted model is a real refusal.
 */
class McpTicketToolsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val fixtures = mutableListOf<DaemonFixture>()
    private val stops = mutableListOf<() -> Unit>()

    private class ListingProvider(override val id: String, private val known: List<String>) : AgentProvider {
        override fun spec(request: RunRequest) = ProcessSpec(listOf("noop"))
        override fun defaultModel(): String? = null
        override fun knownModels() = known
    }

    @AfterTest
    fun stopAll() {
        stops.forEach { runCatching { it() } }
        fixtures.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
    }

    private fun launch(): DaemonFixture = DaemonFixture.launch(
        StubScript.default(),
        null,
        Files.createTempDirectory("agenthq-mcp-ticket-test").toFile(),
        additionalProviders = listOf(ListingProvider("claude", listOf("haiku", "sonnet", "opus"))),
        // BOARD-API-2: agenthq_ticket_get is Infantry_Board's daemon half.
        pluginsRoot = com.superiorsoftllc.agenthq.daemon.TestDaemonPlugins.boardRoot(),
    ).also { fixtures += it; it.client.await(Envelope.READY) }

    /** One `agenthq mcp serve` over pipes; returns a tools/call function. */
    private fun serve(fixture: DaemonFixture, env: Map<String, String> = emptyMap()): (String, JsonObject) -> JsonObject {
        val toServer = PipedWriter()
        val serverIn = PipedReader(toServer, 1 shl 16)
        val fromServer = PipedReader(1 shl 16)
        val serverOut = PrintWriter(PipedWriter(fromServer))
        val stdin = PrintWriter(toServer)
        val stdout = BufferedReader(fromServer)
        val worker = thread(name = "mcp-serve", isDaemon = true) {
            Cli.run(arrayOf("mcp", "serve", "--port", fixture.port.toString()), serverOut, StringBuffer(), { env[it] }, { serverIn })
            serverOut.close()
        }
        stops += { stdin.close(); worker.join(5_000) }
        var nextId = 1
        fun request(method: String, params: JsonObject): JsonObject {
            val id = nextId++
            stdin.print(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }.toString() + "\n")
            stdin.flush()
            val answer = Json.parseToJsonElement(stdout.readLine()!!).jsonObject
            assertEquals(id, answer["id"]!!.jsonPrimitive.int)
            return answer
        }
        request("initialize", Json.parseToJsonElement("""{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"0"}}""").jsonObject)
        return { name, arguments ->
            request("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })["result"]!!.jsonObject
        }
    }

    private fun args(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    private fun JsonObject.isError() = this["isError"]!!.jsonPrimitive.boolean
    private fun JsonObject.text() = this["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    private fun JsonObject.payload() = Json.parseToJsonElement(text()).jsonObject
    private fun JsonObject.field(k: String) = this[k]?.jsonPrimitive?.content

    private var listings = 0
    private fun board(fixture: DaemonFixture): List<Ticket> {
        fixture.send(Envelope(Envelope.TICKET_LIST, ""))
        val frame = fixture.client.await(Envelope.TICKETS, occurrence = listings++, timeoutMs = 4_000)
        return json.decodeFromString(ListSerializer(Ticket.serializer()), frame.data)
    }

    /**
     * Multi-line, indented, quotes, backticks, shell metacharacters, a CRLF. No OUTER whitespace: the daemon's
     * TicketBoard trims a description's ends for every client (CLI and UI alike), which is not the MCP layer's to change.
     */
    private val brief = "## Scope\n\n1. first line\n   - indented \"quoted\" `code`\n\n2. second; with \$(not-a-shell) & | chars\r\nend"

    @Test
    fun `core lists no ticket tool, start, create, update and get are Infantry_Board tools`() {
        val names = McpTools.descriptors().map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(listOf("agenthq_ticket_start", "agenthq_ticket_create", "agenthq_ticket_update", "agenthq_ticket_get").none { it in names }, names.toString())
    }

    @Test
    fun `create stores a multi-line description verbatim with role, provider and model, and get reads it back`() {
        val fixture = launch()
        val call = serve(fixture, mapOf(AccessPolicy.PROJECT_ID_ENV to "proj-mcp"))
        val created = call("agenthq_ticket_create", args(
            "title" to "MCP ticket", "description" to brief, "role" to "Backend", "provider" to "claude", "model" to "haiku", "verbose" to "true",
        ))
        assertFalse(created.isError(), created.text())
        val id = created.payload().field("ticketId")!!

        val stored = board(fixture).single { it.id == id }
        assertEquals(brief, stored.description, "the description is stored byte for byte")
        assertEquals("role:backend", stored.agentId)
        assertEquals("claude", stored.provider)
        assertEquals("haiku", stored.model)
        assertEquals("proj-mcp", stored.projectId, "the session's AGENTHQ_PROJECT_ID is the default project")
        assertEquals("todo", stored.status)

        val read = call("agenthq_ticket_get", args("ticketId" to id.takeLast(8))).payload()
        assertEquals(brief, read.field("description"))
        assertEquals("role:backend", read.field("agentId"))
        assertEquals("haiku", read.field("model"))
    }

    @Test
    fun `update changes only the fields given, description verbatim`() {
        val fixture = launch()
        val call = serve(fixture)
        val id = call("agenthq_ticket_create", args("title" to "before", "provider" to "claude", "model" to "haiku", "verbose" to "true")).payload().field("ticketId")!!
        val updated = call("agenthq_ticket_update", args("ticketId" to id.takeLast(8), "description" to brief, "model" to "sonnet", "role" to "qa", "verbose" to "true"))
        assertFalse(updated.isError(), updated.text())
        val stored = board(fixture).single { it.id == id }
        assertEquals("before", stored.title, "an absent title keeps the stored one")
        assertEquals(brief, stored.description)
        assertEquals("sonnet", stored.model)
        assertEquals("claude", stored.provider, "an absent provider keeps the stored one")
        assertEquals("role:qa", stored.agentId)
    }

    @Test
    fun `the daemon's refusals come back as tool errors, nothing coerced`() {
        val fixture = launch()
        val call = serve(fixture)
        val bad = call("agenthq_ticket_create", args("title" to "bad model", "provider" to "claude", "model" to "haikuu"))
        assertTrue(bad.isError(), bad.text())
        assertTrue(board(fixture).none { it.title == "bad model" })
        assertTrue(call("agenthq_ticket_create", JsonObject(emptyMap())).isError(), "a title is required")
        assertTrue(call("agenthq_ticket_update", args("ticketId" to "nope0000")).isError(), "an unknown ticket is refused")
        assertTrue(call("agenthq_ticket_get", args("ticketId" to "nope0000")).isError())
    }
}
