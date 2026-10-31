package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.daemon.requireBoard

import com.superiorsoftllc.agenthq.cli.Cli
import com.superiorsoftllc.agenthq.cli.shortTicketId
import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketStatus
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
import kotlin.test.assertTrue

/**
 * MCP-DEL-1: an agent deletes a ticket through the REAL `agenthq mcp serve` entrance ([Cli.run] over its stdin/stdout
 * seam, newline-delimited JSON-RPC 2.0) against a real in-process daemon ([DaemonFixture]) -- the same path an agent's
 * MCP client takes, not a fake stream.
 *
 * Covers: create then delete by full id (gone from the board and from agenthq_ticket_get), delete by the 8-char
 * suffix, an unknown id refused with the board unchanged, and an in-progress ticket refused with the board unchanged.
 */
class McpTicketDeleteE2eTest {

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

        fun callTool(name: String, arguments: String = "{}"): JsonObject =
            request("tools/call", """{"name":"$name","arguments":$arguments}""")["result"]!!.jsonObject

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
        val lineage = tempDir("agenthq-mcp-del-lineage")
        val args = arrayOf("mcp", "serve", "--port", fixture.port.toString())
        served.worker = thread(name = "mcp-serve-del-e2e", isDaemon = true) {
            Cli.run(args, serverOut, stderr, { null }, { serverIn }, cwd = cwd)
            serverOut.close()
        }
        servers += served
        served.request(
            "initialize",
            """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"del-e2e","version":"0"}}""",
        )
        return served
    }

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
    fun anAgentDeletesTicketsThroughMcpServe() {
        val fixture = DaemonFixture.launch(stateDir = tempDir("agenthq-mcp-del-daemon"), pluginsRoot = com.superiorsoftllc.agenthq.daemon.TestDaemonPlugins.boardRoot()).also { fixtures += it; it.client.await(Envelope.READY) }
        val board = fixture.daemon.requireBoard()
        val project = tempDir("agenthq-mcp-del-project")
        McpServeConfig.fileIn(project).apply { parentFile.mkdirs() }.writeText("""{"projectId":"$PROJECT"}""")
        val agent = serve(fixture, project)

        fun create(title: String): String =
            payload(agent.callTool("agenthq_ticket_create", """{"title":"$title"}""")).str("ticketId")
        fun boardIds() = board.list().map { it.id }.toSet()

        // 1) create, delete by full id: the answer names it, the board no longer holds it, ticket_get refuses it.
        val first = create("delete me by full id")
        val keep = create("stays on the board")
        assertTrue(first in boardIds() && keep in boardIds(), "both tickets were created: ${boardIds()}")
        val deleted = payload(agent.callTool("agenthq_ticket_delete", """{"ticketId":"$first"}"""))
        assertEquals(first, deleted.str("ticketId"), "$deleted")
        assertEquals("delete me by full id", deleted.str("title"), "$deleted")
        assertEquals(setOf(keep), boardIds(), "only the named ticket was deleted")
        assertRefused(agent.callTool("agenthq_ticket_get", """{"ticketId":"$first"}"""), "no ticket")

        // 4) the 8-char suffix the board prints resolves like ticket_save's.
        val second = create("delete me by suffix")
        val bySuffix = payload(agent.callTool("agenthq_ticket_delete", """{"ticketId":"${shortTicketId(second)}"}"""))
        assertEquals(second, bySuffix.str("ticketId"), "$bySuffix")
        assertEquals(setOf(keep), boardIds(), "the suffix deleted exactly that ticket")

        // 2) an unknown id is refused, and nothing is deleted.
        val before = board.list()
        assertRefused(agent.callTool("agenthq_ticket_delete", """{"ticketId":"zzzzzzzz-no-such-ticket"}"""), "no ticket")
        assertEquals(before, board.list(), "an unknown id leaves the board unchanged")

        // 3) a ticket in progress (a worker holds it) is refused with the way out, and nothing is deleted.
        board.move(TicketMove(keep, TicketStatus.IN_PROGRESS), startOnInProgress = false)
        val held = board.list()
        assertEquals(TicketStatus.IN_PROGRESS, held.single { it.id == keep }.status)
        assertRefused(agent.callTool("agenthq_ticket_delete", """{"ticketId":"$keep"}"""), "stop its worker first")
        assertEquals(held, board.list(), "an in-progress ticket leaves the board unchanged")
    }

    private companion object {
        const val PROJECT = "del-e2e-project"
    }
}
