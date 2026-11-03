package com.superiorsoftllc.infantry.mvp

import com.superiorsoftllc.infantry.protocol.ProjectSave
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * PW-1: a workspace is created inside a project and listed under it.
 * PW-2: a workspace is refused when its project does not exist, and nothing is written.
 */
class WorkspaceStoreTest {

    @Test
    fun workspaceSaveCreatesWorkspaceInProject() {
        val projects = ProjectStore(idFactory = { "project-1" })
        val project = projects.save(ProjectSave(name = "Social Machine"))
        val workspaces = WorkspaceStore(projects = projects, idFactory = { "workspace-1" })

        val created = workspaces.save(projectId = project.id, name = "main")

        assertEquals("workspace-1", created.id, "the store mints the workspace id")
        assertEquals(project.id, created.projectId, "the workspace belongs to the project it was saved in")
        assertEquals("main", created.name)
        assertEquals(null, created.path, "path is optional and defaults to null")
        assertEquals(listOf(created), workspaces.list(project.id), "the project lists exactly that workspace")
    }

    @Test
    fun workspaceSaveRefusesUnknownProject() {
        val projects = ProjectStore(idFactory = { "project-1" })
        projects.save(ProjectSave(name = "Social Machine"))
        val workspaces = WorkspaceStore(projects = projects, idFactory = { "workspace-1" })

        val refusal = assertFailsWith<IllegalArgumentException>("saving into a project that does not exist must be refused") {
            workspaces.save(projectId = "no-such", name = "x")
        }

        assertTrue(
            refusal.message.orEmpty().contains("no-such"),
            "the refusal names the missing project, got: ${refusal.message}",
        )
        assertEquals(emptyList(), workspaces.list("no-such"), "a refused save writes no workspace")
    }

    @Test
    fun workspaceSaveTrimsProjectId() {
        val projects = ProjectStore(idFactory = { "project-1" })
        projects.save(ProjectSave(name = "Social Machine"))
        val workspaces = WorkspaceStore(projects = projects, idFactory = { "workspace-1" })

        val created = workspaces.save(projectId = "  project-1  ", name = "main")

        assertEquals("project-1", created.projectId, "the stored workspace carries the trimmed project id")
    }

    @Test
    fun workspaceSaveRefusesBlankName() {
        val projects = ProjectStore(idFactory = { "project-1" })
        val project = projects.save(ProjectSave(name = "Social Machine"))
        val workspaces = WorkspaceStore(projects = projects, idFactory = { "workspace-1" })

        val refusal = assertFailsWith<IllegalArgumentException>("saving with a blank name must be refused") {
            workspaces.save(projectId = project.id, name = "   ")
        }

        assertTrue(
            refusal.message.orEmpty().contains("name is blank"),
            "the refusal names the blank name issue, got: ${refusal.message}",
        )
        assertEquals(emptyList(), workspaces.list(project.id), "a refused save writes no workspace")
    }

    @Test
    fun workspaceSaveRefusesOverlongOrControlCharName() {
        val projects = ProjectStore(idFactory = { "project-1" })
        val project = projects.save(ProjectSave(name = "Social Machine"))
        val workspaces = WorkspaceStore(projects = projects, idFactory = { "workspace-1" })

        val overlong = "x".repeat(WorkspaceStore.MAX_NAME_LENGTH + 1)
        val refusal1 = assertFailsWith<IllegalArgumentException>("saving with an overlong name must be refused") {
            workspaces.save(projectId = project.id, name = overlong)
        }

        assertTrue(
            refusal1.message.orEmpty().contains("longer than"),
            "the refusal mentions the length limit, got: ${refusal1.message}",
        )
        assertEquals(emptyList(), workspaces.list(project.id), "a refused save writes no workspace")

        val controlChar = "valid\u0007name"
        val refusal2 = assertFailsWith<IllegalArgumentException>("saving with a control character name must be refused") {
            workspaces.save(projectId = project.id, name = controlChar)
        }

        assertTrue(
            refusal2.message.orEmpty().contains("control character"),
            "the refusal mentions the control character, got: ${refusal2.message}",
        )
        assertEquals(emptyList(), workspaces.list(project.id), "a refused save writes no workspace")
    }
}
