package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.WatchState
import com.superiorsoftllc.agenthq.session.WatchdogConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** PIPELINE-WATCHDOG: each state, the nudge -> PM -> owner ladder, and "nothing pending, nothing happens". */
class AgentWatchdogTest {
    private class Rig(autoExit: Boolean) {
        val runner = ScriptedRunner(autoExit = autoExit)
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50)
        val mailbox = Mailbox(MemMailStore(), MailLimits())
        val sent = mutableListOf<MailSendRequest>()
        val attention = com.superiorsoftllc.agenthq.daemon.MemAttention()
        var offset = 0L
        var enabled = true
        val dog = AgentWatchdog(
            manager, mailbox,
            send = { r -> sent += r; mailbox.send(r) },
            attention = attention,
            config = WatchdogConfig(),
            clock = { System.currentTimeMillis() + offset * 60_000 },
            isEnabledFor = { enabled },
        )
        fun at(minutes: Long) { offset = minutes; dog.tick() }
        fun nudges() = sent.count { it.subject == AgentWatchdog.NUDGE_SUBJECT }
        fun pmMails() = sent.count { it.to == "pm" }
    }

    @Test
    fun an_agent_with_nothing_pending_is_idle_and_untouched() {
        val r = Rig(autoExit = true)
        val s = r.manager.startNew("hello")
        r.at(60)
        assertEquals(WatchState.IDLE.name, r.manager.get(s.id)!!.watch!!.state)
        assertTrue(r.sent.isEmpty())
        assertTrue(r.attention.list().isEmpty())
    }

    @Test
    fun an_unanswered_mail_stalls_then_nudges_then_tells_the_pm_then_the_owner() {
        val r = Rig(autoExit = true)
        val s = r.manager.startNew("hello")
        r.mailbox.send(MailSendRequest(from = "human", to = s.id, subject = "review X", body = "please"))

        r.at(1)
        assertEquals(WatchState.WAITING.name, r.manager.get(s.id)!!.watch!!.state, "younger than the stall threshold")
        assertEquals(0, r.nudges())

        r.at(6)
        val watch = r.manager.get(s.id)!!.watch!!
        assertEquals(WatchState.STALLED.name, watch.state)
        assertNotNull(watch.pendingSince)
        assertTrue(watch.pending!!.contains("review X"))
        assertEquals(1, r.nudges(), "one nudge on the first stalled tick")
        r.at(7)
        assertEquals(1, r.nudges(), "at most one per interval")
        assertEquals(0, r.pmMails())

        r.at(12)
        assertEquals(1, r.pmMails(), "the PM hears after the nudge went unanswered")
        assertEquals(1, r.nudges(), "one nudge per stall by default")
        assertTrue(r.attention.list().isEmpty())

        r.at(18)
        assertEquals(1, r.pmMails(), "the PM is told once")
        assertEquals(1, r.attention.list().size, "then the owner")
    }

    @Test
    fun a_project_with_the_watchdog_off_gets_no_sends_no_attention_and_shows_idle() {
        val r = Rig(autoExit = true)
        r.enabled = false
        val s = r.manager.startNew("hello")
        r.mailbox.send(MailSendRequest(from = "human", to = s.id, subject = "review X", body = "please"))
        for (m in listOf(1L, 6L, 12L, 18L, 40L)) r.at(m)
        assertTrue(r.sent.isEmpty())
        assertTrue(r.attention.list().isEmpty())
        assertEquals(WatchState.IDLE.name, r.manager.get(s.id)!!.watch!!.state)
        assertEquals(null, r.manager.get(s.id)!!.watch!!.pending)
    }

    @Test
    fun flipping_the_switch_takes_effect_on_the_next_tick_without_a_restart() {
        val r = Rig(autoExit = true)
        r.enabled = false
        val s = r.manager.startNew("hello")
        r.mailbox.send(MailSendRequest(from = "human", to = s.id, subject = "review X", body = "please"))
        r.at(6)
        assertEquals(0, r.nudges())
        r.enabled = true
        r.at(7)
        assertEquals(1, r.nudges())
        r.enabled = false
        r.at(30)
        r.at(60)
        assertEquals(1, r.nudges())
        assertEquals(0, r.pmMails())
        assertEquals(WatchState.IDLE.name, r.manager.get(s.id)!!.watch!!.state)
    }

    @Test
    fun mail_from_system_or_a_trigger_is_never_a_pending_item() {
        val r = Rig(autoExit = true)
        val s = r.manager.startNew("hello")
        r.mailbox.send(MailSendRequest(from = "system", to = s.id, subject = "FYI", body = "x"))
        r.mailbox.send(MailSendRequest(from = "trigger:nightly", to = s.id, subject = "wake", body = "x"))
        r.at(6)
        r.at(30)
        assertTrue(r.sent.isEmpty())
        assertEquals(WatchState.IDLE.name, r.manager.get(s.id)!!.watch!!.state)
    }

    @Test
    fun mailing_past_the_item_clears_the_stall() {
        val r = Rig(autoExit = true)
        val s = r.manager.startNew("hello")
        r.mailbox.send(MailSendRequest(from = "human", to = s.id, body = "ping"))
        Thread.sleep(5)
        r.mailbox.send(MailSendRequest(from = s.id, to = "human", body = "done"))
        r.at(30)
        assertEquals(WatchState.IDLE.name, r.manager.get(s.id)!!.watch!!.state)
        assertEquals(0, r.nudges())
    }

    @Test
    fun a_busy_agent_is_working_then_quiet_and_the_pm_hears_once() {
        val r = Rig(autoExit = false)
        val s = r.manager.startNew("long job")
        r.at(1)
        assertEquals(WatchState.WORKING.name, r.manager.get(s.id)!!.watch!!.state)
        r.at(16)
        assertEquals(WatchState.QUIET.name, r.manager.get(s.id)!!.watch!!.state)
        r.at(20)
        assertEquals(1, r.pmMails())
        assertEquals(0, r.nudges(), "a busy agent is not nudged")
    }
}
