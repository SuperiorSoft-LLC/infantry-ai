package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.parseNodes
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.model.visibleAgents
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.FinishedPayload
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import com.superiorsoftllc.agenthq.ui.protocol.StatusPayload
import com.superiorsoftllc.agenthq.ui.protocol.WaitingOn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun rec(id: String, state: SessionState = SessionState.IDLE, psid: String? = "p") =
        SessionRecord(id, "stub", "/w", spawnedAt = 1, lastHeartbeat = 1, state = state, providerSessionId = psid)

    private fun session(r: SessionRecord) = Envelope(Envelope.SESSION, json.encodeToString(SessionRecord.serializer(), r), r.id)
    private fun feed(vararg e: Envelope) = e.fold(AppState()) { s, f -> reduce(s, f, 100) }

    @Test fun `ready marks connected only via the frame and records the contract`() {
        val s = feed(Envelope(Envelope.READY, "provider=stub contract=1.2.0"))
        assertEquals(ConnState.CONNECTED, s.connection.state)
        assertEquals("1.2.0", s.connection.contract)
    }

    @Test fun `text deltas coalesce into one line and status adds a line only for a provider label`() {
        val s = feed(
            session(rec("a")),
            Envelope(Envelope.TEXT_DELTA, "hel", "a"), Envelope(Envelope.TEXT_DELTA, "lo", "a"),
            Envelope(Envelope.STATUS, json.encodeToString(StatusPayload.serializer(), StatusPayload(SessionState.BUSY)), "a"),
            Envelope(Envelope.STATUS, json.encodeToString(StatusPayload.serializer(), StatusPayload(SessionState.BUSY, "thinking")), "a"),
            Envelope(Envelope.FINISHED, json.encodeToString(FinishedPayload.serializer(), FinishedPayload(0, FinishedPayload.Outcome.SUCCEEDED)), "a"),
        )
        val out = s.agents.getValue("a").output
        assertEquals(listOf(LineKind.TEXT, LineKind.STATUS, LineKind.FINISHED), out.map { it.kind })
        assertEquals("hello", out[0].text)
    }

    @Test fun `role is read off the session frame when present and absent otherwise`() {
        val withRole = Envelope(
            Envelope.SESSION,
            """{"id":"r","provider":"x","workingDir":"/","spawnedAt":1,"lastHeartbeat":1,"state":"IDLE","role":"reviewer"}""",
            "r",
        )
        val s = feed(withRole, session(rec("n")))
        assertEquals("reviewer", s.agents.getValue("r").role)
        assertNull(s.agents.getValue("n").role)
        assertEquals(listOf("r"), visibleAgents(s, "", "reviewer").map { it.id })
    }

    @Test fun `needs input comes only from WAITING_INPUT or a mail wait, never from text`() {
        val s0 = feed(session(rec("a", SessionState.BUSY)), Envelope(Envelope.TEXT_DELTA, "Should I continue?", "a"))
        assertFalse(s0.needsInput("a"))
        assertEquals(0, s0.needsInputCount)
        assertTrue(reduce(s0, session(rec("a", SessionState.WAITING_INPUT)), 1).needsInput("a"))
        val w = WaitingOn("b", "m", "t", "a", 1, "q")
        val s2 = reduce(
            feed(session(rec("b"))),
            Envelope(Envelope.MAIL_WAITING, json.encodeToString(ListSerializer(WaitingOn.serializer()), listOf(w))),
            1,
        )
        assertTrue(s2.needsInput("b"))
        assertFalse(reduce(s2, Envelope(Envelope.MAIL_WAITING, "[]"), 1).needsInput("b"))
    }

    @Test fun `a frame that cannot be decoded becomes a notice and does not throw`() {
        assertEquals(1, feed(Envelope(Envelope.SESSION, "{not json")).notices.size)
    }

    @Test fun `a session-less error is a notice and a session error lands in that transcript`() {
        val s = feed(session(rec("a")), Envelope(Envelope.ERROR, "unknown pod 'x'"), Envelope(Envelope.ERROR, "boom", "a"))
        assertEquals("unknown pod 'x'", s.notices.single().text)
        assertEquals("boom", s.agents.getValue("a").lastError)
    }

    @Test fun `resumable follows the record so resume can be refused up front`() {
        assertTrue(rec("a", SessionState.EXITED).resumable)
        assertFalse(rec("a", SessionState.EXITED, psid = null).resumable)
        assertFalse(rec("a", SessionState.BUSY).resumable)
    }

    @Test fun `parseNodes reads deps and attempts and names the bad line`() {
        val nodes = parseNodes("a: do a\nb after a x3: do b\n# comment\nc after a,b: do c").getOrThrow()
        assertEquals(setOf("a", "b"), nodes[2].dependsOn)
        assertEquals(3, nodes[1].maxAttempts)
        assertTrue(parseNodes("a do a").exceptionOrNull()!!.message!!.contains("line 1"))
        assertTrue(parseNodes("").isFailure)
    }
}
