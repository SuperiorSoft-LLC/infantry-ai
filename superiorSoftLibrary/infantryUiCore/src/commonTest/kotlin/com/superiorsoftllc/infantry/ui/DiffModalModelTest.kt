package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ChangesView
import com.superiorsoftllc.infantry.ui.model.DiffBody
import com.superiorsoftllc.infantry.ui.model.DiffLineKind
import com.superiorsoftllc.infantry.ui.model.DiffRow
import com.superiorsoftllc.infantry.ui.model.DiffTarget
import com.superiorsoftllc.infantry.ui.model.diffBody
import com.superiorsoftllc.infantry.ui.model.diffRows
import com.superiorsoftllc.infantry.ui.model.diffTargets
import com.superiorsoftllc.infantry.ui.model.projectPatchView
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.model.step
import com.superiorsoftllc.infantry.ui.protocol.ChangeKind
import com.superiorsoftllc.infantry.ui.protocol.ChangedFile
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitPatch
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DIFF-MODAL: from the `project_git_patch` frame, through the reducer, to what the modal draws. */
class DiffModalModelTest {
    private val json = Json { encodeDefaults = true }

    /** Feed one patch frame through the real reducer and derive the modal body for (path, staged). */
    private fun bodyFor(p: ProjectGitPatch, path: String = p.path, staged: Boolean = p.staged): DiffBody {
        val s = reduce(AppState(), Envelope(Envelope.PROJECT_GIT_PATCH, json.encodeToString(ProjectGitPatch.serializer(), p)), 100)
        return diffBody(projectPatchView(s.projectGitPatch[p.projectId], path, staged))
    }

    @Test fun binaryIsBinaryNotEmpty() {
        assertEquals(DiffBody.Binary, bodyFor(ProjectGitPatch("A", "logo.png", available = true, binary = true)))
    }

    @Test fun truncatedKeepsItsRowsAndSaysSo() {
        val body = bodyFor(ProjectGitPatch("A", "big.kt", available = true, truncated = true, patch = "@@ -1 +1 @@\n-a\n+b\n"))
        assertIs<DiffBody.Lines>(body)
        assertTrue(body.truncated)
        assertEquals(1, body.added)
        assertEquals(1, body.removed)
    }

    @Test fun unknownCarriesTheReasonAndIsNeverNoChanges() {
        val body = bodyFor(ProjectGitPatch("A", "x.kt", available = false, reason = "git diff failed: exit 128"))
        assertEquals(DiffBody.Unknown("git diff failed: exit 128"), body)
        val blank = bodyFor(ProjectGitPatch("A", "x.kt", available = false))
        assertIs<DiffBody.Unknown>(blank)
        assertTrue(blank.reason.isNotBlank(), "a blank daemon reason still says why")
    }

    @Test fun anAnswerForAnotherFileOrSideIsStillLoading() {
        val p = ProjectGitPatch("A", "x.kt", available = true, patch = "@@ -1 +1 @@\n-a\n+b\n")
        assertEquals(DiffBody.Loading, bodyFor(p, path = "y.kt"))
        assertEquals(DiffBody.Loading, bodyFor(p, staged = true))
    }

    @Test fun rowsCarryOldAndNewLineNumbersAndCounts() {
        val patch = """
            diff --git a/f.kt b/f.kt
            index 1..2 100644
            --- a/f.kt
            +++ b/f.kt
            @@ -10,3 +10,3 @@ fun x()
             keep
            --- a removed line that starts with dashes
            +added
             tail
            \ No newline at end of file
        """.trimIndent()
        val body = bodyFor(ProjectGitPatch("A", "f.kt", available = true, patch = patch))
        assertIs<DiffBody.Lines>(body)
        assertEquals(1, body.added, "+++ header is not an addition")
        assertEquals(1, body.removed, "--- header is not a removal, but a '-' line starting with -- inside the hunk is")
        val r = body.rows
        assertEquals(List(4) { DiffLineKind.META } + DiffLineKind.HUNK, r.take(5).map { it.kind })
        assertEquals(DiffRow(DiffLineKind.CONTEXT, 10, 10, " keep"), r[5])
        assertEquals(DiffRow(DiffLineKind.DEL, 11, null, "--- a removed line that starts with dashes"), r[6])
        assertEquals(DiffRow(DiffLineKind.ADD, null, 11, "+added"), r[7])
        assertEquals(DiffRow(DiffLineKind.CONTEXT, 12, 12, " tail"), r[8])
        assertEquals(DiffLineKind.META, r[9].kind)
    }

    @Test fun hunkWithoutCountsStartsAtItsLine() {
        val r = diffRows(listOf("@@ -5 +7 @@", "-a", "+b"))
        assertEquals(5, r[1].oldNo)
        assertEquals(7, r[2].newNo)
    }

    @Test fun prevNextFollowThePanelOrderStagedFirst() {
        val view = ChangesView.Files(
            staged = listOf(ChangedFile("a.kt", ChangeKind.MODIFIED, staged = true)),
            unstaged = listOf(ChangedFile("b.kt", ChangeKind.ADDED, staged = false), ChangedFile("c.kt", ChangeKind.DELETED, staged = false)),
            truncated = false,
        )
        val order = diffTargets(view).map { it.first }
        assertEquals(listOf(DiffTarget("a.kt", true), DiffTarget("b.kt", false), DiffTarget("c.kt", false)), order)
        assertNull(order.step(order[0], -1), "no previous before the first")
        assertEquals(order[1], order.step(order[0], 1))
        assertNull(order.step(order[2], 1), "no next after the last")
        assertNull(order.step(DiffTarget("gone.kt", false), 1), "a file no longer listed has no neighbours")
        assertFalse(diffTargets(ChangesView.Clean).isNotEmpty())
    }
}
