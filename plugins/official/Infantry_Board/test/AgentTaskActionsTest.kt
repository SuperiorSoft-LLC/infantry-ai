// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board

import com.superiorsoftllc.infantry.ui.protocol.Ticket
import com.superiorsoftllc.infantry.ui.protocol.TicketHasWork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentTaskActionsTest {
    private val base = Ticket(id = "t1", title = "T", status = "todo")

    @Test fun menuMapsToTools() {
        assertEquals("""{"ticketId":"t1"}""", AgentTaskRequests.unassign("t1").args.toString())
        assertEquals("""{"ticketId":"t1","agentId":"a"}""", AgentTaskRequests.assignNow("t1", "a").args.toString())
        assertEquals("""{"ticketId":"t1","agentId":"a","queue":true}""", AgentTaskRequests.assignQueue("t1", "a").args.toString())
        assertEquals("infantry_agent_switch", AgentTaskRequests.switchTo("a", "t2").tool)
        assertEquals("""{"agentId":"a","ticketId":"t2"}""", AgentTaskRequests.switchTo("a", "t2").args.toString())
    }

    @Test fun badgesFollowTheDto() {
        assertNull(AgentTaskRequests.hasWorkLabel(base)); assertNull(AgentTaskRequests.queuedLabel(base))
        val t = base.copy(hasWork = TicketHasWork("b", "0123456789", 3), queuedAgent = "agent-1", queuedAgentName = "Ann", queueRank = 2)
        assertEquals("has work 0123456 +3 commits", AgentTaskRequests.hasWorkLabel(t))
        assertEquals("queued: Ann #2", AgentTaskRequests.queuedLabel(t))
        assertEquals("has work abc +1 commit", AgentTaskRequests.hasWorkLabel(base.copy(hasWork = TicketHasWork("b", "abc", 1))))
    }
}
