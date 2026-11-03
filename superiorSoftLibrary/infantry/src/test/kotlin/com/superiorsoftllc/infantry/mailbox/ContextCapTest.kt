// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.persistence.AgentRepository
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** USAGE-OUT-2: a woken session past its context cap restarts fresh with a handoff, the mail in the first turn. */
class ContextCapTest {
    private class Repo : AgentRepository {
        private val p = mutableMapOf<String?, AgentProfile>()
        override fun save(profile: AgentProfile, at: Long) { p[profile.id] = profile }
        override fun get(id: String) = p[id]
        override fun all() = p.values.toList()
        override fun delete(id: String) = p.remove(id) != null
    }

    private class H(cap: (String, String) -> Long, used: Long) {
        val runner = ScriptedRunner(autoExit = true)
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50)
        val mailbox = Mailbox(MemMailStore(), MailLimits())
        val router = MailRouter(manager, mailbox, AgentProfiles(Repo(), manager), contextCap = cap, contextTokens = { used })
            .also { r -> manager.eventSink = { e -> r.onSessionEvent(e) } }
    }

    @Test
    fun `past the cap the session restarts and the mail rides in the fresh first turn with a handoff`() {
        val h = H({ _, _ -> 100 }, 500)
        val agent = h.manager.startNew("bootstrap")
        assertTrue(agent.runCount > 0)
        h.router.send(MailSendRequest(from = "human", to = agent.id, body = "wake up please"))
        val turn = h.runner.processes.last().input.toString()
        assertTrue("CONTEXT HANDOFF" in turn && "wake up please" in turn, turn)
        assertEquals(1L, h.manager.get(agent.id)!!.runCount.toLong(), "fresh context: the run count restarted")
    }

    @Test
    fun `cap 0 is off and under the cap changes nothing`() {
        for ((cap, used) in listOf(0L to 999_999L, 1000L to 10L)) {
            val h = H({ _, _ -> cap }, used)
            val agent = h.manager.startNew("bootstrap")
            val before = agent.runCount
            h.router.send(MailSendRequest(from = "human", to = agent.id, body = "hello"))
            assertFalse("CONTEXT HANDOFF" in h.runner.processes.last().input)
            assertTrue(h.manager.get(agent.id)!!.runCount > before)
        }
    }

    @Test
    fun `a per-role cap is asked with the session role`() {
        val seen = mutableListOf<String>()
        val h = H({ role, _ -> seen += role; 0L }, 500)
        val agent = h.manager.startNew("bootstrap")
        h.router.send(MailSendRequest(from = "human", to = agent.id, body = "hello"))
        assertEquals(1, seen.size)
    }
}
