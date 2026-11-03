// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli.mcp

import com.superiorsoftllc.infantry.cli.Cli
import com.superiorsoftllc.infantry.cli.shortTicketId
import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.protocol.Envelope
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BUG ticket-91a3d988: the board printed `ticket-1` (the id cut to 8 characters INCLUDING the `ticket-` scheme), so a
 * board line could not be addressed. Through the REAL `infantry mcp serve` entrance against a real daemon: every board
 * line starts with the 8-char short id, that short id round-trips into infantry_ticket_get, a shorter head prefix
 * (with or without `ticket-`) resolves when unique, and an ambiguous one lists every candidate id and title.
 *
 * (Harness below follows MCP-DEL-1: an agent deletes a ticket through the REAL `infantry mcp serve` entrance ([Cli.run] over its stdin/stdout
 * seam, newline-delimited JSON-RPC 2.0) against a real in-process daemon ([DaemonFixture]) -- the same path an agent's
 * MCP client takes, not a fake stream.
 *
 * Covers: create then delete by full id (gone from the board and from infantry_ticket_get), delete by the 8-char
 * suffix, an unknown id refused with the board unchanged, and an in-progress ticket refused with the board unchanged.
 */
class McpBoardShortIdE2eTest {

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
        val lineage = tempDir("infantry-mcp-board-lineage")
        val args = arrayOf("mcp", "serve", "--port", fixture.port.toString())
        served.worker = thread(name = "mcp-serve-board-e2e", isDaemon = true) {
            Cli.run(args, serverOut, stderr, { null }, { serverIn }, cwd = cwd)
            serverOut.close()
        }
        servers += served
        served.request(
            "initialize",
            """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"board-e2e","version":"0"}}""",
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
    fun boardLinesCarryTheShortIdAndItRoundTripsIntoTicketGet() {
        val fixture = DaemonFixture.launch(stateDir = tempDir("infantry-mcp-board-daemon"), pluginsRoot = com.superiorsoftllc.infantry.daemon.TestDaemonPlugins.boardRoot()).also { fixtures += it; it.client.await(Envelope.READY) }
        val project = tempDir("infantry-mcp-board-project")
        McpServeConfig.fileIn(project).apply { parentFile.mkdirs() }.writeText("""{"projectId":"$PROJECT"}""")
        val agent = serve(fixture, project)

        val longTitle = "L".repeat(150)
        // Seventeen tickets: sixteen hex heads, so two MUST share their first tail character (pigeonhole).
        val ids = (0..16).map { i ->
            val title = if (i == 0) longTitle else "Board ticket $i"
            payload(agent.callTool("infantry_ticket_create", """{"title":"$title"}""")).str("ticketId")
        }

        val lines = payload(agent.callTool("infantry_board", """{"limit":100}""")).getValue("board").jsonArray.map { it.jsonPrimitive.content }
        for (id in ids) {
            val short = shortTicketId(id)
            val line = assertNotNull(lines.singleOrNull { it.trimStart().startsWith("$short | ") }, "exactly one board line must start with $short: $lines")
            assertTrue(!line.contains("ticket-"), "the board line carries the short id, not a cut full id: $line")
            val got = payload(agent.callTool("infantry_ticket_get", """{"ticketId":"$short"}"""))
            assertEquals(id, got.str("ticketId"), "the board's short id must round-trip into ticket_get")
        }
        val longLine = lines.single { it.trimStart().startsWith(shortTicketId(ids[0])) }
        assertTrue(longLine.contains("L".repeat(97) + "...") && !longLine.contains("L".repeat(98)), longLine)

        val byHead = ids.groupBy { it.removePrefix("ticket-").take(1) }
        val (sharedHead, colliding) = byHead.entries.first { it.value.size >= 2 }
        for (typed in listOf(sharedHead, "ticket-$sharedHead")) {
            val refusal = agent.callTool("infantry_ticket_get", """{"ticketId":"$typed"}""")
            assertRefused(refusal, "ambiguous")
            for (id in colliding) assertTrue(text(refusal).contains(id), "the ambiguity must list $id: ${text(refusal)}")
            assertTrue(text(refusal).contains("Board ticket") || text(refusal).contains("LLLL"), "it lists the titles: ${text(refusal)}")
        }
        // A head prefix that only one ticket carries resolves, with or without the scheme.
        val unique = ids.first { id -> ids.count { it.removePrefix("ticket-").startsWith(id.removePrefix("ticket-").take(3)) } == 1 }
        val head3 = unique.removePrefix("ticket-").take(3)
        assertEquals(unique, payload(agent.callTool("infantry_ticket_get", """{"ticketId":"$head3"}""")).str("ticketId"))
        assertEquals(unique, payload(agent.callTool("infantry_ticket_get", """{"ticketId":"ticket-$head3"}""")).str("ticketId"))
    }

    private companion object {
        const val PROJECT = "board-e2e-project"
    }
}
