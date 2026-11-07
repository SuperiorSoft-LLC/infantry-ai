// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
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
import kotlin.test.assertTrue

/**
 * MAIL-MCP-1: an agent reads its mail through its own `infantry mcp serve`, not only through the CLI.
 *
 * A real in-process daemon on a scripted provider and the REAL [Cli.run] serving MCP over its stdin/stdout seam, one
 * server per session the way the daemon wires them (`INFANTRY_SESSION_ID`): the worker mails its PM with
 * `infantry_message`, the PM lists that mail with `infantry_inbox` and reads its body with `infantry_read`.
 */
class McpMailToolsE2eTest {

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

    private fun serve(fixture: DaemonFixture, cwd: File, sessionId: String? = null): Served {
        val toServer = PipedWriter()
        val serverIn = PipedReader(toServer, 1 shl 16)
        val fromServer = PipedReader(1 shl 16)
        val serverOut = PrintWriter(PipedWriter(fromServer))
        val stderr = StringBuffer()
        val served = Served(PrintWriter(toServer), BufferedReader(fromServer), stderr)
        val env = mapOf("INFANTRY_SESSION_ID" to sessionId)
        val args = arrayOf("mcp", "serve", "--port", fixture.port.toString())
        served.worker = thread(name = "mcp-serve-mail", isDaemon = true) {
            Cli.run(args, serverOut, stderr, { env[it] }, { serverIn }, cwd = cwd)
            serverOut.close()
        }
        servers += served
        served.request(
            "initialize",
            """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"mail-e2e","version":"0"}}""",
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

    /**
     * The inbox tool answers {unread, total, shown, items:["<id> | <from> | <subject> | <state>"], hint} (token-lean listing);
     * each item line is turned back into an object so the assertions name its columns.
     */
    private fun messages(answer: JsonObject) = answer["items"]!!.jsonArray.map { item ->
        val (id, from, subject, state) = item.jsonPrimitive.content.split(" | ")
        kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(id))
            put("from", kotlinx.serialization.json.JsonPrimitive(from))
            put("subject", kotlinx.serialization.json.JsonPrimitive(subject))
            put("read", kotlinx.serialization.json.JsonPrimitive((state == "READ").toString()))
        }
    }

    @Test
    fun aWorkerMailsItsPmAndThePmReadsItThroughMcp() {
        val script = StubScript(
            stdoutLines = listOf(
                """{"type":"step_start","sessionID":"scripted-provider-session"}""",
                """{"type":"text","text":"ok"}""",
                """{"type":"step_finish"}""",
            ),
        )
        val fixture = DaemonFixture.launch(script, ScriptedRunner(script), tempDir("infantry-mail-mcp-daemon"), pluginsRoot = com.superiorsoftllc.infantry.daemon.TestDaemonPlugins.fullRoot())
            .also { fixtures += it; it.client.await(Envelope.READY) }
        val manager = fixture.daemon.manager
        val project = tempDir("infantry-mail-mcp-project")

        // Two sessions on the daemon: the PM and its worker.
        val orchestrator = serve(fixture, project)
        val pm = payload(orchestrator.callTool("infantry_spawn", """{"role":"qa"}""")).str("sessionId")
        val worker = payload(orchestrator.callTool("infantry_spawn", """{"role":"qa"}""")).str("sessionId")

        val pmServer = serve(fixture, project, sessionId = pm)
        val tools = pmServer.request("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject.str("name") }.toSet()
        assertTrue(INBOX in tools && READ in tools, "the PM's MCP serves $INBOX and $READ: $tools")

        // Nothing yet.
        assertEquals(emptyList(), messages(payload(pmServer.callTool(INBOX))), "a fresh PM has no mail")

        // The worker mails its PM through its own MCP.
        val workerServer = serve(fixture, project, sessionId = worker)
        // A write answers the ack line `ok <id8> <STATE>` (limits.mcp.defaultDetail 0); the sender is checked on the PM's inbox row.
        val ack = text(workerServer.callTool("infantry_message", """{"to":"$pm","subject":"$SUBJECT","body":"$BODY"}""")).split(" ")
        assertEquals("ok", ack[0], "the send was acknowledged: $ack")
        val idPrefix = ack[1]
        val messageId = messages(payload(pmServer.callTool(INBOX))).single { it.str("id").startsWith(idPrefix) }.str("id")
        fixture.await(what = "the mail to reach the PM's console") { manager.get(pm)!!.runCount >= 1 && !manager.get(pm)!!.live }

        // The PM's inbox lists it, unread.
        val unread = messages(payload(pmServer.callTool(INBOX, """{"unreadOnly":true}""")))
        val row = unread.singleOrNull { it.str("id") == messageId } ?: error("the worker's mail is not in the PM's unread inbox: $unread")
        assertEquals(worker, row.str("from"))
        assertEquals(SUBJECT, row.str("subject"))
        assertEquals("false", row.str("read"), "listed before it is read: $row")

        // infantry_read by the 8-char prefix returns the body and marks it read.
        val read = payload(pmServer.callTool(READ, """{"messageId":"${messageId.take(8)}"}"""))
        assertEquals(messageId, read.str("id"))
        assertEquals(BODY, read.str("body"))
        assertEquals(worker, read.str("from"))
        assertEquals("true", read.str("read"), "read marks it read: $read")

        // Read mail leaves the unread listing but stays in the full one.
        assertTrue(messages(payload(pmServer.callTool(INBOX, """{"unreadOnly":true}"""))).none { it.str("id") == messageId })
        assertEquals("true", messages(payload(pmServer.callTool(INBOX))).single { it.str("id") == messageId }.str("read"))

        // Another session's inbox is not the PM's: the worker has no mail, and cannot read the PM's.
        assertEquals(emptyList(), messages(payload(workerServer.callTool(INBOX))), "the worker's inbox is its own")
        assertRefused(workerServer.callTool(READ, """{"messageId":"$messageId"}"""), "no such message")
        assertRefused(pmServer.callTool(READ, """{"messageId":"zzzzzzzz"}"""), "no such message")
        assertRefused(pmServer.callTool(READ), "messageId")
    }

    private companion object {
        const val INBOX = "infantry_inbox"
        const val READ = "infantry_read"
        const val SUBJECT = "report"
        const val BODY = "done: branch ticket/abc on base 1234"
    }
}
