// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli.mcp

import com.superiorsoftllc.infantry.daemon.requireBoard

import com.superiorsoftllc.infantry.cli.Cli
import com.superiorsoftllc.infantry.cli.shortTicketId
import com.superiorsoftllc.infantry.daemon.DaemonFixture
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
import kotlin.test.assertTrue

/**
 * BUG-REORDER (ticket-63df1aba): an agent sets a ticket's place in its column (order = priority) with
 * `infantry_ticket_move` `before` / `after` / `top` / `bottom` / `position`, through the REAL `infantry mcp serve` entrance against a real
 * in-process daemon ([DaemonFixture]), reads the order back from the daemon's board, and finds it again after the
 * daemon restarts on the same state.
 */
class McpTicketMoveE2eTest {

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
        val lineage = tempDir("infantry-mcp-move-lineage")
        val args = arrayOf("mcp", "serve", "--port", fixture.port.toString())
        served.worker = thread(name = "mcp-serve-move-e2e", isDaemon = true) {
            Cli.run(args, serverOut, stderr, { null }, { serverIn }, cwd = cwd)
            serverOut.close()
        }
        servers += served
        served.request(
            "initialize",
            """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"move-e2e","version":"0"}}""",
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
    fun anAgentReordersTicketsInsideAColumnThroughMcpServe() {
        val stateDir = tempDir("infantry-mcp-move-daemon")
        val fixture = DaemonFixture.launch(stateDir = stateDir, pluginsRoot = com.superiorsoftllc.infantry.daemon.TestDaemonPlugins.boardRoot()).also { fixtures += it; it.client.await(Envelope.READY) }
        val project = tempDir("infantry-mcp-move-project")
        McpServeConfig.fileIn(project).apply { parentFile.mkdirs() }.writeText("""{"projectId":"$PROJECT"}""")
        val agent = serve(fixture, project)

        val ids = listOf("A", "B", "C", "D").associateWith { title ->
            payload(agent.callTool("infantry_ticket_create", """{"title":"$title"}""")).str("ticketId")
        }
        val titles = ids.entries.associate { (k, v) -> v to k }
        fun todo(board: List<com.superiorsoftllc.infantry.protocol.Ticket>) =
            board.filter { it.status == TicketStatus.TODO }.sortedBy { it.position }.map { titles.getValue(it.id) }
        fun todo() = todo(fixture.daemon.requireBoard().list())
        assertEquals(listOf("A", "B", "C", "D"), todo())

        // top, with no status: reordered in its own column, the answer reads back the stored place.
        val topped = payload(agent.callTool("infantry_ticket_move", """{"ticketId":"${ids["C"]}","top":true}"""))
        assertEquals(TicketStatus.TODO, topped.str("status"))
        assertEquals("0", topped.str("position"), "$topped")
        assertEquals(listOf("C", "A", "B", "D"), todo())

        // bottom, by the 8-char suffix.
        payload(agent.callTool("infantry_ticket_move", """{"ticketId":"${shortTicketId(ids.getValue("C"))}","bottom":true}"""))
        assertEquals(listOf("A", "B", "D", "C"), todo())

        // before, moving DOWN (the lifted-out index) and UP, the target named by its suffix.
        payload(agent.callTool("infantry_ticket_move", """{"ticketId":"${ids["A"]}","before":"${shortTicketId(ids.getValue("C"))}"}"""))
        assertEquals(listOf("B", "D", "A", "C"), todo())
        payload(agent.callTool("infantry_ticket_move", """{"ticketId":"${ids["C"]}","status":"todo","before":"${ids["B"]}"}"""))
        assertEquals(listOf("C", "B", "D", "A"), todo())

        // after (just below) and a raw position (0 = top).
        payload(agent.callTool("infantry_ticket_move", """{"ticketId":"${ids["C"]}","after":"${ids["A"]}"}"""))
        assertEquals(listOf("B", "D", "A", "C"), todo())
        payload(agent.callTool("infantry_ticket_move", """{"ticketId":"${ids["A"]}","position":0}"""))
        assertEquals(listOf("A", "B", "D", "C"), todo())
        payload(agent.callTool("infantry_ticket_move", """{"ticketId":"${ids["C"]}","status":"todo","position":1}"""))
        assertEquals(listOf("A", "C", "B", "D"), todo())

        // A plain status move still works and lands at the bottom of the new column.
        payload(agent.callTool("infantry_ticket_move", """{"ticketId":"${ids["D"]}","status":"done"}"""))
        assertEquals(listOf("A", "C", "B"), todo())

        // Refusals change nothing.
        val before = fixture.daemon.requireBoard().list()
        assertRefused(agent.callTool("infantry_ticket_move", """{"ticketId":"${ids["A"]}"}"""), "needs a status")
        assertRefused(agent.callTool("infantry_ticket_move", """{"ticketId":"${ids["A"]}","top":true,"bottom":true}"""), "one of before, after, top, bottom, position")
        assertRefused(agent.callTool("infantry_ticket_move", """{"ticketId":"${ids["A"]}","before":"${ids["A"]}"}"""), "before itself")
        assertRefused(agent.callTool("infantry_ticket_move", """{"ticketId":"${ids["A"]}","before":"${ids["D"]}"}"""), "is in done, not todo")
        assertRefused(agent.callTool("infantry_ticket_move", """{"ticketId":"${ids["A"]}","before":"zzzzzzzz-no-such-ticket"}"""), "no ticket")
        assertRefused(agent.callTool("infantry_ticket_move", """{"ticketId":"${ids["A"]}","position":-1}"""), "position must be 0 or more")
        assertRefused(agent.callTool("infantry_ticket_move", """{"ticketId":"${ids["A"]}","position":"x"}"""), "whole number")
        assertEquals(before, fixture.daemon.requireBoard().list(), "a refused move leaves the board unchanged")

        // The order is stored: a daemon booted again on the same state lists it.
        agent.close()
        fixture.stop()
        val again = DaemonFixture.launch(stateDir = stateDir, pluginsRoot = com.superiorsoftllc.infantry.daemon.TestDaemonPlugins.boardRoot()).also { fixtures += it; it.client.await(Envelope.READY) }
        assertEquals(listOf("A", "C", "B"), todo(again.daemon.requireBoard().list()), "the order survives a restart")
    }

    private companion object {
        const val PROJECT = "move-e2e-project"
    }
}
