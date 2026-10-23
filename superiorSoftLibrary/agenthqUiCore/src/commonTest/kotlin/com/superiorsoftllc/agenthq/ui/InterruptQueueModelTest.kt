package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.QueueSend
import com.superiorsoftllc.agenthq.ui.model.QueuedPrompt
import com.superiorsoftllc.agenthq.ui.model.TranscriptEntry
import com.superiorsoftllc.agenthq.ui.model.interruptBlocker
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.model.queuedFor
import com.superiorsoftllc.agenthq.ui.model.withQueue
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UI-INTERRUPT: the rules behind the Interrupt control and the per-agent message queue. */
class InterruptQueueModelTest {
    private fun agent(state: SessionState) = AgentView(
        session = SessionRecord("a", "stub", "/w", spawnedAt = 1, lastHeartbeat = 1, state = state, providerSessionId = "p"),
        entries = listOf(TranscriptEntry(1, LineKind.USER, "scan", 1)),
    )

    @Test fun `interrupt is allowed only while a run is in flight`() {
        for (busy in listOf(SessionState.STARTING, SessionState.BUSY, SessionState.WAITING_INPUT)) {
            assertNull(interruptBlocker(agent(busy)), "a $busy agent has a turn to stop")
        }
        for (atRest in listOf(SessionState.IDLE, SessionState.EXITED, SessionState.FAILED, SessionState.ORPHANED)) {
            assertNotNull(interruptBlocker(agent(atRest)), "a $atRest agent has no turn to stop")
        }
    }

    @Test fun `queued messages are listed per agent and never written into the transcript`() {
        val base = AppState(agents = mapOf("a" to agent(SessionState.BUSY)))
        val queued = base.withQueue("a", listOf(QueuedPrompt("first", 2), QueuedPrompt("second", 3)))

        assertEquals(listOf("first", "second"), queued.queuedFor("a").map { it.text })
        assertEquals(emptyList(), queued.queuedFor("other"))
        assertEquals(base.agents.getValue("a").entries, queued.agents.getValue("a").entries, "waiting is not sending")
    }

    @Test fun `an empty queue removes the entry so there is one representation of no queue`() {
        val queued = AppState().withQueue("a", listOf(QueuedPrompt("x", 1)))
        assertTrue(queued.queued.containsKey("a"))
        assertTrue(queued.withQueue("a", emptyList()).queued.isEmpty())
    }

    // --- ticket-b5aa01bc: a queued message the daemon refuses as busy is kept, not lost. ---

    private fun sending(): AppState = AppState(agents = mapOf("a" to agent(SessionState.BUSY)))
        .withQueue("a", listOf(QueuedPrompt("second", 3)))
        .copy(queueSending = mapOf("queue-1" to QueueSend("a", QueuedPrompt("first", 2))))

    private val busyRefusal = "run_in_flight a: session a already has a run in flight"

    @Test fun `a busy refusal of a queued send puts it back at the front of the queue, with no notice and no transcript line`() {
        val before = sending()
        val after = reduce(before, Envelope(Envelope.ERROR, busyRefusal, requestId = "queue-1"), 10)

        assertEquals(listOf("first", "second"), after.queuedFor("a").map { it.text })
        assertTrue(after.queueSending.isEmpty())
        assertEquals(before.notices, after.notices, "a requeued message is not a failure")
        assertEquals(before.agents.getValue("a").entries, after.agents.getValue("a").entries, "it was never sent")
    }

    @Test fun `a busy refusal that names no queued send is still reported`() {
        val after = reduce(sending(), Envelope(Envelope.ERROR, busyRefusal, requestId = "other"), 10)
        assertEquals(listOf("second"), after.queuedFor("a").map { it.text })
        assertTrue(after.notices.any { it.text == busyRefusal })
    }

    @Test fun `the queued send's own turn on the stream ends its wait`() {
        val after = reduce(sending(), Envelope(Envelope.TURN, "first", "a", sequence = 2), 10)
        assertTrue(after.queueSending.isEmpty())
        val other = reduce(sending(), Envelope(Envelope.TURN, "mail for you", "a", sequence = 2), 10)
        assertEquals(setOf("queue-1"), other.queueSending.keys, "another turn (mail) does not settle it")
    }
}
