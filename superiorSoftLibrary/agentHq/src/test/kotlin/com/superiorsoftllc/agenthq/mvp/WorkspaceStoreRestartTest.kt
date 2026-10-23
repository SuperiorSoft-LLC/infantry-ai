package com.superiorsoftllc.agenthq.mvp

import com.superiorsoftllc.agenthq.db.AgentHQDatabaseFactory
import com.superiorsoftllc.agenthq.protocol.ProjectSave
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PW-3: a workspace outlives the daemon that saved it.
 *
 * A daemon restart is, from the workspace store's side, a brand-new `WorkspaceStore` over the same
 * database file the old one was using. This test does exactly that, the way [ProjectStoreRestartTest]
 * does for projects: save through one store, CLOSE the handle the way the daemon does when it stops,
 * reopen the very same path, build a new project store and a new workspace store over it, and prove
 * the workspace listed under its project is the one that was saved.
 */
class WorkspaceStoreRestartTest {

    /** A temp directory holding the SQLite file a daemon would keep its state in. */
    private fun stateDir() = Files.createTempDirectory("agenthq-pw3-").toFile()

    @Test
    fun workspacesSurviveRestart() {
        val state = stateDir()
        try {
            val dbPath = File(state, "agenthq.db").absolutePath

            val first = AgentHQDatabaseFactory.open(dbPath)
            val firstProjects = ProjectStore(persistence = SqlProjectPersistence(first.database))
            val project = firstProjects.save(ProjectSave(name = "Social Machine"))
            val created = WorkspaceStore(
                projects = firstProjects,
                persistence = SqlWorkspacePersistence(first.database),
                idFactory = { "workspace-1" },
            ).save(projectId = project.id, name = "main", path = "/work/main")
            first.close()

            // The restart: a new handle on the same file, a new project store and workspace store over it.
            val second = AgentHQDatabaseFactory.open(dbPath)
            try {
                val reopenedProjects = ProjectStore(persistence = SqlProjectPersistence(second.database))
                val reopened = WorkspaceStore(
                    projects = reopenedProjects,
                    persistence = SqlWorkspacePersistence(second.database),
                )
                assertEquals(
                    listOf(created),
                    reopened.list(project.id),
                    "the workspace saved before the restart is listed under its project after it",
                )
            } finally {
                second.close()
            }
        } finally {
            runCatching { state.deleteRecursively() }
        }
    }
}
