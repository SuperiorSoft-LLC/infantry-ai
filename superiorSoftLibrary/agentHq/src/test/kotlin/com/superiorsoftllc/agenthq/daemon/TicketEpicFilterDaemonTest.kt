package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import com.superiorsoftllc.agenthq.protocol.Ticket
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E1-b (contract 1.66.0), over the REAL daemon and a real WebSocket: `ticket_list` takes an optional
 * `epicId` filter. The answer is the board's own `tickets` frame, so the order is board order; an epic no
 * ticket is filed under answers an empty board, never an error. The legacy forms (empty data, a bare
 * project id) keep answering as before.
 */
class TicketEpicFilterDaemonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun withDaemon(block: (DaemonFixture) -> Unit) {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)
            block(fixture)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    private fun DaemonFixture.create(title: String, extra: String = ""): Ticket {
        send(Envelope.TICKET_SAVE, """{"title":"$title"$extra}""")
        val frame = client.awaitWhere("the create of $title") { envelope ->
            envelope.type == Envelope.TICKET && runCatching {
                json.decodeFromString(Ticket.serializer(), envelope.data)
            }.getOrNull()?.title == title
        }
        return json.decodeFromString(Ticket.serializer(), frame.data)
    }

    /** Sends `ticket_list` with [data] and answers the next `tickets` frame, decoded. */
    private fun DaemonFixture.list(data: String): List<Ticket> {
        val before = client.of(Envelope.TICKETS).size
        send(Envelope.TICKET_LIST, data)
        val frame = client.await(Envelope.TICKETS, occurrence = before)
        return json.decodeFromString(ListSerializer(Ticket.serializer()), frame.data)
    }

    @Test
    fun theEpicFilterAnswersOnlyThatEpicsTicketsInBoardOrder() = withDaemon { fixture ->
        val a1 = fixture.create("A one", ""","epicId":"epic-A","projectId":"proj-1"""")
        val b1 = fixture.create("B one", ""","epicId":"epic-B","projectId":"proj-1"""")
        val loose = fixture.create("No epic", ""","projectId":"proj-1"""")
        val a2 = fixture.create("A two", ""","epicId":"epic-A","projectId":"proj-2"""")

        // Board order is not creation order: put A two on top of todo, so the filter must keep the board's order.
        fixture.send(Envelope.TICKET_REORDER, """{"status":"todo","ids":["${a2.id}"]}""")
        fixture.client.await(Envelope.TICKETS) // the reorder's own push, so the next answers are counted from here
        val whole = fixture.list("")
        assertEquals(listOf(a2.id, a1.id, b1.id, loose.id), whole.map { it.id }, "the reorder must have landed")

        val epicA = fixture.list("""{"epicId":"epic-A"}""")
        assertEquals(listOf(a2.id, a1.id), epicA.map { it.id }, "filter epic-A answers exactly its 2, in board order")

        assertEquals(listOf(b1.id), fixture.list("""{"epicId":"epic-B"}""").map { it.id })

        val unknown = fixture.list("""{"epicId":"epic-nobody"}""")
        assertEquals(emptyList(), unknown.map { it.id }, "an unknown epic answers an empty board, not an error")
        assertTrue(fixture.client.of(Envelope.ERROR).isEmpty(), "no error frame: ${fixture.client.of(Envelope.ERROR)}")

        // The filters compose: project AND epic.
        assertEquals(listOf(a1.id), fixture.list("""{"projectId":"proj-1","epicId":"epic-A"}""").map { it.id })
        // A blank epicId is no filter; a bare project id (UI-BOARD-PROJ) keeps working.
        assertEquals(4, fixture.list("""{"epicId":""}""").size)
        assertEquals(listOf(a1.id, b1.id, loose.id), fixture.list("proj-1").map { it.id })

        // E1-b shipped as contract 1.66.0; the pin tracks the live contract, not that number.
        assertTrue(fixture.client.of(Envelope.READY).single().data.contains("contract=${EventVocabulary.VERSION}"))
    }
}
