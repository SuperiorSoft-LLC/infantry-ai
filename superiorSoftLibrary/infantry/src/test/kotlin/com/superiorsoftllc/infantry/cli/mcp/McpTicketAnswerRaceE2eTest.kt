package com.superiorsoftllc.infantry.cli.mcp

import com.superiorsoftllc.infantry.daemon.requireBoard

import com.superiorsoftllc.infantry.cli.Cli
import com.superiorsoftllc.infantry.cli.contractAtLeast
import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.daemon.saveProfile
import com.superiorsoftllc.infantry.orchestration.GatedRunner
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.REQUEST_ID_CONTRACT
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * BUG-MCP-RACE-1: `infantry_ticket_create` / `infantry_ticket_update` wait for the daemon's `ticket` answer to their
 * `ticket_save`. Every board change is ALSO pushed to every client as a `ticket` frame, so while a started ticket A is
 * being pushed (its run, or anyone editing it), a save for B that matched its answer by TYPE could take A's push as
 * its answer and hand the calling agent A's id -- which it then updates, starts or deletes.
 *
 * The rig: a REAL daemon, the REAL `infantry mcp serve` entrance, ticket A started through MCP (its gated run held
 * open), and a second client pushing `ticket` frames for A continuously -- the PM/orchestrator case, where other
 * tickets change while an agent files new ones. Every create and update through MCP must answer with ITS ticket.
 */
class McpTicketAnswerRaceE2eTest {

    private val dirs = mutableListOf<File>()
    private val fixtures = mutableListOf<DaemonFixture>()
    private val servers = mutableListOf<Served>()
    private val stopPushing = AtomicBoolean(false)
    private val pushers = mutableListOf<Thread>()

    @AfterTest
    fun cleanUp() {
        stopPushing.set(true)
        pushers.forEach { it.join(5_000) }
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
        val lineage = tempDir("infantry-mcp-race-lineage")
        val args = arrayOf("mcp", "serve", "--port", fixture.port.toString())
        served.worker = thread(name = "mcp-serve-race-e2e", isDaemon = true) {
            Cli.run(args, serverOut, stderr, { null }, { serverIn }, cwd = cwd)
            serverOut.close()
        }
        servers += served
        served.request(
            "initialize",
            """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"race-e2e","version":"0"}}""",
        )
        return served
    }

    private fun text(result: JsonObject): String =
        result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

    private fun payload(result: JsonObject): JsonObject {
        assertEquals("false", result["isError"]!!.jsonPrimitive.content, "tool reported an error: ${text(result)}")
        return Json.parseToJsonElement(text(result)).jsonObject
    }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    @Test
    fun ticketCreateAndUpdateAnswerWithTheirOwnTicketWhileAStartedTicketIsBeingPushed() {
        val gated = GatedRunner()
        val state = tempDir("infantry-mcp-race-daemon")
        val fixture = DaemonFixture.launch(stateDir = state, runner = gated, workingDir = File(state, "workdir").apply { mkdirs() }, pluginsRoot = com.superiorsoftllc.infantry.daemon.TestDaemonPlugins.boardRoot())
            .also { fixtures += it; it.client.await(Envelope.READY) }
        val board = fixture.daemon.requireBoard()
        val root = tempDir("infantry-mcp-race-project")
        fixture.client.saveProfile("""{"id":"backend-1","name":"Backend","role":"backend"}""")
        val agent = serve(fixture, root)

        // Ticket A, started: its gated run stays open, so A stays in progress.
        val a = payload(agent.callTool("infantry_ticket_create", """{"title":"Ticket A","role":"backend"}""")).str("ticketId")
        val started = agent.callTool("infantry_ticket_start", """{"ticketId":"$a"}""")
        assertEquals("false", started["isError"]!!.jsonPrimitive.content, "start must succeed: ${text(started)}")
        fixture.await(what = "A's worker run") { gated.prompts.isNotEmpty() }

        // A is pushed continuously to EVERY client, the MCP server's connections included.
        val pushes = AtomicInteger()
        pushers += thread(name = "ticket-a-pusher", isDaemon = true) {
            while (!stopPushing.get()) {
                val n = pushes.incrementAndGet()
                fixture.send(Envelope.TICKET_SAVE, """{"id":"$a","description":${JsonPrimitive("push $n")}}""")
                Thread.sleep(1)
            }
        }
        fixture.await(what = "A's pushes flowing") { pushes.get() > 20 }

        repeat(ROUNDS) { round ->
            val title = "Ticket B$round"
            val created = payload(agent.callTool("infantry_ticket_create", """{"title":"$title"}"""))
            val b = created.str("ticketId")
            assertNotEquals(a, b, "round $round: infantry_ticket_create answered with the STARTED ticket A's id")
            assertEquals(title, board.get(b).title, "round $round: the id infantry_ticket_create returned is not the new ticket")
            assertEquals(title, created.str("title"), "round $round: the create answer describes another ticket: $created")

            val renamed = "$title renamed"
            val updated = payload(agent.callTool("infantry_ticket_update", """{"ticketId":"$b","title":"$renamed"}"""))
            assertEquals(b, updated.str("ticketId"), "round $round: infantry_ticket_update answered with another ticket's id")
            assertEquals(renamed, updated.str("title"), "round $round: the update answer describes another ticket: $updated")
        }

        stopPushing.set(true)
        assertTrue(pushes.get() > ROUNDS, "A must really have been pushed during the saves (${pushes.get()} pushes)")
        assertEquals("Ticket A", board.get(a).title, "A was never renamed by a save meant for B")
    }

    private companion object {
        const val ROUNDS = 10
    }
}

/** BUG-MCP-RACE-1: the version gate deciding whether a request is correlated or matched by type (older daemon). */
class ContractAtLeastTest {
    @Test
    fun comparesNumericallyAndTreatsUnknownAsOld() {
        val at = ::contractAtLeast
        assertTrue(at("1.87.0", REQUEST_ID_CONTRACT))
        assertTrue(at("1.102.0", REQUEST_ID_CONTRACT), "1.100 is newer than 1.87, not older (no string compare)")
        assertTrue(at("2.0.0", REQUEST_ID_CONTRACT))
        assertTrue(!at("1.86.9", REQUEST_ID_CONTRACT), "a 1.86 daemon cannot echo a requestId")
        assertTrue(!at("1.9.0", REQUEST_ID_CONTRACT))
        assertTrue(!at("dev", REQUEST_ID_CONTRACT), "an unparseable contract is treated as an old daemon")
    }
}
