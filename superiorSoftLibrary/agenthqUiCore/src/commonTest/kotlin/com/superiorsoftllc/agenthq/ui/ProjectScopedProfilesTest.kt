package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.protocol.AgentProfile
import kotlin.test.Test
import kotlin.test.assertEquals

/** CP-3: switching project switches the agents listed; an unbound or foreign agent is never shown. */
class ProjectScopedProfilesTest {
    private val profiles = listOf(
        AgentProfile(id = "a1", name = "A1", role = "backend", projectId = "A"),
        AgentProfile(id = "b1", name = "B1", role = "backend", projectId = "B"),
        AgentProfile(id = "x", name = "Unbound", role = "qa"),
    )

    @Test
    fun only_the_active_projects_agents_are_visible() {
        val a = AppState(profiles = profiles, activeProjectId = "A")
        assertEquals(listOf("a1"), a.visibleProfiles.map { it.id })
        assertEquals(listOf("b1"), a.copy(activeProjectId = "B").visibleProfiles.map { it.id })
        assertEquals(listOf("a1", "b1", "x"), a.copy(activeProjectId = null).visibleProfiles.map { it.id }, "no project selected = nothing to scope by")
        assertEquals(emptyList(), a.copy(activeProjectId = "B").profilesFor("qa").map { it.id })
    }
}
