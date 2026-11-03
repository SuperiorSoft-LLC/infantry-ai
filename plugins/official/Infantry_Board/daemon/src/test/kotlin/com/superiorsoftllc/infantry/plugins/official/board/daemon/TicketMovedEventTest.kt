// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.board.TicketEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SCRIPT-5: the `ticket.moved` payload a lane-enter hook reads. */
class TicketMovedEventTest {
    @Test fun doneEventCarriesTicketAndLanes() {
        val e = TicketEvent(seq = 7, ticketId = "ticket-abc", kind = TicketEvent.DONE, from = "in_progress", to = "done")
        assertTrue(e.isTransition)
        val o = Json.parseToJsonElement(TicketMovedEvent.payload(e)).jsonObject
        assertEquals("ticket-abc", o["ticketId"]!!.jsonPrimitive.content)
        assertEquals("in_progress", o["from"]!!.jsonPrimitive.content)
        assertEquals("done", o["to"]!!.jsonPrimitive.content)
        assertEquals("done", o["kind"]!!.jsonPrimitive.content)
        assertEquals("7", o["seq"]!!.jsonPrimitive.content)
    }

    @Test fun doneEventCarriesTheAgentQueueHeadOnlyWhenThereIsOne() {
        val e = TicketEvent(seq = 8, ticketId = "ticket-abc", kind = TicketEvent.DONE, from = "in_progress", to = "done")
        assertEquals("ticket-next", Json.parseToJsonElement(TicketMovedEvent.payload(e, "ticket-next")).jsonObject["agentQueueNext"]!!.jsonPrimitive.content)
        assertFalse(Json.parseToJsonElement(TicketMovedEvent.payload(e)).jsonObject.containsKey("agentQueueNext"))
    }

    @Test fun createdEventHasNoFromAndUpdatesAreNotTransitions() {
        val created = TicketEvent(seq = 1, ticketId = "t", kind = TicketEvent.CREATED, to = "todo")
        assertFalse(Json.parseToJsonElement(TicketMovedEvent.payload(created)).jsonObject.containsKey("from"))
        assertFalse(TicketEvent(seq = 2, ticketId = "t", kind = TicketEvent.UPDATED, to = "todo").isTransition)
    }
}
