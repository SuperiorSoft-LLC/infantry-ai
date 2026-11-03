package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.SessionPaneAction
import com.superiorsoftllc.infantry.ui.model.sessionPaneAction
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** GAP-006b: what an empty chat pane offers for the session it shows. */
class SessionPaneActionTest {

    private fun rec(state: SessionState = SessionState.IDLE, psid: String? = null, runCount: Int = 1, ticketId: String? = null) =
        SessionRecord(
            "s1", "claude", "/repo", spawnedAt = 1, lastHeartbeat = 1, state = state, providerSessionId = psid, runCount = runCount,
            role = "backend", model = "claude-sonnet-5-5", ticketId = ticketId,
            worktreeBranch = if (ticketId != null) "ticket/e13c0aa3-x" else null, worktreePath = if (ticketId != null) "/wt/e13c0aa3" else null,
        )

    private val ticket = Ticket(id = "ticket-e13c0aa3", title = "Fix the thing", status = "in_progress", sessionId = "s1", branch = "ticket/e13c0aa3-x")

    @Test fun `a phantom ticket worker starts its ticket on the settings it already has`() {
        val action = assertIs<SessionPaneAction.Start>(sessionPaneAction(rec(ticketId = ticket.id), listOf(ticket)))
        with(action.plan) {
            assertEquals("s1", sessionId)
            assertEquals(ticket.id, ticketId)
            assertEquals("Fix the thing", ticketTitle)
            assertEquals("backend", role)
            assertEquals("claude", provider)
            assertEquals("claude-sonnet-5-5", model)
            assertEquals("ticket/e13c0aa3-x", branch)
            assertEquals("/wt/e13c0aa3", worktree)
        }
    }

    @Test fun `header Resume is enabled only for an ended resumable session`() {
        for (st in SessionState.values()) {
            val ended = st in ENDED_STATES
            assertEquals(ended, resumeEnabled(rec(st, psid = "p")), "state $st with a provider session id")
            assertEquals(false, resumeEnabled(rec(st, psid = null)), "state $st without one")
        }
    }

    @Test fun `a session with a conversation resumes`() {
        assertEquals(SessionPaneAction.Resume("s1"), sessionPaneAction(rec(psid = "p"), emptyList()))
        assertEquals(SessionPaneAction.Resume("s1"), sessionPaneAction(rec(SessionState.EXITED, psid = "p"), emptyList()))
    }

    @Test fun `a bound never-run session starts itself`() {
        val plan = assertIs<SessionPaneAction.Start>(sessionPaneAction(rec(runCount = 0), emptyList())).plan
        assertNull(plan.ticketId)
        assertEquals("/repo", plan.worktree)
    }

    @Test fun `a turn in flight offers nothing`() {
        assertNull(sessionPaneAction(rec(SessionState.BUSY), emptyList()))
    }

    @Test fun `a ticket worker that failed before its brief landed still starts its ticket, a plain one offers nothing`() {
        assertIs<SessionPaneAction.Start>(sessionPaneAction(rec(SessionState.FAILED, ticketId = ticket.id), listOf(ticket)))
        assertNull(sessionPaneAction(rec(SessionState.FAILED), emptyList()), "the daemon refuses it: nothing to start or resume")
    }

    @Test fun `END-SESSION-1 a session that ends while open shows the Resume splash, a live one does not`() {
        for (st in SessionState.values()) {
            val r = rec(st, psid = "p")
            assertEquals(st in ENDED_STATES, com.superiorsoftllc.infantry.ui.model.showsResumeSplash(r, sessionPaneAction(r, emptyList())), "state $st")
        }
    }
}
