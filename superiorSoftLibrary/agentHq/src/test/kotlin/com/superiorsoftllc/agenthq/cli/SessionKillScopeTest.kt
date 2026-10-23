package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.orchestration.GatedRunner
import com.superiorsoftllc.agenthq.protocol.Envelope
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract 1.114.0: who may kill (and `session rm --force`) a running session. A pm kills any session in its own
 * project; another project is refused; a worker kills only what it spawned (recorded spawner id, so it survives an
 * MCP restart). A GatedRunner holds a run open on "HANG" so a session is BUSY on cue.
 */
class SessionKillScopeTest {

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
        DaemonFixture.launch(runner = GatedRunner(holdMs = 50), stateDir = Files.createTempDirectory("agenthq-kill-scope").toFile())
            .also { fixtures += it; it.client.await(Envelope.READY) }

    /** A session of [role] in [project]; BUSY (a held run) when [busy]. */
    private fun session(fixture: DaemonFixture, role: String, project: String, busy: Boolean = false): String {
        val manager = fixture.daemon.manager
        val before = manager.list().map { it.id }.toSet()
        fixture.send(Envelope(Envelope.NEW, """{"role":"$role","projectId":"$project"}"""))
        fixture.await(what = "the $role session") { manager.list().any { it.id !in before } }
        val id = manager.list().single { it.id !in before }.id
        if (busy) {
            fixture.send(Envelope(Envelope.PROMPT, "HANG", id))
            fixture.await(what = "$id to be running") { manager.get(id)!!.live }
        }
        return id
    }

    private data class Result(val exit: Int, val out: String, val err: String)

    private fun envOf(callerId: String?): (String) -> String? = { key ->
        if (key == "AGENTHQ_SESSION_ID" && callerId != null) callerId else CliTestEnv.getenv(key)
    }

