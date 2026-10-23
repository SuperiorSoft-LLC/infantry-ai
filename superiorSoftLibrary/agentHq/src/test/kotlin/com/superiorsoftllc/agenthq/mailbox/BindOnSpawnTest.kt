package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.daemon.projectAgents
import com.superiorsoftllc.agenthq.daemon.bindSessionOnSpawn
import com.superiorsoftllc.agenthq.daemon.roster
import com.superiorsoftllc.agenthq.persistence.AgentRepository
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.SpawnOptions
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BIND ticket: a console opened as a profile must be addressable BY PROFILE ID before anyone has typed
 * into it. The owner's reproduction: two console agents, address one by profile id, assert DELIVERED,
 * with no message typed into the second console first.
 */
class BindOnSpawnTest {
    private class Repo : AgentRepository {
        private val m = mutableMapOf<String?, AgentProfile>()
        override fun save(profile: AgentProfile, at: Long) { m[profile.id] = profile }
        override fun get(id: String) = m[id]
        override fun all() = m.values.toList()
        override fun delete(id: String) = m.remove(id) != null
    }

    private class Rig {
        val runner = ScriptedRunner(autoExit = true)
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50)
        val mailbox = Mailbox(MemMailStore(), MailLimits())
        val repo = Repo()
        val agents = AgentProfiles(repo, manager)
        val router = MailRouter(
            manager, mailbox, agents,
        ).also { r -> manager.eventSink = { e -> r.onSessionEvent(e) } }

        init {
            repo.save(AgentProfile(id = "architect", name = "Architect", role = "architect", instructions = "You are the architect."), 0)
            repo.save(AgentProfile(id = "pm", name = "PM", role = "pm"), 0)
        }
    }

    @Test
    fun two_console_agents_reach_each_other_by_profile_id_with_nothing_typed() {
        val rig = Rig()
        // Both consoles open (the `new` frame). Nothing is ever prompted.
        val architect = bindSessionOnSpawn(rig.manager, rig.agents, SpawnOptions(agentId = "architect", projectId = "p1"), null)
        val pm = bindSessionOnSpawn(rig.manager, rig.agents, SpawnOptions(agentId = "pm", projectId = "p1"), null)
        assertNotNull(architect); assertNotNull(pm)
        assertEquals(0, rig.runner.processes.size, "no run may exist: nothing was typed")

        val sent = rig.router.send(MailSendRequest(from = architect.id, to = "pm", body = "hello pm")) as MailResult.Sent

        assertEquals(MessageState.DELIVERED, rig.mailbox.message(sent.message.id)!!.state)
        assertTrue(rig.runner.processes.single().input.contains("hello pm"), "the message is the pm's first turn")
    }

    @Test
    fun the_bound_session_carries_all_four_fields_and_rests_idle() {
        val rig = Rig()
        val s = bindSessionOnSpawn(rig.manager, rig.agents, SpawnOptions(agentId = "pm", projectId = "p1"), null)!!
        assertEquals("pm", s.agentId)
        assertEquals("pm", s.role)
        assertEquals("p1", s.projectId)
        assertEquals(SessionState.IDLE, s.state)
        // projectId is what the roster and role mail filter on.
        val rows = roster("p1", projectAgents("p1", rig.manager.list(), rig.agents::get), rig.manager.list(), rig.mailbox)
        assertEquals("pm", rows.single().agentId)
        assertEquals(s.id, rows.single().sessionId)
    }

    @Test
    fun the_persona_rides_the_first_delivered_message() {
        val rig = Rig()
        val a = bindSessionOnSpawn(rig.manager, rig.agents, SpawnOptions(agentId = "architect"), null)!!
        rig.router.send(MailSendRequest(from = "human", to = "architect", body = "ping"))
        val input = rig.runner.processes.single().input.toString()
        assertTrue(input.startsWith("You are the architect."), input)
        assertEquals(1, rig.manager.get(a.id)!!.runCount)
    }

    @Test
    fun options_naming_no_profile_stay_anonymous() {
        val rig = Rig()
        assertNull(bindSessionOnSpawn(rig.manager, rig.agents, SpawnOptions(), null))
        assertTrue(rig.manager.list().isEmpty())
    }
}
