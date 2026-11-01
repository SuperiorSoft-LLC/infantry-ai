package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.persistence.AgentRepository
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** OVERNIGHT-1: queued mail and trigger firings wake an idle session within a hard bound; a backlog is ONE short turn. */
class MailWakeBoundTest {
    private class Repo : AgentRepository {
        private val p = mutableMapOf<String?, AgentProfile>()
        override fun save(profile: AgentProfile, at: Long) { p[profile.id] = profile }
        override fun get(id: String) = p[id]
        override fun all() = p.values.toList()
        override fun delete(id: String) = p.remove(id) != null
    }

    private class H(mode: String = "real", debounceMs: Long = 0L, maxMs: Long = 120_000L) {
        var now = System.currentTimeMillis()
        val runner = ScriptedRunner(autoExit = true)
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50)
        val mailbox = Mailbox(MemMailStore(), MailLimits(perSenderPerWindow = 1000), clock = { now })
        val router = MailRouter(
            manager, mailbox, AgentProfiles(Repo(), manager),
            wakeMode = { _, _ -> mode }, wakeDebounceMs = { debounceMs }, wakeMaxMs = { maxMs }, clock = { now },
        ).also { r -> manager.eventSink = { e -> r.onSessionEvent(e) } }
        val agent = manager.startNew("bootstrap")
        val turns get() = runner.processes.size
    }

    @Test
    fun `automation mail under wakeMode real stays queued before the bound and wakes the session at it`() {
        val h = H(mode = "real")
        val before = h.turns
        h.router.send(MailSendRequest(from = "trigger:trg-1", to = h.agent.id, subject = "Trigger: Reminder", body = "ping"))
        assertEquals(before, h.turns, "young automation mail rides along, no wake yet")
        h.now += 121_000
        h.router.pump(h.agent.id)
        assertEquals(before + 1, h.turns, "past the hard bound it wakes the idle session")
    }

    @Test
    fun `wakeMode never cannot stop a session from waking at the bound`() {
        val h = H(mode = "never")
        val before = h.turns
        h.router.send(MailSendRequest(from = "human", to = h.agent.id, subject = "hi", body = "x"))
        assertEquals(before, h.turns)
        h.now += 120_000
        h.router.pump(h.agent.id)
        assertEquals(before + 1, h.turns)
    }

    @Test
    fun `real mail wakes an idle session at once`() {
        val h = H(mode = "real")
        val before = h.turns
        h.router.send(MailSendRequest(from = "human", to = h.agent.id, subject = "hi", body = "x"))
        assertEquals(before + 1, h.turns)
    }

    @Test
    fun `the bound timer wakes the session without any further pump`() {
        val h = H(mode = "never", maxMs = 200)
        val before = h.turns
        h.router.send(MailSendRequest(from = "system", to = h.agent.id, subject = "[AUTOMATIC] note", body = "x"))
        h.now += 300
        val deadline = System.currentTimeMillis() + 5_000
        while (h.turns == before && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(before + 1, h.turns)
    }

    @Test
    fun `a 39 item backlog is ONE turn, duplicates collapse with a count, one line per item`() {
        val h = H(mode = "real")
        val before = h.turns
        // queue them directly (as a restart or a busy stretch would leave them)
        repeat(30) { h.mailbox.send(MailSendRequest(from = "trigger:trg-8ab478f9", to = h.agent.id, subject = "Trigger: Reminder", body = "Reminder body ".repeat(200))) }
        repeat(8) { h.mailbox.send(MailSendRequest(from = "system", to = h.agent.id, subject = "[AUTOMATIC] ticket ticket-77434467 is waiting for instructions", body = "Worker finished\n".repeat(50))) }
        h.mailbox.send(MailSendRequest(from = "human", to = h.agent.id, subject = "go", body = "do it"))
        assertEquals(39, h.mailbox.inbox(h.agent.id).count { it.state == MessageState.QUEUED })
        h.router.pump(h.agent.id)
        assertEquals(before + 1, h.turns, "one turn, not 39")
        val text = h.runner.processes.last().input.toString()
        assertTrue("(x30)" in text && "(x8)" in text, text)
        assertEquals(3, text.lines().count { it.startsWith("- ") }, text)
        assertTrue(text.length < 1_000, "short payload: ${text.length}")
        assertEquals(0, h.mailbox.inbox(h.agent.id).count { it.state == MessageState.QUEUED }, "everything delivered by that one turn")
    }

    @Test
    fun `the debounce never exceeds the hard bound`() {
        val h = H(mode = "all", debounceMs = 10_000_000L, maxMs = 150)
        val before = h.turns
        h.router.send(MailSendRequest(from = "human", to = h.agent.id, subject = "hi", body = "x"))
        val deadline = System.currentTimeMillis() + 5_000
        while (h.turns == before && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(before + 1, h.turns)
        assertFalse(h.turns == before)
    }
}
