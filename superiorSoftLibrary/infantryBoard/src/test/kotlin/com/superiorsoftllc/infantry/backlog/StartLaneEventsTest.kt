// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.daemon.api.board.BoardAgent
import com.superiorsoftllc.infantry.daemon.api.board.TicketEventAppender
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.protocol.TicketStatus.IN_PROGRESS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** START-LANE-1: a hook-started ticket is pushed In Progress to the board feed AND its started event is delivered at once. */
class StartLaneEventsTest {

    @Test
    fun aStartedTicketIsPushedInProgressAndItsEventsAreDelivered() {
        val host = InMemoryBoardHost()
        val worker = host.agents.save(BoardAgent(name = "Backend", role = "backend"))
        val pushes = mutableListOf<BoardEvent>()
        var delivers = 0
        val board = TicketBoard(host, idFactory = { "a" }, profilesAvailable = true)
        board.eventSink = { pushes += it }
        board.events = object : TicketEventAppender {
            override fun record(before: Map<String, String>, after: Map<String, String>, changedIds: Collection<String>, deleted: Collection<String>) {}
            override fun deliver() { delivers++ }
        }
        val t = board.save(TicketSave(title = "Next", agentId = worker.id)).ticket
        pushes.clear()
        delivers = 0

        val answer = board.move(TicketMove(t.id, IN_PROGRESS))

        assertEquals("started", answer.ticket.start?.reason)
        val feed = pushes.filterIsInstance<BoardEvent.Changed>().flatMap { it.tickets }
        assertTrue(feed.any { it.id == t.id && it.status == IN_PROGRESS }, "the feed carries the ticket In Progress: $feed")
        assertTrue(delivers >= 1, "the started event reaches the subscribers right away")
    }
}
