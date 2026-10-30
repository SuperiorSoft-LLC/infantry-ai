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
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.File
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

    /** The deleted McpTicketFilesTools.descriptors(), as it served them (TICKET-FILES, contract 1.137.0). */
    private val TICKET_FILES_GOLDEN = """[
        {"name":"agenthq_ticket_files",
         "description":"Files changed by a ticket (status A/M/D/R, +added -removed, path), or with `path` the tickets that changed matching files (glob, `**` ok; no wildcard = any path containing it). Recorded when each ticket went done, kept after its branch is deleted. Cheaper than grepping git history.",
         "inputSchema":{"type":"object","properties":{
            "ticketId":{"type":"string","description":"ticket id, e.g. ticket-55476f3c or 55476f3c"},
            "path":{"type":"string","description":"path or glob, e.g. **/TicketBoard.kt"},
            "limit":{"type":"integer","description":"most lines (default: limits.ticketFiles.maxLines)"}},
            "required":[],"additionalProperties":false},
         "annotations":{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}},
        {"name":"agenthq_ticket_related_files",
         "description":"Call this BEFORE grepping: the tickets that changed the same files as this ticket (ranked by overlap), and the union of the files they changed, each with the ticket ids that touched it.",
         "inputSchema":{"type":"object","properties":{
            "ticketId":{"type":"string","description":"ticket id, e.g. ticket-55476f3c"},
            "top":{"type":"integer","description":"how many related tickets (default: limits.ticketFiles.relatedTop)"},
            "limit":{"type":"integer","description":"most file lines (default: limits.ticketFiles.maxLines)"}},
            "required":["ticketId"],"additionalProperties":false},
         "annotations":{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}}
    ]"""

    /** The deleted McpUsageTools / McpProcessTools descriptors, as they were served (USAGE-PACE 1.119.0, ROGUE-1 1.120.0). */
    private val USAGE_PROCESSES_GOLDEN = """[
        {"name":"agenthq_usage",
         "description":"Read plan usage per meter (claude = the Claude subscription, also spent by pi/opencode running anthropic/* models; opencode-go; others): percent of the plan window used (the highest window), when it resets, points ahead/under an even burn, and how many ticket workers the pacing rule allows versus how many run. An agent's ticket start beyond the allowance is refused, with the reason. Unknown usage = no pacing (fail open). Text summary first, then the JSON report.",
         "inputSchema":{"type":"object","properties":{},"required":[],"additionalProperties":false},
         "annotations":{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}},
        {"name":"agenthq_processes",
         "description":"List rogue background processes (Gradle/Kotlin daemons, node, JVMs) left behind by ended or idle sessions, with MB, age and owner session, plus free RAM against the floor. all=true also lists healthy tracked processes and heavy processes that are not ours (never reapable).",
         "inputSchema":{"type":"object","properties":{"all":{"type":"string","description":"true to include healthy tracked and unattributed processes."}},"required":[],"additionalProperties":false},
         "annotations":{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}},
        {"name":"agenthq_processes_reap",
         "description":"Kill one process (pid) or every rogue one (all=true). Only a process a session of this daemon provably started (pid and start time recorded) is killed; anything else is refused.",
         "inputSchema":{"type":"object","properties":{"pid":{"type":"string","description":"The process id to kill."},"all":{"type":"string","description":"true to reap every rogue process."}},"required":[],"additionalProperties":false},
         "annotations":{"readOnlyHint":false,"destructiveHint":true,"idempotentHint":true,"openWorldHint":false}}
    ]"""

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

    private fun serve(vararg connection: String, env: Map<String, String> = emptyMap()): Served {
        val toServer = PipedWriter()
        val serverIn = PipedReader(toServer, 1 shl 16)
        val fromServer = PipedReader(1 shl 16)
        val serverOut = PrintWriter(PipedWriter(fromServer))
        val stderr = StringBuffer()
        val served = Served(PrintWriter(toServer), BufferedReader(fromServer), stderr)
        served.worker = thread(name = "mcp-serve", isDaemon = true) {
            val code = Cli.run(arrayOf("mcp", "serve", *connection), serverOut, stderr, { env[it] }, { serverIn })
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
                "agenthq_spawn", "agenthq_prompt", "agenthq_message", "agenthq_kill", "agenthq_session_remove", "agenthq_session_restart",
                "agenthq_ticket_create", "agenthq_ticket_update", // MCP-1 (agenthq_ticket_get is Infantry_Board's daemon tool: absent without the plugin)
                "agenthq_ticket_start", // BUG-ASSIGN-1
                "agenthq_epic_base", // BASE-1c
                "agenthq_ticket_delete", // MCP-DEL-1
            "agenthq_ticket_move", // MCP-MOVE-1
                "agenthq_inbox", "agenthq_read", // MAIL-MCP-1
                "agenthq_pipeline", "agenthq_pipeline_record", // PIPE-1
                // ROLE-EDIT-1, LANE-CAPS, ASK-1: the role, project config and ask tools are daemon plugin tools now (RUNTIME-LOAD-3).
                "agenthq_progress", // PROGRESS-1 (1.117.0)
                "agenthq_watch_file", // STALL-4
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

    /**
     * DAEMON-PLUGINS-1: the TICKET-FILES tools moved from this MCP server into Infantry_Board's daemon plugin. Loaded from its
     * built jar, the daemon lists them (after this server's own) with the EXACT descriptors the deleted
     * McpTicketFilesTools served ([TICKET_FILES_GOLDEN], copied from it), the same argument errors, and the same answer as
     * the daemon's own `ticket_files` frame. Without the plugin they are not listed (the test above).
     */
    @Test
    fun ticketFilesToolsComeFromTheBoardPluginUnchanged() {
        val root = com.superiorsoftllc.agenthq.daemon.TestDaemonPlugins.root("official/Infantry_Board")
        try {
            val fixture = DaemonFixture.launch(StubScript.default(), null, Files.createTempDirectory("agenthq-mcp-plugin").toFile(), pluginsRoot = root)
                .also { fixtures += it }
            fixture.client.await(Envelope.READY)
            val served = serve("--port", fixture.port.toString())
            initialize(served)

            val tools = served.request("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
            // The plugin's tools come after this server's own, sorted by name (agenthq_ticket_get is Infantry_Board's too).
            val filesTools = tools.filter { it["name"]!!.jsonPrimitive.content in setOf("agenthq_ticket_files", "agenthq_ticket_related_files") }
            assertEquals(listOf("agenthq_ticket_files", "agenthq_ticket_related_files"), filesTools.map { it["name"]!!.jsonPrimitive.content })
            assertEquals(Json.parseToJsonElement(TICKET_FILES_GOLDEN), JsonArray(filesTools), "the descriptors changed in the move")

            // The argument errors, word for word.
            fun text(result: JsonObject) = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
            val neither = served.callTool("agenthq_ticket_files")
            assertEquals(true, neither["isError"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("give exactly one of ticketId or path", text(neither))
            assertEquals("ticketId is required", text(served.callTool("agenthq_ticket_related_files")))

            // The answer is the daemon's own `ticket_files` answer (a bare short id is the ticket `ticket-<id>`).
            fixture.client.send(Envelope(Envelope.TICKET_FILES, """{"ticketId":"ticket-55476f3c"}""", requestId = "direct"))
            val direct = fixture.client.awaitWhere("the direct ticket_files answer") { it.requestId == "direct" }
            assertEquals(Envelope.TICKET_FILES_REPORT, direct.type, direct.data)
            val viaPlugin = served.callTool("agenthq_ticket_files", """{"ticketId":"55476f3c"}""")
            assertEquals(false, viaPlugin["isError"]!!.jsonPrimitive.content.toBoolean(), "$viaPlugin")
            assertEquals(com.superiorsoftllc.agenthq.ticketfiles.TicketFilesReport.decode(direct.data).text, text(viaPlugin))
            val related = served.callTool("agenthq_ticket_related_files", """{"ticketId":"ticket-55476f3c","top":3}""")
            assertEquals(false, related["isError"]!!.jsonPrimitive.content.toBoolean(), "$related")
        } finally {
            root.deleteRecursively()
        }
    }

    private val TICKET_GET_GOLDEN = """{"name":"agenthq_ticket_get","description":"Read ONE ticket back: title, verbatim description, status, project, role/agent, provider, model, branch, worker session, attached images.","inputSchema":{"type":"object","properties":{"ticketId":{"type":"string","description":"Full ticket id, or its 8-char suffix."}},"required":["ticketId"],"additionalProperties":false},"annotations":{"readOnlyHint":true,"destructiveHint":false,"openWorldHint":false}}"""

    /**
     * BOARD-API-2 (first F5 slice): `agenthq_ticket_get` moved from this MCP server into Infantry_Board's daemon half, over the
     * Tickets API. Loaded from its built jar, the daemon lists it with the EXACT descriptor the deleted McpTicketTools served
     * ([TICKET_GET_GOLDEN]), answers the same JSON and the same refusals, on the same policy action (`board.read`, the action of the
     * `ticket_get` / `ticket_list` frames it replaced). A worker session still reads only its own project's tickets.
     */
    @Test
    fun ticketGetComesFromTheBoardPluginUnchanged() {
        val root = com.superiorsoftllc.agenthq.daemon.TestDaemonPlugins.root("official/Infantry_Board")
        try {
            val fixture = DaemonFixture.launch(StubScript.default(), null, Files.createTempDirectory("agenthq-mcp-plugin").toFile(), pluginsRoot = root)
                .also { fixtures += it }
            fixture.client.await(Envelope.READY)
            val served = serve("--port", fixture.port.toString())
            initialize(served)
            val tools = served.request("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
            assertEquals(Json.parseToJsonElement(TICKET_GET_GOLDEN), tools.single { it["name"]!!.jsonPrimitive.content == "agenthq_ticket_get" }, "the descriptor changed in the move")
            val manifest = Json.parseToJsonElement(File(root, "official/Infantry_Board/module.json").readText()).jsonObject["daemon"]!!.jsonObject["tools"]!!.jsonArray
                .map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "agenthq_ticket_get" }
            assertEquals("board.read", manifest["action"]!!.jsonPrimitive.content)
            assertEquals("read", manifest["kind"]!!.jsonPrimitive.content)

            fun text(result: JsonObject) = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
            fun isError(result: JsonObject) = result["isError"]!!.jsonPrimitive.content.toBoolean()
            val noId = served.callTool("agenthq_ticket_get")
            assertEquals(true, isError(noId))
            assertEquals("agenthq_ticket_get needs a ticketId", text(noId))
            val unknown = served.callTool("agenthq_ticket_get", """{"ticketId":"nope0000"}""")
            assertEquals(true, isError(unknown))
            assertEquals("no ticket 'nope0000' on this daemon", text(unknown))

            fixture.client.send(Envelope(Envelope.TICKET_SAVE, """{"title":"read me back","description":"line one\nline two","projectId":"project-a"}""", requestId = "save"))
            val saved = fixture.client.awaitWhere("the saved ticket") { it.requestId == "save" }
            val id = Json.parseToJsonElement(saved.data).jsonObject["id"]!!.jsonPrimitive.content
            val got = served.callTool("agenthq_ticket_get", """{"ticketId":"${id.takeLast(8)}"}""")
            assertEquals(false, isError(got), "$got")
            val ticket = Json.parseToJsonElement(text(got)).jsonObject
            assertEquals(id, ticket["ticketId"]!!.jsonPrimitive.content)
            assertEquals("read me back", ticket["title"]!!.jsonPrimitive.content)
            assertEquals("line one\nline two", ticket["description"]!!.jsonPrimitive.content)
            assertEquals("todo", ticket["status"]!!.jsonPrimitive.content)
            assertEquals(
                listOf("ticketId", "title", "description", "status", "projectId", "epicId", "agentId", "role", "provider", "model", "weight",
                    "contextLimit", "effectiveContextLimit", "effectiveContextLimitSource", "contextLimitSupport",
                    "effectiveProvider", "effectiveProviderSource", "effectiveModel", "effectiveModelSource", "effectiveRule", "branch", "sessionId", "images"),
                ticket.keys.toList(), "the answer's fields and their order",
            )
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * DAEMON-PLUGINS-3: `agenthq_usage`, `agenthq_processes` and `agenthq_processes_reap` moved from this MCP server into the
     * Infantry_Usage / Infantry_Processes daemon plugins. Same descriptors ([USAGE_PROCESSES_GOLDEN]), same argument errors,
     * same answers as the daemon's own frames rendered by the shared API text helpers.
     */
    @Test
    fun usageAndProcessesToolsComeFromTheirPluginsUnchanged() {
        val root = com.superiorsoftllc.agenthq.daemon.TestDaemonPlugins.root("official/Infantry_Usage", "official/Infantry_Processes")
        try {
            val fixture = DaemonFixture.launch(StubScript.default(), null, Files.createTempDirectory("agenthq-mcp-plugin").toFile(), pluginsRoot = root)
                .also { fixtures += it }
            fixture.client.await(Envelope.READY)
            val served = serve("--port", fixture.port.toString())
            initialize(served)

            val tools = served.request("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
            val mine = tools.filter { it["name"]!!.jsonPrimitive.content in setOf("agenthq_usage", "agenthq_processes", "agenthq_processes_reap") }
                .sortedBy { listOf("agenthq_usage", "agenthq_processes", "agenthq_processes_reap").indexOf(it["name"]!!.jsonPrimitive.content) }
            assertEquals(Json.parseToJsonElement(USAGE_PROCESSES_GOLDEN), JsonArray(mine), "the descriptors changed in the move")

            fun text(result: JsonObject) = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
            fun isError(result: JsonObject) = result["isError"]!!.jsonPrimitive.content.toBoolean()
            // The argument errors, word for word.
            val none = served.callTool("agenthq_processes_reap")
            assertEquals(true, isError(none))
            assertEquals("agenthq_processes_reap needs exactly one of pid or all=true", text(none))
            assertEquals("agenthq_processes_reap: pid must be a number", text(served.callTool("agenthq_processes_reap", """{"pid":"abc"}""")))

            // The usage answer: the shared text, a blank line, then the JSON report of the daemon's own `usage_get` answer.
            fixture.client.send(Envelope(Envelope.USAGE_GET, "", requestId = "direct"))
            val direct = fixture.client.awaitWhere("the direct usage_get answer") { it.requestId == "direct" || it.type == Envelope.USAGE_REPORT }
            val report = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString(com.superiorsoftllc.agenthq.daemon.api.usage.UsageReport.serializer(), direct.data)
            val usage = served.callTool("agenthq_usage")
            assertEquals(false, isError(usage), "$usage")
            assertTrue(text(usage).startsWith(com.superiorsoftllc.agenthq.daemon.api.usage.UsageText.render(report)), text(usage))

            // The processes list: the shared text, not an error.
            val list = served.callTool("agenthq_processes")
            assertEquals(false, isError(list), "$list")
            assertTrue(text(list).contains("memory:"), text(list))
            assertTrue(text(list).contains("rogue ("), text(list))
        } finally {
            root.deleteRecursively()
        }
    }

    /** RUNTIME-LOAD-1: the deleted McpTools descriptor of `agenthq_harness_status`, as it was served (HARNESS-INSTALL-1, 1.100.0). */
    private val HARNESS_STATUS_GOLDEN = """{"name":"agenthq_harness_status",
         "description":"Which harness CLIs (claude, opencode, pi) are installed on the daemon's machine: state installed / missing / outdated, the version, where it runs from, and whether npm is available. checkLatest=true asks npm for the latest version too (slower). Install the missing ones with agenthq_harness_install.",
         "inputSchema":{"type":"object","properties":{"checkLatest":{"type":"boolean","description":"Also ask npm for each CLI's latest version (default false)."}},"additionalProperties":false},
         "annotations":{"readOnlyHint":true}}"""

    private val HARNESS_INSTALL_GOLDEN = """{"name":"agenthq_harness_install",
         "description":"npm-install harness CLIs on the daemon's machine, into Infantry AI's own prefix dir (no admin rights, no user data touched; idempotent). ids: which ones (default: every missing one). update=true also refreshes outdated ones. Needs Node/npm on that machine (the answer says so when it is missing). Sign-in is still the user's: each CLI keeps its own login. Can take minutes.",
         "inputSchema":{"type":"object","properties":{"ids":{"type":"array","items":{"type":"string"},"description":"Harness ids to install, from: claude, opencode, pi. Default: all missing."},"update":{"type":"boolean","description":"Also update installed CLIs that are outdated (default false)."}},"additionalProperties":false},
         "annotations":{"readOnlyHint":true}}"""

    private val LIST_HARNESSES_GOLDEN = """{"name":"agenthq_list_harnesses",
         "description":"List the harnesses a ticket can run on: each harness id (the value of a ticket's provider) with its models (the values of its model), which one is the default, and whether its CLI is available. Pin them with agenthq_ticket_create / agenthq_ticket_update, then start the ticket with agenthq_ticket_start.",
         "inputSchema":{"type":"object","properties":{},"additionalProperties":false},
         "annotations":{"readOnlyHint":true}}"""

    /** RUNTIME-LOAD-2: `agenthq_list_harnesses` comes from Infantry_Agents with its old descriptor; the answer lists the daemon's own providers, with the old keys. */
    @Test
    fun listHarnessesToolComesFromInfantryAgentsUnchanged() {
        val root = com.superiorsoftllc.agenthq.daemon.TestDaemonPlugins.root("official/Infantry_Agents")
        try {
            val fixture = DaemonFixture.launch(StubScript.default(), null, Files.createTempDirectory("agenthq-mcp-plugin").toFile(), pluginsRoot = root)
                .also { fixtures += it }
            fixture.client.await(Envelope.READY)
            val served = serve("--port", fixture.port.toString())
            initialize(served)
            val tools = served.request("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
            assertEquals(Json.parseToJsonElement(LIST_HARNESSES_GOLDEN), tools.single { it["name"]!!.jsonPrimitive.content == "agenthq_list_harnesses" }, "the descriptor changed in the move")

            fixture.client.send(Envelope(Envelope.PROVIDERS, "", requestId = "direct"))
            val direct = fixture.client.awaitWhere("the direct providers answer") { it.type == Envelope.PROVIDERS }
            val ids = Json.parseToJsonElement(direct.data).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            val got = served.callTool("agenthq_list_harnesses")
            assertEquals(false, got["isError"]!!.jsonPrimitive.content.toBoolean(), "$got")
            val payload = Json.parseToJsonElement(got["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content).jsonObject
            assertEquals(ids, payload["harnesses"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
            assertEquals(
                setOf("id", "default", "defaultModel", "models", "available", "unavailableReason", "note", "contextLimit", "contextLimitHow"),
                payload["harnesses"]!!.jsonArray.first().jsonObject.keys,
            )
            assertEquals(com.superiorsoftllc.agenthq.daemon.api.harness.HarnessCatalogText.HOW_TO_PIN, payload["howToUse"]!!.jsonPrimitive.content)
        } finally {
            root.deleteRecursively()
        }
    }

    /** RUNTIME-LOAD-2: `agenthq_harness_install` comes from Infantry_Agents with the descriptor it had in core, and a call reaches the daemon's frame. */
    @Test
    fun harnessInstallToolComesFromInfantryAgentsUnchanged() {
        val root = com.superiorsoftllc.agenthq.daemon.TestDaemonPlugins.root("official/Infantry_Agents")
        try {
            val fixture = DaemonFixture.launch(StubScript.default(), null, Files.createTempDirectory("agenthq-mcp-plugin").toFile(), pluginsRoot = root)
                .also { fixtures += it }
            fixture.client.await(Envelope.READY)
            val served = serve("--port", fixture.port.toString())
            initialize(served)
            val tools = served.request("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
            assertEquals(Json.parseToJsonElement(HARNESS_INSTALL_GOLDEN), tools.single { it["name"]!!.jsonPrimitive.content == "agenthq_harness_install" }, "the descriptor changed in the move")
            assertTrue(tools.none { "timeoutMs" in it }, "the timeout is bookkeeping for the MCP server, not part of the MCP descriptor")
            // The daemon's own list carries each tool's effective timeout: the 15 minutes both harness tools declare.
            fixture.client.send(Envelope("plugin_tools", "", requestId = "timeouts"))
            val raw = Json.parseToJsonElement(fixture.client.awaitWhere("the plugin_tools answer") { it.type == "plugin_tools" }.data).jsonArray.map { it.jsonObject }
            assertEquals(
                mapOf("agenthq_harness_install" to 900_000L, "agenthq_harness_status" to 900_000L),
                raw.filter { it["name"]!!.jsonPrimitive.content.startsWith("agenthq_harness_") }.associate { it["name"]!!.jsonPrimitive.content to it["timeoutMs"]!!.jsonPrimitive.content.toLong() },
            )
            // An unknown id installs nothing: the daemon's own refusal comes back as the tool's error, through the plugin's frame request.
            val got = served.callTool("agenthq_harness_install", """{"ids":["no-such-harness"]}""")
            assertEquals(true, got["isError"]!!.jsonPrimitive.content.toBoolean(), "$got")
            assertTrue("unknown harness no-such-harness" in got["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content, "$got")
        } finally {
            root.deleteRecursively()
        }
    }

    /** RUNTIME-LOAD-1: `agenthq_harness_status` comes from Infantry_Agents unchanged: descriptor, and the answer the old code gave. */
    @Test
    fun harnessStatusToolComesFromInfantryAgentsUnchanged() {
        val root = com.superiorsoftllc.agenthq.daemon.TestDaemonPlugins.root("official/Infantry_Agents")
        try {
            val fixture = DaemonFixture.launch(StubScript.default(), null, Files.createTempDirectory("agenthq-mcp-plugin").toFile(), pluginsRoot = root)
                .also { fixtures += it }
            fixture.client.await(Envelope.READY)
            val served = serve("--port", fixture.port.toString())
            initialize(served)
            val tools = served.request("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
            assertEquals(Json.parseToJsonElement(HARNESS_STATUS_GOLDEN), tools.single { it["name"]!!.jsonPrimitive.content == "agenthq_harness_status" }, "the descriptor changed in the move")

            // The answer is what the old in-core code built from the daemon's own frame: {"report":<wire report>,"text":<table>}.
            fixture.client.send(Envelope(Envelope.HARNESS_INSTALL_STATUS, "{}", requestId = "direct"))
            val direct = fixture.client.awaitWhere("the direct harness_install_status answer") { it.requestId == "direct" || it.type == Envelope.HARNESS_INSTALL_STATUS }
            val wire = Json { ignoreUnknownKeys = true }.decodeFromString(com.superiorsoftllc.agenthq.protocol.HarnessInstallReport.serializer(), direct.data)
            val old = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
            val expected = old.encodeToString(JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
                put("report", old.encodeToJsonElement(com.superiorsoftllc.agenthq.protocol.HarnessInstallReport.serializer(), wire))
                put("text", StringBuilder().also { com.superiorsoftllc.agenthq.cli.HarnessInstallClient.render(wire, it) }.toString())
            })
            val got = served.callTool("agenthq_harness_status")
            assertEquals(false, got["isError"]!!.jsonPrimitive.content.toBoolean(), "$got")
            assertEquals(expected, got["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        } finally {
            root.deleteRecursively()
        }
    }

    /** RUNTIME-LOAD-1/2: with Infantry_Agents absent harness status and install disappear cleanly: not listed, no error. */
    @Test
    fun harnessStatusToolIsGoneWithoutInfantryAgents() {
        val root = com.superiorsoftllc.agenthq.daemon.TestDaemonPlugins.root()
        try {
            val fixture = DaemonFixture.launch(StubScript.default(), null, Files.createTempDirectory("agenthq-mcp-plugin").toFile(), pluginsRoot = root)
                .also { fixtures += it }
            fixture.client.await(Envelope.READY)
            val served = serve("--port", fixture.port.toString())
            initialize(served)
            val tools = served.request("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertTrue("agenthq_harness_status" !in tools, "$tools")
            assertTrue("agenthq_harness_install" !in tools, "install left core with the plugin: $tools")
            assertTrue("agenthq_list_harnesses" !in tools, "list_harnesses left core with the plugin: $tools")
            // RUNTIME-LOAD-3: the roles, ask and project config tools left core with their plugins too.
            assertEquals(emptyList<String>(), tools.filter { it.startsWith("agenthq_role_") || it.startsWith("agenthq_project_config_") || it == "agenthq_ask" }, "$tools")
        } finally {
            root.deleteRecursively()
        }
    }

    private fun text(result: JsonObject) = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
    private fun isError(result: JsonObject) = result["isError"]!!.jsonPrimitive.content.toBoolean()

    private fun withPlugins(vararg modules: String, env: Map<String, String> = emptyMap(), body: (Served, List<JsonObject>) -> Unit) {
        val root = com.superiorsoftllc.agenthq.daemon.TestDaemonPlugins.root(*modules)
        try {
            val fixture = DaemonFixture.launch(StubScript.default(), null, Files.createTempDirectory("agenthq-mcp-plugin").toFile(), pluginsRoot = root)
                .also { fixtures += it }
            fixture.client.await(Envelope.READY)
            val served = serve("--port", fixture.port.toString(), env = env)
            initialize(served)
            body(served, served.request("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject })
        } finally {
            root.deleteRecursively()
        }
    }

    /** RUNTIME-LOAD-3 (F3): the five roles tools come from Infantry_Roles with the schemas and hints they had in core; the agent-caller rule holds. */
    @Test
    fun rolesToolsComeFromInfantryRolesUnchanged() {
        val names = listOf("agenthq_role_list", "agenthq_role_get", "agenthq_role_update", "agenthq_role_create", "agenthq_role_delete")
        withPlugins("official/Infantry_Roles") { person, tools ->
            assertEquals(names.sorted(), tools.map { it["name"]!!.jsonPrimitive.content }.filter { it in names })
            fun annotations(name: String) = tools.single { it["name"]!!.jsonPrimitive.content == name }["annotations"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content.toBoolean() }
            assertEquals(mapOf("readOnlyHint" to true, "destructiveHint" to false, "idempotentHint" to true, "openWorldHint" to false), annotations("agenthq_role_list"))
            assertEquals(mapOf("readOnlyHint" to false, "destructiveHint" to true, "idempotentHint" to false, "openWorldHint" to false), annotations("agenthq_role_delete"))
            val update = tools.single { it["name"]!!.jsonPrimitive.content == "agenthq_role_update" }["inputSchema"]!!.jsonObject
            assertEquals(listOf("id", "persona", "description", "name", "color", "icon"), update["properties"]!!.jsonObject.keys.toList())
            assertEquals(listOf("id"), update["required"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(false, update["additionalProperties"]!!.jsonPrimitive.content.toBoolean())

            val listed = Json.parseToJsonElement(text(person.callTool("agenthq_role_list"))).jsonArray.map { it.jsonObject }
            val backend = listed.single { it["id"]!!.jsonPrimitive.content == "backend" }
            assertEquals(setOf("id", "name", "description", "source", "personaChars"), backend.keys)
            assertEquals("backend", Json.parseToJsonElement(text(person.callTool("agenthq_role_get", """{"id":"backend"}"""))).jsonObject["id"]!!.jsonPrimitive.content)
            val unknown = person.callTool("agenthq_role_get", """{"id":"no-such-role"}""")
            assertTrue(isError(unknown) && text(unknown) == "agenthq_role_get: unknown role 'no-such-role'", "$unknown")

            // A person creates, renames and deletes a role.
            val created = person.callTool("agenthq_role_create", """{"id":"zz-test","name":"ZZ Test","persona":"one"}""")
            assertTrue(!isError(created) && text(created).startsWith("saved role 'zz-test' (3 persona characters)"), "$created")
            assertTrue(isError(person.callTool("agenthq_role_create", """{"id":"zz-test","name":"again"}""")))
            assertTrue(!isError(person.callTool("agenthq_role_update", """{"id":"zz-test","name":"ZZ Renamed"}""")))
            assertEquals("deleted role 'zz-test'", text(person.callTool("agenthq_role_delete", """{"id":"zz-test"}""")))
        }
        // An agent (AGENTHQ_ROLE / AGENTHQ_SESSION_ID in the MCP process) may only update persona and description of an existing role.
        for (env in listOf(mapOf("AGENTHQ_ROLE" to "backend"), mapOf("AGENTHQ_SESSION_ID" to "agent-1"))) {
            withPlugins("official/Infantry_Roles", env = env) { agent, _ ->
                assertEquals("agenthq_role_create needs a non-agent caller: an agent may only update the persona and description of an existing role", text(agent.callTool("agenthq_role_create", """{"id":"zz-agent","name":"x"}""")))
                assertEquals("agenthq_role_delete needs a non-agent caller: an agent may not delete roles", text(agent.callTool("agenthq_role_delete", """{"id":"zz-agent"}""")))
                assertEquals("agenthq_role_update: an agent may change only persona and description; name need a non-agent caller", text(agent.callTool("agenthq_role_update", """{"id":"backend","name":"x"}""")))
                val ok = agent.callTool("agenthq_role_update", """{"id":"backend","description":"agent edit"}""")
                assertTrue(!isError(ok) && text(ok).startsWith("saved role 'backend'"), "$ok")
                // Reading stays open to an agent.
                assertTrue(!isError(agent.callTool("agenthq_role_list")))
            }
        }
    }

    /** RUNTIME-LOAD-3 (F4): `agenthq_ask` comes from Infantry_Attention; the asker is the MCP process's own session. */
    @Test
    fun askToolComesFromInfantryAttention() {
        val args = """{"question":"Which?","options":[{"label":"A","description":"a"},{"label":"B"}],"recommended":"A","reason":"simplest"}"""
        withPlugins("official/Infantry_Attention") { person, tools ->
            val ask = tools.single { it["name"]!!.jsonPrimitive.content == "agenthq_ask" }
            assertEquals(listOf("question", "options", "recommended", "reason"), ask["inputSchema"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("question", "options", "recommended", "reason", "allowOther", "defaultAfterMin"), ask["inputSchema"]!!.jsonObject["properties"]!!.jsonObject.keys.toList())
            assertEquals(false, ask["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.content.toBoolean())
            val noSession = person.callTool("agenthq_ask", args)
            assertTrue(isError(noSession) && text(noSession).startsWith("agenthq_ask needs the asking session in AGENTHQ_SESSION_ID"), "$noSession")
        }
        withPlugins("official/Infantry_Attention", env = mapOf("AGENTHQ_SESSION_ID" to "agent-1")) { agent, _ ->
            assertEquals("agenthq_ask needs options: [{label, description}, ...]", text(agent.callTool("agenthq_ask", """{"question":"q"}""")))
            // With a session the call reaches the daemon's question_ask: its own answer (a question id or its refusal), never the session message.
            val sent = agent.callTool("agenthq_ask", args)
            assertTrue("needs the asking session" !in text(sent), "$sent")
        }
    }

    /** RUNTIME-LOAD-3 (F4): the project config tools come from Infantry_Settings; argument errors are the ones core gave, the daemon answers the frame. */
    @Test
    fun projectConfigToolsComeFromInfantrySettings() {
        withPlugins("official/Infantry_Settings") { served, tools ->
            assertEquals(listOf("agenthq_project_config_get", "agenthq_project_config_set"), tools.map { it["name"]!!.jsonPrimitive.content }.filter { it.startsWith("agenthq_project_config_") })
            val set = tools.single { it["name"]!!.jsonPrimitive.content == "agenthq_project_config_set" }["inputSchema"]!!.jsonObject
            assertEquals(listOf("projectId", "role", "cap", "autoRemoveDoneWorkers", "routingRules", "experiment", "value"), set["properties"]!!.jsonObject.keys.toList())
            assertEquals("agenthq_project_config_get needs a projectId", text(served.callTool("agenthq_project_config_get")))
            assertEquals("agenthq_project_config_set needs a role (with a cap), autoRemoveDoneWorkers, or routingRules", text(served.callTool("agenthq_project_config_set", """{"projectId":"p"}""")))
            assertEquals("agenthq_project_config_set: autoRemoveDoneWorkers must be true or false, was 'maybe'", text(served.callTool("agenthq_project_config_set", """{"projectId":"p","autoRemoveDoneWorkers":"maybe"}""")))
            assertEquals("agenthq_project_config_set needs a cap of 1 or more", text(served.callTool("agenthq_project_config_set", """{"projectId":"p","role":"backend","cap":"0"}""")))
            assertTrue(text(served.callTool("agenthq_project_config_set", """{"projectId":"p","routingRules":"not json"}""")).startsWith("agenthq_project_config_set: routingRules must be a JSON array of {name, weights?"))
            // The daemon's own answer to the frame comes back as the tool's result: an unknown project is its refusal, not a plugin error.
            val unknown = served.callTool("agenthq_project_config_get", """{"projectId":"no-such-project"}""")
            assertTrue(isError(unknown) && "unexpected answer" !in text(unknown), "$unknown")
        }
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

    /** Contract 1.117.0: outside a session agenthq_progress is a no-op that says so; a bad mode is a tool error. */
    @Test
    fun progressToolIsHarmlessOutsideASession() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        val served = serve("--port", fixture.port.toString())
        initialize(served)
        val ok = served.callTool("agenthq_progress", """{"mode":"percent","percent":40}""")
        assertEquals("false", ok["isError"]!!.jsonPrimitive.content, "$ok")
        assertTrue(ok["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content.startsWith("not running inside an Infantry AI session"), "$ok")
        val bad = served.callTool("agenthq_progress", """{"mode":"guess"}""")
        assertEquals("true", bad["isError"]!!.jsonPrimitive.content, "$bad")
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
