package com.superiorsoftllc.infantry.plugins.official.board

import com.superiorsoftllc.infantry.ui.*
import com.superiorsoftllc.infantry.ui.shell.blocks.*

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.BoardDrop
import com.superiorsoftllc.infantry.ui.model.DropPlan
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.Project
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import com.superiorsoftllc.infantry.ui.protocol.TicketColumns
import com.superiorsoftllc.infantry.ui.protocol.TicketStart
import com.superiorsoftllc.infantry.ui.shell.CoreTickets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A fake daemon for the board: it holds the columns and answers `ticket_move` the way `TicketBoard` does (a status
 * and/or a position placed by index after the ticket is lifted out; one `ticket` per changed ticket). A move to
 * `in_progress` is a hand-out: [startAnswer] decides whether it starts or what refusal it answers.
 */
private class BoardLink(scope: CoroutineScope, tickets: List<Ticket>) : DaemonClient(scope) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val sent = mutableListOf<Envelope>()
    var board: List<Ticket> = tickets
    /** Null = the start succeeds (appended to in_progress, start ok); else the refusal the ticket comes back with. */
    var startAnswer: TicketStart? = null
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    override val frames: SharedFlow<Envelope> get() = incoming

    override suspend fun send(envelope: Envelope): Boolean {
        synchronized(sent) { sent += envelope }
        if (envelope.type == Envelope.TICKET_MOVE) answerMove(Json.parseToJsonElement(envelope.data).jsonObject)
        return true
    }

    private fun answerMove(body: JsonObject) {
        val id = body.getValue("id").jsonPrimitive.content
        val status = body["status"]?.jsonPrimitive?.content
        val position = body["position"]?.jsonPrimitive?.intOrNull
        val row = board.first { it.id == id }
        if (status == TicketColumns.IN_PROGRESS && row.status != TicketColumns.IN_PROGRESS) {
            val refusal = startAnswer
            if (refusal != null) return emit(row.copy(start = refusal))
            place(id, TicketColumns.IN_PROGRESS, null)
            return board.forEach { emit(if (it.id == id) it.copy(start = TicketStart(ok = true, reason = "started")) else it) }
        }
        place(id, status ?: row.status, position)
        board.forEach { emit(it) }
    }

    private fun place(id: String, status: String, index: Int?) {
        val row = board.first { it.id == id }
        val columns = TicketColumns.ALL.associateWith { s -> board.filter { it.status == s && it.id != id }.sortedBy { it.position }.toMutableList() }
        val target = columns.getValue(status)
        target.add(index?.coerceIn(0, target.size) ?: target.size, row.copy(status = status))
        board = columns.values.flatMap { col -> col.mapIndexed { i, t -> t.copy(position = i) } }
    }

    private fun emit(t: Ticket) = check(incoming.tryEmit(Envelope(Envelope.TICKET, json.encodeToString(Ticket.serializer(), t)))) { "frame buffer full" }

    suspend fun push(e: Envelope) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 10_000.milliseconds
        while (incoming.subscriptionCount.value == 0 && deadline.hasNotPassedNow()) delay(10)
        check(incoming.tryEmit(e)) { "frame buffer full" }
    }

    fun moves(): List<JsonObject> = synchronized(sent) { sent.filter { it.type == Envelope.TICKET_MOVE }.map { Json.parseToJsonElement(it.data).jsonObject } }
}

/**
 * The board's drag and drop, end to end below the pixels: what a drop plans, the `ticket_move` it sends through the
 * real Controller, and what the daemon's answer does to the board and to the card that snapped back.
 */
class BoardDropTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun ticket(id: String, status: String, position: Int) = Ticket(id = id, title = id.uppercase(), projectId = "p1", status = status, position = position)

    private val todo = listOf(ticket("a", TicketColumns.TODO, 0), ticket("b", TicketColumns.TODO, 1), ticket("c", TicketColumns.TODO, 2))

    private class Board(val controller: Controller, val link: BoardLink, val session: BoardDropSession) {
        val state: AppState get() = controller.state.value
        fun column(status: String) = state.column(status)
        fun ids(status: String) = column(status).map { it.id }
        fun plan(id: String, to: String, slot: Int) = BoardDrop.plan(::column, state.ticket(id)!!, to, slot)
        suspend fun drop(plan: DropPlan) = session.drop(plan, state.ticket(plan.ticketId))
    }

    private fun withBoard(tickets: List<Ticket>, body: suspend Board.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = BoardLink(scope, tickets)
            val controller = Controller(scope, link)
            val backlog = CoreTickets(controller.state.value, controller)
            val session = BoardDropSession(ticketMoves { backlog })
            runBlocking {
                link.push(Envelope(Envelope.READY, "contract=1.105.0 provider=stub instance=i1"))
                link.push(Envelope(Envelope.PROJECTS, json.encodeToString(ListSerializer(Project.serializer()), listOf(Project("p1", "P1")))))
                link.push(Envelope(Envelope.TICKETS, json.encodeToString(ListSerializer(Ticket.serializer()), tickets)))
                await(controller) { it.connection.isLive && it.activeProjectId == "p1" && it.visibleTickets.size == tickets.size }
                // The board reads every change for a start's answer, as BoardPane does; on this thread, in order.
                val observer = launch { controller.state.collect { s -> session.observe(s.visibleTickets, s::column) } }
                try { Board(controller, link, session).body() } finally { observer.cancel() }
            }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun await(controller: Controller, what: (AppState) -> Boolean) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 10_000.milliseconds
        while (!what(controller.state.value) && deadline.hasNotPassedNow()) delay(10)
        check(what(controller.state.value)) { "state never reached the expected shape: ${controller.state.value.tickets}" }
    }

    private suspend fun Board.until(what: (AppState) -> Boolean) = await(controller, what)

    @Test fun `dragging the third card to the top sends its new position and the board keeps that order`() = withBoard(todo) {
        val plan = plan("c", TicketColumns.TODO, 0)
        assertEquals(DropPlan.Reorder("c", 0), plan)
        drop(plan)
        val move = link.moves().single()
        assertEquals("c", move.getValue("id").jsonPrimitive.content)
        assertEquals(0, move.getValue("position").jsonPrimitive.intOrNull)
        assertNull(move["status"], "a reorder never changes the status")
        until { it.column(TicketColumns.TODO).map { t -> t.id } == listOf("c", "a", "b") }
        assertEquals(listOf("c", "a", "b"), ids(TicketColumns.TODO))
    }

    @Test fun `dropping in place sends nothing`() = withBoard(todo) {
        val plan = plan("b", TicketColumns.TODO, 1)
        assertEquals(DropPlan.Stay("b"), plan)
        drop(plan)
        assertEquals(emptyList(), link.moves())
    }

    @Test fun `dragging a todo card into done changes its status at the slot it was dropped on`() = withBoard(todo + ticket("d", TicketColumns.DONE, 0)) {
        val plan = plan("b", TicketColumns.DONE, 0)
        assertEquals(DropPlan.Move("b", TicketColumns.DONE, 0), plan)
        drop(plan)
        val move = link.moves().single()
        assertEquals(TicketColumns.DONE, move.getValue("status").jsonPrimitive.content)
        assertEquals(0, move.getValue("position").jsonPrimitive.intOrNull)
        until { it.column(TicketColumns.DONE).map { t -> t.id } == listOf("b", "d") }
        assertEquals(listOf("a", "c"), ids(TicketColumns.TODO))
        assertEquals(TicketColumns.DONE, state.ticket("b")!!.status)
    }

    @Test fun `dropping into in progress starts the ticket and then places it at its slot`() = withBoard(todo + ticket("w", TicketColumns.IN_PROGRESS, 0)) {
        val plan = plan("b", TicketColumns.IN_PROGRESS, 0)
        assertEquals(DropPlan.Start("b", 0), plan)
        drop(plan)
        // The start is the Start action's frame exactly: a status move to in_progress, nothing else.
        val start = link.moves().first()
        assertEquals("b", start.getValue("id").jsonPrimitive.content)
        assertEquals(TicketColumns.IN_PROGRESS, start.getValue("status").jsonPrimitive.content)
        assertNull(start["position"], "the hand-out ignores a position; the placement follows the start")
        until { it.column(TicketColumns.IN_PROGRESS).map { t -> t.id } == listOf("b", "w") }
        val follow = link.moves()[1]
        assertNull(follow["status"], "placing a started card is a reorder, never a second start")
        assertEquals(0, follow.getValue("position").jsonPrimitive.intOrNull)
        assertEquals(2, link.moves().size)
        assertNull(session.notes["b"])
    }

    @Test fun `a refused start snaps the card back with the daemon's reason`() = withBoard(todo) {
        link.startAnswer = TicketStart(ok = false, reason = "start_refused", detail = "no profile resolved for ticket")
        drop(plan("a", TicketColumns.IN_PROGRESS, 0))
        until { session.notes["a"] != null }
        assertEquals("Not started: no profile resolved for ticket", session.notes["a"])
        assertEquals(listOf("a", "b", "c"), ids(TicketColumns.TODO), "the card never left To do")
        assertEquals(emptyList(), ids(TicketColumns.IN_PROGRESS))
        assertEquals(1, link.moves().size, "nothing follows a refused start")
        assertFalse(session.isStarting("a"))
    }

    @Test fun `a start refused for want of a role is told apart from every other refusal`() {
        val noRole = BoardDrop.startNote(TicketStart(false, "start_refused", "ticket has no role; set one with `infantry ticket update <id> --role <role id>`"))
        assertTrue(BoardDrop.isNoRoleNote(noRole))
        assertFalse(BoardDrop.isNoRoleNote(BoardDrop.startNote(TicketStart(false, "held", "ticket-x"))))
        assertFalse(BoardDrop.isNoRoleNote(BoardDrop.startNote(TicketStart(false, "queued", "role:qa"))))
    }

    @Test fun `a held or queued start also snaps back and says why`() {
        assertEquals("Not started: held by ticket-x", BoardDrop.startNote(TicketStart(false, "held", "ticket-x")))
        assertEquals("Not started: queued for role:qa, which is at capacity", BoardDrop.startNote(TicketStart(false, "queued", "role:qa")))
        withBoard(todo) {
            link.startAnswer = TicketStart(ok = false, reason = "queued", detail = "role:qa")
            drop(plan("c", TicketColumns.IN_PROGRESS, 0))
            until { session.notes["c"] != null }
            assertEquals("Not started: queued for role:qa, which is at capacity", session.notes["c"])
            assertEquals(TicketColumns.TODO, state.ticket("c")!!.status)
        }
    }

    @Test fun `reordering inside in progress never starts anything again`() = withBoard(listOf(ticket("x", TicketColumns.IN_PROGRESS, 0), ticket("y", TicketColumns.IN_PROGRESS, 1))) {
        val plan = plan("y", TicketColumns.IN_PROGRESS, 0)
        assertEquals(DropPlan.Reorder("y", 0), plan)
        drop(plan)
        val move = link.moves().single()
        assertNull(move["status"])
        until { it.column(TicketColumns.IN_PROGRESS).map { t -> t.id } == listOf("y", "x") }
    }

    @Test fun `a drop on pending is not allowed and sends nothing`() = withBoard(todo + ticket("p", TicketColumns.PENDING, 0)) {
        val plan = plan("a", TicketColumns.PENDING, 0)
        assertIs<DropPlan.NotAllowed>(plan)
        drop(plan)
        assertEquals(emptyList(), link.moves())
        assertEquals(BoardDrop.PENDING_REFUSAL, session.notes["a"])
        assertEquals(TicketColumns.TODO, state.ticket("a")!!.status)
        // A held card cannot be dragged out of pending either; the daemon would refuse it.
        val out = plan("p", TicketColumns.TODO, 0)
        assertEquals(DropPlan.NotAllowed("p", BoardDrop.HELD_REFUSAL), out)
        drop(out)
        assertEquals(emptyList(), link.moves())
        session.dismiss("p")
        assertNull(session.notes["p"])
    }

    @Test fun `a card moves between to do and backlog both ways and a backlog card is never dropped on in progress`() =
        withBoard(todo + ticket("k", TicketColumns.BACKLOG, 0)) {
            // To do -> Backlog, at the slot it was dropped on.
            val park = plan("b", TicketColumns.BACKLOG, 0)
            assertEquals(DropPlan.Move("b", TicketColumns.BACKLOG, 0), park)
            drop(park)
            until { it.column(TicketColumns.BACKLOG).map { t -> t.id } == listOf("b", "k") }
            // Backlog -> In progress: refused in the UI, nothing sent, the card stays.
            val sentBefore = link.moves().size
            val start = plan("k", TicketColumns.IN_PROGRESS, 0)
            assertEquals(DropPlan.NotAllowed("k", BoardDrop.BACKLOG_START_REFUSAL), start)
            drop(start)
            assertEquals(sentBefore, link.moves().size, "a refused drop sends nothing")
            assertEquals(BoardDrop.BACKLOG_START_REFUSAL, session.notes["k"])
            assertEquals(TicketColumns.BACKLOG, state.ticket("k")!!.status)
            // Backlog -> To do: a plain move.
            val promote = plan("k", TicketColumns.TODO, 0)
            assertEquals(DropPlan.Move("k", TicketColumns.TODO, 0), promote)
            drop(promote)
            until { it.column(TicketColumns.TODO).map { t -> t.id } == listOf("k", "a", "c") }
        }

    @Test fun `a held ticket may be parked from pending in backlog but nowhere else`() {
        assertNull(BoardDrop.refusal(TicketColumns.PENDING, TicketColumns.BACKLOG))
        assertEquals(BoardDrop.HELD_REFUSAL, BoardDrop.refusal(TicketColumns.PENDING, TicketColumns.TODO))
        assertEquals(BoardDrop.PENDING_REFUSAL, BoardDrop.refusal(TicketColumns.BACKLOG, TicketColumns.PENDING))
        assertNull(BoardDrop.refusal(TicketColumns.BACKLOG, TicketColumns.DONE))
        assertEquals(TicketColumns.BACKLOG, TicketColumns.ALL.first(), "Backlog is the first lane")
    }

    @Test fun `the pointer's slot counts the other cards above it`() {
        assertEquals(0, BoardDrop.slotAt(listOf(10f, 40f), 5f))
        assertEquals(1, BoardDrop.slotAt(listOf(10f, 40f), 20f))
        assertEquals(2, BoardDrop.slotAt(listOf(10f, 40f), 90f))
        assertEquals(0, BoardDrop.slotAt(emptyList(), 90f))
    }
}
