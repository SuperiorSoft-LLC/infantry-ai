package com.superiorsoftllc.infantry.cli.mcp

import com.superiorsoftllc.infantry.daemon.requireBoard

import com.superiorsoftllc.infantry.cli.Cli
import com.superiorsoftllc.infantry.cli.CliTestEnv
import com.superiorsoftllc.infantry.cli.shortTicketId
import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.daemon.saveProfile
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.TicketStatus
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BACKLOG (contract 1.131.0, ticket-39eab724) against a REAL in-process daemon ([DaemonFixture], temp state dir,
 * ephemeral port): a ticket created into Backlog through the CLI and through `infantry mcp serve` lands there, both
 * surfaces list it the same way (`tickets --status backlog`, `infantry board` / `infantry_board`), every start path
 * (`ticket start`, `ticket move ... in_progress`, `infantry_ticket_start`) is refused naming the lane, and hand moves
 * to and from Backlog work. Every assertion reads the daemon's own board as well as what a tool printed.
 */
class BacklogLaneE2eTest {

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

    private class Served(private val stdin: PrintWriter, private val stdout: BufferedReader, val stderr: StringBuffer) {
        lateinit var worker: Thread
        private var nextId = 1

        fun request(method: String, params: String = "{}"): JsonObject {
            val id = nextId++
            stdin.print("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""" + "\n")
            stdin.flush()
            val line = stdout.readLine() ?: error("stdout closed; stderr=$stderr")
            val answer = Json.parseToJsonElement(line).jsonObject
            assertEquals(id, answer["id"]!!.jsonPrimitive.int, "answer must carry the request id: $answer")
            return answer
        }

        /** The board tools answer terse text by default; these tests read the JSON payload (verbose). */
        private fun verbose(a: String) = if (a.trim() == "{}") """{"verbose":true}""" else a.replaceFirst("{", """{"verbose":true,""")

        fun callTool(name: String, arguments: String = "{}"): JsonObject =
            request("tools/call", """{"name":"$name","arguments":${verbose(arguments)}}""")["result"]!!.jsonObject

        fun close() {
            runCatching { stdin.close() }
            worker.join(5_000)
        }
    }

    private fun serve(fixture: DaemonFixture, cwd: File): Served {
        val toServer = PipedWriter()
        val serverIn = PipedReader(toServer, 1 shl 16)
        val fromServer = PipedReader(1 shl 16)
        val serverOut = PrintWriter(PipedWriter(fromServer))
        val stderr = StringBuffer()
        val served = Served(PrintWriter(toServer), BufferedReader(fromServer), stderr)
        val lineage = tempDir("infantry-mcp-backlog-lineage")
        val args = arrayOf("mcp", "serve", "--port", fixture.port.toString())
        served.worker = thread(name = "mcp-serve-backlog-e2e", isDaemon = true) {
            Cli.run(args, serverOut, stderr, { null }, { serverIn }, cwd = cwd)
            serverOut.close()
        }
        servers += served
        served.request(
            "initialize",
            """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"backlog-e2e","version":"0"}}""",
        )
        return served
    }

    private data class Run(val exit: Int, val out: String, val err: String)

