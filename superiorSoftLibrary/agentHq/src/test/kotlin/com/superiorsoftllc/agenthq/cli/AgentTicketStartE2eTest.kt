package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.daemon.TestWait
import com.superiorsoftllc.agenthq.cli.mcp.McpTicketTools
import com.superiorsoftllc.agenthq.context.TicketContextComposer
import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.daemon.AgentHqBin
import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.daemon.FakeTicketContext
import com.superiorsoftllc.agenthq.daemon.awaitAfter
import com.superiorsoftllc.agenthq.daemon.mark
import com.superiorsoftllc.agenthq.daemon.saveProfile
import com.superiorsoftllc.agenthq.daemon.saveTicket
import com.superiorsoftllc.agenthq.orchestration.GatedRunner
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.provider.AgentProvider
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.worktree.TicketBranches
import com.superiorsoftllc.agenthq.worktree.newGitRepo
import com.superiorsoftllc.agenthq.worktree.testGit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BUG-ASSIGN-1: an agent gives a ticket its harness (provider), model and role and starts it THROUGH ITS OWN ENTRY
 * POINTS -- the MCP tools of `agenthq mcp serve` and the `agenthq` CLI -- never through the board UI. The start must
 * be the board's own hand-out, so the worker:
 *  (a) runs the ticket's provider and model, and the run reaches THAT harness's transport;
 *  (b) works in the ticket's own worktree on `ticket/<short-id>-<slug>`, never the project checkout;
 *  (c) leaves the ticket IN_PROGRESS on the board, started_by the calling agent's session;
 *  (d) runs as the role the ticket asked for.
 *
 * The owner's report (mvp a05eec9e): the worker started as fullstack on the daemon's default harness, in the project
 * checkout, and the ticket stayed in To Do -- an agent had ticket create/update/get but no start, and `agenthq_spawn`
 * launched outside the board. So the rig makes each of those wrong answers observable: the FIRST profile is fullstack,
 * both profiles pin the default `stub` harness, and the project is a real git checkout.
 *
 * Everything runs against a REAL daemon on a temp state dir and an ephemeral port, cutting worktrees from a throwaway
 * git repo under the system temp dir; never ~/.agenthq, never the operator's 8787/8788. Every assertion reads the
 * daemon itself (its session record, its board, its database) and git, never only what a tool printed.
 */
