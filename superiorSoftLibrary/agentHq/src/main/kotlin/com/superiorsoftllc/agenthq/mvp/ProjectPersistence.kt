package com.superiorsoftllc.agenthq.mvp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import com.superiorsoftllc.agenthq.db.AgentHQDatabase
import com.superiorsoftllc.agenthq.protocol.Project

/**
 * MVP Sprint-Zero, slice A2: the storage surface [ProjectStore] persists through.
 *
 * WHY A SEAM AND NOT SQL INSIDE THE STORE: the store owns every project rule (what a create needs,
 * what an edit may leave out, how ids are minted), and A1 proved those rules against plain
 * memory. Persistence is a different axis with exactly one requirement -- what the store wrote
 * must outlive the store, because a daemon restart is a new store over the same database file --
 * so it is injected, and a caller that does not care about restarts keeps the in-memory default.
 * The store's public operations (save / get / list) do not move: the daemon constructs it with a
 * durable surface and every other caller constructs it with none.
 *
 * An implementation stores the [Project] model exactly as the seam now shapes it -- id, name and
 * goal -- and NOTHING else. (Retired, contract 1.29.0: a project used to also echo an ordered
 * `storyIds` list I0 maintained; a story's order now lives on
 * [com.superiorsoftllc.agenthq.epics.Epic.storyIds] instead.)
 *
 * Everything is fail-loud: a storage failure propagates to the caller, the way A1's refusals do,
 * rather than being swallowed into a store that silently forgets a project.
 */
interface ProjectPersistence {

    /**
     * Every stored project, in the order [ProjectStore.list] must answer: creation order. An empty
     * list is a store nobody has written to yet, which is the state of a fresh database file.
     */
    fun loadAll(): List<Project>

    /**
     * Writes [project] whole, creating its row the first time and overwriting it in place after --
     * its id is the MVP's join key, so a write never moves or re-mints it. Called on every create
     * AND every edit, so the stored row always equals what the store answers with.
     */
    fun upsert(project: Project)
}

/**
 * A1's behaviour, unchanged: projects live in an insertion-ordered map and die with the store.
 * This is the default surface, so every existing caller of [ProjectStore] keeps its exact
 * semantics and A1's test suite proves them.
 */
class InMemoryProjectPersistence : ProjectPersistence {
    private val rows = LinkedHashMap<String, Project>()

    override fun loadAll(): List<Project> = rows.values.toList()

    override fun upsert(project: Project) {
        rows[project.id] = project
    }
}

/**
 * The durable surface: projects in the daemon's own SQLite database (`projects` table, queried
 * through the generated `projectsQueries`), so a project survives the daemon being stopped and
 * started again over the same file.
 *
 * RETIRED (contract 1.29.0): the `projects` table still carries a `story_ids` column (dropping it
 * would need a schema migration this project's SQLite dialect does not support -- see 15.sqm's
 * neighbours, every one of them `ADD COLUMN`, never `DROP COLUMN`), but neither [loadAll] nor
 * [upsert] reads or writes it any more. `projects.sq`'s `insertProjectIgnore` / `updateProject` no
 * longer take it as a parameter, so a freshly inserted row gets the column's own SQL default
 * (`'[]'`) and an edited row keeps whatever it already had -- a dead column nothing derives from,
 * left in place rather than risking a migration this schema's dialect cannot express. A story's
 * order now lives on [com.superiorsoftllc.agenthq.epics.Epic.storyIds] instead.
 *
 * Callers own the database handle: the daemon keeps one open handle for its lifetime and closes it
 * when it stops, which is the "restart" boundary the store is proven against.
 */
class SqlProjectPersistence(
    private val database: AgentHQDatabase,
) : ProjectPersistence {

    override fun loadAll(): List<Project> =
        database.projectsQueries.getAllProjects().executeAsList().map { row ->
            Project(
                id = row.id,
                name = row.name,
                goal = row.goal,
                directory = row.directory,
                baseBranch = row.base_branch,
                laneCaps = runCatching { Json.decodeFromString<Map<String, Int>>(row.lane_caps) }.getOrDefault(emptyMap()),
                autoRemoveDoneWorkers = row.auto_remove_done_workers != 0L,
                experiments = runCatching { Json.decodeFromString<Map<String, String>>(row.experiments) }.getOrDefault(emptyMap()),
            )
        }

    override fun upsert(project: Project) {
        val now = System.currentTimeMillis()
        val inserted = database.projectsQueries.insertProjectIgnore(
            id = project.id,
            name = project.name,
            goal = project.goal,
            created_at = now,
            directory = project.directory,
            base_branch = project.baseBranch,
            lane_caps = Json.encodeToString(project.laneCaps),
            auto_remove_done_workers = if (project.autoRemoveDoneWorkers) 1L else 0L,
            experiments = Json.encodeToString(project.experiments),
        ).value
        // The insert reports how many rows it actually wrote: 1 for a create, 0 when the id was
        // already there -- and SQLDelight drops every statement after the first in a label, so the
        // update is its own query rather than a second statement under one label.
        if (inserted == 0L) {
            database.projectsQueries.updateProject(
                name = project.name,
                goal = project.goal,
                directory = project.directory,
                base_branch = project.baseBranch,
                lane_caps = Json.encodeToString(project.laneCaps),
                auto_remove_done_workers = if (project.autoRemoveDoneWorkers) 1L else 0L,
                experiments = Json.encodeToString(project.experiments),
                id = project.id,
            )
        }
    }
}
