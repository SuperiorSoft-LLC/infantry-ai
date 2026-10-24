package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.TranscriptEntry
import com.superiorsoftllc.agenthq.ui.model.chatStatusOf
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitWorktreeList
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoStuckThinkingAndNotGitTest {
    private val entries = listOf(
        TranscriptEntry(1, LineKind.USER, "hi", at = 1_000),
        TranscriptEntry(2, LineKind.TEXT, "hello", at = 2_000),
    )

    @Test fun parkedSessionWithNoTurnInFlightShowsNoThinking() {
        assertNull(chatStatusOf(entries, 5_000, state = SessionState.WAITING_INPUT, turnActive = false))
    }

    @Test fun activeTurnStillThinks() {
        assertNotNull(chatStatusOf(entries, 5_000, state = SessionState.BUSY, turnActive = true))
        assertNotNull(chatStatusOf(entries, 5_000, state = SessionState.BUSY, turnActive = null))
    }

    @Test fun notAGitRepositoryIsRecognised() {
        val l = ProjectGitWorktreeList("p", available = false, reason = "the project directory is not a git repository: x")
        assertTrue(isNotGitRepository(l))
        assertFalse(isNotGitRepository(ProjectGitWorktreeList("p", available = false, reason = "git could not be run")))
        assertFalse(isNotGitRepository(null))
        assertEquals(false, isNotGitRepository(ProjectGitWorktreeList("p", available = true)))
    }
}
