package com.superiorsoftllc.agenthq.epics

import com.superiorsoftllc.agenthq.db.AgentHQDatabase
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * EPIC-3: the durable surface -- epics in the daemon's own SQLite database (`epics` table, queried
 * through the generated `epicsQueries`), so an epic and its ordered [Epic.storyIds] survive the
 * daemon being stopped and started again over the same file.
 *
 * Mirrors [com.superiorsoftllc.agenthq.mvp.SqlProjectPersistence]: the store owns every rule, this
 * layer only writes the [Epic] it is handed and reads the rows back in creation order.
 * [Epic.storyIds] is stored as a JSON array in one column -- the ids are an ordered list the store
 * echoes, never a relationship this layer joins on, so a text column is the whole truth.
 *
 * Callers own the database handle: the daemon keeps one open handle for its lifetime and closes it
 * when it stops, which is the "restart" boundary the store is proven against.
 */
class SqlEpicPersistence(
    private val database: AgentHQDatabase,
) : EpicPersistence {

    override fun loadAll(): List<Epic> =
        database.epicsQueries.getAllEpics().executeAsList().map { row ->
            Epic(
                id = row.id,
                projectId = row.project_id,
                name = row.name,
                goal = row.goal,
                storyIds = decodeStoryIds(row.story_ids),
                baseBranch = row.base_branch,
            )
        }

    override fun upsert(epic: Epic) {
        val now = System.currentTimeMillis()
        val inserted = database.epicsQueries.insertEpicIgnore(
            id = epic.id,
            project_id = epic.projectId,
            name = epic.name,
            goal = epic.goal,
            story_ids = encodeStoryIds(epic.storyIds),
            created_at = now,
            base_branch = epic.baseBranch,
        ).value
        // The insert reports how many rows it actually wrote: 1 for a create, 0 when the id was
        // already there -- and SQLDelight drops every statement after the first in a label, so the
        // update is its own query rather than a second statement under one label.
        if (inserted == 0L) {
            database.epicsQueries.updateEpic(
                project_id = epic.projectId,
                name = epic.name,
                goal = epic.goal,
                story_ids = encodeStoryIds(epic.storyIds),
                base_branch = epic.baseBranch,
                id = epic.id,
            )
        }
    }

    private companion object {
        /**
         * The encoder used for the `story_ids` column. Owned privately on purpose: the wire's
         * decoders live in the protocol package and are the client's contract, while this is an
         * internal storage detail that must survive a round-trip, nothing more.
         */
        private val json = Json { encodeDefaults = true }
        private val storyIdsSerializer = ListSerializer(serializer<String>())

        private fun encodeStoryIds(storyIds: List<String>): String =
            json.encodeToString(storyIdsSerializer, storyIds)

        private fun decodeStoryIds(raw: String): List<String> =
            json.decodeFromString(storyIdsSerializer, raw)
    }
}
