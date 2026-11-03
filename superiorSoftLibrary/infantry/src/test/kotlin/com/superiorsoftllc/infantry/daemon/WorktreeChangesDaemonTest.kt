package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.worktree.ChangeKind
import com.superiorsoftllc.infantry.worktree.ChangedFile
import com.superiorsoftllc.infantry.worktree.WorktreeStatus
import com.superiorsoftllc.infantry.worktree.newGitRepo
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * GIT VIEW over the REAL daemon and a real WebSocket: the existing `worktrees` / `worktree` frames carry
 * the changed files of the session's OWN worktree, and a session id in the `worktrees` request polls ONE
 * agent instead of every worktree. No new frame type.
 */
class WorktreeChangesDaemonTest {

    private fun withSession(block: (fixture: DaemonFixture, sessionId: String, worktree: File, root: File) -> Unit) {
        val repo = newGitRepo()
        val fixture = DaemonFixture.launch(repo = repo)
        try {
            fixture.client.await(Envelope.READY)
            fixture.client.send(Envelope.PROMPT, "git view probe")
            val first = fixture.client.await(Envelope.WORKTREE)
            val status = JSON.decodeFromString(WorktreeStatus.serializer(), first.data)
            block(fixture, status.sessionId, File(status.path), repo)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun theListFrameCarriesTheChangedFiles_ofTheSessionsOwnWorktree() = withSession { fixture, sessionId, worktree, root ->
        File(worktree, "README.md").writeText("hello\nedited in the worktree\n")
        File(worktree, "brand-new.txt").writeText("n\n")
        File(root, "root-only.txt").writeText("the project root is dirty too\n")

        val from = fixture.client.mark()
        fixture.client.send(Envelope.WORKTREES, "")
        val answer = fixture.client.awaitAfter(from, "worktrees list") { it.type == Envelope.WORKTREES }
        val mine = JSON.decodeFromString(ListSerializer(WorktreeStatus.serializer()), answer.data)
            .single { it.sessionId == sessionId }

        assertEquals(
            setOf(
                ChangedFile("README.md", ChangeKind.MODIFIED, staged = false),
                ChangedFile("brand-new.txt", ChangeKind.UNTRACKED, staged = false),
            ),
            assertNotNull(mine.changes).toSet(),
            "the project root's root-only.txt must not appear in the session's list",
        )
        assertTrue(mine.dirty)
    }

    @Test
    fun aSessionIdInTheRequest_answersOnlyThatSession_asAWorktreeFrame() = withSession { fixture, sessionId, worktree, _ ->
        File(worktree, "only.txt").writeText("x")

        val from = fixture.client.mark()
        fixture.client.send(Envelope.WORKTREES, sessionId)
        val answer = fixture.client.awaitAfter(from, "single worktree frame") {
            it.type == Envelope.WORKTREE || it.type == Envelope.WORKTREES || it.type == Envelope.ERROR
        }
        assertEquals(Envelope.WORKTREE, answer.type, "a per-session poll is answered with the single-status frame: ${answer.data}")
        val status = JSON.decodeFromString(WorktreeStatus.serializer(), answer.data)
        assertEquals(sessionId, status.sessionId)
        assertEquals(listOf("only.txt"), status.changes?.map { it.path })
    }

    @Test
    fun aCleanWorktree_isReportedEmpty_overTheWire() = withSession { fixture, sessionId, _, _ ->
        val from = fixture.client.mark()
        fixture.client.send(Envelope.WORKTREES, sessionId)
        val answer = fixture.client.awaitAfter(from, "single worktree frame") { it.type == Envelope.WORKTREE || it.type == Envelope.ERROR }
        val status = JSON.decodeFromString(WorktreeStatus.serializer(), answer.data)
        assertEquals(emptyList(), status.changes, "clean is an empty list on the wire, not an absent field")
        // And the raw frame really carries the key: absent would read as UNKNOWN to a client.
        val raw = fixture.client.rawFrames.last { it["type"].toString().contains("worktree") }
        assertTrue(raw["data"].toString().contains("\\\"changes\\\":[]"), "raw frame: $raw")
    }

    @Test
    fun anUnknownSessionId_isRefused_notAnEmptyAnswer() = withSession { fixture, _, _, _ ->
        val from = fixture.client.mark()
        fixture.client.send(Envelope.WORKTREES, "no-such-session")
        val answer = fixture.client.awaitAfter(from, "refusal") { it.type == Envelope.ERROR || it.type == Envelope.WORKTREE }
        assertEquals(Envelope.ERROR, answer.type)
        assertTrue(answer.data.contains("no-such-session"))
    }
}
