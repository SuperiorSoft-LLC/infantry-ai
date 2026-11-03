package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.board.*

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import com.superiorsoftllc.infantry.ui.protocol.TicketColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Records what the board sends; answers nothing (the board's reaction to answers is BoardDropTest's). */
private class RecordingLink(scope: CoroutineScope) : DaemonClient(scope) {
    val sent = mutableListOf<Envelope>()
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 8)
    override val frames: SharedFlow<Envelope> get() = incoming

    /** Say READY once the Controller listens, as the daemon does on connect. */
    fun ready() = runBlocking {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 10_000.milliseconds
        while (incoming.subscriptionCount.value == 0 && deadline.hasNotPassedNow()) delay(10)
        check(incoming.tryEmit(Envelope(Envelope.READY, "provider=stub contract=1.105.0 instance=A since=0")))
    }
    override suspend fun send(envelope: Envelope): Boolean {
        synchronized(sent) { sent += envelope }
        return true
    }
    fun moves() = synchronized(sent) { sent.filter { it.type == Envelope.TICKET_MOVE }.map { Json.parseToJsonElement(it.data).jsonObject } }
}

/**
 * The board's drag handle in the real surface: the grab cursor on hover (through the Clickables.kt wrapper, as the
 * HandCursorGuardTest rule requires), the card body still opening the ticket, and a real mouse drag sending a move.
 */
@OptIn(ExperimentalTestApi::class)
class BoardDragUiTest {
    private val tickets = listOf("a", "b", "c").mapIndexed { i, id -> Ticket(id = id, title = id.uppercase(), status = TicketColumns.TODO, position = i) } +
        Ticket(id = "d", title = "D", status = TicketColumns.DONE, position = 0)

    private fun state(contract: String? = "1.105.0"): AppState =
        if (contract == null) AppState().copy(tickets = tickets)
        else reduce(AppState(), Envelope(Envelope.READY, "provider=stub contract=$contract instance=A since=0"), 0).copy(tickets = tickets)

    private fun hasCursor(icon: PointerIcon) = SemanticsMatcher("hover cursor is $icon") { node ->
        node.config.any { (k, v) -> k.name == "HoverCursor" && v == icon }
    }

    private fun hasNoCursor() = SemanticsMatcher("no hover cursor") { node -> node.config.none { (k, _) -> k.name == "HoverCursor" } }

    @Test fun `every card's handle shows the grab cursor and the card body still shows the hand`() = runDesktopComposeUiTest(1000, 700) {
        val controller = Controller(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        setContent { BoardPane(state(), controller, onOpenAgent = {}) }
        tickets.forEach { onNodeWithTag(TICKET_HANDLE_TAG_PREFIX + it.id, useUnmergedTree = true).assert(hasCursor(GrabIcon)) }
        onNodeWithTag(TICKET_CARD_TAG_PREFIX + "a").assert(hasCursor(PointerIcon.Hand))
    }

    @Test fun `an offline board's handle keeps the arrow`() = runDesktopComposeUiTest(1000, 700) {
        val controller = Controller(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        setContent { BoardPane(state(contract = null), controller, onOpenAgent = {}) }
        onNodeWithTag(TICKET_HANDLE_TAG_PREFIX + "a", useUnmergedTree = true).assert(hasNoCursor())
    }

    @Test fun `dragging the third card's handle above the first sends its new position`() = runDesktopComposeUiTest(1000, 700) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val link = RecordingLink(scope)
        val controller = Controller(scope, link)
        link.ready()
        waitUntil("the controller is live", 5_000) { controller.state.value.connection.isLive }
        setContent { BoardPane(state(), controller, onOpenAgent = {}) }
        val from = onNodeWithTag(TICKET_HANDLE_TAG_PREFIX + "c", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val top = onNodeWithTag(TICKET_CARD_TAG_PREFIX + "a").fetchSemanticsNode().boundsInRoot
        val up = top.top - from.center.y - 4f
        onNodeWithTag(TICKET_HANDLE_TAG_PREFIX + "c", useUnmergedTree = true).performMouseInput {
            moveTo(center)
            press()
            repeat(10) { moveBy(Offset(0f, up / 10)) }
            release()
        }
        waitForIdle()
        waitUntil("a ticket_move was sent", 5_000) { link.moves().isNotEmpty() }
        val move = link.moves().single()
        assertEquals("c", move.getValue("id").jsonPrimitive.content)
        assertEquals(0, move.getValue("position").jsonPrimitive.intOrNull)
        assertNull(move["status"])
    }

    @Test fun `dragging a card onto another column sends a status move`() = runDesktopComposeUiTest(1000, 700) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val link = RecordingLink(scope)
        val controller = Controller(scope, link)
        link.ready()
        waitUntil("the controller is live", 5_000) { controller.state.value.connection.isLive }
        setContent { BoardPane(state(), controller, onOpenAgent = {}) }
        val from = onNodeWithTag(TICKET_HANDLE_TAG_PREFIX + "b", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val done = onNodeWithTag(BOARD_COLUMN_TAG_PREFIX + TicketColumns.DONE).fetchSemanticsNode().boundsInRoot
        val doneCard = onNodeWithTag(TICKET_CARD_TAG_PREFIX + "d").fetchSemanticsNode().boundsInRoot
        val by = Offset(done.center.x - from.center.x, doneCard.bottom + 40f - from.center.y)
        onNodeWithTag(TICKET_HANDLE_TAG_PREFIX + "b", useUnmergedTree = true).performMouseInput {
            moveTo(center)
            press()
            repeat(10) { moveBy(by / 10f) }
            release()
        }
        waitForIdle()
        waitUntil("a ticket_move was sent", 5_000) { link.moves().isNotEmpty() }
        val move = link.moves().single()
        assertEquals("b", move.getValue("id").jsonPrimitive.content)
        assertEquals(TicketColumns.DONE, move.getValue("status").jsonPrimitive.content)
        assertEquals(1, move.getValue("position").jsonPrimitive.intOrNull)
    }
}
