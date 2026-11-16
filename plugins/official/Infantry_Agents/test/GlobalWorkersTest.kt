// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.agents

import com.superiorsoftllc.infantry.plugins.official.InfantryAgents
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ProjectView
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WORKERS-GLOBAL-1: the all-projects Working view: lanes by project, the flag off by default, the chat by session id. */
class GlobalWorkersTest {
    private fun agent(id: String, project: String?, state: SessionState, at: Long = 0) =
        AgentView(SessionRecord(id, "claude", "/w", spawnedAt = at, lastHeartbeat = 0, state = state, projectId = project), role = "r")

    private fun stateOf(vararg agents: AgentView, active: String? = "p-b") = AppState(
        agents = agents.associateBy { it.id },
        projects = listOf(ProjectView("p-a", "Alpha"), ProjectView("p-b", "Beta"), ProjectView("p-c", "Gamma")),
        activeProjectId = active,
    )

    @Test
    fun `lanes hold exactly the working sessions the counter counts, one lane per project`() {
        val s = stateOf(
            agent("a1", "p-a", SessionState.BUSY, at = 1), agent("a2", "p-a", SessionState.STARTING, at = 2),
            agent("b1", "p-b", SessionState.BUSY), agent("c1", "p-c", SessionState.IDLE),
            agent("x1", null, SessionState.BUSY), agent("u1", "p-unknown", SessionState.BUSY),
        )
        val lanes = workerLanes(s)
        assertEquals(s.workingCount, lanes.sumOf { it.agents.size }, "the lanes and the Working counter agree")
        // Active project first, then by name, unknown project shows its id, no project last; Gamma has no worker so no lane.
        assertEquals(listOf("Beta", "Alpha", "p-unknown", NO_PROJECT_LANE), lanes.map { it.name })
        assertEquals(listOf("a2", "a1"), lanes.first { it.projectId == "p-a" }.agents.map { it.id }, "newest first inside a lane")
    }

    @Test
    fun `no working agent means no lanes (the modal shows its empty state)`() {
        assertTrue(workerLanes(stateOf(agent("c1", "p-c", SessionState.IDLE))).isEmpty())
    }

    @Test
    fun `the flag is declared off by default, on the device, in the manifest and module json alike`() {
        val decl = InfantryAgents.manifest.config.single { it.node == GLOBAL_WORKERS_NODE }
        val field = decl.fields.single { it.key == GLOBAL_WORKERS_KEY }
        assertEquals(JsonPrimitive(false), field.default)
        assertEquals("checkbox", field.type)
        assertEquals("device", field.scope)
        val json = File(System.getProperty("infantryAgents.moduleJson")).readText()
        assertTrue(json.contains("\"key\": \"$GLOBAL_WORKERS_KEY\"") && json.contains("\"node\": \"$GLOBAL_WORKERS_NODE\""))
    }

    @Test
    fun `flag off keeps the plain counter, and the chat modal is the shared panel bound by session id`() {
        val src = File(System.getProperty("infantryAgents.moduleJson")).parentFile.resolve("src")
        val sidebar = src.resolve("Sidebar.kt").readText()
        assertTrue(sidebar.contains("onShowWorkers: (() -> Unit)? = null"), "Sidebar defaults to no button")
        assertTrue(sidebar.contains("if (onShowWorkers == null) Counter(\"Working\", state.workingCount, Palette.Ok)"), "flag off draws the old counter")
        assertTrue(sidebar.contains("onShowWorkers = if (globalWorkers) ({ workersOpen = true }) else null"), "only the flag turns the button on")
        val host = src.resolve("GlobalWorkers.kt").readText()
        assertTrue(host.contains("panel(agent.id, controller, m, chat, false, GLOBAL_WORKERS_TRANSCRIPT_TAG)"), "the chat is Infantry_Chat's ChatPanel, by session id")
        assertTrue(host.contains("AgentRow(a, state"), "the lanes reuse the left panel's card")
    }
}
