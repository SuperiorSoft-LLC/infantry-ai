package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.protocol.Role
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** M2-3: an agent is titled by its role's display name; the id is the fallback and the secondary text. */
class AgentTitleModelTest {
    private fun agent(role: String?, branch: String? = null) = AgentView(
        session = SessionRecord(id = "19451abf0c2e", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "p", worktreeBranch = branch),
        role = role,
    )

    private val state = AppState(roles = listOf(Role("pm", "Project Manager", "#8B5CF6")))

    @Test
    fun aKnownRoleIsTitledByItsDisplayNameAndKeepsTheIdAsSecondary() {
        val a = agent("pm", branch = "feature-x")
        assertEquals("Project Manager", state.agentTitle(a))
        assertEquals("19451abf", state.agentSecondaryId(a))
    }

    @Test
    fun aRoleTheCatalogueDoesNotKnowShowsItsIdRatherThanTheSessionId() {
        assertEquals("custom-bot", state.agentTitle(agent("custom-bot")))
    }

    @Test
    fun noRoleFallsBackToTheIdWithNoDuplicateSecondary() {
        val a = agent(null)
        assertEquals("19451abf", state.agentTitle(a))
        assertNull(state.agentSecondaryId(a))
        assertEquals("19451abf", state.agentTitle(agent("  ")))
    }

    @Test
    fun noRoleWithABranchKeepsTheBranchTitle() {
        val a = agent(null, branch = "feature-x")
        assertEquals("feature-x", state.agentTitle(a))
        assertEquals("19451abf", state.agentSecondaryId(a))
    }
}
