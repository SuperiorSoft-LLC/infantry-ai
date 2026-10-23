package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.ProjectView
import com.superiorsoftllc.agenthq.ui.model.WorkspaceView
import com.superiorsoftllc.agenthq.ui.model.selectProject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PW-4: switching project scopes the shell. The model holds every project the daemon listed and the
 * workspaces it knows across them; selecting a project is a UI-side choice (like selecting a session)
 * and only that project's workspaces stay visible.
 */
class ProjectSwitchModelTest {
    private val a = ProjectView("A", "Alpha")
    private val b = ProjectView("B", "Beta")
    private val a1 = WorkspaceView("A1", "A", "one")
    private val a2 = WorkspaceView("A2", "A", "two")
    private val b1 = WorkspaceView("B1", "B", "one")

    private fun state() = AppState(projects = listOf(a, b), workspaces = listOf(a1, a2, b1))

    @Test fun switchingProjectFiltersWorkspaces() {
        val s = state()

        val onB = s.selectProject("B")
        assertEquals(b, onB.activeProject, "selecting B makes B the active project")
        assertEquals(listOf(b1), onB.visibleWorkspaces, "only B's workspaces stay visible")

        val backToA = onB.selectProject("A")
        assertEquals(a, backToA.activeProject, "selecting A makes A active again")
        assertEquals(listOf(a1, a2), backToA.visibleWorkspaces, "A's workspaces come back, in order")

        val unknown = onB.selectProject("ghost")
        assertEquals(b, unknown.activeProject, "an unknown project leaves the active project unchanged")
        assertEquals(listOf(b1), unknown.visibleWorkspaces, "and its workspaces unchanged too")
    }
}
