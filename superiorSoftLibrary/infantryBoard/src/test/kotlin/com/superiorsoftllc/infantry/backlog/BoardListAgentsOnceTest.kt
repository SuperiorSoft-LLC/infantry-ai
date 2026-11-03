// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.daemon.api.board.BoardAgent
import com.superiorsoftllc.infantry.daemon.api.board.BoardAgents
import com.superiorsoftllc.infantry.daemon.api.board.BoardHost
import com.superiorsoftllc.infantry.protocol.TicketSave
import kotlin.test.Test
import kotlin.test.assertEquals

/** BOARD-STALL-1: list() runs inside the board lock, so it must read the agents once, not once per ticket. */
class BoardListAgentsOnceTest {
    private class CountingHost(val inner: InMemoryBoardHost) : BoardHost by inner {
        var listCalls = 0
        override val agents: BoardAgents = object : BoardAgents by inner.agents {
            override fun list(): List<BoardAgent> { listCalls++; return inner.agents.list() }
        }
    }

    @Test
    fun listReadsTheAgentsOnceForFiftyTicketsAssignedToARole() {
        val host = CountingHost(InMemoryBoardHost())
        host.inner.agents.save(BoardAgent(name = "x1", role = "backend"))
        val ids = generateSequence(1) { it + 1 }.map { "t$it" }.iterator()
        val board = TicketBoard(host, idFactory = { ids.next() })
        repeat(50) { board.save(TicketSave(title = "work $it", agentId = "role:backend")) }
        host.listCalls = 0
        assertEquals(50, board.list().size)
        assertEquals(1, host.listCalls, "agents.list() calls during one list()")
    }
}
