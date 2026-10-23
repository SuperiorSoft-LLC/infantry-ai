package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.COMPACTING_LABEL
import com.superiorsoftllc.agenthq.ui.model.ChatStatus
import com.superiorsoftllc.agenthq.ui.model.RunActivityView
import com.superiorsoftllc.agenthq.ui.model.chatStatusOf
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompactingStatusModelTest {
    private fun agent(state: SessionState, phase: String?) = AgentView(
        SessionRecord("a", "stub", "/w", spawnedAt = 1, lastHeartbeat = 1, state = state, providerSessionId = "p"),
        runActivity = phase?.let { RunActivityView(it, null, 10) },
    )

    @Test fun compactingRunReadsCompactingNotThinking() {
        val s = chatStatusOf(emptyList(), 20, null, SessionState.BUSY, RunActivityView("compacting", null, 10))
        assertIs<ChatStatus.Thinking>(s)
        assertTrue(s.compacting)
        assertEquals("Compacting…", COMPACTING_LABEL)
    }

    @Test fun thinkingAfterCompactionIsNotCompacting() {
        val s = chatStatusOf(emptyList(), 20, null, SessionState.BUSY, RunActivityView("thinking", null, 10))
        assertFalse((s as ChatStatus.Thinking).compacting)
    }

    @Test fun rowAndHeaderSayCompactingWhileBusy() {
        val a = agent(SessionState.BUSY, "compacting")
        assertEquals("compacting", a.stateText)
        assertEquals(COMPACTING_LABEL, a.lastActivity)
    }

    @Test fun staleCompactingPhaseOnAnIdleSessionIsIgnored() {
        val a = agent(SessionState.IDLE, "compacting")
        assertEquals("idle", a.stateText)
        assertEquals(null, chatStatusOf(emptyList(), 20, null, SessionState.IDLE, RunActivityView("compacting", null, 10)))
    }
}
