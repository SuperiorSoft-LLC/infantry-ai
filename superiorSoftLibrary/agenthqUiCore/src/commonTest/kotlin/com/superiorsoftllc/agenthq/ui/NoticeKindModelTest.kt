package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.Notice
import com.superiorsoftllc.agenthq.ui.model.NoticeKind
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.model.withNotice
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.Ticket
import com.superiorsoftllc.agenthq.ui.protocol.TicketStart
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** NOTICE-COLOUR-1: red is opt-in; each raiser of a notice says whether it is routine, a heads-up or a failure. */
class NoticeKindModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun kindOf(env: Envelope) = reduce(AppState(), env, 1).notices.single().kind

    private fun startKind(start: TicketStart) =
        kindOf(Envelope(Envelope.TICKET, json.encodeToString(Ticket.serializer(), Ticket("t1", "T", status = "in_progress", start = start))))

    @Test fun `a notice is routine unless its raiser says otherwise`() {
        assertEquals(NoticeKind.OK, Notice(1, "x").kind)
        assertEquals(NoticeKind.OK, AppState().withNotice(1, "x", null).notices.single().kind)
    }

    @Test fun `ticket start answers`() {
        assertEquals(NoticeKind.OK, startKind(TicketStart(false, "queued", "fullstack")))
        assertEquals(NoticeKind.OK, startKind(TicketStart(true, "started", "cut from main 1a2b3c4d")))
        assertEquals(NoticeKind.HEADS_UP, startKind(TicketStart(true, "started", "cut from main 1a2b3c4d; launch warning: no key")))
        assertEquals(NoticeKind.HEADS_UP, startKind(TicketStart(false, "held", "t0")))
        assertEquals(NoticeKind.ERROR, startKind(TicketStart(false, "start_refused", "no repo configured")))
    }

    @Test fun `a busy agent is a heads-up and any other daemon error is red`() {
        assertEquals(NoticeKind.HEADS_UP, kindOf(Envelope(Envelope.ERROR, "session s1 already has a run in flight")))
        assertEquals(NoticeKind.HEADS_UP, kindOf(Envelope(Envelope.ERROR, "run_stop refused: no such run in flight")))
        assertEquals(NoticeKind.ERROR, kindOf(Envelope(Envelope.ERROR, "unknown pod 'x'")))
        assertEquals(NoticeKind.ERROR, kindOf(Envelope(Envelope.SESSION, "{not json")))
    }
}
