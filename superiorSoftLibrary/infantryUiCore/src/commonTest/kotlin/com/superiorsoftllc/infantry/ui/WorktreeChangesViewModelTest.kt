package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ChangesView
import com.superiorsoftllc.infantry.ui.model.badge
import com.superiorsoftllc.infantry.ui.model.changesView
import com.superiorsoftllc.infantry.ui.model.display
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.model.summary
import com.superiorsoftllc.infantry.ui.protocol.ChangeKind
import com.superiorsoftllc.infantry.ui.protocol.ChangedFile
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.WireRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** GIT VIEW, client side: the pure changes view and the reducer path that feeds it. */
class WorktreeChangesViewModelTest {

    private fun frame(type: String, data: String) = Envelope(type, data)

    private fun statusJson(
        id: String = "s1",
        exists: Boolean = true,
        changes: String? = null,
        truncated: Boolean = false,
    ): String = buildString {
        append("""{"sessionId":"$id","repoRoot":"/r","path":"/r/wt","branch":"infantry/x","baseRef":"abc","exists":$exists,""")
        append(""""dirty":${changes != null && changes != "[]"},"ahead":2,"behind":1,"removed":false""")
        if (changes != null) append(""","changes":$changes""")
        if (truncated) append(""","changesTruncated":true""")
        append("}")
    }

    private fun viewAfter(vararg frames: Envelope, id: String = "s1"): ChangesView =
        changesView(frames.fold(AppState()) { s, f -> reduce(s, f, 100) }.worktrees[id])

    @Test fun aKnownModificationAndUntrackedFile_showWithTheirOwnBadges() {
        val view = viewAfter(
            frame(
                Envelope.WORKTREE,
                statusJson(
                    changes = """[{"path":"src/A.kt","status":"MODIFIED","staged":false},{"path":"new.txt","status":"UNTRACKED","staged":false}]""",
                ),
            ),
        )
        val files = assertIs<ChangesView.Files>(view)
        assertEquals(listOf("new.txt" to "?", "src/A.kt" to "M"), files.unstaged.map { it.path to it.status.badge() })
        assertTrue(files.staged.isEmpty())
        assertEquals("2 changed files", files.summary())
    }

    /** Named mutation: rendering a null list as Clean turns this red -- the silent-wrong-answer shape. */
    @Test fun anAbsentList_isUnknown_neverClean() {
        val view = viewAfter(frame(Envelope.WORKTREE, statusJson(changes = null)))
        assertIs<ChangesView.Unknown>(view)
    }

    @Test fun anEmptyList_isClean_notUnknown() {
        assertEquals(ChangesView.Clean, viewAfter(frame(Envelope.WORKTREE, statusJson(changes = "[]"))))
    }

    @Test fun noStatusAtAll_andAMissingDirectory_areUnknownWithDifferentReasons() {
        val none = changesView(null)
        val gone = viewAfter(frame(Envelope.WORKTREE, statusJson(exists = false, changes = null)))
        val noneReason = assertIs<ChangesView.Unknown>(none).reason
        val goneReason = assertIs<ChangesView.Unknown>(gone).reason
        assertTrue(noneReason != goneReason, "the pane must be able to say which: '$noneReason' vs '$goneReason'")
        assertTrue(goneReason.contains("missing"))
    }

    /** Named mutation: ignoring `staged` (everything one list) turns this red. */
    @Test fun stagedAndUnstagedAreSeparate_andAFileOnBothSidesAppearsInBoth() {
        val files = assertIs<ChangesView.Files>(
            viewAfter(
                frame(
                    Envelope.WORKTREE,
                    statusJson(
                        changes = """[{"path":"b.kt","status":"MODIFIED","staged":true},{"path":"a.kt","status":"ADDED","staged":true},{"path":"b.kt","status":"MODIFIED","staged":false}]""",
                    ),
                ),
            ),
        )
        assertEquals(listOf("a.kt", "b.kt"), files.staged.map { it.path })
        assertEquals(listOf("b.kt"), files.unstaged.map { it.path })
        assertEquals(3, files.total)
    }

    @Test fun aRenameReadsOldToNew_andATruncatedListSaysSo() {
        val files = assertIs<ChangesView.Files>(
            viewAfter(
                frame(
                    Envelope.WORKTREE,
                    statusJson(changes = """[{"path":"new.kt","status":"RENAMED","staged":true,"fromPath":"old.kt"}]""", truncated = true),
                ),
            ),
        )
        assertEquals("old.kt -> new.kt", files.staged.single().display())
        assertTrue(files.truncated)
        assertTrue(files.summary().contains("cut"), files.summary())
    }

    @Test fun theListFrame_andTheSingleFrame_bothFeedTheSameView() {
        val one = """[{"path":"x","status":"DELETED","staged":false}]"""
        val viaList = viewAfter(frame(Envelope.WORKTREES, "[" + statusJson(changes = one) + "]"))
        val viaOne = viewAfter(frame(Envelope.WORKTREE, statusJson(changes = one)))
        assertEquals(viaList, viaOne)
        assertEquals("D", ChangeKind.DELETED.badge())
    }

    @Test fun aNewerSingleFrame_replacesTheOlderChangesOfThatSession() {
        val s = listOf(
            frame(Envelope.WORKTREE, statusJson(changes = """[{"path":"old","status":"MODIFIED","staged":false}]""")),
            frame(Envelope.WORKTREE, statusJson(changes = "[]")),
        ).fold(AppState()) { acc, f -> reduce(acc, f, 100) }
        assertEquals(ChangesView.Clean, changesView(s.worktrees["s1"]))
    }

    @Test fun anOlderDaemonsFrame_withNoChangesKey_stillDecodes_asUnknown() {
        val s = reduce(AppState(), frame(Envelope.WORKTREE, statusJson(changes = null)), 100)
        val w = assertNotNull(s.worktrees["s1"])
        assertEquals(2, w.ahead)
        assertEquals(1, w.behind)
        assertIs<ChangesView.Unknown>(changesView(w))
    }

    @Test fun thePollNamesOneSession() {
        val req = WireRequests.worktreeOf("sess-9")
        assertEquals(Envelope.WORKTREES, req.type)
        assertEquals("sess-9", req.data)
        // Quiet a stray unused-import warning for ChangedFile while pinning its shape.
        assertEquals("p", ChangedFile("p", ChangeKind.ADDED, staged = true).path)
    }
}
