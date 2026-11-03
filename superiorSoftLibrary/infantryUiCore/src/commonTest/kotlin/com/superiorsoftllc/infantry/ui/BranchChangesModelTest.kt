// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.BranchChangesView
import com.superiorsoftllc.infantry.ui.model.PatchView
import com.superiorsoftllc.infantry.ui.model.ProjectView
import com.superiorsoftllc.infantry.ui.model.branchChangesView
import com.superiorsoftllc.infantry.ui.model.changesBranch
import com.superiorsoftllc.infantry.ui.model.projectPatchView
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.model.selectProject
import com.superiorsoftllc.infantry.ui.model.withChangesBranch
import com.superiorsoftllc.infantry.ui.protocol.BranchChangedFile
import com.superiorsoftllc.infantry.ui.protocol.ChangeKind
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.GitBranch
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitBranchDiffstat
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitBranchList
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitPatch
import com.superiorsoftllc.infantry.ui.protocol.WireRequests
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * BRANCH-VIEW-2, the window's brain: the 1.65.0 branch frames land per project, the panel's branch pick is per
 * project and resets on a project switch, and a branch read the daemon could not make is UNKNOWN, never "no changes".
 */
class BranchChangesModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val two = AppState(projects = listOf(ProjectView("p1", "One"), ProjectView("p2", "Two")), activeProjectId = "p1")

    @Test fun requestsEncodeTheBranchFrames() {
        assertEquals(Envelope.PROJECT_GIT_BRANCHES, WireRequests.projectGitBranches("p1").type)
        assertEquals("""{"projectId":"p1"}""", WireRequests.projectGitBranches("p1").data)
        val ch = WireRequests.projectGitBranchChanges("p1", "side")
        assertEquals(Envelope.PROJECT_GIT_BRANCH_CHANGES, ch.type)
        assertEquals("""{"projectId":"p1","branch":"side","base":null}""", ch.data)
        val d = WireRequests.projectGitBranchDiff("p1", "side", "b.txt")
        assertEquals(Envelope.PROJECT_GIT_BRANCH_DIFF, d.type)
        assertEquals("""{"projectId":"p1","branch":"side","base":null,"path":"b.txt"}""", d.data)
    }

    @Test fun branchAnswersLandInTheirProjectsSlot() {
        val list = ProjectGitBranchList("p1", available = true, current = "main", branches = listOf(GitBranch("main"), GitBranch("side")))
        val stat = ProjectGitBranchDiffstat("p2", "side", base = "main", available = true, changes = listOf(BranchChangedFile("b.txt", ChangeKind.MODIFIED, added = 1)))
        val s = listOf(
            Envelope(Envelope.PROJECT_GIT_BRANCH_LIST, json.encodeToString(ProjectGitBranchList.serializer(), list)),
            Envelope(Envelope.PROJECT_GIT_BRANCH_DIFFSTAT, json.encodeToString(ProjectGitBranchDiffstat.serializer(), stat)),
        ).fold(two) { acc, e -> reduce(acc, e, 1) }
        assertEquals(listOf("main", "side"), s.projectGitBranches["p1"]?.branches?.map { it.name })
        assertNull(s.projectGitBranches["p2"])
        assertEquals("side", s.projectGitBranchChanges["p2"]?.branch)
        assertNull(s.projectGitBranchChanges["p1"])
    }

    @Test fun thePickIsPerProjectAndASwitchResetsItToTheWorkingTree() {
        val picked = two.withChangesBranch("p1", "side")
        assertEquals("side", picked.changesBranch)
        assertEquals("side", picked.copy(connection = picked.connection).changesBranch, "a re-render (new state copy) keeps the pick")
        val switched = picked.selectProject("p2")
        assertNull(switched.changesBranch, "another project starts on its working tree")
        assertNull(switched.selectProject("p1").changesBranch, "and coming back does not resurrect the old pick")
        assertNull(picked.withChangesBranch("p1", null).changesBranch)
    }

    @Test fun anUnavailableBranchIsUnknownWithItsReasonNeverEmpty() {
        val v = branchChangesView(ProjectGitBranchDiffstat("p1", "side", available = false, reason = "no base could be resolved"), "side")
        assertEquals(BranchChangesView.Unknown("no base could be resolved"), v)
        assertIs<BranchChangesView.Unknown>(branchChangesView(null, "side"))
        val other = ProjectGitBranchDiffstat("p1", "other", base = "main", available = true)
        assertIs<BranchChangesView.Unknown>(branchChangesView(other, "side"), "another branch's answer is not this branch's files")
    }

    @Test fun theHeaderNamesBranchBaseCountAndLines() {
        val stat = ProjectGitBranchDiffstat(
            "p1", "side", base = "main", available = true,
            changes = listOf(BranchChangedFile("z.txt", ChangeKind.ADDED, added = 3), BranchChangedFile("b.txt", ChangeKind.MODIFIED, added = 1, removed = 2)),
        )
        val v = assertIs<BranchChangesView.Files>(branchChangesView(stat, "side"))
        assertEquals(listOf("b.txt", "z.txt"), v.files.map { it.path })
        assertEquals("side vs main · 2 files · +4 −2", v.header())
    }

    @Test fun aBranchPatchIsShownOnlyForThatBranch() {
        val p = ProjectGitPatch("p1", "b.txt", staged = false, available = true, patch = "+x\n", branch = "side")
        assertIs<PatchView.Text>(projectPatchView(p, "b.txt", false, "side"))
        assertEquals(PatchView.None, projectPatchView(p, "b.txt", false), "a branch patch is not the working tree's")
        assertEquals(PatchView.None, projectPatchView(p.copy(branch = null), "b.txt", false, "side"), "a working-tree patch is not the branch's")
    }
}
