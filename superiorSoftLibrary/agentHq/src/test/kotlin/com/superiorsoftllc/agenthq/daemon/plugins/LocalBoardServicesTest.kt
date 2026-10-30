package com.superiorsoftllc.agenthq.daemon.plugins

import com.superiorsoftllc.agenthq.backlog.TicketBoard
import com.superiorsoftllc.agenthq.daemon.api.Caller
import com.superiorsoftllc.agenthq.daemon.api.board.BoardResult
import com.superiorsoftllc.agenthq.daemon.api.board.GRANT_TICKETS_READ
import com.superiorsoftllc.agenthq.daemon.api.board.NoBoard
import com.superiorsoftllc.agenthq.daemon.api.board.TicketDraft
import com.superiorsoftllc.agenthq.daemon.api.board.TicketPatch
import com.superiorsoftllc.agenthq.daemon.api.board.TicketQuery
import com.superiorsoftllc.agenthq.daemon.api.orchestrator.LaunchVerdict
import com.superiorsoftllc.agenthq.daemon.api.orchestrator.OrchestratorLink
import com.superiorsoftllc.agenthq.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.agenthq.daemon.api.orchestrator.TicketRef
import com.superiorsoftllc.agenthq.epics.EpicStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DP-4a: the local binding of the Tickets / Epics / Board API over the real SQLite board. One golden test per operation (the
 * answer equals what the board itself answers), the host's project scope, the host's policy gate, the Unavailable modes and the
 * community grants.
 */
