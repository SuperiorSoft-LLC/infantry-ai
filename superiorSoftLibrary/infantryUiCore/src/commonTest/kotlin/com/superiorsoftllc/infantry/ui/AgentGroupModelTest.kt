package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AgentGroup
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.protocol.NodeRecord
import com.superiorsoftllc.infantry.ui.protocol.PodRecord
import com.superiorsoftllc.infantry.ui.protocol.PodStatus
import com.superiorsoftllc.infantry.ui.protocol.RunRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals

/** M2-4: the agents list is grouped by status (NEEDS YOU, TO REVIEW, WORKING, INACTIVE), not by role. */
class AgentGroupModelTest {
    private fun agent(id: String, state: SessionState) = AgentView(
        session = SessionRecord(id = id, provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = state, providerSessionId = "p"),
        role = "pm",
    )

    private val waiting = agent("w", SessionState.WAITING_INPUT)
    private val busy = agent("b", SessionState.BUSY)
    private val starting = agent("s", SessionState.STARTING)
    private val idle = agent("i", SessionState.IDLE)
    private val reviewed = agent("r", SessionState.IDLE)

    /** A pod whose one node's last run, by [sessionId], is PENDING review. */
    private fun pendingReview(sessionId: String) = PodStatus(
        pod = PodRecord(id = "pod", goal = "g", state = "RUNNING", createdAt = 1),
        nodes = listOf(NodeRecord(id = "n", podId = "pod", name = "n", specIndex = 0, prompt = "p", state = "DONE", reviewState = "PENDING", createdAt = 1)),
        runs = mapOf("n" to listOf(RunRecord(id = "run", sessionId = sessionId, provider = "stub", workingDir = "/w", prompt = "p", state = SessionState.EXITED, startedAt = 1, endedAt = 2))),
    )

    private val all = listOf(idle, busy, waiting, reviewed, starting)
    private val state = AppState(agents = all.associateBy { it.id }, pods = mapOf("pod" to pendingReview("r")))

    @Test
    fun eachAgentLandsInItsStatusGroup() {
        assertEquals(AgentGroup.NEEDS_YOU, state.agentGroup(waiting))
        assertEquals(AgentGroup.TO_REVIEW, state.agentGroup(reviewed))
        assertEquals(AgentGroup.WORKING, state.agentGroup(busy))
        assertEquals(AgentGroup.WORKING, state.agentGroup(starting))
        assertEquals(AgentGroup.INACTIVE, state.agentGroup(idle))
    }

    @Test
    fun groupsComeInDisplayOrderAndKeepTheListOrderInside() {
        val groups = state.groupedAgents(all)
        assertEquals(listOf(AgentGroup.NEEDS_YOU, AgentGroup.TO_REVIEW, AgentGroup.WORKING, AgentGroup.INACTIVE), groups.map { it.first })
        assertEquals(listOf("b", "s"), groups.first { it.first == AgentGroup.WORKING }.second.map { it.id })
    }

    @Test
    fun emptyGroupsAreHidden() {
        val groups = state.groupedAgents(listOf(idle, busy))
        assertEquals(listOf(AgentGroup.WORKING, AgentGroup.INACTIVE), groups.map { it.first })
    }

    @Test
    fun headerLabelsAreOurOwnWording() {
        assertEquals(listOf("Waiting on you", "Ready to check", "Running", "Quiet"), AgentGroup.entries.map { it.label })
    }
}
