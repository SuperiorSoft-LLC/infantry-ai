package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.model.withEventsAwaiting
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.Ticket
import com.superiorsoftllc.agenthq.ui.protocol.TicketColumns
import com.superiorsoftllc.agenthq.ui.protocol.TicketStart
import com.superiorsoftllc.agenthq.ui.protocol.TriggerEventView
import com.superiorsoftllc.agenthq.ui.protocol.TriggerView
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The scrum board (contracts 1.5.0 / 1.6.0) and the trigger list (contract 1.14.0) are rendered from frames only. */
class BoardTriggersModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun feed(vararg e: Envelope, from: AppState = AppState()) = e.fold(from) { s, f -> reduce(s, f, 100) }

    private fun ticket(id: String, status: String, position: Int, start: TicketStart? = null) =
        Ticket(id = id, title = "T $id", status = status, position = position, start = start)

    private fun tickets(vararg t: Ticket) = Envelope(Envelope.TICKETS, json.encodeToString(ListSerializer(Ticket.serializer()), t.toList()))
    private fun one(t: Ticket) = Envelope(Envelope.TICKET, json.encodeToString(Ticket.serializer(), t))
    private fun trigger(id: String, state: String = "armed") = TriggerView(id = id, name = "N $id", state = state)
    private fun triggers(vararg t: TriggerView) = Envelope(Envelope.TRIGGERS, json.encodeToString(ListSerializer(TriggerView.serializer()), t.toList()))
    private fun events(vararg e: TriggerEventView) = Envelope(Envelope.TRIGGER_EVENTS, json.encodeToString(ListSerializer(TriggerEventView.serializer()), e.toList()))
    private fun event(id: String, trigger: String, disposition: String) = TriggerEventView(id, trigger, 1, "manual", disposition)

    @Test fun `the board is the daemon's list, each column top first, and a pushed ticket moves in place`() {
        val s = feed(tickets(ticket("b", TicketColumns.TODO, 1), ticket("a", TicketColumns.TODO, 0), ticket("c", TicketColumns.DONE, 0)))
        assertEquals(listOf("a", "b"), s.column(TicketColumns.TODO).map { it.id })
        assertEquals(emptyList(), s.column(TicketColumns.IN_PROGRESS))

        val moved = feed(one(ticket("a", TicketColumns.IN_PROGRESS, 0)), one(ticket("d", TicketColumns.TODO, 5)), from = s)
        assertEquals(listOf("a"), moved.column(TicketColumns.IN_PROGRESS).map { it.id })
        assertEquals(listOf("b", "d"), moved.column(TicketColumns.TODO).map { it.id }, "a new ticket joins; a known one is replaced, not duplicated")
        assertEquals(4, moved.tickets.size)
        assertEquals("T a", moved.ticket("a")!!.title)
        assertNull(moved.ticket("nope"))
    }

    @Test fun `a delete answers with the whole board, which replaces what was held`() {
        val s = feed(tickets(ticket("a", TicketColumns.TODO, 0), ticket("b", TicketColumns.TODO, 1)))
        assertEquals(listOf("b"), feed(tickets(ticket("b", TicketColumns.TODO, 0)), from = s).tickets.map { it.id })
    }

    @Test fun `a start the daemon did not carry out is said in a notice, and a start that ran is not`() {
        val refused = feed(one(ticket("a", TicketColumns.TODO, 0, TicketStart(false, "start_refused", "no repo configured"))))
        assertEquals(1, refused.notices.size)
        assertTrue(refused.notices.single().text.contains("start_refused") && refused.notices.single().text.contains("no repo configured"), refused.notices.single().text)

        val started = feed(one(ticket("a", TicketColumns.IN_PROGRESS, 0, TicketStart(true, "started"))))
        assertEquals(emptyList(), started.notices)
    }

    @Test fun `a ticket from a newer daemon with fields this build does not know still decodes`() {
        val raw = """{"id":"x","title":"Future","status":"todo","position":0,"createdAt":1,"updatedAt":1,"brandNew":{"a":1}}"""
        assertEquals("Future", feed(Envelope(Envelope.TICKET, raw)).ticket("x")!!.title)
    }

    @Test fun `triggers come as a list and a pushed trigger replaces its own row, so a trip shows at once`() {
        val s = feed(triggers(trigger("t1"), trigger("t2")))
        assertEquals(0, s.trippedTriggerCount)
        val tripped = feed(Envelope(Envelope.TRIGGER, json.encodeToString(TriggerView.serializer(), trigger("t2", "tripped").copy(trippedReason = "run failed"))), from = s)
        assertEquals(listOf("armed", "tripped"), tripped.triggers.map { it.state })
        assertEquals(1, tripped.trippedTriggerCount)
        assertEquals("run failed", tripped.trigger("t2")!!.trippedReason)
        assertEquals(listOf("t1"), feed(triggers(trigger("t1")), from = tripped).triggers.map { it.id }, "a delete answers with what is left")
    }

    @Test fun triggerViewDecodesIntervalMs() {
        val withField = json.decodeFromString(
            TriggerView.serializer(),
            """{"id":"t1","name":"N t1","state":"armed","intervalMs":60000}""",
        )
        assertEquals(60_000L, withField.intervalMs, "a schedule trigger's period decodes from the intervalMs field")

        val without = json.decodeFromString(
            TriggerView.serializer(),
            """{"id":"t2","name":"N t2","state":"armed"}""",
        )
        assertNull(without.intervalMs, "a trigger sent without intervalMs still decodes, with no period")
    }

    @Test fun `event rows are kept per trigger, and an empty answer is matched to the trigger that asked`() {
        val s = feed(triggers(trigger("t1"), trigger("t2")))
        val withRows = feed(events(event("e1", "t1", "started"), event("e2", "t1", "folded")), from = s)
        assertEquals(listOf("started", "folded"), withRows.triggerEvents.getValue("t1").map { it.disposition })

        val asked = withRows.withEventsAwaiting("t2")
        val empty = feed(events(), from = asked)
        assertEquals(emptyList(), empty.triggerEvents.getValue("t2"))
        assertEquals(emptyList(), empty.eventsAwaiting, "the request is answered")
        assertEquals(2, empty.triggerEvents.getValue("t1").size, "another trigger's rows are untouched")
    }

    @Test fun `deleting a trigger drops its event rows`() {
        val s = feed(triggers(trigger("t1"), trigger("t2")), events(event("e1", "t2", "started")))
        assertEquals(setOf("t2"), s.triggerEvents.keys)
        assertEquals(emptySet(), feed(triggers(trigger("t1")), from = s).triggerEvents.keys)
    }
    // S4: the trigger target lanes' test moved to :agenthqAppUi with triggerLanes (TriggerLanesTest).
}