    private fun cli(fixture: DaemonFixture, vararg args: String): Run {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf(*args, "--port", fixture.port.toString()), out, err, CliTestEnv.getenv)
        return Run(exit, out.toString(), err.toString())
    }

    private fun text(result: JsonObject): String =
        result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

    private fun payload(result: JsonObject): JsonObject {
        assertEquals("false", result["isError"]!!.jsonPrimitive.content, "tool reported an error: ${text(result)}")
        return Json.parseToJsonElement(text(result)).jsonObject
    }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    @Test
    fun backlogHasParityAcrossCliAndMcpAndRefusesEveryStart() {
        val fixture = DaemonFixture.launch(stateDir = tempDir("infantry-backlog-daemon"), pluginsRoot = com.superiorsoftllc.infantry.daemon.TestDaemonPlugins.boardRoot()).also { fixtures += it; it.client.await(Envelope.READY) }
        // A role with a profile on the stub harness: a todo ticket of that role could start, so a refusal is the lane's.
        fixture.client.saveProfile("""{"id":"backend-1","name":"Backend","role":"backend","provider":"stub"}""")
        val project = tempDir("infantry-backlog-project")
        McpServeConfig.fileIn(project).apply { parentFile.mkdirs() }.writeText("""{"projectId":"$PROJECT"}""")
        val agent = serve(fixture, project)
        fun status(id: String) = fixture.daemon.requireBoard().list().single { it.id == id }.status

        // Create: MCP status backlog, CLI --status backlog, and a plain create still entering the sprint.
        val viaMcp = payload(agent.callTool("infantry_ticket_create", """{"title":"Parked by MCP","role":"backend","status":"backlog"}"""))
        assertEquals(TicketStatus.BACKLOG, viaMcp.str("status"))
        val mcpId = viaMcp.str("ticketId")
        val created = cli(fixture, "ticket", "create", "--title", "Parked by CLI", "--role", "backend", "--project", PROJECT, "--status", "backlog")
        assertEquals(0, created.exit, "ticket create --status backlog failed: ${created.err}")
        val sprint = cli(fixture, "ticket", "create", "--title", "Sprint work", "--role", "backend", "--project", PROJECT)
        assertEquals(0, sprint.exit, sprint.err)
        val board = fixture.daemon.requireBoard().list()
        val cliId = board.single { it.title == "Parked by CLI" }.id
        assertEquals(TicketStatus.BACKLOG, status(mcpId))
        assertEquals(TicketStatus.BACKLOG, status(cliId))
        assertEquals(TicketStatus.TODO, board.single { it.title == "Sprint work" }.status)
        assertEquals(listOf("Parked by MCP", "Parked by CLI", "Sprint work"), board.map { it.title }, "backlog is listed first")

        // A create into any other column is refused on both surfaces, before anything is stored.
        val badCli = cli(fixture, "ticket", "create", "--title", "x", "--project", PROJECT, "--status", "done")
        assertEquals(2, badCli.exit, "a create --status done is a usage error: ${badCli.out}${badCli.err}")
        assertTrue(badCli.err.contains("backlog"), badCli.err)
        val badMcp = agent.callTool("infantry_ticket_create", """{"title":"x","status":"in_progress"}""")
        assertEquals("true", badMcp["isError"]!!.jsonPrimitive.content, "an MCP create into in_progress is refused: $badMcp")
        assertEquals(3, fixture.daemon.requireBoard().list().size, "refused creates store nothing")

        // Listing parity: tickets --status backlog, infantry board and infantry_board.
        val listed = cli(fixture, "tickets", "--status", "backlog")
        assertEquals(0, listed.exit, listed.err)
        assertTrue(listed.out.contains("Parked by MCP") && listed.out.contains("Parked by CLI"), listed.out)
        assertTrue(!listed.out.contains("Sprint work"), "only the backlog column: ${listed.out}")
        val cliBoard = cli(fixture, "board", "--project", PROJECT)
        assertEquals(0, cliBoard.exit, cliBoard.err)
        val mcpBoard = payload(agent.callTool("infantry_board", """{"projectId":"$PROJECT"}"""))["board"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("backlog (2)", mcpBoard.first(), "infantry_board prints the Backlog group first: $mcpBoard")
        assertEquals("todo (1)", mcpBoard[3], "$mcpBoard")
        assertEquals(cliBoard.out.trim().lines().first().trim(), mcpBoard.first(), "CLI and MCP print the same board")

        // Every start path is refused naming the lane, and the ticket stays in Backlog with no worker.
        val mcpStart = agent.callTool("infantry_ticket_start", """{"ticketId":"$mcpId"}""")
        assertTrue(text(mcpStart).contains("Backlog lane"), "infantry_ticket_start names the lane: ${text(mcpStart)}")
        val cliStart = cli(fixture, "ticket", "start", shortTicketId(cliId))
        assertEquals(1, cliStart.exit, cliStart.out)
        assertTrue(cliStart.err.contains(TicketStatus.BACKLOG_START_REFUSAL), "ticket start names the lane: ${cliStart.err}")
        val cliMove = cli(fixture, "ticket", "move", shortTicketId(cliId), "in_progress")
        assertTrue(cliMove.exit != 0 && cliMove.err.contains("Backlog lane"), "ticket move in_progress is a refused start: ${cliMove.err}")
        for (id in listOf(mcpId, cliId)) {
            assertEquals(TicketStatus.BACKLOG, status(id), "a refused start leaves it in backlog")
            assertNull(fixture.daemon.requireBoard().list().single { it.id == id }.sessionId, "no worker was started")
        }
        assertTrue(fixture.daemon.manager.list().isEmpty(), "no session was spawned by any refused start")

        // Hand moves: backlog -> todo over the CLI, todo -> backlog over MCP.
        val promoted = cli(fixture, "ticket", "move", shortTicketId(cliId), "todo")
        assertEquals(0, promoted.exit, promoted.err)
        assertEquals(TicketStatus.TODO, status(cliId))
        val parked = payload(agent.callTool("infantry_ticket_move", """{"ticketId":"$cliId","status":"backlog"}"""))
        assertEquals(TicketStatus.BACKLOG, parked.str("status"))
        assertEquals(TicketStatus.BACKLOG, status(cliId))
    }

    private companion object {
        const val PROJECT = "backlog-e2e-project"
    }
}
