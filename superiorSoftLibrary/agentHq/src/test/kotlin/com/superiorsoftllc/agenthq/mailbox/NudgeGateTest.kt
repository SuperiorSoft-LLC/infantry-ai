package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.persistence.TicketRow
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** NUDGE-SCRIPT-1: each converted built-in nudge emits its event; deferred = left to the hook, not deferred = sent as before. */
class NudgeGateTest {
    private class Rig(val deferred: Set<String>) {
        var now = System.currentTimeMillis() + 1_000
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val manager = SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = true), tempSessionStore(), killGraceMs = 50, bindingPolicy = com.superiorsoftllc.agenthq.daemon.plugins.TicketBindingPolicy())
        val mailbox = Mailbox(MemMailStore(), MailLimits(), clock = { now })
        val sent = mutableListOf<MailSendRequest>()
        val sessionId = manager.startNew("work", ticketId = "t1").id
        val monitor = FinishNoticeMonitor(
            manager, mailbox, send = { r -> sent += r; mailbox.send(r) },
            ticketOf = { TicketRow(id = "t1", title = "T", status = "in_progress", position = 0, createdAt = 1, updatedAt = 1, sessionId = sessionId, startedBy = "pm") },
            commitOf = { null }, clock = { now },
            idleNoticeMs = { 10 * 60_000L }, idleNudgeMs = { 5 * 60_000L },
            gate = NudgeGate({ n, p -> events += n to p }, { it in deferred }),
        )
        fun exit(code: Int) = monitor.onSessionEvent(SessionEvent.Exit(sessionId, code))
        fun minutes(n: Long) { now += n * 60_000L }
    }

    @Test
    fun failure_notice_without_a_hook_is_sent_as_before_and_the_event_is_emitted() {
        val r = Rig(emptySet())
        r.exit(1)
        assertEquals(1, r.sent.size)
        assertTrue(r.sent[0].subject.orEmpty().contains("AUTOMATIC FAILURE"))
        assertEquals(listOf(NudgeGate.TICKET_FAILED), r.events.map { it.first })
        assertEquals("t1", r.events[0].second["ticketId"])
    }

    @Test
    fun failure_notice_deferred_to_a_hook_sends_no_mail() {
        val r = Rig(setOf(NudgeGate.TICKET_FAILED))
        r.exit(1)
        assertEquals(0, r.sent.size)
        assertEquals("1", r.events.single().second["exitCode"])
    }

    @Test
    fun idle_nudge_and_waiting_note_defer_independently() {
        val deferNudge = Rig(setOf(NudgeGate.WORKER_IDLE))
        deferNudge.exit(0)
        deferNudge.minutes(6)
        deferNudge.monitor.sweep()
        assertEquals(0, deferNudge.sent.size)
        assertEquals(NudgeGate.WORKER_IDLE, deferNudge.events.single().first)

        val plain = Rig(emptySet())
        plain.exit(0)
        plain.minutes(6)
        plain.monitor.sweep()
        assertEquals(1, plain.sent.size)
        assertEquals(plain.sessionId, plain.sent[0].to)

        val deferWaiting = Rig(setOf(NudgeGate.WORKER_WAITING))
        deferWaiting.exit(0)
        deferWaiting.minutes(6); deferWaiting.monitor.sweep()
        deferWaiting.minutes(11); deferWaiting.monitor.sweep()
        assertEquals(listOf(deferWaiting.sessionId), deferWaiting.sent.map { it.to })
        assertTrue(deferWaiting.events.any { it.first == NudgeGate.WORKER_WAITING })
    }

    @Test
    fun a_failing_emit_never_blocks_the_default() {
        val g = NudgeGate({ _, _ -> error("bus down") }, { false })
        assertFalse(g.handled("notice.ticket_finished", emptyMap()))
        assertFalse(NudgeGate.NONE.handled("x", emptyMap()))
    }
}
