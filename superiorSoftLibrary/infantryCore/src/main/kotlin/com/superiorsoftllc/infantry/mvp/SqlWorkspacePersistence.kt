// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mvp

import com.superiorsoftllc.infantry.db.InfantryDatabase

/**
 * The durable surface for workspaces: rows in the `workspaces` table of the daemon's own SQLite
 * database (queried through the generated `workspacesQueries`), so a workspace survives the daemon
 * being stopped and started again over the same file.
 *
 * Mirrors [SqlProjectPersistence]: the store owns every rule, this layer only writes the [Workspace]
 * it is handed and reads the rows back in creation order. Callers own the database handle.
 */
class SqlWorkspacePersistence(
    private val database: InfantryDatabase,
) : WorkspacePersistence {

    override fun loadAll(): List<Workspace> =
        database.workspacesQueries.getAllWorkspaces().executeAsList().map { row ->
            Workspace(
                id = row.id,
                projectId = row.project_id,
                name = row.name,
                path = row.path,
            )
        }

    override fun upsert(workspace: Workspace) {
        val now = System.currentTimeMillis()
        val inserted = database.workspacesQueries.insertWorkspaceIgnore(
            id = workspace.id,
            project_id = workspace.projectId,
            name = workspace.name,
            path = workspace.path,
            created_at = now,
        ).value
        // 1 for a create, 0 when the id was already there: then the update is its own query, as in
        // SqlProjectPersistence (SQLDelight keeps only the first statement of a label).
        if (inserted == 0L) {
            database.workspacesQueries.updateWorkspace(
                project_id = workspace.projectId,
                name = workspace.name,
                path = workspace.path,
                id = workspace.id,
            )
        }
    }
}
