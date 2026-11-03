// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli.mcp

import com.superiorsoftllc.infantry.cli.Cli
import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.SessionState
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P11-B: the WRITE tools of `infantry mcp serve` (spawn, prompt, message, kill) and their guardrails (depth,
 * concurrency, kill scope), driven over piped stdin/stdout through the REAL [Cli.run] against a REAL
 * in-process daemon on the stub provider.
 */
class McpWriteToolsTest {

    private val fixtures = mutableListOf<DaemonFixture>()
    private val servers = mutableListOf<Served>()
    private val pluginsRoot: File = com.superiorsoftllc.infantry.daemon.TestDaemonPlugins.root("official/Infantry_Board", "official/Infantry_Agents", "official/Infantry_Chat")

    @AfterTest
    fun stopAll() {
        servers.forEach { it.close() }
        fixtures.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
        pluginsRoot.deleteRecursively()
    }

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

    private fun serve(fixture: DaemonFixture, vararg flags: String, env: Map<String, String> = emptyMap()): Served {
        val toServer = PipedWriter()
        val serverIn = PipedReader(toServer, 1 shl 16)
        val fromServer = PipedReader(1 shl 16)
        val serverOut = PrintWriter(PipedWriter(fromServer))
        val stderr = StringBuffer()
        val served = Served(PrintWriter(toServer), BufferedReader(fromServer), stderr)
        val args = arrayOf("mcp", "serve", "--port", fixture.port.toString(), *flags)
        served.worker = thread(name = "mcp-serve", isDaemon = true) {
            Cli.run(args, serverOut, stderr, { env[it] }, { serverIn })
            serverOut.close()
        }
        servers += served
        served.request("initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"0"}}""")
        return served
    }

    private fun launch(): DaemonFixture =
        DaemonFixture.launch(StubScript.default(), null, Files.createTempDirectory("infantry-mcp-write-test").toFile(), pluginsRoot = pluginsRoot)
            .also { fixtures += it; it.client.await(Envelope.READY) }

    private fun payload(result: JsonObject): JsonObject {
        assertEquals("false", result["isError"]!!.jsonPrimitive.content, "tool reported an error: $result")
        return Json.parseToJsonElement(text(result)).jsonObject
    }

    private fun text(result: JsonObject): String =
        result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

    private fun assertRefused(result: JsonObject, contains: String) {
        assertEquals("true", result["isError"]!!.jsonPrimitive.content, "expected a refusal: $result")
        assertTrue(text(result).contains(contains), "refusal must say '$contains': ${text(result)}")
    }

    private fun spawn(served: Served, arguments: String): String =
        payload(served.callTool("infantry_spawn", arguments))["sessionId"]!!.jsonPrimitive.content

