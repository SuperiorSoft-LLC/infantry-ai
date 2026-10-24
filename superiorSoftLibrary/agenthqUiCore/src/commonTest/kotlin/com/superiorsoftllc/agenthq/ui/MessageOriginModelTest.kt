package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.LegacyOriginAdapter
import com.superiorsoftllc.agenthq.ui.model.MessageOrigin
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Contract 1.125.0: the `turn` frame's typed origin reaches the transcript line; absent = Owner; legacy header mapped once. */
class MessageOriginModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val session = Envelope(
        Envelope.SESSION,
        json.encodeToString(SessionRecord.serializer(), SessionRecord("a", "stub", "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "p")),
        "a", 1,
    )

    private fun originOf(turn: Envelope): MessageOrigin =
        reduce(reduce(AppState(), session, 100), turn, 100).agents.getValue("a").output.single().origin

    @Test fun `a turn frame's origin lands on its transcript line`() {
        val o = MessageOrigin.Agent("s9", "a9", "architect")
        assertEquals(o, originOf(Envelope(Envelope.TURN, "hi", "a", 2, origin = o)))
        assertEquals(MessageOrigin.External("gh", "mail"), originOf(Envelope(Envelope.TURN, "hi", "a", 2, origin = MessageOrigin.External("gh", "mail"))))
    }

    @Test fun `a frame with no origin field is the owner`() {
        val frame = json.decodeFromString(Envelope.serializer(), """{"type":"turn","data":"why?","sessionId":"a","sequence":2}""")
        assertNull(frame.origin)
        assertEquals(MessageOrigin.Owner, originOf(frame))
        assertFalse(json.encodeToString(Envelope.serializer(), Envelope("turn", "x")).contains("origin"))
    }

    @Test fun `the daemon wire shape decodes to each origin kind`() {
        fun decode(origin: String) = json.decodeFromString(Envelope.serializer(), """{"type":"turn","origin":$origin}""").origin
        assertEquals(MessageOrigin.Owner, decode("""{"type":"owner"}"""))
        assertEquals(MessageOrigin.Agent("s", "a", "qa"), decode("""{"type":"agent","sessionId":"s","agentId":"a","role":"qa"}"""))
        assertEquals(MessageOrigin.External("x", "mail"), decode("""{"type":"external","senderLabel":"x","channel":"mail"}"""))
        assertEquals(MessageOrigin.System("handover"), decode("""{"type":"system","kind":"handover"}"""))
    }

    @Test fun `an old transcript's mail header maps to an origin only through the legacy adapter`() {
        val id = "dd062cba-1111-2222-3333-444455556666"
        val header = "[Infantry AI COMMS] message m1 from $id · thread t1 · subj\n\nbody"
        assertEquals(MessageOrigin.Agent(sessionId = id), LegacyOriginAdapter.originOf(header))
        assertEquals(MessageOrigin.Owner, LegacyOriginAdapter.originOf("[Infantry AI COMMS] message m1 from human · thread t"))
        assertEquals(MessageOrigin.System("mail"), LegacyOriginAdapter.originOf("[Infantry AI COMMS] message m1 from system · thread t"))
        assertEquals(MessageOrigin.External("bridge", "mail"), LegacyOriginAdapter.originOf("[Infantry AI COMMS] message m1 from bridge · thread t"))
        assertEquals(MessageOrigin.Owner, LegacyOriginAdapter.originOf("an ordinary question"))
        // through the reducer: an origin-less frame with the header gets the legacy origin; one WITH an origin never does
        assertEquals(MessageOrigin.Agent(sessionId = id), originOf(Envelope(Envelope.TURN, header, "a", 2)))
        assertEquals(MessageOrigin.Owner, originOf(Envelope(Envelope.TURN, header, "a", 2, origin = MessageOrigin.Owner)))
    }
}
