// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mvp

import kotlinx.serialization.Serializable

/**
 * PW-1: a workspace lives inside a project. [path] is the optional checkout folder it works in.
 */
@Serializable
data class Workspace(
    val id: String,
    val projectId: String,
    val name: String,
    val path: String? = null,
)

/** Where workspaces outlive the store; the in-memory default mirrors [ProjectPersistence]. */
interface WorkspacePersistence {
    fun loadAll(): List<Workspace>
    fun upsert(workspace: Workspace)
}

class InMemoryWorkspacePersistence : WorkspacePersistence {
    private val rows = LinkedHashMap<String, Workspace>()

    override fun loadAll(): List<Workspace> = rows.values.toList()

    override fun upsert(workspace: Workspace) {
        rows[workspace.id] = workspace
    }
}

/**
 * The local store of workspaces, each owned by one project. Shaped after [ProjectStore]: in memory by
 * default, with an injected [WorkspacePersistence] for a durable caller. A refusal throws
 * [IllegalArgumentException] and nothing is written.
 */
class WorkspaceStore(
    private val projects: ProjectStore,
    private val persistence: WorkspacePersistence = InMemoryWorkspacePersistence(),
    private val idFactory: () -> String = { "workspace-" + java.util.UUID.randomUUID().toString().take(8) },
) {
    /** Insertion ordered, so [list] answers in creation order. */
    private val workspaces = LinkedHashMap<String, Workspace>()

    init {
        persistence.loadAll().forEach { workspaces[it.id] = it }
    }

    /**
     * Create a workspace named [name] inside project [projectId]; answered with the whole [Workspace].
     * Refuses (and writes nothing) when [projectId] names no project in [projects].
     */
    @Synchronized
    fun save(projectId: String, name: String, path: String? = null): Workspace {
        val owner = projectId.trim()
        require(owner.isNotEmpty()) { "expects a project id" }
        projects.get(owner)
        val checkedName = checkName(name)
        val created = Workspace(
            id = idFactory(),
            projectId = owner,
            name = checkedName,
            path = path?.trim()?.takeIf { it.isNotEmpty() },
        )
        workspaces[created.id] = created
        persistence.upsert(created)
        return created
    }

    /** Every workspace of project [projectId], in the order they were created. */
    @Synchronized
    fun list(projectId: String): List<Workspace> =
        workspaces.values.filter { it.projectId == projectId.trim() }

    companion object {
        const val MAX_NAME_LENGTH = 200

        private fun checkName(raw: String): String {
            val name = raw.trim()
            require(name.isNotEmpty()) { "name is blank" }
            require(name.length <= MAX_NAME_LENGTH) { "name is longer than $MAX_NAME_LENGTH characters" }
            require(name.none { it.isISOControl() }) { "name contains a control character" }
            return name
        }
    }
}
