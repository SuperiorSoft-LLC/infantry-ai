// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli.mcp

import com.superiorsoftllc.infantry.daemon.requireBoard

import com.superiorsoftllc.infantry.cli.Cli
import com.superiorsoftllc.infantry.cli.shortTicketId
import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.TicketMove
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
import kotlin.test.assertTrue

/**
 * MCP-DEL-1: an agent deletes a ticket through the REAL `infantry mcp serve` entrance ([Cli.run] over its stdin/stdout
 * seam, newline-delimited JSON-RPC 2.0) against a real in-process daemon ([DaemonFixture]) -- the same path an agent's
 * MCP client takes, not a fake stream.
 *
 * Covers: create then delete by full id (gone from the board and from infantry_ticket_get), delete by the 8-char
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
        val lineage = tempDir("infantry-mcp-del-lineage")
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

    /** The delete tool answers terse text (`ok <short> deleted`), never JSON. */
    private fun deletedOk(result: JsonObject): String {
        assertEquals("false", result["isError"]!!.jsonPrimitive.content, "tool reported an error: ${text(result)}")
        return text(result).also { assertTrue(it.contains("deleted"), it) }
    }

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
        val fixture = DaemonFixture.launch(stateDir = tempDir("infantry-mcp-del-daemon"), pluginsRoot = com.superiorsoftllc.infantry.daemon.TestDaemonPlugins.boardRoot()).also { fixtures += it; it.client.await(Envelope.READY) }
        val board = fixture.daemon.requireBoard()
        val project = tempDir("infantry-mcp-del-project")
        McpServeConfig.fileIn(project).apply { parentFile.mkdirs() }.writeText("""{"projectId":"$PROJECT"}""")
        val agent = serve(fixture, project)

        fun create(title: String): String =
            payload(agent.callTool("infantry_ticket_create", """{"title":"$title"}""")).str("ticketId")
        fun boardIds() = board.list().map { it.id }.toSet()

        // 1) create, delete by full id: the answer names it, the board no longer holds it, ticket_get refuses it.
        val first = create("delete me by full id")
        val keep = create("stays on the board")
        assertTrue(first in boardIds() && keep in boardIds(), "both tickets were created: ${boardIds()}")
        val deleted = deletedOk(agent.callTool("infantry_ticket_delete", """{"ticketId":"$first"}"""))
        assertTrue(deleted.contains(shortTicketId(first)), deleted)
        assertEquals(setOf(keep), boardIds(), "only the named ticket was deleted")
        assertRefused(agent.callTool("infantry_ticket_get", """{"ticketId":"$first"}"""), "no ticket")

        // 4) the 8-char suffix the board prints resolves like ticket_save's.
        val second = create("delete me by suffix")
        val bySuffix = deletedOk(agent.callTool("infantry_ticket_delete", """{"ticketId":"${shortTicketId(second)}"}"""))
        assertTrue(bySuffix.contains(shortTicketId(second)), bySuffix)
        assertEquals(setOf(keep), boardIds(), "the suffix deleted exactly that ticket")

        // 2) an unknown id is refused, and nothing is deleted.
        val before = board.list()
        assertRefused(agent.callTool("infantry_ticket_delete", """{"ticketId":"zzzzzzzz-no-such-ticket"}"""), "no ticket")
        assertEquals(before, board.list(), "an unknown id leaves the board unchanged")

        // 3) a ticket in progress (a worker holds it) is refused with the way out, and nothing is deleted.
        // the deprecated story_move shim files a ticket in progress with no worker (the board's own start is not under test here)
        fixture.client.send(Envelope.STORY_MOVE, """{"id":"$keep","status":"doing"}""")
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until && !(board.list().single { it.id == keep }.status == TicketStatus.IN_PROGRESS)) Thread.sleep(50)
        val held = board.list()
        assertEquals(TicketStatus.IN_PROGRESS, held.single { it.id == keep }.status)
        assertRefused(agent.callTool("infantry_ticket_delete", """{"ticketId":"$keep"}"""), "stop its worker first")
        assertEquals(held, board.list(), "an in-progress ticket leaves the board unchanged")
    }

    private companion object {
        const val PROJECT = "del-e2e-project"
    }
}
