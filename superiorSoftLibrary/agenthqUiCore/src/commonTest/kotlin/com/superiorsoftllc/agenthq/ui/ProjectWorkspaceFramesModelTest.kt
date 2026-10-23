package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.Project
import com.superiorsoftllc.agenthq.ui.protocol.Workspace
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UI-WS-1: the UI fills AppState.projects / AppState.workspaces from the daemon's list frames
 * (contract 1.18.0), and the first project becomes active so only its workspaces are visible.
 */
class ProjectWorkspaceFramesModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun feed(vararg e: Envelope, from: AppState = AppState()) = e.fold(from) { s, f -> reduce(s, f, 100) }

    private fun projects(vararg p: Project) =
        Envelope(Envelope.PROJECTS, json.encodeToString(ListSerializer(Project.serializer()), p.toList()))

    private fun workspaces(vararg w: Workspace) =
        Envelope(Envelope.WORKSPACES, json.encodeToString(ListSerializer(Workspace.serializer()), w.toList()))

    @Test fun reducerLoadsProjectsAndWorkspaces() {
        val s = feed(
            projects(Project("A", "Alpha"), Project("B", "Beta")),
            workspaces(
                Workspace("A1", "A", "one"),
                Workspace("A2", "A", "two"),
                Workspace("B1", "B", "one"),
            ),
        )

        assertEquals(listOf("A", "B"), s.projects.map { it.id }, "projects come from the projects frame, in order")
        assertEquals(listOf("A1", "A2", "B1"), s.workspaces.map { it.id }, "workspaces come from the workspaces frame")
        assertEquals("A", s.activeProjectId, "the first project is active when none was chosen")
        assertEquals(listOf("A1", "A2"), s.visibleWorkspaces.map { it.id }, "only the active project's workspaces are visible")
    }

    @Test fun savedProjectAndWorkspaceFramesAreUpsertedById() {
        fun project(p: Project) = Envelope(Envelope.PROJECT, json.encodeToString(Project.serializer(), p))
        fun workspace(w: Workspace) = Envelope(Envelope.WORKSPACE, json.encodeToString(Workspace.serializer(), w))

        var s = feed(projects(Project("A", "Alpha")), workspaces(Workspace("A1", "A", "one")))
        s = feed(project(Project("B", "Beta")), workspace(Workspace("A2", "A", "two")), from = s)
        assertEquals(listOf("A", "B"), s.projects.map { it.id }, "a created project is appended")
        assertEquals(listOf("A1", "A2"), s.workspaces.map { it.id }, "a created workspace is appended")
        assertEquals("A", s.activeProjectId, "creating a project does not steal the selection")

        s = feed(project(Project("A", "Alpha renamed")), workspace(Workspace("A1", "A", "one renamed")), from = s)
        assertEquals(listOf("Alpha renamed", "Beta"), s.projects.map { it.name }, "a known project id is replaced in place")
        assertEquals(listOf("one renamed", "two"), s.workspaces.map { it.name }, "a known workspace id is replaced in place")

        val first = feed(project(Project("N", "New")))
        assertEquals("N", first.activeProjectId, "the first project saved on an empty shell becomes the selection")
    }

    @Test fun emptyListFramesClearTheLists() {
        var s = feed(
            projects(Project("A", "Alpha"), Project("B", "Beta")),
            workspaces(
                Workspace("A1", "A", "one"),
                Workspace("A2", "A", "two"),
                Workspace("B1", "B", "one"),
            ),
        )
        assertEquals(2, s.projects.size, "precondition: two projects loaded")
        assertEquals(3, s.workspaces.size, "precondition: three workspaces loaded")

        s = feed(projects(), from = s)
        assertEquals(0, s.projects.size, "empty project_list frame clears the project list")
        assertEquals(null, s.activeProjectId, "active project id is cleared when no projects remain")

        s = feed(workspaces(), from = s)
        assertEquals(0, s.workspaces.size, "empty workspace_list frame clears the workspace list")
    }
}
