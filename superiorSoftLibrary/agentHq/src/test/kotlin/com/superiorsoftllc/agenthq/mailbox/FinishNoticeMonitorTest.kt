package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.persistence.TicketRow
import com.superiorsoftllc.agenthq.session.BgJob
import com.superiorsoftllc.agenthq.session.KillOutcome
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals

/** False "[AUTOMATIC FAILURE]" notices: a waiting worker is silent, a real failure is sent once per window. */
class FinishNoticeMonitorTest {
    private class Rig {
        val manager = SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = true), tempSessionStore(), killGraceMs = 50)
        val mailbox = Mailbox(MemMailStore(), MailLimits())
        val sent = mutableListOf<MailSendRequest>()
        var now = 10_000_000L
        val sessionId = manager.startNew("work", ticketId = "t1").id
        var owner = sessionId
        var status = "in_progress"
        val monitor = FinishNoticeMonitor(
            manager, mailbox, send = { r -> sent += r; mailbox.send(r) },
            ticketOf = { TicketRow(id = "t1", title = "T", status = status, position = 0, createdAt = 1, updatedAt = 1, sessionId = owner, startedBy = "pm") },
            commitOf = { null }, clock = { now },
        ).also { now += FinishNoticeMonitor.RESUME_GRACE_MS + 1 }
        fun exit(code: Int, session: String = sessionId) = monitor.onSessionEvent(SessionEvent.Exit(session, code))
    }

    @Test
    fun exit_zero_with_a_running_background_task_sends_nothing() {
        val r = Rig()
        r.manager.bgJobBeat(r.sessionId, BgJob(id = "j", label = "gradle", pid = 1, startedAt = 1))
        r.exit(0)
        assertEquals(0, r.sent.size)
    }

    @Test
    fun exit_zero_after_a_plan_mail_waiting_for_go_sends_nothing() {
        val r = Rig()
        r.mailbox.send(MailSendRequest(from = r.sessionId, to = "pm", subject = "plan", body = "go?"))
        r.exit(0)
        assertEquals(0, r.sent.size)
    }

    @Test
    fun exit_zero_right_after_a_daemon_start_sends_nothing() {
        val r = Rig()
        r.now -= FinishNoticeMonitor.RESUME_GRACE_MS
        r.exit(0)
        assertEquals(0, r.sent.size)
    }

    @Test
    fun non_zero_exit_sends_one_and_exit_zero_with_nothing_running_still_fails() {
        val r = Rig()
        r.exit(1)
        assertEquals(1, r.sent.size)
        assertEquals(true, r.sent[0].subject!!.startsWith("[AUTOMATIC FAILURE]"))
        val q = Rig()
        q.exit(0)
        assertEquals(1, q.sent.size)
    }

    @Test
    fun two_failures_within_fifteen_minutes_send_one() {
        val r = Rig()
        r.exit(1)
        val second = r.manager.startNew("again", ticketId = "t1").id
        r.owner = second
        r.now += 60_000
        r.exit(1, second)
        assertEquals(1, r.sent.size)
        val third = r.manager.startNew("later", ticketId = "t1").id
        r.owner = third
        r.now += FinishNoticeMonitor.REPEAT_WINDOW_MS
        r.exit(1, third)
        assertEquals(2, r.sent.size)
    }

    @Test
    fun a_kill_is_noticed_and_names_who_killed_it() {
        val r = Rig()
        r.manager.kill(r.sessionId, by = "watchdog", reason = "stalled: no output for 15 min (limit 15 min)")
        r.monitor.onSessionEvent(SessionEvent.Exit(r.sessionId, null, KillOutcome.GRACEFUL))
        assertEquals(1, r.sent.size)
        assertEquals(true, r.sent[0].subject!!.startsWith("[AUTOMATIC FAILURE]"))
        assertEquals(true, r.sent[0].body.contains("FAILURE: killed by watchdog (stalled: no output for 15 min (limit 15 min)); the ticket is still in_progress"))
        assertEquals(true, r.sent[0].body.contains("killed by: watchdog - stalled"))
    }

    @Test
    fun a_kill_after_done_or_by_the_done_worker_reaper_sends_nothing() {
        val r = Rig()
        r.status = "done"
        r.manager.kill(r.sessionId, by = "watchdog")
        r.monitor.onSessionEvent(SessionEvent.Exit(r.sessionId, null, KillOutcome.GRACEFUL))
        val q = Rig()
        q.manager.kill(q.sessionId, force = true, by = "done-worker reaper")
        q.monitor.onSessionEvent(SessionEvent.Exit(q.sessionId, null, KillOutcome.FORCED))
        assertEquals(0, r.sent.size + q.sent.size)
    }
}