class AgentTicketStartE2eTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest
    fun cleanUp() {
        cleanups.asReversed().forEach { runCatching { it() } }
        cleanups.clear()
    }

    /** A harness that LISTS its models (so a typo is a real refusal) and runs on its OWN transport, so a run is countable. */
    private class ListingHarness(override val id: String, private val known: List<String>, private val runner: ProcessRunner) :
        AgentProvider by StubProvider(delayMs = 0) {
        override fun knownModels() = known
        override fun transport(): ProcessRunner = runner
    }

    private class Rig(
        val fixture: DaemonFixture,
        val repo: File,
        /** The `claude` harness's transport: every run that reached that harness, and only those. */
        val claude: GatedRunner,
        /** The session standing for the agent that drives the tools: its AGENTHQ_SESSION_ID. */
        val caller: String,
        val scratch: File,
    )

    /**
     * Every start composes the prompt HANG, so the gated `claude` run stays open and the ticket stays in progress while
     * the test reads it. The calling agent is a session at rest (`new` naming the lead profile), as a real agent's is.
     *
     * [repoConfigured] false is the owner's daemon (BUG-REGRESS-1): no `AGENTHQ_REPO`, running INSIDE the project
     * checkout, so a ticket start cuts its worktree from the checkout it runs in (BUG-WT-1).
     */
    private fun rig(repoConfigured: Boolean = true, agentsPlugin: Boolean = false): Rig {
        val scratch = Files.createTempDirectory("agenthq-assign-scratch").toFile()
        val repo = newGitRepo("agenthq-assign")
        cleanups += { scratch.deleteRecursively(); repo.deleteRecursively() }
        val claude = GatedRunner()
        // RUNTIME-LOAD-2: agenthq_list_harnesses is Infantry_Agents' daemon half; a test that calls it loads the built plugin.
        val plugins = com.superiorsoftllc.agenthq.daemon.TestDaemonPlugins.boardRoot(*(if (agentsPlugin) arrayOf("official/Infantry_Agents") else emptyArray()))
        val fixture = DaemonFixture.launch(
            repo = repo.takeIf { repoConfigured },
            context = FakeTicketContext("HANG"),
            additionalProviders = listOf(ListingHarness("claude", listOf("haiku", "sonnet", "opus"), claude)),
            workingDir = if (repoConfigured) File(scratch, "workdir").apply { mkdirs() } else repo,
            pluginsRoot = plugins,
        )
        cleanups += { fixture.stop(); fixture.deleteState() }
        fixture.client.await(Envelope.READY)
        // fullstack FIRST: the profile a start that ignored the ticket's role would land on.
        fixture.client.saveProfile("""{"id":"fullstack-1","name":"Fullstack","role":"fullstack","provider":"stub"}""")
        fixture.client.saveProfile("""{"id":"backend-1","name":"Backend","role":"backend","provider":"stub"}""")
        fixture.client.saveProfile("""{"id":"lead-1","name":"Lead","role":"pm"}""")
        fixture.send(Envelope(Envelope.NEW, """{"agentId":"lead-1"}"""))
        fixture.await(what = "the calling agent's session") { fixture.daemon.manager.list().any { it.agentId == "lead-1" } }
        val caller = fixture.daemon.manager.list().single { it.agentId == "lead-1" }.id
        return Rig(fixture, repo, claude, caller, scratch)
    }

    // --- the agent's two entry points ------------------------------------------------------------------------------

    /**
     * What the daemon hands the calling agent's session: its session id and the daemon's address (AGENTHQ_URL), the
     * subset AgentHqMcp passes on to `mcp serve`. Plus a TEMP lineage dir, so nothing is written under ~/.agenthq.
     */
    private fun sessionEnv(rig: Rig): Map<String, String> = mapOf(
        AccessPolicy.SESSION_ID_ENV to rig.caller,
        AgentHqBin.URL_ENV to "ws://127.0.0.1:${rig.fixture.port}/ws",
        "AGENTHQ_MCP_LINEAGE_DIR" to File(rig.scratch, "mcp-lineage").path,
        ANSWER_TIMEOUT_ENV to TestWait.defaultMs.toString(),
    )

    /**
     * `agenthq mcp serve` over pipes, started the way AgentHqMcp.launch starts it for a session: no flags, the
     * session's env; returns tools/call.
     */
    private fun mcp(rig: Rig): (String, JsonObject) -> JsonObject = mcpRpc(rig).let { rpc ->
        { name: String, arguments: JsonObject ->
            val answer = rpc("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })
            assertNotNull(answer["result"], "tools/call $name must answer a tool result: $answer").jsonObject
        }
    }

    /** The raw JSON-RPC exchange with that server, initialized. */
    private fun mcpRpc(rig: Rig): (String, JsonObject) -> JsonObject {
        val toServer = PipedWriter()
        val serverIn = PipedReader(toServer, 1 shl 16)
        val fromServer = PipedReader(1 shl 16)
        val serverOut = PrintWriter(PipedWriter(fromServer))
        val stdin = PrintWriter(toServer)
        val stdout = BufferedReader(fromServer)
        val stderr = StringBuffer()
        val env = sessionEnv(rig)
        val worker = thread(name = "mcp-serve-assign", isDaemon = true) {
            Cli.run(arrayOf("mcp", "serve"), serverOut, stderr, { env[it] }, { serverIn }, cwd = rig.scratch)
            serverOut.close()
        }
        cleanups += { stdin.close(); worker.join(5_000) }
        var nextId = 1
        fun request(method: String, params: JsonObject): JsonObject {
            val id = nextId++
            stdin.print(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }.toString() + "\n")
            stdin.flush()
            val answer = Json.parseToJsonElement(stdout.readLine() ?: error("mcp serve closed stdout; stderr=$stderr")).jsonObject
            assertEquals(id, answer["id"]!!.jsonPrimitive.int)
            return answer
        }
        request("initialize", Json.parseToJsonElement("""{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"agent","version":"0"}}""").jsonObject)
        return { method, params -> request(method, params) }
    }

    private data class Run(val exit: Int, val out: String, val err: String)

    /** The `agenthq` CLI as the calling agent runs it inside its session: the session's env, no address flag. */
    private fun cli(rig: Rig, vararg args: String): Run {
        val out = StringBuilder()
        val err = StringBuilder()
        val env = sessionEnv(rig)
        val exit = Cli.run(arrayOf(*args), out, err, { env[it] })
        return Run(exit, out.toString(), err.toString())
    }

    /** The role ids a refusal lists after "role ids: ", in order. */
    private fun roleIdsIn(text: String): List<String> =
        Regex("role ids: ([a-z0-9, -]+)").find(text)?.groupValues?.get(1)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun args(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })
    private fun JsonObject.isError() = this["isError"]!!.jsonPrimitive.boolean
    private fun JsonObject.text() = this["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    private fun JsonObject.payload() = Json.parseToJsonElement(text()).jsonObject
    private fun JsonObject.field(key: String) = this[key]?.jsonPrimitive?.content

    /** The board as the daemon serves it now, read by its own answer type (a `ticket` push is never taken for it). */
    private fun board(fixture: DaemonFixture): List<Ticket> {
        val from = fixture.client.mark()
        fixture.send(Envelope(Envelope.TICKET_LIST, ""))
        val listed = fixture.client.awaitAfter(from, "the board listing") { it.type == Envelope.TICKETS }
        return json.decodeFromString(ListSerializer(Ticket.serializer()), listed.data)
    }

    /** (a)-(d), from the daemon's own state and git. Returns the worker session. */
    private fun assertStartedAsAsked(rig: Rig, ticketId: String, title: String): SessionRecord {
        val fixture = rig.fixture
        val ticket = board(fixture).single { it.id == ticketId }

        // (c) In Progress on the board, started by the calling agent's session.
        assertEquals(TicketStatus.IN_PROGRESS, ticket.status, "(c) the ticket must be in progress on the board: $ticket")
        val sessionId = assertNotNull(ticket.sessionId, "(c) the started ticket records its worker session")
        val startedBy = fixture.daemon.store.transaction { fixture.daemon.store.tickets.get(ticketId) }?.startedBy
        assertEquals(rig.caller, startedBy, "(c) started_by is the agent session that started it")
        val session = assertNotNull(fixture.daemon.manager.get(sessionId), "(c) the worker session exists")
        assertEquals(ticketId, session.ticketId, "(c) the worker is the ticket's")

        // (a) the ticket's own harness and model -- not the profile's stub -- and the run reached THAT harness.
        assertEquals("claude", session.provider, "(a) the worker runs the ticket's provider")
        assertEquals("haiku", session.model, "(a) the worker runs the ticket's model")
        fixture.await(what = "the run to reach the claude harness's transport") { rig.claude.processes.size == 1 }

        // (b) its own worktree on ticket/<short-id>-<slug>, never the project checkout.
        val branch = TicketBranches.branchFor(ticketId, title)
        assertEquals(branch, session.worktreeBranch, "(b) the worker's branch is the ticket's")
        assertEquals(branch, ticket.branch, "(b) the board records the ticket's branch")
        val worktree = File(assertNotNull(session.worktreePath, "(b) the worker has a worktree")).canonicalFile
        assertEquals(worktree, File(session.workingDir).canonicalFile, "(b) the worker runs IN its worktree")
        assertNotEquals(rig.repo.canonicalFile, worktree, "(b) never the project checkout")
        assertEquals(
            branch,
            testGit().run(worktree, listOf("rev-parse", "--abbrev-ref", "HEAD")).out(),
            "(b) git: the worktree is checked out on the ticket branch",
        )

        // (d) the role asked for: the backend profile, never the first (fullstack) one.
        assertEquals("backend", session.role, "(d) the worker runs the role the ticket asked for")
        assertEquals("backend-1", session.agentId, "(d) the profile of that role")
        return session
    }

    // --- acceptance ------------------------------------------------------------------------------------------------

    @Test
    fun `MCP - an agent pins role, provider and model on a ticket, then ticket_start runs the worker exactly so`() {
        val rig = rig()
        val call = mcp(rig)
        val title = "Wire the payments API"
        val created = call("agenthq_ticket_create", args("title" to title, "role" to "backend", "provider" to "claude", "model" to "haiku"))
        assertFalse(created.isError(), created.text())
        val ticketId = created.payload().field("ticketId")!!

        val started = call(TICKET_START, args("ticketId" to ticketId.takeLast(8)))
        assertFalse(started.isError(), "agenthq_ticket_start must start the ticket: ${started.text()}")
        val session = assertStartedAsAsked(rig, ticketId, title)

        // What the agent reads back names what it got, so it never has to guess.
        val answer = started.payload()
        assertEquals("started", answer.field("outcome"), answer.toString())
        assertEquals(TicketStatus.IN_PROGRESS, answer.field("status"))
        assertEquals(session.id, answer.field("sessionId"))
        assertEquals("backend", answer.field("role"))
        assertEquals("claude", answer.field("provider"))
        assertEquals("haiku", answer.field("model"))
        assertEquals(session.worktreeBranch, answer.field("branch"))
        assertEquals(File(session.worktreePath!!).canonicalFile, File(answer.field("worktree")!!).canonicalFile)
        assertEquals(rig.caller, answer.field("startedBy"))

        // A second start of a ticket already in progress starts nothing and names the worker it already has.
        val again = call(TICKET_START, args("ticketId" to ticketId))
        assertFalse(again.isError(), again.text())
        assertEquals("already_in_progress", again.payload().field("outcome"))
        assertEquals(session.id, again.payload().field("sessionId"))
        assertEquals(1, rig.claude.processes.size, "no second worker")
    }

    @Test
    fun `CLI - an agent pins role, provider and model on a ticket, then ticket start runs the worker exactly so`() {
        val rig = rig()
        val title = "Wire the refunds API"
        val created = cli(rig, "ticket", "create", "--title", title, "--role", "backend", "--provider", "claude", "--model", "haiku")
        assertEquals(0, created.exit, "ticket create: out=${created.out} err=${created.err}")
        val shortId = created.out.trim().removePrefix("created ")

        val started = cli(rig, "ticket", "start", shortId)
        assertEquals(0, started.exit, "ticket start must start the ticket: out=${started.out} err=${started.err}")
        val ticketId = board(rig.fixture).single { it.title == title }.id
        val session = assertStartedAsAsked(rig, ticketId, title)

        assertTrue(started.out.startsWith("started $ticketId -> ${TicketStatus.IN_PROGRESS}"), started.out)
        for (expected in listOf(session.id, "backend", "claude", "haiku", session.worktreeBranch!!)) {
            assertTrue(started.out.contains(expected), "ticket start must print '$expected': ${started.out}")
        }
    }

    @Test
    fun `MCP - agenthq_spawn given a ticketId starts that ticket through the board, a conflicting role is refused`() {
        val rig = rig()
        val call = mcp(rig)
        val title = "Index the ledger"
        val ticketId = call("agenthq_ticket_create", args("title" to title, "role" to "backend", "provider" to "claude", "model" to "haiku"))
            .payload().field("ticketId")!!

        val conflicting = call(SPAWN, args("ticketId" to ticketId.takeLast(8), "role" to "fullstack"))
        assertTrue(conflicting.isError(), "a role the ticket does not have must be refused, not ignored: ${conflicting.text()}")
        assertTrue(conflicting.text().contains("agenthq_ticket_update"), conflicting.text())
        assertEquals(TicketStatus.TODO, board(rig.fixture).single { it.id == ticketId }.status, "a refused spawn starts nothing")

        val spawned = call(SPAWN, args("ticketId" to ticketId.takeLast(8)))
        assertFalse(spawned.isError(), "agenthq_spawn with a ticketId must start the ticket: ${spawned.text()}")
        val session = assertStartedAsAsked(rig, ticketId, title)
        assertEquals(session.id, spawned.payload().field("sessionId"))
    }

    // --- BUG-REGRESS-1: a dirty project checkout never refuses a start ----------------------------------------------

    /** The owner's daemon (no repo configured, inside the project checkout), with a tracked edit nobody committed. */
    private fun dirtyCheckoutRig(): Pair<Rig, String> {
        val rig = rig(repoConfigured = false)
        val head = testGit().run(rig.repo, listOf("rev-parse", "HEAD")).out()
        File(rig.repo, "README.md").writeText("half-written\n")
        val dirt = testGit().run(rig.repo, listOf("status", "--porcelain", "--untracked-files=no")).out()
        assertTrue(dirt.isNotEmpty(), "the project checkout must be dirty, or this proves nothing")
        return rig to head
    }

    /**
     * The owner's report: a start no longer moved the ticket to In Progress. With no AGENTHQ_REPO a start cuts a ticket
     * worktree from the checkout the daemon runs in (BUG-WT-1), and a checkout with an uncommitted edit refused EVERY
     * start. Through the agent's own entrance (`agenthq mcp serve`), the start now lands In Progress on the ticket's
     * branch, cut from HEAD's commit; the edit stays in the checkout, and the answer says what the branch was cut from.
     */
    @Test
    fun `MCP - ticket_start over a dirty project checkout starts on the ticket branch and says what it was cut from`() {
        val (rig, head) = dirtyCheckoutRig()
        val call = mcp(rig)
        val title = "Wire the payouts API"
        val created = call("agenthq_ticket_create", args("title" to title, "role" to "backend", "provider" to "claude", "model" to "haiku"))
        assertFalse(created.isError(), created.text())
        val ticketId = created.payload().field("ticketId")!!

        val started = call(TICKET_START, args("ticketId" to ticketId.takeLast(8)))
        assertFalse(started.isError(), "a dirty project checkout must never refuse the start: ${started.text()}")
        val session = assertStartedAsAsked(rig, ticketId, title)

        val answer = started.payload()
        val note = "cut from HEAD ${head.take(8)}"
        assertEquals(note, answer.field("note"), "the answer says what the branch was cut from: $answer")
        assertTrue(answer.field("message")!!.contains(note), "the message the agent reads says it too: $answer")
        assertNull(answer.field("warning"), "a cut note is not a launch warning: $answer")
        assertEquals(head, board(rig.fixture).single { it.id == ticketId }.base, "the ticket records the commit it was cut from")
        val worktree = File(session.worktreePath!!)
        assertTrue(worktree.canonicalPath.startsWith(File(rig.repo, ".worktrees").canonicalPath), "cut in the project's repo: $worktree")
        assertEquals("hello", File(worktree, "README.md").readText().trim(), "only committed work is on the ticket branch")
        assertEquals("half-written", File(rig.repo, "README.md").readText().trim(), "the edit stays in the project checkout")
    }

    /** The CLI's `ticket move ... in_progress` is the same start, and prints the same note under its moved line. */
    @Test
    fun `CLI - ticket move to in_progress over a dirty project checkout starts and prints what it was cut from`() {
        val (rig, head) = dirtyCheckoutRig()
        val title = "Wire the refunds ledger"
        val created = cli(rig, "ticket", "create", "--title", title, "--role", "backend", "--provider", "claude", "--model", "haiku")
        assertEquals(0, created.exit, "ticket create: out=${created.out} err=${created.err}")
        val shortId = created.out.trim().removePrefix("created ")

        val moved = cli(rig, "ticket", "move", shortId, TicketStatus.IN_PROGRESS)
        assertEquals(0, moved.exit, "the start must not be refused: out=${moved.out} err=${moved.err}")
        val ticketId = board(rig.fixture).single { it.title == title }.id
        assertStartedAsAsked(rig, ticketId, title)
        val lines = moved.out.lines().map { it.trim() }
        assertEquals("moved $ticketId -> ${TicketStatus.IN_PROGRESS}", lines.first(), moved.out)
        assertTrue("note:     cut from HEAD ${head.take(8)}" in lines, "the move prints what the branch was cut from: ${moved.out}")
    }

    // --- discovery and the update answer ---------------------------------------------------------------------------

    @Test
    fun `an agent lists the harnesses it may pin and each one's models, through MCP and the CLI`() {
        val rig = rig(agentsPlugin = true)
        val listed = mcp(rig)(LIST_HARNESSES, JsonObject(emptyMap()))
        assertFalse(listed.isError(), listed.text())
        val harnesses = listed.payload()["harnesses"]!!.jsonArray.map { it.jsonObject }
        val claude = harnesses.single { it.field("id") == "claude" }
        assertEquals(listOf("haiku", "sonnet", "opus"), claude["models"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(harnesses.single { it.field("id") == "stub" }["default"]!!.jsonPrimitive.boolean, harnesses.toString())

        val printed = cli(rig, "harnesses")
        assertEquals(0, printed.exit, "harnesses: out=${printed.out} err=${printed.err}")
        val row = printed.out.lines().singleOrNull { it.substringBefore(" |") == "claude" }
        assertNotNull(row, "a row for the claude harness: ${printed.out}")
        assertTrue(row.contains("haiku, sonnet, opus"), row)
    }

    @Test
    fun `CONTEXT-LIMIT-1 ticket create and update carry a context limit, off clears it, a typo is refused, the CLI sets it too`() {
        val rig = rig()
        val call = mcp(rig)
        val created = call("agenthq_ticket_create", args("title" to "Big refactor", "contextLimit" to "500k"))
        assertFalse(created.isError(), created.text())
        val ticketId = created.payload().field("ticketId")!!
        assertEquals(500_000, board(rig.fixture).single { it.id == ticketId }.contextLimit)

        val bad = call("agenthq_ticket_update", args("ticketId" to ticketId.takeLast(8), "contextLimit" to "200"))
        assertTrue(bad.isError(), bad.text())
        assertEquals(500_000, board(rig.fixture).single { it.id == ticketId }.contextLimit, "a refused limit changes nothing")

        val cleared = call("agenthq_ticket_update", args("ticketId" to ticketId.takeLast(8), "contextLimit" to "off"))
        assertFalse(cleared.isError(), cleared.text())
        assertEquals(listOf("contextLimit"), cleared.payload()["saved"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(null, board(rig.fixture).single { it.id == ticketId }.contextLimit)

        val viaCli = cli(rig, "ticket", "update", ticketId.takeLast(8), "--context-limit", "1m")
        assertEquals(0, viaCli.exit, "out=${viaCli.out} err=${viaCli.err}")
        val stored = board(rig.fixture).single { it.id == ticketId }
        assertEquals(1_000_000, stored.contextLimit)
        assertEquals("ticket", stored.effectiveContextLimit?.source)
        val shown = cli(rig, "ticket", "show", ticketId.takeLast(8))
        assertTrue(shown.out.contains("CONTEXT LIMIT: 1m (ticket)"), shown.out)
    }

    @Test
    fun `ticket update answers that it saved role, provider and model`() {
        val rig = rig()
        val call = mcp(rig)
        val ticketId = call("agenthq_ticket_create", args("title" to "Tune the cache")).payload().field("ticketId")!!

        val updated = call("agenthq_ticket_update", args("ticketId" to ticketId.takeLast(8), "role" to "backend", "provider" to "claude", "model" to "sonnet"))
        assertFalse(updated.isError(), updated.text())
        val answer = updated.payload()
        assertEquals("backend", answer.field("role"))
        assertEquals("role:backend", answer.field("agentId"))
        assertEquals("claude", answer.field("provider"))
        assertEquals("sonnet", answer.field("model"))
        assertEquals(listOf("role", "provider", "model"), answer["saved"]!!.jsonArray.map { it.jsonPrimitive.content })
        val message = answer.field("message")!!
        assertTrue(message.contains("role backend") && message.contains("provider claude") && message.contains("model sonnet"), message)

        val stored = board(rig.fixture).single { it.id == ticketId }
        assertEquals("role:backend", stored.agentId)
        assertEquals("claude", stored.provider)
        assertEquals("sonnet", stored.model)

        val viaCli = cli(rig, "ticket", "update", ticketId.takeLast(8), "--model", "opus")
        assertEquals(0, viaCli.exit, "ticket update: out=${viaCli.out} err=${viaCli.err}")
        assertTrue(viaCli.out.startsWith("updated ") && viaCli.out.contains("model opus"), viaCli.out)
        assertEquals("opus", board(rig.fixture).single { it.id == ticketId }.model)
    }

    @Test
    fun `ticket show and agenthq_ticket_get show the provider and model the ticket pins`() {
        val rig = rig()
        val created = cli(rig, "ticket", "create", "--title", "Show the pins", "--role", "backend", "--provider", "claude", "--model", "sonnet")
        assertEquals(0, created.exit, "ticket create: out=${created.out} err=${created.err}")
        val shortId = created.out.trim().removePrefix("created ")

        val shown = cli(rig, "ticket", "show", shortId)
        assertEquals(0, shown.exit, "ticket show: out=${shown.out} err=${shown.err}")
        val lines = shown.out.lines().map { it.trim() }
        assertTrue("PROVIDER: claude" in lines, "ticket show must print the provider: ${shown.out}")
        assertTrue("MODEL: sonnet" in lines, "ticket show must print the model: ${shown.out}")

        val got = mcp(rig)("agenthq_ticket_get", args("ticketId" to shortId))
        assertFalse(got.isError(), got.text())
        assertEquals("claude", got.payload().field("provider"), got.text())
        assertEquals("sonnet", got.payload().field("model"), got.text())
        assertEquals("backend", got.payload().field("role"), got.text())
    }

    // --- a start the board refuses, and a role the daemon refuses ----------------------------------------------------

    @Test
    fun `CLI - a ticket move to in_progress whose start is refused exits non-zero with the reason, never moved`() {
        val rig = rig()
        // A ticket with no role can never start: the board refuses it and the ticket stays in To Do. (INSTALL-PROFILE-1:
        // a known role with no profile no longer refuses -- it creates a default profile -- so it is not used here.)
        val title = "Audit the ledger"
        val created = cli(rig, "ticket", "create", "--title", title, "--provider", "claude", "--model", "haiku")
        assertEquals(0, created.exit, "ticket create: out=${created.out} err=${created.err}")
        val shortId = created.out.trim().removePrefix("created ")
        val reason = "ticket has no role"

        val moved = cli(rig, "ticket", "move", shortId, TicketStatus.IN_PROGRESS)
        assertNotEquals(0, moved.exit, "a refused start must not exit 0: out=${moved.out} err=${moved.err}")
        assertTrue(moved.err.contains(reason), "the daemon's reason must be printed: ${moved.err}")
        assertFalse(moved.out.contains("moved"), "a refused start is never printed as a move: ${moved.out}")
        val ticket = board(rig.fixture).single { it.title == title }
        assertEquals(TicketStatus.TODO, ticket.status, "the refused ticket stays in To Do")
        assertNull(ticket.sessionId, "no worker")
        assertEquals(0, rig.claude.processes.size, "nothing ran")

        val started = cli(rig, "ticket", "start", shortId)
        assertNotEquals(0, started.exit, "ticket start of a refused start: out=${started.out} err=${started.err}")
        assertTrue(started.err.contains(reason), started.err)
        val viaMcp = mcp(rig)(TICKET_START, args("ticketId" to shortId))
        assertTrue(viaMcp.isError(), viaMcp.text())
        assertTrue(viaMcp.text().contains(reason), viaMcp.text())
    }

    @Test
    fun `a role refused at create or update names the valid role ids, through MCP and the CLI`() {
        val rig = rig()
        val call = mcp(rig)
        val ticketId = call("agenthq_ticket_create", args("title" to "Name the roles", "role" to "backend")).payload().field("ticketId")!!

        // A display name is refused, never matched to a role id; the refusal lists the ids to use instead.
        val updated = call("agenthq_ticket_update", args("ticketId" to ticketId.takeLast(8), "role" to "Backend Developer"))
        assertTrue(updated.isError(), "a display name is not a role id: ${updated.text()}")
        val listed = roleIdsIn(updated.text())
        assertTrue(listOf("backend", "fullstack", "qa").all { it in listed }, "the refusal names the valid role ids: ${updated.text()}")
        assertEquals("role:backend", board(rig.fixture).single { it.id == ticketId }.agentId, "a refused role changes nothing")

        val created = call("agenthq_ticket_create", args("title" to "Never saved", "role" to "Backend Developer"))
        assertTrue(created.isError(), created.text())
        assertTrue("backend" in roleIdsIn(created.text()), "the create refusal names them too: ${created.text()}")

        val viaCli = cli(rig, "ticket", "update", ticketId.takeLast(8), "--role", "Backend Developer")
        assertNotEquals(0, viaCli.exit, "ticket update with a display name: out=${viaCli.out} err=${viaCli.err}")
        assertTrue(listOf("backend", "fullstack", "qa").all { it in roleIdsIn(viaCli.err) }, "the CLI names them: ${viaCli.err}")
    }

    // --- BUG-ROLE-3: no silent first-profile role, no role outside the live catalog ---------------------------------

    /**
     * A ticket created with no role stays unassigned. The rig's FIRST profile is fullstack, so a create that defaulted to
     * it would store `role:fullstack` and later start as fullstack -- the owner's "the worker ran fullstack".
     */
    @Test
    fun `a ticket created without a role stays unassigned, through MCP and the CLI`() {
        val rig = rig()
        val created = mcp(rig)("agenthq_ticket_create", args("title" to "No role given"))
        assertFalse(created.isError(), created.text())
        val viaMcp = board(rig.fixture).single { it.title == "No role given" }
        assertNull(viaMcp.agentId, "MCP create without a role must store no role, never the first profile's")

        val viaCli = cli(rig, "ticket", "create", "--title", "No role either")
        assertEquals(0, viaCli.exit, "ticket create: out=${viaCli.out} err=${viaCli.err}")
        assertNull(board(rig.fixture).single { it.title == "No role either" }.agentId, "CLI create without a role must store no role")

        // Starting an unassigned ticket is a clear, reported refusal through both entrances; nothing runs.
        val reason = "ticket has no role; set one with"
        val viaMcpStart = mcp(rig)(TICKET_START, args("ticketId" to viaMcp.id))
        assertTrue(viaMcpStart.isError(), viaMcpStart.text())
        assertTrue(viaMcpStart.text().contains(reason), viaMcpStart.text())
        val viaCliStart = cli(rig, "ticket", "start", viaMcp.id.takeLast(8))
        assertNotEquals(0, viaCliStart.exit, "out=${viaCliStart.out} err=${viaCliStart.err}")
        assertTrue(viaCliStart.err.contains(reason), viaCliStart.err)
        val after = board(rig.fixture).single { it.id == viaMcp.id }
        assertEquals(TicketStatus.TODO, after.status, "a refused start stays in To Do")
        assertNull(after.sessionId, "no worker")
        assertEquals(0, rig.claude.processes.size, "nothing ran")
    }

    /** A role id of the right shape that the live catalog does not have is refused at create and update, listing the ids. */
    @Test
    fun `a role id outside the catalog is refused at create and update, through MCP and the CLI`() {
        val rig = rig()
        val call = mcp(rig)
        val created = call("agenthq_ticket_create", args("title" to "Unknown role", "role" to "backend-developer"))
        assertTrue(created.isError(), "an unknown role id must be refused at create: ${created.text()}")
        assertTrue("backend" in roleIdsIn(created.text()), "the refusal names the valid ids: ${created.text()}")
        assertTrue(board(rig.fixture).none { it.title == "Unknown role" }, "a refused create saves nothing")

        val ticketId = call("agenthq_ticket_create", args("title" to "Known role", "role" to "backend")).payload().field("ticketId")!!
        val updated = call("agenthq_ticket_update", args("ticketId" to ticketId.takeLast(8), "role" to "backend-developer"))
        assertTrue(updated.isError(), "an unknown role id must be refused at update: ${updated.text()}")
        assertTrue("backend" in roleIdsIn(updated.text()), updated.text())
        assertEquals("role:backend", board(rig.fixture).single { it.id == ticketId }.agentId, "a refused update changes nothing")

        val cliCreate = cli(rig, "ticket", "create", "--title", "Unknown via CLI", "--role", "backend-developer")
        assertNotEquals(0, cliCreate.exit, "CLI create with an unknown role: out=${cliCreate.out} err=${cliCreate.err}")
        assertTrue("backend" in roleIdsIn(cliCreate.err), cliCreate.err)
        assertTrue(board(rig.fixture).none { it.title == "Unknown via CLI" })
        val cliUpdate = cli(rig, "ticket", "update", ticketId.takeLast(8), "--role", "backend-developer")
        assertNotEquals(0, cliUpdate.exit, "CLI update with an unknown role: out=${cliUpdate.out} err=${cliUpdate.err}")
        assertTrue("backend" in roleIdsIn(cliUpdate.err), cliUpdate.err)
        assertEquals("role:backend", board(rig.fixture).single { it.id == ticketId }.agentId)
    }

    @Test
    fun `the agent-facing guidance says to start ticket work with ticket start, never with spawn`() {
        val rig = rig()
        val tools = mcpRpc(rig)("tools/list", JsonObject(emptyMap()))["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
        val spawn = tools.single { it.field("name") == SPAWN }.field("description")!!
        assertTrue(spawn.contains(TICKET_START), "agenthq_spawn's description must send ticket work to $TICKET_START: $spawn")

        // The preamble every ticket's agent reads (its MCP line) and the CLI's own usage say the same.
        val line = TicketContextComposer.MCP_LINE
        assertTrue(line.contains(TICKET_START) && line.contains("agenthq ticket start"), line)
        assertTrue(line.contains("never $SPAWN"), "the preamble must rule spawn out for ticket work: $line")
        val usage = cli(rig, "ticket")
        assertTrue(usage.err.contains("start <id|prefix>"), "the ticket usage must name start: ${usage.err}")
        val help = cli(rig, "--help")
        assertTrue(help.out.contains("Start ticket work this way, never with a spawn"), "the CLI usage must say it too: ${help.out}")
    }

    // --- BASE-1a: a project's base branch, set through the CLI, decides where an agent's ticket_start cuts ---------

    private fun git(dir: File, vararg args: String): String {
        val result = testGit().run(dir, args.toList())
        assertTrue(result.ok, "git ${args.joinToString(" ")} failed: ${result.err()}")
        return result.out().trim()
    }

    /** `develop` one commit ahead of HEAD in [repo]; the checkout stays where it was. Returns develop's commit. */
    private fun developIn(repo: File): String {
        val head = git(repo, "rev-parse", "--abbrev-ref", "HEAD")
        git(repo, "checkout", "-q", "-b", "develop")
        File(repo, "develop.txt").writeText("develop\n")
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "on develop")
        val sha = git(repo, "rev-parse", "HEAD")
        git(repo, "checkout", "-q", head)
        return sha
    }

    private fun createProject(rig: Rig, name: String, directory: File? = null): String {
        val from = rig.fixture.client.mark()
        val dir = directory?.let { ""","directory":${JsonPrimitive(it.absolutePath)}""" } ?: ""
        rig.fixture.send(Envelope(Envelope.PROJECT_SAVE, """{"name":"$name"$dir}"""))
        val frame = rig.fixture.client.awaitAfter(from, "project $name") { it.type == Envelope.PROJECT }
        return Json.parseToJsonElement(frame.data).jsonObject["id"]!!.jsonPrimitive.content
    }

    /**
     * The owner's acceptance, through the agent's real entrance: project A's base is set to `develop` with
     * `agenthq project base`, then an agent calls agenthq_ticket_start through `agenthq mcp serve`. The ticket is In
     * Progress on its own branch cut at develop's commit (not HEAD), records it, and the note says so. Project B, with no
     * setting, still cuts from HEAD.
     */
    @Test
    fun `MCP - a project base set with the CLI decides the commit an agent's ticket_start cuts from`() {
        val rig = rig()
        val head = git(rig.repo, "rev-parse", "HEAD")
        val developSha = developIn(rig.repo)
        val a = createProject(rig, "A", rig.repo)
        val b = createProject(rig, "B")

        val set = cli(rig, "project", "base", a, "develop")
        assertEquals(0, set.exit, "project base must be set: ${set.err}")
        assertEquals("base develop", set.out.trim())

        val call = mcp(rig)
        val title = "Ship the develop work"
        val created = call(
            "agenthq_ticket_create",
            args("title" to title, "projectId" to a, "role" to "backend", "provider" to "claude", "model" to "haiku"),
        )
        assertFalse(created.isError(), created.text())
        val ticketId = created.payload().field("ticketId")!!
        val started = call(TICKET_START, args("ticketId" to ticketId))
        assertFalse(started.isError(), started.text())
        assertStartedAsAsked(rig, ticketId, title) // In Progress, on its own ticket/ branch and worktree

        val ticket = board(rig.fixture).single { it.id == ticketId }
        assertEquals(developSha, git(rig.repo, "rev-parse", "refs/heads/${ticket.branch}"), "git: the branch sits on develop's commit")
        assertNotEquals(head, developSha)
        assertEquals(developSha, ticket.base, "ticket.base records develop's commit")
        assertEquals("cut from develop ${developSha.take(8)} (project base)", started.payload().field("note"), started.text())

        // Project B has no setting: its agent's start still cuts from HEAD. On the gated claude harness too, so it stays
        // In Progress while it is read (the stub would finish it at once).
        val createdB = call("agenthq_ticket_create", args("title" to "B work", "projectId" to b, "role" to "fullstack", "provider" to "claude", "model" to "haiku"))
        assertFalse(createdB.isError(), createdB.text())
        val idB = createdB.payload().field("ticketId")!!
        val startedB = call(TICKET_START, args("ticketId" to idB))
        assertFalse(startedB.isError(), startedB.text())
        val ticketB = board(rig.fixture).single { it.id == idB }
        assertEquals(TicketStatus.IN_PROGRESS, ticketB.status)
        assertEquals(head, ticketB.base, "B cuts from HEAD")
        assertEquals(head, git(rig.repo, "rev-parse", "refs/heads/${ticketB.branch}"))
        assertEquals("cut from HEAD ${head.take(8)}", startedB.payload().field("note"), startedB.text())
    }

    /** `agenthq project base` reads, sets, refuses and clears; `agenthq project branches` lists the repo it read. */
    @Test
    fun `CLI - project base reads, sets, refuses and clears, and project branches lists the project repo's branches`() {
        val rig = rig()
        developIn(rig.repo)
        val current = git(rig.repo, "rev-parse", "--abbrev-ref", "HEAD")
        val a = createProject(rig, "A", rig.repo)

        assertEquals("base (none): new tickets cut from the daemon's AGENTHQ_BASE_BRANCH, else their start is refused", cli(rig, "project", "base", a).out.trim())
        val refused = cli(rig, "project", "base", a, "nope")
        assertEquals(1, refused.exit)
        assertTrue(refused.err.contains("base branch 'nope' does not resolve to a commit"), refused.err)
        assertEquals("base develop", cli(rig, "project", "base", a, "develop").out.trim())
        assertEquals("base develop", cli(rig, "project", "base", a).out.trim(), "read back")
        val cleared = cli(rig, "project", "base", a, "--clear")
        assertEquals(0, cleared.exit, cleared.err)
        assertEquals("base cleared: new tickets cut from the daemon's AGENTHQ_BASE_BRANCH, else their start is refused", cleared.out.trim())

        val branches = cli(rig, "project", "branches", a)
        assertEquals(0, branches.exit, branches.err)
        val lines = branches.out.trim().lines()
        assertEquals("repo ${rig.repo.absolutePath}", lines.first(), "names the repository it listed")
        assertTrue("* $current" in lines, "the checked-out branch is marked: $lines")
        assertTrue("  develop" in lines, "every local branch is listed: $lines")

        val usage = cli(rig, "project", "base")
        assertEquals(2, usage.exit)
        assertTrue(usage.err.contains("agenthq project base <projectId> [<branch>|--clear]"), usage.err)
    }

    // --- BASE-1c: an epic's own base branch overrides the project's, through the agent's real entrance -------------

    /** A commit on a new branch [name] of [repo] that HEAD does not have; the checkout stays where it was. */
    private fun commitOnBranch(repo: File, name: String): String {
        val head = git(repo, "rev-parse", "--abbrev-ref", "HEAD")
        git(repo, "checkout", "-q", "-b", name)
        File(repo, "$name.txt").writeText("$name\n")
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "on $name")
        val sha = git(repo, "rev-parse", "HEAD")
        git(repo, "checkout", "-q", head)
        return sha
    }

    private fun createEpic(rig: Rig, projectId: String, name: String): String {
        val from = rig.fixture.client.mark()
        rig.fixture.send(Envelope(Envelope.EPIC_SAVE, """{"projectId":"$projectId","name":"$name"}"""))
        val frame = rig.fixture.client.awaitAfter(from, "epic $name") { it.type == Envelope.EPIC }
        return Json.parseToJsonElement(frame.data).jsonObject["id"]!!.jsonPrimitive.content
    }

    /** A ticket pinned to the gated claude harness (it stays In Progress while read), filed in [epicId] when given. */
    private fun fileTicket(rig: Rig, title: String, projectId: String, epicId: String?, role: String): String {
        val epic = epicId?.let { ""","epicId":"$it"""" } ?: ""
        val ticket = rig.fixture.client.saveTicket(
            """{"title":"$title","agentId":"role:$role","provider":"claude","model":"haiku","projectId":"$projectId"$epic}""",
        )
        assertEquals(epicId, ticket.epicId)
        return ticket.id
    }

    /**
     * The owner's acceptance, through the agent's real entrance (`agenthq mcp serve`, agenthq_ticket_start). The
     * project base is `develop` (set with the CLI) and epic E's base is `release` (set with agenthq_epic_base):
     * a ticket in E cuts from release's commit; a ticket of the same project outside E cuts from develop's; after the
     * epic setting is cleared, the next ticket in E cuts from develop.
     */
    @Test
    fun `MCP - an epic base overrides the project base for the epic's tickets, and a clear falls back to the project`() {
        val rig = rig()
        rig.fixture.client.saveProfile("""{"id":"backend-2","name":"Backend 2","role":"backend","provider":"stub"}""")
        val developSha = commitOnBranch(rig.repo, "develop")
        val releaseSha = commitOnBranch(rig.repo, "release")
        assertNotEquals(developSha, releaseSha)
        val p = createProject(rig, "P", rig.repo)
        assertEquals("base develop", cli(rig, "project", "base", p, "develop").out.trim())
        val e = createEpic(rig, p, "E")

        val call = mcp(rig)
        val set = call(EPIC_BASE, buildJsonObject { put("epicId", e); put("branch", "release") })
        assertFalse(set.isError(), set.text())
        assertEquals("release", set.payload().field("baseBranch"))

        // A ticket in E cuts from release.
        val inE = fileTicket(rig, "in E", p, e, "backend")
        val startedE = call(TICKET_START, args("ticketId" to inE))
        assertFalse(startedE.isError(), startedE.text())
        val ticketE = board(rig.fixture).single { it.id == inE }
        assertEquals(TicketStatus.IN_PROGRESS, ticketE.status)
        assertEquals(releaseSha, ticketE.base, "a ticket in E records release's commit")
        assertEquals(releaseSha, git(rig.repo, "rev-parse", "refs/heads/${ticketE.branch}"), "git: on release's commit")
        assertEquals("cut from release ${releaseSha.take(8)} (epic $e)", startedE.payload().field("note"), startedE.text())

        // A ticket of the same project outside E cuts from develop.
        val outside = fileTicket(rig, "outside E", p, null, "fullstack")
        val startedOut = call(TICKET_START, args("ticketId" to outside))
        assertFalse(startedOut.isError(), startedOut.text())
        val ticketOut = board(rig.fixture).single { it.id == outside }
        assertEquals(developSha, ticketOut.base, "outside E: the project's develop")
        assertEquals(developSha, git(rig.repo, "rev-parse", "refs/heads/${ticketOut.branch}"))
        assertEquals("cut from develop ${developSha.take(8)} (project base)", startedOut.payload().field("note"), startedOut.text())

        // Clear E's setting: the next ticket in E cuts from develop; the started one keeps release.
        val cleared = call(EPIC_BASE, buildJsonObject { put("epicId", e); put("clear", true) })
        assertFalse(cleared.isError(), cleared.text())
        assertEquals("", cleared.payload().field("baseBranch"))
        val next = fileTicket(rig, "in E after clear", p, e, "backend")
        val startedNext = call(TICKET_START, args("ticketId" to next))
        assertFalse(startedNext.isError(), startedNext.text())
        val ticketNext = board(rig.fixture).single { it.id == next }
        assertEquals(TicketStatus.IN_PROGRESS, ticketNext.status)
        assertEquals(developSha, ticketNext.base, "after the clear the next ticket in E cuts from develop")
        assertEquals(developSha, git(rig.repo, "rev-parse", "refs/heads/${ticketNext.branch}"))
        assertEquals("cut from develop ${developSha.take(8)} (project base)", startedNext.payload().field("note"), startedNext.text())
        assertEquals(releaseSha, board(rig.fixture).single { it.id == inE }.base, "the started ticket keeps its recorded base")
    }

    /** `agenthq epic base` reads, sets, refuses (with the reason) and clears; agenthq_epic_base refuses the same way. */
    @Test
    fun `CLI - epic base reads, sets, refuses and clears`() {
        val rig = rig()
        commitOnBranch(rig.repo, "release")
        val p = createProject(rig, "P", rig.repo)
        val e = createEpic(rig, p, "E")

        assertEquals("base (none): new tickets in the epic cut from the project's base", cli(rig, "epic", "base", e).out.trim())
        val refused = cli(rig, "epic", "base", e, "nope")
        assertEquals(1, refused.exit)
        assertTrue(refused.err.contains("base branch 'nope' does not resolve to a commit"), refused.err)
        assertEquals("base release", cli(rig, "epic", "base", e, "release").out.trim())
        assertEquals("base release", cli(rig, "epic", "base", e).out.trim(), "read back")
        val viaMcp = mcp(rig)(EPIC_BASE, buildJsonObject { put("epicId", e); put("branch", "nope") })
        assertTrue(viaMcp.isError(), viaMcp.text())
        assertTrue(viaMcp.text().contains("base branch 'nope' does not resolve to a commit"), viaMcp.text())
        assertEquals("base release", cli(rig, "epic", "base", e).out.trim(), "the refused MCP set wrote nothing")
        val cleared = cli(rig, "epic", "base", e, "--clear")
        assertEquals(0, cleared.exit, cleared.err)
        assertEquals("base cleared: new tickets in the epic cut from the project's base", cleared.out.trim())

        val usage = cli(rig, "epic", "base")
        assertEquals(2, usage.exit)
        assertTrue(usage.err.contains("agenthq epic base <epicId> [<branch>|--clear]"), usage.err)
        val both = cli(rig, "epic", "base", e, "release", "--clear")
        assertEquals(2, both.exit)
        assertTrue(both.err.contains("not both"), both.err)
    }

    private companion object {
        const val EPIC_BASE = "agenthq_epic_base"
        // Names, not constants: this file must compile against the code that has no such tools yet (the RED run).
        const val TICKET_START = "agenthq_ticket_start"
        const val SPAWN = "agenthq_spawn"
        const val LIST_HARNESSES = "agenthq_list_harnesses"
    }
}
