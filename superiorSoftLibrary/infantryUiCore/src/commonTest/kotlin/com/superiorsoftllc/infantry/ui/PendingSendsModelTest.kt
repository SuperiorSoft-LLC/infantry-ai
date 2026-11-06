// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.model.PendingSend
import com.superiorsoftllc.infantry.ui.model.PendingSends
import com.superiorsoftllc.infantry.ui.model.SendState
import com.superiorsoftllc.infantry.ui.model.expirePendingSend
import com.superiorsoftllc.infantry.ui.model.failSendsOnDrop
import com.superiorsoftllc.infantry.ui.model.pendingRefusedBusy
import com.superiorsoftllc.infantry.ui.model.pendingSendsFor
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.model.settlePendingSends
import com.superiorsoftllc.infantry.ui.model.turnCarries
import com.superiorsoftllc.infantry.ui.model.withPendingSend
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CHAT-SENDING-1: a sent message is shown at once, settled by the daemon's ack / echo, and marked "not sent - retry" otherwise. */
class PendingSendsModelTest {
    private val base = AppState(
        connection = ConnectionInfo(state = ConnState.CONNECTED, contract = "1.151.0"),
        agents = mapOf(
            "a" to AgentView(SessionRecord("a", "claude", "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "p")),
        ),
    )
    private val sent = PendingSend("send:1", "a", "run the tests", at = 10)
    private val pending = base.withPendingSend(sent)

    /** [env] reduced onto [s], then the pending sends settled against it, as the controller's frame loop does. */
    private fun frame(s: AppState, env: Envelope): AppState = settlePendingSends(s, reduce(s, env, 20), env)

    @Test fun `a sent message shows at once as sending`() {
        val shown = pending.pendingSendsFor("a").single()
        assertEquals(SendState.SENDING, shown.state)
        assertEquals("sending...", PendingSends.label(shown))
    }

    @Test fun `the ack marks it sent and the turn echo replaces the bubble`() {
        val acked = frame(pending, Envelope(Envelope.PROMPT_ACK, "accepted", "a", requestId = "send:1"))
        assertEquals(SendState.ACCEPTED, acked.pendingSends["send:1"]?.state)
        val echoed = frame(acked, Envelope(Envelope.TURN, "run the tests", "a", sequence = 1))
        assertTrue(echoed.pendingSendsFor("a").isEmpty(), "the transcript's own line takes over: no double bubble")
        assertEquals(1, echoed.agents["a"]!!.entries.size)
    }

    @Test fun `an older daemon's echo alone settles it`() {
        val echoed = frame(pending, Envelope(Envelope.TURN, "run the tests", "a", sequence = 1))
        assertTrue(echoed.pendingSendsFor("a").isEmpty())
    }

    @Test fun `an accepted send whose echo was wrapped is settled by the session's next turn`() {
        val acked = frame(pending, Envelope(Envelope.PROMPT_ACK, "accepted", "a", requestId = "send:1"))
        val echoed = frame(acked, Envelope(Envelope.TURN, "[persona]\nrun the tests, please", "a", sequence = 1))
        assertTrue(echoed.pendingSendsFor("a").isEmpty())
    }

    @Test fun `a replayed turn that adds nothing settles nothing`() {
        val once = reduce(base, Envelope(Envelope.TURN, "run the tests", "a", sequence = 1), 5).withPendingSend(sent)
        val replay = frame(once, Envelope(Envelope.TURN, "run the tests", "a", sequence = 1))
        assertEquals(SendState.SENDING, replay.pendingSends["send:1"]?.state, "an old turn with the same text is not this message")
    }

    /** CHAT-DUP-1: the owner's screenshot: text + image, the daemon's echo BEFORE its ack; the "sent" bubble stayed beside the card. */
    @Test fun `a text and image message whose echo comes before the ack shows once`() {
        val text = "That didnt work.\n\n[image: C:\\up\\chat\\0c24.png]"
        val withImage = base.withPendingSend(PendingSend("send:2", "a", text, at = 10))
        val echoed = frame(withImage, Envelope(Envelope.TURN, text, "a", sequence = 1))
        val acked = frame(echoed, Envelope(Envelope.PROMPT_ACK, "accepted", "a", requestId = "send:2"))
        assertTrue(acked.pendingSendsFor("a").isEmpty(), "one card: the echo replaced the bubble")
        assertEquals(1, acked.agents["a"]!!.entries.size)
    }

    @Test fun `the ack settles a send whose echo came first even when the echo reads differently`() {
        val echoed = frame(pending, Envelope(Envelope.TURN, "[persona]\nrun the tests, please", "a", sequence = 1))
        assertEquals(SendState.SENDING, echoed.pendingSends["send:1"]?.state, "text alone cannot tell")
        val acked = frame(echoed, Envelope(Envelope.PROMPT_ACK, "accepted", "a", requestId = "send:1"))
        assertTrue(acked.pendingSendsFor("a").isEmpty(), "the client id's ack + a newer turn settle it")
    }

    @Test fun `an ack does not take an older turn, or another pending message's echo, as its own`() {
        val old = reduce(base, Envelope(Envelope.TURN, "earlier", "a", sequence = 1), 5)
        val first = PendingSend("send:1", "a", "one\n\ntwo", at = 10, userSeqAtSend = 1)
        val second = PendingSend("send:2", "a", "three", at = 11, userSeqAtSend = 1)
        val both = old.withPendingSend(first).withPendingSend(second)
        assertEquals(SendState.ACCEPTED, frame(both, Envelope(Envelope.PROMPT_ACK, "accepted", "a", requestId = "send:1")).pendingSends["send:1"]?.state)
        val echoedFirst = frame(both, Envelope(Envelope.TURN, "[wrapped] one\n\ntwo!", "a", sequence = 2))
        val ackSecond = frame(echoedFirst, Envelope(Envelope.PROMPT_ACK, "accepted", "a", requestId = "send:2"))
        assertEquals(SendState.ACCEPTED, ackSecond.pendingSends["send:2"]?.state, "the one new turn is the older message's")
    }

    /** CHAT-DUP-1: a long session's transcript is at its line cap, so a new turn does not make it longer; it is still a new turn. */
    @Test fun `a turn on a transcript at its cap still settles the accepted send`() {
        var full = base
        for (i in 1..2_000L) full = reduce(full, Envelope(Envelope.TEXT_DELTA, "line $i\n", "a", sequence = i), 5)
        val size = full.agents["a"]!!.entries.size
        val acked = frame(full.withPendingSend(sent), Envelope(Envelope.PROMPT_ACK, "accepted", "a", requestId = "send:1"))
        val echoed = frame(acked, Envelope(Envelope.TURN, "run the tests", "a", sequence = 2_001))
        assertEquals(size, echoed.agents["a"]!!.entries.size, "the cap holds the size")
        assertTrue(echoed.pendingSendsFor("a").isEmpty(), "the echo replaced the bubble")
    }

    @Test fun `a turn carries a message of several paragraphs as one unbroken run`() {
        assertTrue(turnCarries("a\n\nb\n\n[image: x]", "b\n\n[image: x]"))
        assertTrue(turnCarries("b\r\n\r\n[image: x]", "b\n\n[image: x]"))
        assertFalse(turnCarries("b\n\nc\n\n[image: x]", "b\n\n[image: x]"))
        assertTrue(turnCarries("x\n\nrun the tests", "run the tests"))
    }

    @Test fun `no answer in time shows not sent - retry, text kept`() {
        val expired = pending.expirePendingSend("send:1", 45)
        val p = expired.pendingSends["send:1"]!!
        assertEquals(SendState.FAILED, p.state)
        assertEquals("run the tests", p.text)
        assertEquals("not sent (no answer in 45s) - retry", PendingSends.label(p))
        val acked = frame(pending, Envelope(Envelope.PROMPT_ACK, "accepted", "a", requestId = "send:1"))
        assertEquals(SendState.ACCEPTED, acked.expirePendingSend("send:1", 45).pendingSends["send:1"]?.state, "an accepted send never expires")
    }

    @Test fun `a refusal fails it with the daemon's reason`() {
        val refused = frame(pending, Envelope(Envelope.ERROR, "resume failed: no such session", requestId = "send:1"))
        assertEquals(SendState.FAILED, refused.pendingSends["send:1"]?.state)
        assertEquals("resume failed: no such session", refused.pendingSends["send:1"]?.reason)
        assertNull(pending.pendingRefusedBusy(Envelope(Envelope.ERROR, "resume failed", requestId = "send:1")))
        assertEquals(sent, pending.pendingRefusedBusy(Envelope(Envelope.ERROR, "run_in_flight a: busy", requestId = "send:1")), "busy goes to the queue")
    }

    @Test fun `a dropped link fails what is still sending`() {
        val dropped = failSendsOnDrop(pending, pending.copy(connection = pending.connection.copy(state = ConnState.RECONNECTING)))
        assertEquals(SendState.FAILED, dropped.pendingSends["send:1"]?.state)
        assertEquals("connection dropped", dropped.pendingSends["send:1"]?.reason)
    }

    @Test fun `the wait comes from the daemon's settings frame`() {
        assertEquals(12L, reduce(base, Envelope(Envelope.SETTINGS, """{"limits.chat.sendAckSeconds":12}"""), 1).sendAckSeconds)
        assertEquals(PendingSends.DEFAULT_ACK_SECONDS, reduce(base, Envelope(Envelope.SETTINGS, "{}"), 1).sendAckSeconds)
    }

    @Test fun `client ids carry the prefix the daemon dedupes on and differ per message`() {
        val a = PendingSends.newClientId(1)
        val b = PendingSends.newClientId(1)
        assertTrue(a.startsWith(Envelope.CLIENT_SEND_PREFIX))
        assertTrue(a != b)
    }
}
