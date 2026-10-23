package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.daemon.bindSessionOnSpawn
import com.superiorsoftllc.agenthq.daemon.projectAgents
import com.superiorsoftllc.agenthq.daemon.roster
import com.superiorsoftllc.agenthq.persistence.AgentRepository
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.SpawnOptions
import com.superiorsoftllc.agenthq.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CP-3: an agent belongs to exactly one project. Two projects, an agent in A: it is absent from B's
 * roster and list, and unreachable from B's mail, however it is addressed.
 */
class ProjectScopedAgentsTest {
    private class Repo : AgentRepository {
        private val m = linkedMapOf<String?, AgentProfile>()
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
            agents.save(AgentProfile(id = "a-dev", name = "A Dev", role = "backend", projectId = "A"), requireProject = true)
            agents.save(AgentProfile(id = "a-pm", name = "A PM", role = "pm", projectId = "A"), requireProject = true)
            agents.save(AgentProfile(id = "b-dev", name = "B Dev", role = "backend", projectId = "B"), requireProject = true)
        }
    }

    @Test
    fun agent_in_A_is_absent_from_Bs_roster_and_list() {
        val rig = Rig()
        for (id in listOf("a-dev", "a-pm", "b-dev")) bindSessionOnSpawn(rig.manager, rig.agents, SpawnOptions(agentId = id), null)!!
        val sessions = rig.manager.list()
        val rosterB = roster("B", projectAgents("B", sessions, rig.agents::get), sessions, rig.mailbox)
        assertEquals(listOf("b-dev"), rosterB.map { it.agentId })
        val rosterA = roster("A", projectAgents("A", sessions, rig.agents::get), sessions, rig.mailbox)
        assertEquals(setOf("a-dev", "a-pm"), rosterA.map { it.agentId }.toSet())
        assertEquals(listOf("b-dev"), rig.agents.list("B").map { it.id })
        assertTrue(rig.agents.list("").isEmpty())
    }

    @Test
    fun agent_in_A_is_unreachable_from_B_by_profile_id_session_id_and_role() {
        val rig = Rig()
        val aDev = bindSessionOnSpawn(rig.manager, rig.agents, SpawnOptions(agentId = "a-dev"), null)!!
        val bDev = bindSessionOnSpawn(rig.manager, rig.agents, SpawnOptions(agentId = "b-dev"), null)!!
        assertEquals("A", aDev.projectId)
        assertEquals("B", bDev.projectId)

        for (to in listOf("a-dev", aDev.id)) {
            val refused = rig.router.send(MailSendRequest(from = bDev.id, to = to, body = "cross")) as MailResult.Refused
            assertTrue("project B" in refused.reason && "project A" in refused.reason, "names both projects: ${refused.reason}")
        }
        // A role resolves only inside the sender's project; B has no pm, so it is an error, never A's pm.
        val sent = rig.router.send(MailSendRequest(from = bDev.id, to = "pm", body = "cross")) as MailResult.Sent
        assertEquals(MessageState.UNDELIVERABLE, rig.mailbox.message(sent.message.id)!!.state)
        assertTrue("no pm agent in this project" in rig.mailbox.message(sent.message.id)!!.failureReason.orEmpty())
        assertEquals(0, rig.runner.processes.size, "nothing may have been delivered to A")
    }

    @Test
    fun allowCrossProject_overrides_the_guard() {
        val rig = Rig()
        val aDev = bindSessionOnSpawn(rig.manager, rig.agents, SpawnOptions(agentId = "a-dev"), null)!!
        val bDev = bindSessionOnSpawn(rig.manager, rig.agents, SpawnOptions(agentId = "b-dev"), null)!!
        val sent = rig.router.send(MailSendRequest(from = bDev.id, to = aDev.id, body = "ok", allowCrossProject = true)) as MailResult.Sent
        assertTrue(rig.mailbox.message(sent.message.id)!!.state != MessageState.UNDELIVERABLE)
    }

    @Test
    fun mail_inside_a_project_still_flows() {
        val rig = Rig()
        val dev = bindSessionOnSpawn(rig.manager, rig.agents, SpawnOptions(agentId = "a-dev"), null)!!
        bindSessionOnSpawn(rig.manager, rig.agents, SpawnOptions(agentId = "a-pm"), null)!!
        val sent = rig.router.send(MailSendRequest(from = dev.id, to = "a-pm", body = "hi")) as MailResult.Sent
        assertEquals(MessageState.DELIVERED, rig.mailbox.message(sent.message.id)!!.state)
    }

    @Test
    fun a_spawn_takes_the_profiles_project_and_refuses_another() {
        val rig = Rig()
        val s = bindSessionOnSpawn(rig.manager, rig.agents, SpawnOptions(agentId = "a-dev"), null)
        assertNotNull(s)
        assertEquals("A", s.projectId)
        assertFailsWith<IllegalArgumentException> {
            bindSessionOnSpawn(rig.manager, rig.agents, SpawnOptions(agentId = "a-dev", projectId = "B"), null)
        }
    }

    @Test
    fun a_ticket_or_session_in_B_cannot_pull_an_A_agent_into_Bs_roster() {
        val rig = Rig()
        val s = bindSessionOnSpawn(rig.manager, rig.agents, SpawnOptions(agentId = "a-dev"), null)!!
        val forged = s.copy(projectId = "B")
        val rows = projectAgents("B", listOf(forged), rig.agents::get)
        assertTrue(rows.isEmpty(), "a session in B cannot pull A's agent into B: $rows")
    }

    @Test
    fun saving_binds_once_and_never_moves() {
        val rig = Rig()
        assertFailsWith<IllegalArgumentException> { rig.agents.save(AgentProfile(name = "Orphan", role = "qa"), requireProject = true) }
        assertFailsWith<IllegalArgumentException> { rig.agents.save(AgentProfile(id = "a-dev", name = "A Dev", role = "backend", projectId = "B")) }
        val kept = rig.agents.save(AgentProfile(id = "a-dev", name = "A Dev 2", role = "backend"), requireProject = true)
        assertEquals("A", kept.projectId)
        val created = rig.agents.save(AgentProfile(name = "New", role = "qa", projectId = " B "), requireProject = true)
        assertEquals("B", created.projectId)
    }
}
