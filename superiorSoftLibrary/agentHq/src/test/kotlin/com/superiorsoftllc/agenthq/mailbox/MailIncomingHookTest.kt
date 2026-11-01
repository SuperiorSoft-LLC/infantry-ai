package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.persistence.AgentRepository
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MAIL-HOOK-1: the pre-delivery hook. Handled = read, nobody woken; a failing / null hook = normal delivery; a text replaces the delivered text. */
class MailIncomingHookTest {
    private class Repo : AgentRepository {
        private val p = mutableMapOf<String?, AgentProfile>()
        override fun save(profile: AgentProfile, at: Long) { p[profile.id] = profile }
        override fun get(id: String) = p[id]
        override fun all() = p.values.toList()
        override fun delete(id: String) = p.remove(id) != null
    }

    private class H {
        val runner = ScriptedRunner(autoExit = true)
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50)
        val mailbox = Mailbox(MemMailStore(), MailLimits(perSenderPerWindow = 1000))
        val router = MailRouter(manager, mailbox, AgentProfiles(Repo(), manager), wakeMode = { _, _ -> "real" }, wakeDebounceMs = { 0L })
            .also { r -> manager.eventSink = { e -> r.onSessionEvent(e) } }
        val agent = manager.startNew("bootstrap")
        val turns get() = runner.processes.size
    }

    @Test
    fun `a handled mail is marked read, wakes nobody, and the hook saw the resolved recipient`() {
        val h = H()
        var seen: MailRouter.IncomingMailInfo? = null
        h.router.onIncoming = { seen = it; MailRouter.MailVerdictInfo(handled = true) }
        val before = h.turns
        val sent = h.router.send(MailSendRequest(from = "human", to = h.agent.id, subject = "hi", body = "routine")) as MailResult.Sent
        assertEquals(before, h.turns, "no wake")
        assertEquals(MessageState.READ, h.mailbox.message(sent.message.id)!!.state)
        assertEquals(h.agent.id, seen!!.recipientSessionId)
        assertEquals("routine", seen!!.message.body)
    }

    @Test
    fun `a hook that throws, or answers null, delivers as today`() {
        for (hook in listOf<(MailRouter.IncomingMailInfo) -> MailRouter.MailVerdictInfo?>({ error("boom") }, { null }, { MailRouter.MailVerdictInfo(false) })) {
            val h = H()
            h.router.onIncoming = hook
            val before = h.turns
            val sent = h.router.send(MailSendRequest(from = "human", to = h.agent.id, subject = "hi", body = "x")) as MailResult.Sent
            assertEquals(before + 1, h.turns, "woken")
            assertTrue(h.mailbox.message(sent.message.id)!!.state != MessageState.READ)
        }
    }

    @Test
    fun `a replacement text is what the session is given, for that one mail`() {
        val h = H()
        h.router.onIncoming = { MailRouter.MailVerdictInfo(false, "REPLACED TEXT") }
        val before = h.turns
        h.router.send(MailSendRequest(from = "human", to = h.agent.id, subject = "hi", body = "original body"))
        assertEquals(before + 1, h.turns)
        val text = h.runner.processes.last().input.toString()
        assertTrue("REPLACED TEXT" in text && "original body" !in text, text)
    }
}
