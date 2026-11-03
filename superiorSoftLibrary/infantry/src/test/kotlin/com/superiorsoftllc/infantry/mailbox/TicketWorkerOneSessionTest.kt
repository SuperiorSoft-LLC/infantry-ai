package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.persistence.AgentRepository
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.session.RunInFlightException
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * BASICS-KILL-1: one ticket, one session. After an app restart the owner saw a ticket worker come back as a SECOND
 * session with the same agent id on another harness (the agent profile's), running in the same worktree next to the
 * real worker. The cause was mail to the agent: BUG-S3's "the agent moved harness" rule compared the ticket session's
 * provider (the ticket's pin) with the profile's and cut a fresh profile-harness session. These tests pin: a ticket
 * session keeps its harness on mail; a start or resume next to a busy session of the same ticket / ticket agent is
 * refused; ticketless sessions of one agent (pod nodes) are not restricted.
 */
class TicketWorkerOneSessionTest {

    private class Repo : AgentRepository {
        val profiles = mutableMapOf<String?, AgentProfile>()
        override fun save(profile: AgentProfile, at: Long) { profiles[profile.id] = profile }
        override fun get(id: String): AgentProfile? = profiles[id]
        override fun all(): List<AgentProfile> = profiles.values.toList()
        override fun delete(id: String): Boolean = profiles.remove(id) != null
    }

    private class Harness(val manager: SessionManager, val mailbox: Mailbox, val router: MailRouter, val runner: ScriptedRunner, val binds: MutableList<String>)

    /** The agent `fe`'s profile names harness `other`; every session here runs on the manager's `fake` provider. */
    private fun harness(runner: ScriptedRunner): Harness {
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50, bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy())
        val mailbox = Mailbox(MemMailStore(), MailLimits())
        val repo = Repo().apply { save(AgentProfile(id = "fe", name = "Frontend", role = "frontend", provider = "other"), 0) }
        val binds = mutableListOf<String>()
        val router = MailRouter(
            manager = manager, mailbox = mailbox, agentProfiles = AgentProfiles(repo, manager),
            bindFreshSession = { agentId -> binds += agentId; manager.startNew("", agentId = agentId) },
        )
        manager.eventSink = { event -> router.onSessionEvent(event) }
        return Harness(manager, mailbox, router, runner, binds)
    }

    private fun Harness.sessionsOf(agentId: String): List<SessionRecord> = manager.list().filter { it.agentId == agentId }

    @Test
    fun mailToTheAgentOfAnAtRestTicketWorkerResumesThatWorkerOnItsOwnHarness() {
        val h = harness(ScriptedRunner(autoExit = true))
        val worker = h.manager.startNew("ticket brief", agentId = "fe", role = "frontend", ticketId = "t1")
        assertEquals("fake", worker.provider)

        val sent = h.router.send(MailSendRequest(from = "human", to = "fe", body = "status?")) as MailResult.Sent

        assertEquals(MessageState.DELIVERED, h.mailbox.message(sent.message.id)!!.state)
        assertEquals(emptyList(), h.binds, "no fresh session on the profile's harness for a ticket worker")
        assertEquals(listOf(worker.id), h.sessionsOf("fe").map { it.id }, "still exactly one session for the agent")
        assertEquals("fake", h.manager.get(worker.id)!!.provider, "the resumed worker keeps its harness")
        assertEquals(2, h.manager.get(worker.id)!!.runCount, "the mail ran as the worker's next turn")
    }

    @Test
    fun aTicketlessSessionStillFollowsTheAgentsSwappedHarness() {
        val h = harness(ScriptedRunner(autoExit = true))
        h.manager.startNew("chat", agentId = "fe", role = "frontend")

        h.router.send(MailSendRequest(from = "human", to = "fe", body = "hello"))

        assertEquals(listOf("fe"), h.binds, "BUG-S3's swap rule is unchanged for a ticketless session")
    }

    @Test
    fun aSecondStartForABusyTicketOrItsAgentIsRefusedWithTheRunningSessionNamed() {
        val h = harness(ScriptedRunner(autoExit = false))
        val worker = h.manager.startNew("ticket brief", agentId = "fe", role = "frontend", ticketId = "t1")

        val sameTicket = assertFailsWith<IllegalArgumentException> { h.manager.startNew("again", agentId = "be", ticketId = "t1") }
        assertTrue(sameTicket.message!!.contains(worker.id) && sameTicket.message!!.contains("ticket t1"), sameTicket.message)

        val sameAgent = assertFailsWith<IllegalArgumentException> { h.manager.startNew("hello", agentId = "fe") }
        assertTrue(sameAgent.message!!.contains(worker.id) && sameAgent.message!!.contains("agent fe"), sameAgent.message)
        assertEquals(1, h.manager.list().size, "a refused start creates nothing")

        // Pod nodes: ticketless sessions of one agent may run side by side.
        h.manager.startNew("node a", agentId = "pod-agent")
        h.manager.startNew("node b", agentId = "pod-agent")
        assertEquals(2, h.sessionsOf("pod-agent").count { it.live })
    }

    @Test
    fun anOlderSessionOfTheAgentIsNotWokenNextToItsBusyTicketWorker() {
        val runner = ScriptedRunner(autoExit = false)
        val h = harness(runner)
        val old = h.manager.startNew("earlier chat", agentId = "fe", role = "frontend")
        runner.current!!.exit(0)
        val worker = h.manager.startNew("ticket brief", agentId = "fe", role = "frontend", ticketId = "t1")

        assertFailsWith<RunInFlightException> { h.manager.resume(old.id, "direct resume") }

        val sent = h.router.send(MailSendRequest(from = "human", to = old.id, body = "for the old session")) as MailResult.Sent
        assertEquals(MessageState.QUEUED, h.mailbox.message(sent.message.id)!!.state, "it waits for the worker's turn to end")
        assertEquals(1, h.sessionsOf("fe").count { it.live }, "never two live sessions of the agent")

        runner.current!!.exit(0) // the worker comes to rest: mail for its sibling session is drained with it
        assertEquals(MessageState.DELIVERED, h.mailbox.message(sent.message.id)!!.state)
        assertTrue(!h.manager.get(worker.id)!!.live)
    }
}