    private fun cli(fixture: DaemonFixture, caller: String?, vararg args: String): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf(*args, "--port", fixture.port.toString()), out, err, envOf(caller))
        return Result(exit, out.toString(), err.toString())
    }

    @Test
    fun aPmKillsAnotherRolesSessionInItsProjectButNotInAnotherProject() {
        val fixture = launch()
        val manager = fixture.daemon.manager
        val pm = session(fixture, "pm", "alpha")
        val mine = session(fixture, "backend", "alpha", busy = true)
        val foreign = session(fixture, "backend", "beta", busy = true)

        val refused = cli(fixture, pm, "kill", foreign, "--force")
        assertEquals(1, refused.exit, refused.out)
        assertTrue(refused.err.contains("beta"), refused.err)
        assertTrue(manager.get(foreign)!!.live, "a refused kill touches nothing")

        val ok = cli(fixture, pm, "kill", mine.take(8), "--force")
        assertEquals(0, ok.exit, ok.err)
        assertFalse(manager.get(mine)!!.live)
    }

    @Test
    fun aWorkerKillsOnlyWhatItSpawnedAndTheRecordSurvivesAnMcpRestart() {
        val fixture = launch()
        val manager = fixture.daemon.manager
        val worker = session(fixture, "backend", "alpha")
        val stranger = session(fixture, "frontend", "alpha", busy = true)
        val child = session(fixture, "qa", "alpha", busy = true)

        val refused = cli(fixture, worker, "kill", stranger, "--force")
        assertEquals(1, refused.exit)
        assertTrue(refused.err.contains("was not spawned by this session") && refused.err.contains("agenthq send pm"), refused.err)
        assertTrue(manager.get(stranger)!!.live)

        // The spawner is a persisted fact on the session record, not state of one MCP process.
        manager.setSpawner(child, worker)
        assertEquals(worker, manager.get(child)!!.spawnerSessionId)
        val ok = cli(fixture, worker, "kill", child, "--force")
        assertEquals(0, ok.exit, ok.err)
        assertFalse(manager.get(child)!!.live)
    }

    @Test
    fun sessionRmForceKillsABusySessionThenRemovesItAndPlainRmStillRefuses() {
        val fixture = launch()
        val manager = fixture.daemon.manager
        val busy = session(fixture, "fullstack", "alpha", busy = true)

        val plain = cli(fixture, null, "session", "rm", busy)
        assertEquals(1, plain.exit)
        assertTrue(plain.err.contains("agenthq session rm $busy --force") && plain.err.contains("force=true"), plain.err)
        assertNotNull(manager.get(busy))

        val forced = cli(fixture, null, "session", "rm", busy, "--force")
        assertEquals(0, forced.exit, forced.err)
        assertTrue(forced.out.contains("removed $busy"), forced.out)
        assertNull(manager.get(busy))
    }

    // --- MCP -------------------------------------------------------------------------------------------------

    private class Served(private val stdin: PrintWriter, private val stdout: BufferedReader, val stderr: StringBuffer) {
        lateinit var worker: Thread
        private var nextId = 1

        fun callTool(name: String, arguments: String): JsonObject {
            val id = nextId++
            stdin.print("""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}""" + "\n")
            stdin.flush()
            val answer = Json.parseToJsonElement(stdout.readLine() ?: error("stdout closed; stderr=$stderr")).jsonObject
            assertEquals(id, answer["id"]!!.jsonPrimitive.int)
            return answer["result"]!!.jsonObject
        }

        fun initialize() {
            stdin.print("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}""" + '\n')
            stdin.flush()
            stdout.readLine() ?: error("stdout closed; stderr=$stderr")
        }

        fun close() {
            runCatching { stdin.close() }
            worker.join(5_000)
        }
    }

    /** A fresh MCP server process for [caller]: a restart is just another call to this. */
    private fun serve(fixture: DaemonFixture, caller: String): Served {
        val toServer = PipedWriter()
        val serverIn = PipedReader(toServer, 1 shl 16)
        val fromServer = PipedReader(1 shl 16)
        val serverOut = PrintWriter(PipedWriter(fromServer))
        val served = Served(PrintWriter(toServer), BufferedReader(fromServer), StringBuffer())
        val lineage = Files.createTempDirectory("agenthq-kill-scope-lineage").toFile()
        val args = arrayOf("mcp", "serve", "--port", fixture.port.toString(), "--lineage-dir", lineage.path)
        served.worker = thread(name = "mcp-serve", isDaemon = true) {
            Cli.run(args, serverOut, served.stderr, envOf(caller), { serverIn })
            serverOut.close()
        }
        servers += served
        served.initialize()
        return served
    }

    private fun text(result: JsonObject): String = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

    @Test
    fun theMcpRemoveToolWithForceKillsAndRemovesAndAFreshServerStillMayKill() {
        val fixture = launch()
        val manager = fixture.daemon.manager
        val pm = session(fixture, "pm", "alpha")
        val stuck = session(fixture, "fullstack", "alpha", busy = true)
        val other = session(fixture, "fullstack", "beta", busy = true)

        val noForce = serve(fixture, pm).callTool("agenthq_session_remove", """{"sessionId":"$stuck"}""")
        assertEquals("true", noForce["isError"]!!.jsonPrimitive.content)
        assertTrue(text(noForce).contains("agenthq_session_remove with force=true"), text(noForce))

        val foreign = serve(fixture, pm).callTool("agenthq_session_remove", """{"sessionId":"$other","force":true}""")
        assertEquals("true", foreign["isError"]!!.jsonPrimitive.content)
        assertTrue(manager.get(other)!!.live)

        // A brand-new MCP process (an app restart) did not spawn `stuck`, and the pm may still remove it.
        val done = serve(fixture, pm).callTool("agenthq_session_remove", """{"sessionId":"$stuck","force":true}""")
        assertEquals("false", done["isError"]!!.jsonPrimitive.content, text(done))
        assertNull(manager.get(stuck))
    }
}
