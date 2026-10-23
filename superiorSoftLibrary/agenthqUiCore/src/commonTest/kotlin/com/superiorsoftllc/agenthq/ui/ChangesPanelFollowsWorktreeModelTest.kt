package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.ChangesBranchPick
import com.superiorsoftllc.agenthq.ui.model.ProjectView
import com.superiorsoftllc.agenthq.ui.model.aheadHint
import com.superiorsoftllc.agenthq.ui.model.changesShownBranch
import com.superiorsoftllc.agenthq.ui.model.withChangesView
import com.superiorsoftllc.agenthq.ui.model.withChangesWorktree
import com.superiorsoftllc.agenthq.ui.protocol.ChangedFile
import com.superiorsoftllc.agenthq.ui.protocol.GitWorktree
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitWorktreeList
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitWorktreeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** BUG-CHANGES-WRONG-BRANCH: the inner branch picker follows the worktree the top selector shows. */
class ChangesPanelFollowsWorktreeModelTest {
    private val wtA = GitWorktree(path = "/w/a", branch = "ticket/a")
    private val wtB = GitWorktree(path = "/w/b", branch = "ticket/b")
    private val main = GitWorktree(path = "/repo", branch = "feature", main = true)
    private val base = AppState(
        projects = listOf(ProjectView("p1", "One")),
        activeProjectId = "p1",
        projectWorktrees = mapOf("p1" to ProjectGitWorktreeList("p1", available = true, worktrees = listOf(main, wtA, wtB), selected = "/w/a")),
    )

    @Test fun aStaleBranchPickDoesNotLeakOntoTheShownWorktree() {
        val s = base.copy(changesBranchPick = ChangesBranchPick("p1", "ticket/b"))
        assertEquals("ticket/a", s.changesShownBranch)
    }

    @Test fun switchingWorktreeUpdatesTheInnerPicker() {
        assertEquals("ticket/a", base.changesShownBranch)
        assertEquals("ticket/b", base.withChangesWorktree("p1", "/w/b").changesShownBranch)
        assertNull(base.withChangesWorktree("p1", "/repo").changesShownBranch, "the shared checkout shows its working tree")
    }

    @Test fun anExplicitPickBelongsToItsWorktreeOnly() {
        val onA = base.withChangesView("p1", null)
        assertNull(onA.changesShownBranch, "explicit Working tree on A")
        assertEquals("ticket/b", onA.withChangesWorktree("p1", "/w/b").changesShownBranch)
        val compare = base.withChangesView("p1", "ticket/b")
        assertEquals("ticket/b", compare.changesShownBranch)
        assertEquals("ticket/b", compare.withChangesWorktree("p1", "/w/b").changesShownBranch)
    }

    @Test fun emptyWorkingTreeWhileAheadSaysSo() {
        fun st(ahead: Int?, changes: List<ChangedFile> = emptyList()) =
            ProjectGitWorktreeStatus("p1", "/w/a", available = true, ahead = ahead, behind = 0, changes = changes)
        assertEquals("3 commits ahead; see Compare/History", aheadHint(st(3)))
        assertEquals("1 commit ahead; see Compare/History", aheadHint(st(1)))
        assertNull(aheadHint(st(0)))
        assertNull(aheadHint(st(null)))
        assertNull(aheadHint(null))
        assertNull(aheadHint(st(3, listOf(ChangedFile("x.kt", com.superiorsoftllc.agenthq.ui.protocol.ChangeKind.MODIFIED, staged = false)))))
    }
}
