// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.agentWork
import com.superiorsoftllc.infantry.ui.protocol.AgentQueueItem
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DragSeamTest {
    private val box = Rect(0f, 0f, 10f, 10f)
    private val takesTicket: (DragPayload) -> Boolean = { it.kind == "ticket" }

    @Test fun hitsOnlyAnAcceptingTargetUnderThePointer() {
        val targets = mapOf<Any, Pair<Rect, (DragPayload) -> Boolean>>("a" to (box to takesTicket))
        assertEquals("a", DragSeam.hit(DragPayload("ticket", "t1"), Offset(5f, 5f), targets))
        assertNull(DragSeam.hit(DragPayload("ticket", "t1"), Offset(50f, 5f), targets))
        assertNull(DragSeam.hit(DragPayload("other", "x"), Offset(5f, 5f), targets))
        assertNull(DragSeam.hit(null, Offset(5f, 5f), targets))
    }

    @Test fun agentWorkIsTheInProgressTicketAndTheQueue() {
        val running = Ticket(id = "t1", title = "run", status = "in_progress", agentId = "p1")
        val other = Ticket(id = "t2", title = "other", status = "in_progress", agentId = "p2")
        val s = AppState(tickets = listOf(other, running), agentQueues = mapOf("p1" to listOf(AgentQueueItem("t3", "next", 1))))
        val w = s.agentWork("p1")
        assertEquals("t1", w.current?.id)
        assertEquals(listOf("t3"), w.queue.map { it.ticketId })
        assertNull(s.agentWork("p9").current)
        assertEquals(emptyList(), s.agentWork(null).queue)
    }

    /** CARD-TICKET-1: the session's own ticket is the card's ticket (body and menu), even when the board holds it under a role. */
    @Test fun sessionTicketWinsOverTheProfileMatch() {
        val roleHeld = Ticket(id = "t5", title = "role run", status = "in_progress", agentId = "role:backend")
        val done = Ticket(id = "t6", title = "finished", status = "done", agentId = "role:backend")
        val assigned = Ticket(id = "t1", title = "run", status = "in_progress", agentId = "p1")
        val s = AppState(tickets = listOf(roleHeld, done))
        assertEquals("t5", s.agentWork("p1", "t5").current?.id)
        assertEquals("t5", s.agentWork(null, "t5").current?.id)
        assertNull(s.agentWork("p1", "t6").current)
        assertEquals("t1", s.copy(tickets = listOf(done, assigned)).agentWork("p1", "t6").current?.id)
        assertEquals("t9", s.agentWork("p1", "t9").current?.id)
        assertNull(s.agentWork("p1", "").current)
    }
}
