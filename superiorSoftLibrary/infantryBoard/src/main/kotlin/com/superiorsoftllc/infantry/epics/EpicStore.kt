// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.epics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * EPIC-1: one epic inside one project.
 *
 * [projectId] is the id of the [com.superiorsoftllc.infantry.protocol.Project] the epic belongs to --
 * the join key of the hierarchy Project -> Epics -> Stories -> Tasks. [storyIds] is the ordered list
 * of the epic's stories -- the ONLY place a story's order is kept (RETIRED, contract 1.29.0:
 * `Project.storyIds` used to duplicate this; it is gone). A story hangs off an EPIC, never off the
 * project directly.
 */
@Serializable
data class Epic(
    val id: String,
    val projectId: String,
    val name: String,
    val goal: String = "",
    val storyIds: List<String> = emptyList(),
    /**
     * Contract 1.82.0 (BASE-1c): the branch a ticket start in this epic cuts its `ticket/` branch from, overriding the
     * project's base. Empty = none set (the project's base, else `INFANTRY_BASE_BRANCH`, else `HEAD`). Set and cleared
     * ONLY with `epic_base`, which checks it resolves first. An older daemon omits the key.
     */
    val baseBranch: String = "",
)

/** Where epics outlive the store; the in-memory default mirrors [com.superiorsoftllc.infantry.mvp.ProjectPersistence]. */
interface EpicPersistence {
    fun loadAll(): List<Epic>
    fun upsert(epic: Epic)
}

class InMemoryEpicPersistence : EpicPersistence {
    private val rows = LinkedHashMap<String, Epic>()

    override fun loadAll(): List<Epic> = rows.values.toList()

    override fun upsert(epic: Epic) {
        rows[epic.id] = epic
    }
}

/**
 * EPIC-1: the local store of epics, each owned by one project. Shaped after
 * [ProjectDirectory]: in memory by default, with an injected
 * [EpicPersistence] for a durable caller.
 *
 * An epic saved under a project is listed under that project and under no other, in creation order.
 * A refusal throws [IllegalArgumentException] and nothing is written: [save] refuses a blank name and
 * refuses a [projectId] the injected [projects] does not know, exactly as
 * [com.superiorsoftllc.infantry.mvp.WorkspaceStore.save] refuses a workspace into an unknown project.
 * [projects] is optional so an in-memory caller with no project registry (a unit test) still works;
 * when it is absent the project is not checked.
 */
class EpicStore(
    private val projects: ProjectDirectory? = null,
    private val persistence: EpicPersistence = InMemoryEpicPersistence(),
    private val idFactory: () -> String = { "epic-" + UUID.randomUUID().toString().take(8) },
    /**
     * E1-c (contract 1.62.0): when set, every epic this store answers carries [Epic.storyIds] DERIVED from the
     * ticket board -- the ids of the tickets filed under it, in board order -- instead of the stored list. The
     * daemon wires it; `storyIds` stays on the frame for one release, filled with ticket ids. Null (a unit test
     * with no board) answers the stored list, which [appendStoryId] maintains.
     */
    private val ticketIdsByEpic: (() -> Map<String, List<String>>)? = null,
) {
    /** Insertion ordered, so [list] answers in creation order. */
    private val epics = LinkedHashMap<String, Epic>()

    init {
        persistence.loadAll().forEach { epics[it.id] = it }
    }

    /** [epic] as answered: with derived ticket ids when the board is wired, else as stored. */
    private fun view(epic: Epic, derived: Map<String, List<String>>? = ticketIdsByEpic?.invoke()): Epic =
        if (derived == null) epic else epic.copy(storyIds = derived[epic.id].orEmpty())

    /** E1-c: epic [epicId] as answered (derived ticket ids when wired), or null when there is none. */
    @Synchronized
    fun get(epicId: String): Epic? = epics[epicId.trim()]?.let { view(it) }

    /**
     * Create an epic named [name] inside project [projectId]; answered with the whole [Epic].
     *
     * Refuses (and writes nothing) when [name] is blank, or when [projectId] names no project in the
     * injected [projects] -- the mirror of [com.superiorsoftllc.infantry.mvp.WorkspaceStore.save].
     */
    @Synchronized
    fun save(projectId: String, name: String, goal: String = ""): Epic {
        val owner = projectId.trim()
        val checkedName = name.trim()
        require(checkedName.isNotEmpty()) { "name is blank" }
        projects?.let { store ->
            require(runCatching { store.get(owner) }.isSuccess) { "unknown project '${owner.take(80)}'" }
        }
        val created = Epic(
            id = idFactory(),
            projectId = owner,
            name = checkedName,
            goal = goal,
            storyIds = emptyList(),
        )
        epics[created.id] = created
        persistence.upsert(created)
        return view(created)
    }

    /**
     * BASE-1c: set epic [epicId]'s base branch to [branch] (null or blank = clear it), answered with the epic.
     * Refuses an unknown epic; a set runs [check] first (it throws with the reason), so a refused branch writes
     * nothing. Tickets already started keep the base they recorded: this only changes what new cuts choose.
     */
    @Synchronized
    fun setBaseBranch(epicId: String, branch: String?, check: (Epic, String) -> Unit = { _, _ -> }): Epic {
        val existing = epics[epicId.trim()] ?: throw IllegalArgumentException("unknown epic '${epicId.trim().take(80)}'")
        val wanted = branch?.trim().orEmpty()
        if (wanted.isNotEmpty()) check(existing, wanted)
        val updated = existing.copy(baseBranch = wanted)
        epics[existing.id] = updated
        persistence.upsert(updated)
        return view(updated)
    }

    /** BASE-1c: epic [epicId]'s base branch, or null when it has none (or there is no such epic). */
    @Synchronized
    fun baseBranchOf(epicId: String): String? = epics[epicId.trim()]?.baseBranch?.takeIf { it.isNotEmpty() }

    /** Every epic of project [projectId], in the order they were created. */
    @Synchronized
    fun list(projectId: String): List<Epic> {
        val derived = ticketIdsByEpic?.invoke()
        return epics.values.filter { it.projectId == projectId.trim() }.map { view(it, derived) }
    }

    /**
     * E1-c: the daemon no longer calls this (it derives the ids from the board); an unwired store still does.
     * EPIC-2: append [storyId] to the END of epic [epicId]'s ordered [Epic.storyIds] -- the ONLY
     * writer of that list (RETIRED, contract 1.29.0: `ProjectStore.appendStoryId` used to be this
     * list's mirror on the project side; it is gone, so a story's order now lives here alone).
     * Appending at the end is what makes the list creation order. An epic that does not exist is a
     * NO-OP answering null, NOT a refusal -- a story that names an unknown epic is still a story, so
     * the daemon's answer must not depend on an epic nobody created. An id already in the list is
     * left as it is, so a replayed create cannot list one story twice.
     *
     * @return the epic as it now stands, or null when there was no epic to append to.
     */
    @Synchronized
    fun appendStoryId(epicId: String, storyId: String): Epic? {
        val wanted = epicId.trim()
        val story = storyId.trim()
        if (wanted.isEmpty() || story.isEmpty()) return null
        val existing = epics[wanted] ?: return null
        if (story in existing.storyIds) return view(existing)
        val updated = existing.copy(storyIds = existing.storyIds + story)
        epics[wanted] = updated
        persistence.upsert(updated)
        return view(updated)
    }
}

/** DP-5e: the project registry as the epic store sees it; [get] throws for an unknown id (`mvp.ProjectStore` implements it). */
fun interface ProjectDirectory {
    fun get(id: String): Any
}