class LocalBoardServicesTest {
    private val dir: File = Files.createTempDirectory("agenthq-boardapi").toFile()
    private val store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath)

    @AfterTest fun cleanUp() { runCatching { store.close() }; dir.deleteRecursively() }

    private fun session(id: String, role: String, project: String, ticketId: String? = null) = SessionRecord(
        id = id, provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0,
        state = SessionState.IDLE, role = role, projectId = project, ticketId = ticketId,
    )

    private val sessions = listOf(session("s-a", "backend", "A", "ticket-x"), session("s-b", "backend", "B"), session("s-pm", "pm", "A"))
    private var tick = 0L
    private var idn = 0
    private val board = TicketBoard(store, clock = { ++tick }, idFactory = { "t${++idn}" })
    private val epicStore = EpicStore()
    private var refusal: String? = null
    private var link: OrchestratorLink? = Link()

    private class Link(
        val starter: OrchestratorReply<String?> = OrchestratorReply.Ok("s-pm"),
        val ticket: OrchestratorReply<TicketRef?> = OrchestratorReply.Ok(TicketRef("s-a", "A")),
    ) : OrchestratorLink {
        override fun reviewReport(from: String, body: String) = OrchestratorReply.Ok(null)
        override fun ticketOf(ticketId: String) = ticket
        override fun starterOf(workerSessionId: String) = starter
        override fun launchDecision(provider: String, unattended: Boolean, ticketId: String?) = OrchestratorReply.Ok(LaunchVerdict.Allow)
    }

    private fun services(withBoard: Boolean = true) = LocalBoardServices(
        board = { board.takeIf { withBoard } }, epicStore = { epicStore.takeIf { withBoard } }, sessions = { sessions },
        link = { link }, gate = { _, _ -> refusal },
        baseCheck = { _, branch -> require(branch == "main") { "branch '$branch' does not resolve" } },
    )

    private val svc = services()
    private val owner = Caller(owner = true)
    private fun asSession(id: String) = Caller(owner = false, agent = true, activeSession = id)

    private fun <T> BoardResult<T>.ok(): T = if (this is BoardResult.Ok) value else error("expected Ok, got $this")
    private fun BoardResult<*>.refusedText(): String = (this as? BoardResult.Refused)?.text ?: error("expected Refused, got $this")

    private fun seed(title: String, project: String?) = board.save(TicketSave(title = title, projectId = project)).ticket

    // ---- golden: one per operation ---------------------------------------------------------------------------------

    @Test fun `get answers the ticket the board holds, by full id or short id`() {
        val t = seed("one", "A")
        assertEquals(LocalBoardServices.info(board.get(t.id)), svc.tickets.get(t.id, owner).ok())
        assertEquals(t.id, svc.tickets.get(t.id.takeLast(2), owner).ok().id)
        assertEquals("no ticket 'zzz' on this daemon", svc.tickets.get("zzz", owner).refusedText())
    }

    @Test fun `list filters, keeps board order and pages with a cursor`() {
        val ids = (1..5).map { seed("t$it", "A").id }
        seed("other", "B")
        val first = svc.tickets.list(owner, TicketQuery(projectId = "A", limit = 2)).ok()
        assertEquals(ids.take(2), first.items.map { it.id })
        val second = svc.tickets.list(owner, TicketQuery(projectId = "A", limit = 2, cursor = first.next)).ok()
        assertEquals(ids.drop(2).take(2), second.items.map { it.id })
        val last = svc.tickets.list(owner, TicketQuery(projectId = "A", limit = 2, cursor = second.next)).ok()
        assertEquals(ids.takeLast(1), last.items.map { it.id })
        assertNull(last.next)
        assertEquals(6, svc.tickets.list(owner).ok().items.size)
        assertEquals(0, svc.tickets.list(owner, TicketQuery(status = setOf("done"))).ok().items.size)
        assertTrue(svc.tickets.list(owner, TicketQuery(cursor = "nope")).refusedText().contains("unknown cursor"))
    }

    @Test fun `create stores the draft verbatim and is idempotent on its client key`() {
        val draft = TicketDraft(title = "new", description = "line1\nline2", projectId = "A", clientKey = "k1")
        val made = svc.tickets.create(draft, owner).ok()
        assertEquals("line1\nline2", board.get(made.id).description)
        assertEquals("A", made.projectId)
        assertEquals(made.id, svc.tickets.create(draft, owner).ok().id)
        assertEquals(1, board.list().size)
        assertTrue(svc.tickets.create(TicketDraft(title = " "), owner).refusedText().startsWith("ticket_save refused:"))
    }

    @Test fun `update changes only what the patch names`() {
        val t = seed("old", "A")
        val u = svc.tickets.update(t.id, TicketPatch(title = "newer"), owner).ok()
        assertEquals("newer", u.title)
        assertEquals(t.description, board.get(t.id).description)
    }

    @Test fun `move runs the status machine and a repeat is a no-op`() {
        val t = seed("m", "A")
        assertEquals("done", svc.tickets.move(t.id, "done", null, owner).ok().status)
        val updatedAt = board.get(t.id).updatedAt
        assertEquals("done", svc.tickets.move(t.id, "done", null, owner).ok().status)
        assertEquals(updatedAt, board.get(t.id).updatedAt)
        assertEquals("ticket_move refused: to start a ticket use start", svc.tickets.move(t.id, "in_progress", null, owner).refusedText())
        assertTrue(svc.tickets.move(t.id, "nonsense", null, owner).refusedText().startsWith("ticket_move refused:"))
    }

    @Test fun `delete link unlink and reorder do what the board does`() {
        val a = seed("a", "A"); val b = seed("b", "A"); val c = seed("c", "A")
        val linked = svc.tickets.link(a.id, b.id, "blocked_by", owner).ok()
        assertTrue(linked.links.any { it.ticketId == b.id })
        assertEquals("pending", board.get(a.id).status)
        assertFalse(svc.tickets.unlink(a.id, b.id, "blocked_by", owner).ok().links.any { it.ticketId == b.id })
        svc.tickets.reorder("todo", listOf(c.id, b.id), owner).ok()
        assertEquals(c.id, board.list().first { it.status == "todo" }.id)
        svc.tickets.delete(c.id, owner).ok()
        assertTrue(board.list().none { it.id == c.id })
        assertTrue(svc.tickets.reorder("todo", listOf("ghost"), owner).refusedText().contains("no ticket 'ghost'"))
    }

    @Test fun `start answers the board start outcome`() {
        val t = seed("s", "A")
        val reference = board.move(TicketMove(id = seed("same", "A").id, status = "in_progress")).ticket.start
        val started = svc.tickets.start(t.id, owner).ok()
        assertEquals(reference?.reason, started.reason)
        assertEquals(reference?.ok, started.ok)
        assertEquals(t.id, started.ticket.id)
    }

    @Test fun `starterOf ticketOf and workerOf answer through the orchestrator link and the session records`() {
        assertEquals("s-pm", svc.tickets.starterOf("s-a", owner).ok())
        assertEquals("ticket-x", svc.tickets.ticketOf("s-a", owner).ok())
        assertNull(svc.tickets.ticketOf("s-b", owner).ok())
        assertEquals("s-a", svc.tickets.workerOf("ticket-x", owner).ok()?.sessionId)
    }

    @Test fun `epics create get list and set the base branch`() {
        val e = svc.epics.create("A", "epic one", "goal", owner).ok()
        assertEquals(e, svc.epics.get(e.id, owner).ok())
        assertEquals(listOf(e.id), svc.epics.list("A", owner).ok().map { it.id })
        assertEquals("main", svc.epics.setBase(e.id, "main", false, owner).ok().baseBranch)
        assertEquals("main", svc.epics.setBase(e.id, null, false, owner).ok().baseBranch)
        assertTrue(svc.epics.setBase(e.id, "nope", false, owner).refusedText().contains("does not resolve"))
        assertEquals("", svc.epics.setBase(e.id, null, true, owner).ok().baseBranch)
        assertTrue(svc.epics.get("epic-none", owner).refusedText().contains("unknown epic"))
        assertTrue(svc.epics.list(null, owner).refusedText().contains("name a project"))
    }

    @Test fun `board columns come in status order and brief is null without a worker`() {
        val t = seed("c", "A")
        board.move(TicketMove(seed("d", "A").id, "done"))
        val columns = svc.boardView.columns("A", owner).ok()
        assertEquals(TicketStatus.ALL, columns.map { it.status })
        assertEquals(listOf(t.id), columns.first { it.status == "todo" }.tickets.map { it.id })
        assertNull(svc.boardView.brief(t.id, owner).ok())
    }

    // ---- the API is enough for a tool: agenthq_ticket_get's answer, rendered from TicketInfo ----------------------------

    /** What a plugin's `agenthq_ticket_get` renders from the API alone (the staged Infantry_Board half). */
    private fun renderedFromApi(t: com.superiorsoftllc.agenthq.daemon.api.board.TicketInfo) = kotlinx.serialization.json.buildJsonObject {
        fun put(k: String, v: String?) = put(k, kotlinx.serialization.json.JsonPrimitive(v))
        put("ticketId", t.id); put("title", t.title); put("description", t.description); put("status", t.status)
        put("projectId", t.projectId); put("epicId", t.epicId); put("agentId", t.agentId)
        put("role", t.agentId?.takeIf { it.startsWith("role:") }?.removePrefix("role:"))
        put("provider", t.provider); put("model", t.model)
        put("weight", kotlinx.serialization.json.JsonPrimitive(t.weight))
        put("effectiveProvider", t.effectiveProvider); put("effectiveProviderSource", t.effectiveProviderSource)
        put("effectiveModel", t.effectiveModel); put("effectiveModelSource", t.effectiveModelSource); put("effectiveRule", t.effectiveRule)
        put("branch", t.branch); put("sessionId", t.sessionId)
        put("images", kotlinx.serialization.json.JsonArray(t.images.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }

    @Test fun `the API carries every field agenthq_ticket_get answers, byte for byte`() {
        val t = board.save(TicketSave(title = "parity", description = "a\nb", projectId = "A", weight = 3)).ticket
        val json = kotlinx.serialization.json.Json { encodeDefaults = false; explicitNulls = false }
        val expected = json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), com.superiorsoftllc.agenthq.cli.mcp.McpTicketTools.summary(board.get(t.id)))
        val viaApi = json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), renderedFromApi(svc.tickets.get(t.id.takeLast(8), owner).ok()))
        assertEquals(expected, viaApi)
    }

    // ---- scope: applied by the host, to every call -----------------------------------------------------------------

    @Test fun `a caller of project A cannot read or change project B tickets through the API`() {
        val mine = seed("mine", "A"); val theirs = seed("theirs", "B")
        val a = asSession("s-a")
        assertEquals(mine.id, svc.tickets.get(mine.id, a).ok().id)
        assertTrue(svc.tickets.get(theirs.id, a).refusedText().contains("belongs to project 'B'"))
        assertEquals(listOf(mine.id), svc.tickets.list(a).ok().items.map { it.id })
        assertTrue(svc.tickets.list(a, TicketQuery(projectId = "all")).refusedText().contains("pm / owner"))
        assertTrue(svc.tickets.list(a, TicketQuery(projectId = "B")).refusedText().contains("not this session's project"))
        assertIs<BoardResult.Refused>(svc.tickets.move(theirs.id, "done", null, a))
        assertIs<BoardResult.Refused>(svc.tickets.update(theirs.id, TicketPatch(title = "x"), a))
        assertIs<BoardResult.Refused>(svc.tickets.delete(theirs.id, a))
        assertIs<BoardResult.Refused>(svc.tickets.start(theirs.id, a))
        assertIs<BoardResult.Refused>(svc.tickets.link(mine.id, theirs.id, "relates_to", a))
        assertIs<BoardResult.Refused>(svc.tickets.reorder("todo", listOf(theirs.id), a))
        assertIs<BoardResult.Refused>(svc.tickets.create(TicketDraft(title = "x", projectId = "B"), a))
        assertIs<BoardResult.Refused>(svc.tickets.workerOf("ticket-x", asSession("s-b")))
        assertEquals("theirs", board.get(theirs.id).title)
        assertEquals("todo", board.get(theirs.id).status)
        assertEquals("A", svc.tickets.create(TicketDraft(title = "dflt"), a).ok().projectId)
        // a pm caller may reach every project
        assertEquals(theirs.id, svc.tickets.get(theirs.id, asSession("s-pm")).ok().id)
        assertTrue(svc.tickets.list(asSession("s-pm"), TicketQuery(projectId = "all")).ok().items.any { it.projectId == "B" })
    }

    @Test fun `an unknown session or an agent connection with no session is refused, never widened`() {
        seed("x", "A")
        assertTrue(svc.tickets.list(asSession("ghost")).refusedText().contains("unknown to this daemon"))
        assertTrue(svc.tickets.list(Caller(owner = false, agent = true)).refusedText().contains("needs a session"))
    }

    // ---- policy ----------------------------------------------------------------------------------------------------

    @Test fun `a locked policy refuses writes through the API with its neutral text and leaves the board alone`() {
        val t = seed("p", "A")
        refusal = "refused by policy 'locked'"
        assertEquals(refusal, svc.tickets.create(TicketDraft(title = "n"), owner).refusedText())
        assertEquals(refusal, svc.tickets.update(t.id, TicketPatch(title = "n"), owner).refusedText())
        assertEquals(refusal, svc.tickets.move(t.id, "done", null, owner).refusedText())
        assertEquals(refusal, svc.tickets.delete(t.id, owner).refusedText())
        assertEquals(refusal, svc.tickets.start(t.id, owner).refusedText())
        assertEquals(refusal, svc.epics.create("A", "e", "", owner).refusedText())
        assertEquals(refusal, svc.tickets.get(t.id, owner).refusedText())
        refusal = null
        assertEquals(1, board.list().size)
        assertEquals("p", board.get(t.id).title)
        assertEquals("todo", board.get(t.id).status)
    }

    // ---- unavailable -------------------------------------------------------------------------------------------------

    @Test fun `an unreachable orchestrator is Unavailable, not an exception and not a guess`() {
        link = Link(starter = OrchestratorReply.Unavailable("down"), ticket = OrchestratorReply.Unavailable("down"))
        assertEquals(BoardResult.Unavailable("down"), svc.tickets.starterOf("s-a", owner))
        assertEquals(BoardResult.Unavailable("down"), svc.tickets.workerOf("ticket-x", owner))
        // the session-record read does not need the answer of the link
        assertEquals("ticket-x", svc.tickets.ticketOf("s-a", owner).ok())
        link = null
        assertIs<BoardResult.Unavailable>(svc.tickets.starterOf("s-a", owner))
    }

    @Test fun `a daemon with no board answers Unavailable everywhere`() {
        val none = services(withBoard = false)
        assertIs<BoardResult.Unavailable>(none.tickets.list(owner))
        assertIs<BoardResult.Unavailable>(none.tickets.create(TicketDraft(title = "x"), owner))
        assertIs<BoardResult.Unavailable>(none.epics.list("A", owner))
        assertIs<BoardResult.Unavailable>(none.boardView.columns("A", owner))
        assertIs<BoardResult.Unavailable>(NoBoard.tickets.get("x", owner))
    }

    // ---- grants ------------------------------------------------------------------------------------------------------

    @Test fun `a community plugin needs the grant of each operation, an official one does not`() {
        val t = seed("g", "A")
        val granted = setOf(GRANT_TICKETS_READ)
        val community = GrantedBoard({ svc.tickets }, { svc.epics }, { svc.boardView }) { it in granted }
        assertEquals(t.id, community.ticketsApi.get(t.id, owner).ok().id)
        assertEquals("missing grant 'tickets.write' in modules.json", community.ticketsApi.create(TicketDraft(title = "n"), owner).refusedText())
        assertEquals("missing grant 'tickets.write' in modules.json", community.ticketsApi.move(t.id, "done", null, owner).refusedText())
        assertEquals("missing grant 'tickets.delete' in modules.json", community.ticketsApi.delete(t.id, owner).refusedText())
        assertEquals("missing grant 'tickets.start' in modules.json", community.ticketsApi.start(t.id, owner).refusedText())
        assertEquals("missing grant 'epics.write' in modules.json", community.epicsApi.create("A", "e", "", owner).refusedText())
        assertEquals(1, board.list().size)
        val official = GrantedBoard({ svc.tickets }, { svc.epics }, { svc.boardView }) { true }
        assertNotNull(official.ticketsApi.create(TicketDraft(title = "n"), owner).ok())
    }
}
