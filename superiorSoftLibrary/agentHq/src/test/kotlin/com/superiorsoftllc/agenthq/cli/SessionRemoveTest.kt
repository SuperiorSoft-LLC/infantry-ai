package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.daemon.awaitAfter
import com.superiorsoftllc.agenthq.daemon.mark
import com.superiorsoftllc.agenthq.daemon.moveTicket
import com.superiorsoftllc.agenthq.daemon.saveProfile
import com.superiorsoftllc.agenthq.daemon.saveTicket
import com.superiorsoftllc.agenthq.orchestration.GatedRunner
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.session.SessionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.PipedReader
import java.io.PipedWriter
import java.io.PrintWriter
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SESSION-RM-1: `agenthq session rm <id>` and the MCP tool `agenthq_session_remove` take an ENDED session off the
 * daemon's session list (the sidebar), and refuse a live one. Nothing is removed automatically: a ticket worker
 * that has ended after its ticket is done stays listed until it is removed by hand. Real [Cli.run] against a real
 * in-process daemon; [GatedRunner] holds a run open on "HANG" so a session can be live on cue.
 */
class SessionRemoveTest {

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

    private fun launch(): DaemonFixture =
        DaemonFixture.launch(runner = GatedRunner(holdMs = 50), stateDir = Files.createTempDirectory("agenthq-session-rm").toFile())
            .also { fixtures += it; it.client.await(Envelope.READY) }

    private data class Result(val exit: Int, val out: String, val err: String)

