package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.persistence.TicketRow
import com.superiorsoftllc.infantry.session.BgJob
import com.superiorsoftllc.infantry.session.KillOutcome
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ticket ef3356ae: a worker that ends its turn cleanly and waits is never a failure; it gets one calm note per idle
 * period after the idle limit. A real failure is sent once per window.
 */
class FinishNoticeMonitorTest {
    private class Rig(idleMinutes: Long = IDLE_MIN, exempt: Boolean = true, nudgeMinutes: Long? = null) {
        var now = System.currentTimeMillis() + 1_000
        val manager = SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = true), tempSessionStore(), killGraceMs = 50, bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy())
        val mailbox = Mailbox(MemMailStore(), MailLimits(), clock = { now })
        val sent = mutableListOf<MailSendRequest>()
        val sessionId = manager.startNew("work", ticketId = "t1").id
        var owner = sessionId
        var status = "in_progress"
        val monitor = FinishNoticeMonitor(
            manager, mailbox, send = { r -> sent += r; mailbox.send(r) },
            ticketOf = { TicketRow(id = "t1", title = "T", status = status, position = 0, createdAt = 1, updatedAt = 1, sessionId = owner, startedBy = "pm") },
            commitOf = { null }, clock = { now },
            idleNoticeMs = { if (idleMinutes == 0L) Long.MAX_VALUE else idleMinutes * 60_000L },
            idleNudgeMs = { nudgeMinutes?.let { it * 60_000L } ?: Long.MAX_VALUE },
            exemptWhenDone = { exempt },
        )
        fun exit(code: Int, session: String = sessionId) = monitor.onSessionEvent(SessionEvent.Exit(session, code))
        fun minutes(n: Long) { now += n * 60_000L }
        /** A further run of the worker (the mail woke it), back at rest. */
        fun turn() {
            val before = manager.get(sessionId)!!.runCount
            manager.resume(sessionId, "next")
            val deadline = System.currentTimeMillis() + 10_000
            while (manager.get(sessionId)!!.let { it.runCount == before || it.state != SessionState.IDLE }) {
                check(System.currentTimeMillis() < deadline) { "the run did not end" }
                Thread.sleep(10)
            }
        }
    }

    private fun assertCalm(r: MailSendRequest) {
        val text = (r.subject.orEmpty() + "\n" + r.body).lowercase()
        assertFalse(text.contains("failure") || text.contains("failed") || text.contains("did not finish cleanly"), text)
        assertTrue(r.body.contains("Worker finished its turn and is waiting for instructions (ticket still open)."), r.body)
    }

    @Test
    fun stall5_a_non_zero_exit_after_done_or_a_delivered_report_is_silent() {
        val done = Rig()
        done.status = "done"
        done.mailbox.send(MailSendRequest(from = "system", to = done.sessionId, subject = "x", body = "x"))
        done.exit(1)
        assertEquals(0, done.sent.size)
        val reported = Rig()
        reported.now += 10
        reported.mailbox.send(MailSendRequest(from = reported.sessionId, to = "pm", subject = "report", body = "done"))
        reported.exit(1)
        assertEquals(0, reported.sent.size)
    }

    @Test
    fun stall5_a_non_zero_exit_with_no_report_and_the_ticket_open_is_still_a_failure() {
        val r = Rig()
        r.exit(1)
        assertEquals(1, r.sent.size)
        assertTrue(r.sent[0].subject.orEmpty().contains("AUTOMATIC FAILURE"))
    }

    @Test
    fun stall5_the_off_switch_restores_the_failure_notice_after_done() {
        val r = Rig(exempt = false)
        r.status = "done"
        r.exit(1)
        assertEquals(1, r.sent.size)
        assertTrue(r.sent[0].subject.orEmpty().contains("AUTOMATIC FAILURE"))
    }

    @Test
    fun a_clean_end_of_turn_with_the_ticket_open_sends_nothing() {
        val r = Rig()
        r.exit(0)
        r.exit(0)
        assertEquals(0, r.sent.size)
    }

    @Test
    fun a_waiting_worker_gets_one_calm_note_per_idle_period() {
        val r = Rig()
        r.exit(0)
        r.minutes(IDLE_MIN - 1)
        r.monitor.sweep()
        assertEquals(0, r.sent.size)
        r.minutes(1)
        r.monitor.sweep()
        assertEquals(1, r.sent.size)
        assertEquals("pm", r.sent[0].to)
        assertCalm(r.sent[0])
        // More turns and sweeps without mail are the same period: nothing more.
        r.exit(0)
        r.minutes(IDLE_MIN * 3)
        r.monitor.sweep()
        assertEquals(1, r.sent.size)
        // The PM mails it, it ends another turn: a new period, one more note once that one is long enough.
        r.mailbox.send(MailSendRequest(from = "pm", to = r.sessionId, subject = "hold", body = "wait for my go"))
        r.minutes(1)
        r.turn()
        r.exit(0)
        r.minutes(IDLE_MIN)
        r.monitor.sweep()
        r.monitor.sweep()
        assertEquals(2, r.sent.size)
        assertCalm(r.sent[1])
    }

    @Test
    fun no_note_to_a_pm_that_just_mailed_the_worker() {
        val r = Rig()
        r.exit(0)
        r.minutes(IDLE_MIN - 5)
        r.mailbox.send(MailSendRequest(from = "pm", to = r.sessionId, subject = "hold", body = "stay on hold"))
        r.minutes(10)
        r.monitor.sweep()
        assertEquals(0, r.sent.size)
    }

    @Test
    fun a_running_background_task_keeps_the_worker_out_of_the_idle_note() {
        val r = Rig()
        r.exit(0)
        r.manager.bgJobBeat(r.sessionId, BgJob(id = "j", label = "gradle", pid = 1, startedAt = 1))
        r.minutes(IDLE_MIN * 2)
        r.monitor.sweep()
        assertEquals(0, r.sent.size)
    }

    @Test
    fun an_idle_limit_of_zero_never_notes() {
        val r = Rig(idleMinutes = 0)
        r.exit(0)
        r.minutes(100_000)
        r.monitor.sweep()
        assertEquals(0, r.sent.size)
    }

    @Test
    fun a_done_ticket_sends_the_finished_notice_and_no_idle_note() {
        val r = Rig()
        r.status = "done"
        r.exit(0)
        assertEquals(1, r.sent.size)
        assertEquals(true, r.sent[0].subject!!.startsWith("[AUTOMATIC] ticket t1 finished"))
        assertFalse(r.sent[0].body.contains("FAILURE"))
        r.minutes(IDLE_MIN * 2)
        r.monitor.sweep()
        assertEquals(1, r.sent.size)
    }

    @Test
    fun a_non_zero_exit_is_a_failure() {
        val r = Rig()
        r.exit(1)
        assertEquals(1, r.sent.size)
        assertEquals(true, r.sent[0].subject!!.startsWith("[AUTOMATIC FAILURE]"))
        assertEquals(true, r.sent[0].body.contains("FAILURE: exit code 1"))
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


    @Test
    fun overnight1_the_idle_worker_is_prompted_first_and_the_starter_only_after_another_interval() {
        val r = Rig(nudgeMinutes = 10)
        r.exit(0)
        r.minutes(9)
        r.monitor.sweep()
        assertEquals(0, r.sent.size)
        r.minutes(1)
        r.monitor.sweep()
        assertEquals(1, r.sent.size)
        assertEquals(r.sessionId, r.sent[0].to, "the worker is prompted first")
        r.minutes(IDLE_MIN - 1)
        r.monitor.sweep()
        assertEquals(1, r.sent.size, "the starter is not told before a full interval after the nudge")
        r.minutes(1)
        r.monitor.sweep()
        assertEquals(2, r.sent.size)
        assertEquals("pm", r.sent[1].to)
        r.monitor.sweep()
        assertEquals(2, r.sent.size, "told once")
    }
    private companion object {
        const val IDLE_MIN = 60L
    }
}
