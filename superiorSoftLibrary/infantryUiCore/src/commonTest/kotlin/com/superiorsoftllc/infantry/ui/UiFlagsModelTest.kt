package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AgentScope
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ProjectView
import com.superiorsoftllc.infantry.ui.model.UiFlags
import com.superiorsoftllc.infantry.ui.model.visibleAgents
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CONFIG flags surface, first flag (agent scope). Each behaviour test flips the flag and asserts that the
 * list it governs CHANGES, then flips it back, so a flag the code ignores cannot pass.
 *
 * The fleet: "a" is bound to Alpha, "b" to Beta, "free" has no project (a spawn with no project selected).
 */
class UiFlagsModelTest {
    private fun agent(id: String, projectId: String?, spawnedAt: Long) = AgentView(
        session = SessionRecord(
            id = id, provider = "stub", workingDir = "/w", spawnedAt = spawnedAt, lastHeartbeat = 1,
            state = SessionState.IDLE, projectId = projectId,
        ),
    )

    private val fleet = listOf(agent("a", "A", 3), agent("b", "B", 2), agent("free", null, 1))

    private fun state(activeProjectId: String? = "A") = AppState(
        projects = listOf(ProjectView("A", "Alpha"), ProjectView("B", "Beta")),
        activeProjectId = activeProjectId,
        agents = fleet.associateBy { it.session.id },
    )

    private fun shown(flags: UiFlags, s: AppState = state()): List<String> =
        visibleAgents(s, "", null, agentScope = flags.agentScope).map { it.session.id }

    @Test fun `the default is Project Scoped Agents, so another project's agents are hidden without touching Config`() {
        // BUG-M2-15: scoping is the default; Global stays available in Config.
        assertEquals(AgentScope.PROJECT, UiFlags().agentScope, "default flag value is Project Scoped Agents")
        assertEquals(listOf("a", "free"), shown(UiFlags()), "default list under Alpha: Alpha's agent and the unbound one")
        assertEquals(listOf("b", "free"), shown(UiFlags(), state("B")), "default list under Beta: Beta's agent and the unbound one")
    }

    @Test fun `Global Agents lists every project's agents whatever project is selected`() {
        assertEquals(listOf("a", "b", "free"), shown(UiFlags(AgentScope.GLOBAL), state("A")))
        assertEquals(listOf("a", "b", "free"), shown(UiFlags(AgentScope.GLOBAL), state("B")))
    }

    @Test fun `Project Scoped Agents hides another project's agents, and flipping back restores them`() {
        val scoped = UiFlags(AgentScope.PROJECT)
        assertEquals(listOf("a", "free"), shown(scoped), "under Alpha, Beta's agent is hidden and the unbound one stays")
        assertEquals(listOf("a", "b", "free"), shown(UiFlags(AgentScope.GLOBAL)), "flipped back to Global, Beta's agent is listed again")
    }

    @Test fun `Project Scoped Agents follows the selected project`() {
        assertEquals(listOf("b", "free"), shown(UiFlags(AgentScope.PROJECT), state("B")), "under Beta, Alpha's agent is hidden instead")
    }

    @Test fun `with no project selected, Project Scoped Agents lists every agent`() {
        assertEquals(listOf("a", "b", "free"), shown(UiFlags(AgentScope.PROJECT), state(null)), "no project to scope to, so nothing is hidden")
    }

    @Test fun `the dropdown offers exactly the two options the owner named, in order`() {
        assertEquals(listOf("Global Agents", "Project Scoped Agents"), AgentScope.entries.map { it.label })
    }

    @Test fun `the daemon's projectId reaches the UI session model`() {
        // Guard: the scope rule above is only as good as the projectId the UI keeps from the session frame.
        val raw = """{"id":"a","provider":"stub","workingDir":"/w","spawnedAt":3,"lastHeartbeat":1,"state":"IDLE","projectId":"A","live":false}"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(SessionRecord.serializer(), raw)
        assertEquals("A", decoded.projectId, "a session frame's projectId must survive decoding")
    }
}