    @Test
    fun theWriteToolsAreListedWithWriteAnnotations() {
        val fixture = launch()
        val served = serve(fixture)
        val tools = served.request("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
            .associateBy { it["name"]!!.jsonPrimitive.content }
        for (name in listOf("infantry_spawn", "infantry_prompt", "infantry_message", "infantry_kill")) {
            val annotations = assertNotNull(tools[name], "tools/list names $name: ${tools.keys}")["annotations"]!!.jsonObject
            assertEquals("false", annotations["readOnlyHint"]!!.jsonPrimitive.content, "$name is a write tool")
        }
        assertEquals("true", tools["infantry_kill"]!!["annotations"]!!.jsonObject["destructiveHint"]!!.jsonPrimitive.content)
    }

    @Test
    fun spawnPromptMessageAndKillEachWorkAgainstTheDaemon() {
        val fixture = launch()
        val manager = fixture.daemon.manager
        val served = serve(fixture)

        // infantry_spawn: role-first, with a task -- the session is bound, runs the task, records depth 1.
        val spawned = payload(served.callTool("infantry_spawn", """{"role":"qa","task":"check the login flow"}"""))
        val id = spawned["sessionId"]!!.jsonPrimitive.content
        assertEquals("qa", manager.get(id)!!.role)
        assertEquals(1, spawned["depth"]!!.jsonPrimitive.int)
        fixture.await(what = "the spawned task to run") { manager.get(id)!!.runCount >= 1 && !manager.get(id)!!.live }

        // infantry_prompt: by unique prefix, a second turn on the same session.
        val prompted = payload(served.callTool("infantry_prompt", """{"sessionId":"${id.take(8)}","prompt":"and logout"}"""))
        assertEquals(id, prompted["sessionId"]!!.jsonPrimitive.content)
        fixture.await(what = "the prompt to run") { manager.get(id)!!.runCount >= 2 && !manager.get(id)!!.live }

        // infantry_message: mailbox mail from the human handle to the session.
        val mail = payload(served.callTool("infantry_message", """{"to":"$id","body":"status please"}"""))
        assertEquals(id, mail["to"]!!.jsonPrimitive.content)
        assertTrue(mail["messageId"]!!.jsonPrimitive.content.isNotBlank(), "$mail")

        // infantry_kill: a session this server spawned.
        val killed = payload(served.callTool("infantry_kill", """{"sessionId":"$id","force":true}"""))
        assertEquals(id, killed["sessionId"]!!.jsonPrimitive.content)
        fixture.await(what = "the killed session to stop") { !manager.get(id)!!.live }
    }

    @Test
    fun aSpawnBeyondTheMaxDepthIsRefused() {
        val fixture = launch()
        val manager = fixture.daemon.manager

        // Depth from the flag: a server already at the maximum may not spawn.
        val atMax = serve(fixture, "--mcp-depth", "2")
        assertRefused(atMax.callTool("infantry_spawn", """{"role":"qa"}"""), "depth limit")
        assertTrue(manager.list().isEmpty(), "a refused spawn creates nothing: ${manager.list()}")

        // RUNTIME-LOAD-7: the client's values only NARROW the daemon's limits. A larger --max-depth does not lift the daemon's maximum...
        val wider = serve(fixture, "--mcp-depth", "2", "--max-depth", "3")
        assertRefused(wider.callTool("infantry_spawn", """{"role":"qa"}"""), "depth limit")

        // ...a smaller one lowers it: a root server with --max-depth 0 may not spawn at all; with 1 its child sits at depth 1.
        assertRefused(serve(fixture, "--max-depth", "0").callTool("infantry_spawn", """{"role":"qa"}"""), "depth limit")
        val child = payload(serve(fixture, "--max-depth", "1").callTool("infantry_spawn", """{"role":"qa"}"""))
        assertEquals(1, child["depth"]!!.jsonPrimitive.int)
    }

    @Test
    fun spawnsBeyondTheConcurrencyLimitAreRefusedUntilOneEnds() {
        val fixture = launch()
        val served = serve(fixture, "--max-spawns", "2")

        val first = spawn(served, """{"role":"qa"}""")
        spawn(served, """{"role":"qa"}""")
        assertRefused(served.callTool("infantry_spawn", """{"role":"qa"}"""), "concurrency limit")
        assertEquals(2, fixture.daemon.manager.list().size, "the refused spawn created nothing")

        payload(served.callTool("infantry_kill", """{"sessionId":"$first"}"""))
        spawn(served, """{"role":"qa"}""")
        assertEquals(3, fixture.daemon.manager.list().size)
    }

    /**
     * BUG-KILL-IDLE-1: a session this server killed gives its slot back for good, even once it is no longer ENDED.
     * Prompted back to life after the kill, it holds a slot only while its turn is live; when that turn ends IDLE
     * (not live, not ENDED) only the server's own `released` record keeps it from counting again.
     */
    @Test
    fun aKilledSessionRevivedToIdleDoesNotHoldASlotAgain() {
        val fixture = launch()
        val manager = fixture.daemon.manager
        val served = serve(fixture, "--max-spawns", "1")

        // A first turn, so the provider session id the revive resumes is observed; then the helper is at rest.
        val first = spawn(served, """{"role":"qa","task":"first turn"}""")
        fixture.await(what = "the first turn to end") { manager.get(first)!!.let { it.runCount >= 1 && !it.live } }
        val killed = payload(served.callTool("infantry_kill", """{"sessionId":"$first"}"""))
        assertEquals("EXITED", killed["status"]!!.jsonPrimitive.content, "the kill ends the idle session: $killed")

        payload(served.callTool("infantry_prompt", """{"sessionId":"$first","prompt":"one more turn"}"""))
        fixture.await(what = "the revived session's turn to end") {
            manager.get(first)!!.let { it.runCount >= 2 && !it.live }
        }
        assertEquals(SessionState.IDLE, manager.get(first)!!.state, "precondition: the killed session is back, not ENDED")

        spawn(served, """{"role":"qa"}""")
        assertEquals(2, manager.list().size)
    }

    @Test
    fun killIsLimitedToOwnSpawnsForASessionButNotForTheOwner() {
        val fixture = launch()
        fixture.send(Envelope(Envelope.NEW, """{"role":"qa"}"""))
        fixture.await(what = "a session not spawned through MCP") { fixture.daemon.manager.list().isNotEmpty() }
        val foreign = fixture.daemon.manager.list().single().id
        fixture.send(Envelope(Envelope.NEW, """{"role":"backend"}"""))
        fixture.await(what = "the calling worker") { fixture.daemon.manager.list().size == 2 }
        val worker = fixture.daemon.manager.list().single { it.id != foreign }.id

        // Contract 1.114.0: a worker (not pm / owner) may only kill what it spawned.
        val scoped = serve(fixture, env = mapOf("INFANTRY_SESSION_ID" to worker))
        assertRefused(scoped.callTool("infantry_kill", """{"sessionId":"$foreign"}"""), "was not spawned by this session")

        // --allow-kill-any is moot now: the connection of a session is held to the rule whatever the flag says; an owner connection (no session) kills any.
        assertRefused(serve(fixture, "--allow-kill-any", env = mapOf("INFANTRY_SESSION_ID" to worker)).callTool("infantry_kill", """{"sessionId":"$foreign"}"""), "was not spawned by this session")
        val owner = serve(fixture)
        assertEquals(foreign, payload(owner.callTool("infantry_kill", """{"sessionId":"$foreign"}"""))["sessionId"]!!.jsonPrimitive.content)
        assertFalse(fixture.daemon.manager.get(foreign)!!.live)
    }
}