    private fun cli(fixture: DaemonFixture, vararg args: String): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf(*args, "--port", fixture.port.toString()), out, err, CliTestEnv.getenv)
        return Result(exit, out.toString(), err.toString())
    }

    /** A backend ticket started on the board; its worker's session id. [body] is the worker's first prompt. */
    private fun startTicketWorker(fixture: DaemonFixture, body: String): Pair<String, String> {
        fixture.client.saveProfile("""{"id":"backend","name":"Backend","role":"backend"}""")
        val ticket = fixture.client.saveTicket("""{"title":"Ship it","description":"$body","agentId":"backend"}""")
        val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
        assertEquals("started", started.start?.reason, "the ticket must start: ${started.start?.detail}")
        return ticket.id to started.sessionId!!
    }

    /** A long-lived role agent (no ticket), at rest after its first turn. */
    private fun roleAgent(fixture: DaemonFixture, role: String): String {
        val before = fixture.daemon.manager.list().map { it.id }.toSet()
        fixture.send(Envelope(Envelope.NEW, """{"role":"$role"}"""))
        fixture.await(what = "the $role session") { fixture.daemon.manager.list().any { it.id !in before } }
        return fixture.daemon.manager.list().single { it.id !in before }.id
    }

    private fun killAndAwaitExited(fixture: DaemonFixture, id: String) {
        fixture.send(Envelope(Envelope.KILL, "$id force"))
        fixture.await(what = "session $id to end") { fixture.daemon.manager.get(id)?.state == SessionState.EXITED }
    }

    @Test
    fun anEndedTicketWorkerStaysListedAfterItsTicketIsDoneUntilSessionRmRemovesItAndRoleAgentsStay() {
        val fixture = launch()
        val manager = fixture.daemon.manager
        val pm = roleAgent(fixture, "pm")
        val (ticketId, worker) = startTicketWorker(fixture, "do the work")
        fixture.await(what = "the worker's turn to end") { manager.get(worker)!!.let { it.runCount >= 1 && !it.live } }
        fixture.client.moveTicket(ticketId, TicketStatus.DONE)
        killAndAwaitExited(fixture, worker)

        // Nothing is removed automatically: the ticket is done and the worker has exited, and it is still listed.
        Thread.sleep(300)
        assertEquals(ticketId, assertNotNull(manager.get(worker), "an ended worker is never removed automatically").ticketId)
        assertTrue(cli(fixture, "pods").out.contains(worker.take(8)), "pods still lists the exited worker")

        val removed = cli(fixture, "session", "rm", worker.take(8))
        assertEquals(0, removed.exit, "session rm must succeed: ${removed.err}")
        assertTrue(removed.out.contains("removed $worker"), removed.out)
        assertNull(manager.get(worker), "the session left the daemon's list")

        // The role agent is untouched, and the ticket's record is kept (with the worker it ran on).
        assertNotNull(manager.get(pm), "a role agent is not touched by removing another session")
        val shown = cli(fixture, "ticket", "show", ticketId)
        assertEquals(0, shown.exit, "the ticket is kept: ${shown.err}")
        assertTrue(shown.out.contains("STATUS: done"), shown.out)
    }

    @Test
    fun sessionRmRefusesALiveSessionWithAClearMessageAndRemovesNothing() {
        val fixture = launch()
        val manager = fixture.daemon.manager
        val (_, worker) = startTicketWorker(fixture, "HANG")
        fixture.await(what = "the worker to be running") { manager.get(worker)!!.live }

        val refused = cli(fixture, "session", "rm", worker)
        assertEquals(1, refused.exit, "a live session is refused: ${refused.out}")
        assertTrue(refused.err.contains("is still running") && refused.err.contains("kill it first"), refused.err)
        assertNotNull(manager.get(worker), "a refused remove touches nothing")

        // The daemon refuses the frame itself too, with the same advice, for a client that skips the CLI's check.
        val mark = fixture.client.mark()
        fixture.send(Envelope(Envelope.SESSION_DELETE, worker))
        val error = fixture.client.awaitAfter(mark, "the session_delete refusal") { it.type == Envelope.ERROR }
        assertTrue(error.data.contains("is still running") && error.data.contains("kill it first"), error.data)
        assertNotNull(manager.get(worker))
    }

    @Test
    fun sessionRmNeedsAKnownIdAndItsSubcommand() {
        val fixture = launch()
        assertEquals(2, cli(fixture, "session").exit, "no subcommand is a usage error")
        val unknown = cli(fixture, "session", "rm", "nope0000")
        assertEquals(1, unknown.exit)
        assertTrue(unknown.err.contains("no session 'nope0000'"), unknown.err)
    }

    // --- MCP ---------------------------------------------------------------------------------------------------

    private class Served(private val stdin: PrintWriter, private val stdout: BufferedReader, val stderr: StringBuffer) {
        lateinit var worker: Thread
        private var nextId = 1

        fun request(method: String, params: String = "{}"): JsonObject {
            val id = nextId++
            stdin.print("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""" + "\n")
            stdin.flush()
            val line = stdout.readLine() ?: error("stdout closed; stderr=$stderr")
            val answer = Json.parseToJsonElement(line).jsonObject
            assertEquals(id, answer["id"]!!.jsonPrimitive.int)
            return answer
        }

        fun callTool(name: String, arguments: String): JsonObject =
            request("tools/call", """{"name":"$name","arguments":$arguments}""")["result"]!!.jsonObject

        fun close() {
            runCatching { stdin.close() }
            worker.join(5_000)
        }
    }

    private fun serve(fixture: DaemonFixture): Served {
        val toServer = PipedWriter()
        val serverIn = PipedReader(toServer, 1 shl 16)
        val fromServer = PipedReader(1 shl 16)
        val serverOut = PrintWriter(PipedWriter(fromServer))
        val served = Served(PrintWriter(toServer), BufferedReader(fromServer), StringBuffer())
        val lineage = Files.createTempDirectory("agenthq-session-rm-lineage").toFile()
        val args = arrayOf("mcp", "serve", "--port", fixture.port.toString())
        served.worker = thread(name = "mcp-serve", isDaemon = true) {
            Cli.run(args, serverOut, served.stderr, CliTestEnv.getenv, { serverIn })
            serverOut.close()
        }
        servers += served
        served.request("initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"0"}}""")
        return served
    }

    private fun text(result: JsonObject): String = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

    @Test
    fun theMcpToolRemovesAnEndedSessionOfAnyOriginAndRefusesALiveOne() {
        val fixture = launch()
        val manager = fixture.daemon.manager
        val served = serve(fixture)

        val tool = served.request("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
            .single { it["name"]!!.jsonPrimitive.content == "agenthq_session_remove" }
        assertEquals("true", tool["annotations"]!!.jsonObject["destructiveHint"]!!.jsonPrimitive.content)

        // Live: refused, with the same advice as the CLI, and nothing removed.
        val (_, live) = startTicketWorker(fixture, "HANG")
        fixture.await(what = "the worker to be running") { manager.get(live)!!.live }
        val refused = served.callTool("agenthq_session_remove", """{"sessionId":"$live"}""")
        assertEquals("true", refused["isError"]!!.jsonPrimitive.content, "a live session is refused: $refused")
        assertTrue(text(refused).contains("is still running") && text(refused).contains("kill it first"), text(refused))
        assertNotNull(manager.get(live))

        // Ended (killed), and not spawned by this server: removed. The other session stays.
        val qa = roleAgent(fixture, "qa")
        killAndAwaitExited(fixture, live)
        val removed = served.callTool("agenthq_session_remove", """{"sessionId":"${live.take(8)}"}""")
        assertEquals("false", removed["isError"]!!.jsonPrimitive.content, "tool reported an error: $removed")
        val payload = Json.parseToJsonElement(text(removed)).jsonObject
        assertEquals(live, payload["sessionId"]!!.jsonPrimitive.content)
        assertEquals("true", payload["removed"]!!.jsonPrimitive.content)
        assertNull(manager.get(live))
        assertNotNull(manager.get(qa), "another session is not touched")
    }
}
