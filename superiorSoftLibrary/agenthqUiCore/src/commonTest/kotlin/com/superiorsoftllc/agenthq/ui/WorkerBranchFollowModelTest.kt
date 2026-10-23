package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.ChangesBranchPick
import com.superiorsoftllc.agenthq.ui.model.ProjectView
import com.superiorsoftllc.agenthq.ui.model.changesBranch
import com.superiorsoftllc.agenthq.ui.model.followWorkerBranch
import com.superiorsoftllc.agenthq.ui.model.selectedWorkerBranch
import com.superiorsoftllc.agenthq.ui.model.withChangesBranch
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import com.superiorsoftllc.agenthq.ui.protocol.Ticket
import com.superiorsoftllc.agenthq.ui.protocol.TicketColumns
import com.superiorsoftllc.agenthq.ui.protocol.WorktreeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * TASK-BRANCH-FOLLOW: opening an in-progress task worker points the right-hand Changes panel at the branch that
 * worker is on, follows it when it moves, and a worker with no branch leaves the selection alone.
 */
class WorkerBranchFollowModelTest {
    private fun agent(id: String, branch: String? = null) = AgentView(
        session = SessionRecord(id = id, provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.BUSY, worktreeBranch = branch),
    )

    private fun ticket(id: String, sessionId: String, status: String = TicketColumns.IN_PROGRESS) =
        Ticket(id = id, title = id, projectId = "p1", status = status, sessionId = sessionId)

    private fun worktree(sessionId: String, branch: String, removed: Boolean = false) =
        WorktreeStatus(sessionId, "/r", "/w/$sessionId", branch, "main", exists = true, dirty = false, ahead = 0, behind = 0, removed = removed)

    private val base = AppState(
        projects = listOf(ProjectView("p1", "One")),
        activeProjectId = "p1",
        agents = mapOf("a" to agent("a", "ticket/a"), "b" to agent("b", "ticket/b"), "n" to agent("n")),
        tickets = listOf(ticket("ta", "a"), ticket("tb", "b"), ticket("tn", "n")),
    )

    /** One step of the controller's loop: the new state against the one seen before it. */
    private fun AppState.after(previous: AppState) = followWorkerBranch(previous)

    @Test fun viewingWorkerAThenWorkerBShowsAsBranchThenBs() {
        val a = base.copy(selectedId = "a").let { it.after(base) }
        assertEquals("ticket/a", a.changesBranch)
        val b = a.copy(selectedId = "b").let { it.after(a) }
        assertEquals("ticket/b", b.changesBranch)
    }

    @Test fun aWorkerWithNoBranchDoesNotResetTheSelection() {
        val a = base.copy(selectedId = "a").let { it.after(base) }
        val n = a.copy(selectedId = "n")
        assertSame(n, n.after(a))
        assertEquals("ticket/a", n.changesBranch)
        val fromTree = base.copy(selectedId = "n")
        assertNull(fromTree.after(base).changesBranch, "the working tree stays the working tree")
    }

    @Test fun thePaneFollowsTheWorkerWhenItsBranchMoves() {
        val a = base.copy(selectedId = "a").let { it.after(base) }
        val moved = a.copy(worktrees = mapOf("a" to worktree("a", "ticket/a-2")))
        assertEquals("ticket/a-2", moved.after(a).changesBranch, "the live worktree wins over the recorded branch")
    }

    @Test fun aManualPickSurvivesUntilTheWorkerOrItsBranchMoves() {
        val a = base.copy(selectedId = "a").let { it.after(base) }
        val manual = a.withChangesBranch("p1", "main")
        val rerender = manual.copy(connection = manual.connection)
        assertEquals("main", rerender.after(manual).changesBranch)
    }

    @Test fun onlyAnInProgressTicketsWorkerIsFollowed() {
        val done = base.copy(tickets = listOf(ticket("ta", "a", TicketColumns.DONE)), selectedId = "a")
        assertNull(done.selectedWorkerBranch)
        val gone = base.copy(selectedId = "a", worktrees = mapOf("a" to worktree("a", "old", removed = true)))
        assertEquals(ChangesBranchPick("p1", "ticket/a"), gone.selectedWorkerBranch, "a removed worktree is not its branch")
    }
}
