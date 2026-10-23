package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.mailbox.Mailbox
import com.superiorsoftllc.agenthq.mailbox.MailSendRequest
import com.superiorsoftllc.agenthq.mailbox.MemMailStore
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ROSTER-1: the pure "who's here" computation behind the roster.
 *
 * One row per agent profile of the given project, carrying the live status of its session
 * (BUSY / IDLE / OFFLINE) and how much mail waits for it. The status rules are MailRouter's own
 * (extracted shared helper), so "can a message wake this agent" and "is this agent busy" can never
 * drift apart.
 */
class RosterTest {

    private fun session(id: String, agentId: String, state: SessionState): SessionRecord = SessionRecord(
        id = id,
        provider = "fake",
        workingDir = ".",
        spawnedAt = 1L,
        lastHeartbeat = 1L,
        state = state,
        providerSessionId = "prov-$id",
        agentId = agentId,
    )

    @Test
    fun `roster lists exactly this project's profiles, one busy and one idle`() {
        val profiles = listOf(
            ProjectAgent("A", "a1", "Alice", "backend"),
            ProjectAgent("A", "a2", "Bob", "qa"),
            ProjectAgent("B", "b1", "Carol", "pm"),
        )
        val sessions = listOf(
            session("s-a1", "a1", SessionState.BUSY),
            session("s-a2", "a2", SessionState.IDLE),
        )

        val rows = roster("A", profiles, sessions, Mailbox(MemMailStore()), mapOf("s-a1" to "Fix the widget"))

        assertEquals(listOf("a1", "a2"), rows.map { it.agentId }, "roster(\"A\") is exactly project A's two profiles")
        assertEquals(RosterStatus.BUSY, rows.first { it.agentId == "a1" }.status, "a run in flight is BUSY")
        assertEquals(RosterStatus.IDLE, rows.first { it.agentId == "a2" }.status, "at rest and resumable is IDLE")
        assertEquals("s-a1", rows.first { it.agentId == "a1" }.sessionId, "the row names the agent's session")
        assertEquals("Fix the widget", rows.first { it.agentId == "a1" }.title, "the row carries the work's title")
    }

    @Test
    fun `a profile with no session is OFFLINE`() {
        val rows = roster("A", listOf(ProjectAgent("A", "solo", "Solo", "pm")), emptyList(), Mailbox(MemMailStore()))

        assertEquals(1, rows.size)
        assertEquals(RosterStatus.OFFLINE, rows.single().status, "no session means the agent is OFFLINE")
        assertEquals(null, rows.single().sessionId)
    }

    @Test
    fun `two queued messages to an agent give pending 2`() {
        val mailbox = Mailbox(MemMailStore())
        mailbox.send(MailSendRequest(from = "human", to = "a1", body = "first"))
        mailbox.send(MailSendRequest(from = "human", to = "a1", body = "second"))

        val rows = roster(
            "A",
            listOf(ProjectAgent("A", "a1", "Alice", "backend")),
            listOf(session("s-a1", "a1", SessionState.IDLE)),
            mailbox,
        )

        assertEquals(2, rows.single().pending, "mail waiting for the agent is counted")
    }

    // ----- projectAgents: the unified binding rule --------------------------------------------------

    private fun agentLookup(profiles: Map<String, AgentProfile>): (String) -> AgentProfile? = { profiles[it] }

    @Test
    fun `projectAgents includes an agent with a session in the project but no ticket assignment`() {
        val profiles = mapOf("a1" to AgentProfile(id = "a1", name = "Alice", role = "backend"))
        val sessions = listOf(session("s-a1", "a1", SessionState.IDLE).copy(projectId = "A"))

        val result = projectAgents("A", sessions, agentLookup(profiles))

        assertEquals(listOf(ProjectAgent("A", "a1", "Alice", "backend")), result,
            "an agent with a live session in the project is listed, named from its profile")
    }

    @Test
    fun `BUG-ROLE-1 a role-only New Agent session is listed and a profile with no session is not`() {
        // pm-profile is a "New Profile" pm bound to project A, with no session anywhere.
        val profiles = mapOf("pm-profile" to AgentProfile(id = "pm-profile", name = "Profile PM", role = "pm", projectId = "A"))
        // s-pm is a "New Agent" pm opened in A: a role, no profile.
        val roleOnly = session("s-pm", "x", SessionState.IDLE).copy(agentId = null, role = "pm", projectId = "A")

        val result = projectAgents("A", listOf(roleOnly), agentLookup(profiles))

        assertEquals(listOf(ProjectAgent("A", "s-pm", "pm", "pm")), result,
            "the New Agent pm is listed under its session id; the profile-only pm is not")
        val rows = roster("A", result, listOf(roleOnly), Mailbox(MemMailStore()))
        assertEquals(RosterStatus.IDLE, rows.single().status, "the role-only row is joined to its own session")
        assertEquals("s-pm", rows.single().sessionId)
    }

    @Test
    fun `projectAgents leaves out a session nothing can reach`() {
        val dead = session("s-dead", "a1", SessionState.EXITED).copy(projectId = "A", providerSessionId = null, runCount = 1)

        assertTrue(projectAgents("A", listOf(dead), { null }).isEmpty(), "a session that cannot come back is not in session")
    }

    @Test
    fun `projectAgents deduplicates an agent with two sessions`() {
        val profiles = mapOf("a1" to AgentProfile(id = "a1", name = "Alice", role = "backend"))
        val sessions = listOf(
            session("s-a1", "a1", SessionState.IDLE).copy(projectId = "A"),
            session("s-a1b", "a1", SessionState.BUSY).copy(projectId = "A", spawnedAt = 2L),
        )

        val result = projectAgents("A", sessions, agentLookup(profiles))

        assertEquals(listOf("a1"), result.map { it.agentId }, "agent appears once")
    }

    @Test
    fun `projectAgents excludes agents from other projects`() {
        val profiles = mapOf(
            "a1" to AgentProfile(id = "a1", name = "Alice", role = "backend"),
            "b1" to AgentProfile(id = "b1", name = "Bob", role = "qa"),
        )
        val sessions = listOf(
            session("s-a1", "a1", SessionState.IDLE).copy(projectId = "A"),
            session("s-b1", "b1", SessionState.IDLE).copy(projectId = "B"),
        )

        val result = projectAgents("A", sessions, agentLookup(profiles))

        assertEquals(listOf("a1"), result.map { it.agentId }, "only project A's agent is listed")
    }

}
