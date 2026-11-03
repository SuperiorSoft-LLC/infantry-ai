package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.daemon.api.tickets.TicketReports
import com.superiorsoftllc.infantry.persistence.TicketRow
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TICKET-COMMENTS-4: the safety-net comment when a worker's turn ends with the ticket open and no report. */
class TicketIdleCommentTest {
    private class Rig(enabled: Boolean = true) {
        var now = System.currentTimeMillis() + 1_000
        val manager = SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = true), tempSessionStore(), killGraceMs = 50, bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy())
        val mailbox = Mailbox(MemMailStore(), MailLimits(), clock = { now })
        val sent = mutableListOf<MailSendRequest>()
        val posted = mutableListOf<String>()
        var workerCommented = false
        val sessionId = manager.startNew("work", ticketId = "t1").id
        val port = object : TicketReports {
            override fun commentedSince(ticketId: String, sessionId: String, sinceMs: Long) = workerCommented
            override fun postDaemonIssue(ticketId: String, body: String): Boolean { posted += body; return true }
        }
        val monitor = FinishNoticeMonitor(
            manager, mailbox, send = { r -> sent += r; mailbox.send(r) },
            ticketOf = { TicketRow(id = "t1", title = "T", status = "in_progress", position = 0, createdAt = 1, updatedAt = 1, sessionId = sessionId, startedBy = "pm", branch = "ticket/t1-x") },
            commitOf = { null }, clock = { now },
            reports = { port }, idleComment = { enabled },
            gitState = { _, _ -> FinishNoticeMonitor.GitState("abcd1234", 2, true) },
        )
        fun exit(code: Int) = monitor.onSessionEvent(SessionEvent.Exit(sessionId, code))
        fun say(line: String) = monitor.onSessionEvent(SessionEvent.Stdout(sessionId, line))
        fun prompt() { now += 10; monitor.onSessionEvent(SessionEvent.Turn(sessionId, "go")); now += 10 }
    }

    @Test
    fun idle_without_report_posts_one_daemon_comment_with_tip_and_dirty_flag() {
        val r = Rig()
        r.say("all done, committed"); r.exit(0)
        assertEquals(1, r.posted.size)
        val b = r.posted[0]
        assertTrue(b.contains("went idle without a report"), b)
        assertTrue(b.contains("ticket/t1-x") && b.contains("tip abcd1234") && b.contains("2 new commits") && b.contains("worktree dirty"), b)
        assertTrue(b.contains("Last output: all done, committed"), b)
        assertEquals(0, r.sent.size)
    }

    @Test
    fun a_comment_by_the_worker_or_mail_to_the_starter_means_no_daemon_comment() {
        val c = Rig(); c.workerCommented = true; c.exit(0)
        assertEquals(0, c.posted.size)
        val m = Rig(); m.mailbox.send(MailSendRequest(from = m.sessionId, to = "pm", subject = "report", body = "done")); m.exit(0)
        assertEquals(0, m.posted.size)
    }

    @Test
    fun idle_twice_in_the_same_run_posts_one() {
        val r = Rig()
        r.exit(0); r.exit(0)
        assertEquals(1, r.posted.size)
    }

    @Test
    fun switched_off_or_non_zero_exit_posts_nothing_and_failure_keeps_its_notice() {
        val off = Rig(enabled = false); off.exit(0)
        assertEquals(0, off.posted.size)
        val bad = Rig(); bad.exit(1)
        assertEquals(0, bad.posted.size)
        assertEquals(1, bad.sent.size)
        assertTrue(bad.sent[0].subject.orEmpty().contains("AUTOMATIC FAILURE"))
    }
}
