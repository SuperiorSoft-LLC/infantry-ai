// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ProjectView
import com.superiorsoftllc.infantry.ui.model.selectProject
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Switching project must not leave the previous project's agent open in the chat pane. */
class ProjectSwitchSelectionTest {
    private fun agent(id: String, project: String?) = AgentView(
        session = SessionRecord(id = id, provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "p", projectId = project),
        role = "pm",
    )

    private val state = AppState(
        projects = listOf(ProjectView("A", "Alpha"), ProjectView("B", "Beta")),
        agents = listOf(agent("a1", "A"), agent("b1", "B"), agent("g", null)).associateBy { it.id },
        selectedId = "a1", activeProjectId = "A",
    )

    @Test fun otherProjectsAgentIsDeselected() {
        assertNull(state.selectProject("B").selected)
    }

    @Test fun sameProjectAndUnscopedAgentsStaySelected() {
        assertEquals("a1", state.selectProject("A").selectedId)
        assertEquals("g", state.copy(selectedId = "g").selectProject("B").selectedId)
    }
}
