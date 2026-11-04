// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ChangesView
import com.superiorsoftllc.infantry.ui.model.HistoryView
import com.superiorsoftllc.infantry.ui.model.PatchView
import com.superiorsoftllc.infantry.ui.model.headline
import com.superiorsoftllc.infantry.ui.model.projectChangesView
import com.superiorsoftllc.infantry.ui.model.projectHistoryView
import com.superiorsoftllc.infantry.ui.model.projectPatchView
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.model.whenText
import com.superiorsoftllc.infantry.ui.protocol.ChangeKind
import com.superiorsoftllc.infantry.ui.protocol.ChangedFile
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.GitCommit
import com.superiorsoftllc.infantry.ui.protocol.Project
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitHistory
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitPatch
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitStatus
import com.superiorsoftllc.infantry.ui.protocol.WireRequests
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CP-2 slice (c), the window's brain: the frames of contract 1.36.0 land in [AppState] keyed by project,
 * and what the git panel shows is derived from them with the three-state honesty rule -- a read the daemon
 * could not make (`available = false`) is UNKNOWN with its reason, never "clean".
 */
class ProjectGitModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun feed(vararg e: Envelope, from: AppState = AppState()) = e.fold(from) { s, f -> reduce(s, f, 100) }

    private fun status(s: ProjectGitStatus) = Envelope(Envelope.PROJECT_GIT, json.encodeToString(ProjectGitStatus.serializer(), s))
    private fun patch(p: ProjectGitPatch) = Envelope(Envelope.PROJECT_GIT_PATCH, json.encodeToString(ProjectGitPatch.serializer(), p))
    private fun history(h: ProjectGitHistory) = Envelope(Envelope.PROJECT_GIT_HISTORY, json.encodeToString(ProjectGitHistory.serializer(), h))

    // ---- requests -------------------------------------------------------------------------------

    @Test fun requestsEncodeTheContractFramesByteForByte() {
        val st = WireRequests.projectGitStatus("p1")
        assertEquals(Envelope.PROJECT_GIT_STATUS, st.type)
        assertEquals("p1", st.data, "project_git_status carries the raw project id")

        val diff = WireRequests.projectGitDiff("p1", "src/A.kt", staged = true)
        assertEquals(Envelope.PROJECT_GIT_DIFF, diff.type)
        assertEquals("""{"projectId":"p1","path":"src/A.kt","staged":true}""", diff.data)
        assertEquals("""{"projectId":"p1","path":"x","staged":false}""", WireRequests.projectGitDiff("p1", "x", false).data, "staged=false is sent explicitly")

        val log = WireRequests.projectGitLog("p1", 20)
        assertEquals(Envelope.PROJECT_GIT_LOG, log.type)
        assertEquals("""{"projectId":"p1","limit":20}""", log.data)

        val set = WireRequests.projectSetDirectory("p1", "C:\\work\\repo")
        assertEquals(Envelope.PROJECT_SAVE, set.type)
        assertEquals("""{"id":"p1","directory":"C:\\work\\repo"}""", set.data, "an edit names the id and only the directory")
        assertEquals("""{"id":"p1","directory":""}""", WireRequests.projectSetDirectory("p1", "").data, "an empty directory is sent: it CLEARS")
    }

    // ---- reducer --------------------------------------------------------------------------------

    @Test fun aProjectFrameCarriesItsDirectoryIntoTheView() {
        val list = Envelope(Envelope.PROJECTS, json.encodeToString(ListSerializer(Project.serializer()), listOf(Project("A", "Alpha", directory = "/srv/alpha"), Project("B", "Beta"))))
        var s = feed(list)
        assertEquals(listOf("/srv/alpha", ""), s.projects.map { it.directory }, "the projects frame carries each directory")

        s = feed(Envelope(Envelope.PROJECT, json.encodeToString(Project.serializer(), Project("B", "Beta", directory = "/srv/beta"))), from = s)
        assertEquals("/srv/beta", s.projects.single { it.id == "B" }.directory, "a saved project replaces the directory in place")
    }

    @Test fun gitFramesAreKeptPerProject() {
        val a = ProjectGitStatus("A", "/srv/a", available = true, branch = "main", head = "abc", changes = listOf(ChangedFile("x.kt", ChangeKind.MODIFIED, false)))
        val b = ProjectGitStatus("B", "", available = false, reason = "this project has no directory configured")
        var s = feed(status(a), status(b))
        assertEquals(a, s.projectGit["A"], "A's status is kept under A")
        assertEquals(b, s.projectGit["B"], "B's status does not replace A's")

        val a2 = a.copy(changes = emptyList())
        s = feed(status(a2), from = s)
        assertEquals(a2, s.projectGit["A"], "a newer answer for the same project replaces its status")

        val p = ProjectGitPatch("A", "x.kt", available = true, patch = "@@ -1 +1 @@\n-a\n+b\n")
        val h = ProjectGitHistory("A", available = true, commits = listOf(GitCommit("h".repeat(40), "hhhhhhhh", "Ann", 1_700_000_000, "first")))
        s = feed(patch(p), history(h), from = s)
        assertEquals(p, s.projectGitPatch["A"])
        assertEquals(h, s.projectGitHistory["A"])
    }

    // ---- derived views --------------------------------------------------------------------------

    @Test fun unavailableIsUnknownWithItsReasonAndNeverClean() {
        assertIs<ChangesView.Unknown>(projectChangesView(null), "nothing reported yet is not clean")
        val view = projectChangesView(ProjectGitStatus("A", available = false, reason = "the project directory is not a git repository: /tmp/x"))
        assertIs<ChangesView.Unknown>(view)
        assertTrue(view.reason.contains("not a git repository"), "the daemon's reason is shown as-is: ${view.reason}")
        assertFalse(view is ChangesView.Clean)
    }

    @Test fun availableAndEmptyIsKnownClean() {
        assertEquals(ChangesView.Clean, projectChangesView(ProjectGitStatus("A", available = true, branch = "main", head = "abc")))
    }

    @Test fun changesAreSplitByIndexSideAndOrderedByPath() {
        val view = projectChangesView(
            ProjectGitStatus(
                "A", available = true, changesTruncated = true,
                changes = listOf(
                    ChangedFile("b.kt", ChangeKind.MODIFIED, staged = false),
                    ChangedFile("a.kt", ChangeKind.ADDED, staged = true),
                    ChangedFile("a.kt", ChangeKind.MODIFIED, staged = false),
                ),
            ),
        )
        assertIs<ChangesView.Files>(view)
        assertEquals(listOf("a.kt"), view.staged.map { it.path })
        assertEquals(listOf("a.kt", "b.kt"), view.unstaged.map { it.path })
        assertTrue(view.truncated, "the daemon's cut is carried")
    }

    @Test fun historyHasThreeDistinctStates() {
        assertIs<HistoryView.Unknown>(projectHistoryView(null))
        val unknown = projectHistoryView(ProjectGitHistory("A", available = false, reason = "the project directory does not exist: /gone"))
        assertIs<HistoryView.Unknown>(unknown)
        assertTrue(unknown.reason.contains("does not exist"))
        assertEquals(HistoryView.Empty, projectHistoryView(ProjectGitHistory("A", available = true)), "available + none = a repository with no commit yet")
        val commits = listOf(GitCommit("a".repeat(40), "aaaaaaaa", "Ann", 1, "newest"), GitCommit("b".repeat(40), "bbbbbbbb", "Bob", 0, "oldest"))
        val view = projectHistoryView(ProjectGitHistory("A", available = true, commits = commits, truncated = true))
        assertIs<HistoryView.Commits>(view)
        assertEquals(listOf("newest", "oldest"), view.commits.map { it.subject }, "the daemon's newest-first order is kept")
        assertTrue(view.truncated)
    }

    @Test fun aPatchIsShownOnlyForTheFileAndSideThatWasPicked() {
        val p = ProjectGitPatch("A", "x.kt", staged = false, available = true, patch = "@@ -1 +1 @@\n-a\n+b\n")
        assertEquals(PatchView.None, projectPatchView(p, path = null, staged = false), "nothing picked shows nothing")
        assertEquals(PatchView.None, projectPatchView(null, path = "x.kt", staged = false), "no answer yet shows nothing")
        assertEquals(PatchView.None, projectPatchView(p, path = "y.kt", staged = false), "an answer for another file is stale, not shown")
        assertEquals(PatchView.None, projectPatchView(p, path = "x.kt", staged = true), "an answer for the other side is stale, not shown")
        val view = projectPatchView(p, path = "x.kt", staged = false)
        assertIs<PatchView.Text>(view)
        assertEquals(listOf("@@ -1 +1 @@", "-a", "+b"), view.lines)
        assertFalse(view.truncated)
    }

    @Test fun binaryUnavailableAndTruncatedPatchesAreStatedNotDrawnAsEmptyDiffs() {
        assertEquals(PatchView.Binary, projectPatchView(ProjectGitPatch("A", "i.png", available = true, binary = true), "i.png", false))
        val unknown = projectPatchView(ProjectGitPatch("A", "x", available = false, reason = "git diff failed: boom"), "x", false)
        assertIs<PatchView.Unknown>(unknown)
        assertTrue(unknown.reason.contains("boom"))
        val cut = projectPatchView(ProjectGitPatch("A", "x", available = true, patch = "+a\n", truncated = true), "x", false)
        assertIs<PatchView.Text>(cut)
        assertTrue(cut.truncated)
    }

    @Test fun headlineAndCommitTimeReadAsText() {
        assertEquals("branch main @ 1a2b3c4d", ProjectGitStatus("A", available = true, branch = "main", head = "1a2b3c4d5e6f").headline())
        assertEquals("detached HEAD @ 1a2b3c4d", ProjectGitStatus("A", available = true, branch = null, head = "1a2b3c4d5e6f").headline())
        assertEquals("branch main, no commits yet", ProjectGitStatus("A", available = true, branch = "main", head = null).headline())
        assertEquals("2023-11-14 10:13 PM", GitCommit("h", "h", "Ann", 1_700_000_000, "s").whenText(kotlinx.datetime.TimeZone.UTC), "epoch seconds, shown in the given zone")
    }

    // ---- BUG-PGIT-LOAD -------------------------------------------------------------------------------

    @Test fun aGitStatusErrorFrameIsTheReasonUntilAnAnswerArrives() {
        val error = "project_git_status failed: Cannot run program \"git\""
        var s = feed(Envelope(Envelope.ERROR, error))
        assertEquals(error, s.projectGitFailure, "the error is kept for the git panes")
        assertEquals(ChangesView.Unknown(error), projectChangesView(s.projectGit["A"], s.projectGitFailure), "the pane says why, not 'not read yet'")

        s = feed(Envelope(Envelope.ERROR, "ticket_save refused: x"), from = s)
        assertEquals(error, s.projectGitFailure, "an unrelated error does not replace it")

        s = feed(status(ProjectGitStatus("A", available = false, reason = "git could not be run by the daemon: x")), from = s)
        assertEquals(null, s.projectGitFailure, "any project_git answer clears it")
        assertEquals(ChangesView.Unknown("git could not be run by the daemon: x"), projectChangesView(s.projectGit["A"], s.projectGitFailure))
        assertEquals(ChangesView.Unknown("not read yet"), projectChangesView(null), "no answer and no error is still 'not read yet'")
    }
}
