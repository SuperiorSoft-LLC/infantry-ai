// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.QueuedPrompt
import com.superiorsoftllc.infantry.ui.model.forcesMidTurn
import com.superiorsoftllc.infantry.ui.model.localQueue
import com.superiorsoftllc.infantry.ui.model.queuedFor
import com.superiorsoftllc.infantry.ui.model.settlePendingQueue
import com.superiorsoftllc.infantry.ui.model.withQueue
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.QueuedTurnView
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CHAT-QUEUE-1: the window shows the daemon's queue (with ids, for X and Force) plus its own just-sent `queue_add`s until listed. */
class ChatQueueModelTest {
    private fun state(queued: List<QueuedTurnView>, provider: String = "claude") = AppState(
        agents = mapOf(
            "a" to AgentView(
                session = SessionRecord("a", provider, "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.BUSY, providerSessionId = "p", queued = queued),
                entries = emptyList(),
            ),
        ),
    )

    @Test fun `the daemon's held messages are listed first, with their ids, then the window's pending ones`() {
        val s = state(listOf(QueuedTurnView("q-1", "first", 2))).withQueue("a", listOf(QueuedPrompt("second", 3, requestId = "r1")))
        assertEquals(listOf("first", "second"), s.queuedFor("a").map { it.text })
        assertEquals(listOf("q-1", null), s.queuedFor("a").map { it.id })
    }

    @Test fun `a pending queue_add is dropped once the daemon lists it, and only once per daemon item`() {
        val pending = state(emptyList()).withQueue("a", listOf(QueuedPrompt("same", 3, requestId = "r1"), QueuedPrompt("same", 4, requestId = "r2")))
        val listedOne = pending.copy(agents = state(listOf(QueuedTurnView("q-1", "same", 3))).agents)
        val settled = settlePendingQueue(listedOne, Envelope(Envelope.SESSION, "{}", "a"))
        assertEquals(listOf("r2"), settled.localQueue("a").map { it.requestId }, "one daemon item settles one pending add")
        assertEquals(2, settled.queuedFor("a").size, "never shown twice")
    }

    @Test fun `a refused queue_add is dropped by its error`() {
        val pending = state(emptyList()).withQueue("a", listOf(QueuedPrompt("x", 3, requestId = "r1")))
        val refused = settlePendingQueue(pending, Envelope(Envelope.ERROR, "queue_add refused: full", requestId = "r1"))
        assertTrue(refused.queuedFor("a").isEmpty())
        val other = settlePendingQueue(pending, Envelope(Envelope.ERROR, "other", requestId = "r9"))
        assertEquals(1, other.queuedFor("a").size)
    }

    @Test fun `force is offered only for a harness that declares mid-turn input`() {
        val s = state(emptyList())
        assertTrue(s.copy(providers = listOf(ProviderInfo("claude", midTurnInput = true))).forcesMidTurn("a"))
        assertFalse(s.copy(providers = listOf(ProviderInfo("claude"))).forcesMidTurn("a"), "absent = cannot")
        assertFalse(s.copy(providers = listOf(ProviderInfo("other", midTurnInput = true))).forcesMidTurn("a"), "decided by the session's own harness")
    }
}
