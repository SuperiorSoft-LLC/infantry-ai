package com.superiorsoftllc.agenthq.memory

import com.superiorsoftllc.agenthq.db.AgentHQDatabase

/**
 * The durable surface for memory notes: rows in the `memory_notes` table of the daemon's own SQLite
 * database (queried through the generated `memory_notesQueries`), so a note survives the daemon being
 * stopped and started again over the same file.
 *
 * Mirrors [com.superiorsoftllc.agenthq.mvp.SqlProjectPersistence]: the [MemoryStore] owns every rule,
 * this layer only writes the [MemoryNote] it is handed and reads the rows back in first-save order.
 * Callers own the database handle: the daemon keeps one open handle for its lifetime and closes it
 * when it stops, which is the "restart" boundary the store is proven against.
 *
 * [MemoryNote.updatedAt] is stored verbatim in one column -- it is the ISO instant the store stamped,
 * echoed on read, never derived here. `created_at` is stamped on the FIRST write only, so an edit that
 * keeps the note's id can never reorder the listing.
 */
class SqlMemoryPersistence(
    private val database: AgentHQDatabase,
) : MemoryPersistence {

    override fun loadAll(): List<MemoryNote> =
        database.memory_notesQueries.getAllMemoryNotes().executeAsList().map { row ->
            MemoryNote(
                id = row.id,
                projectId = row.project_id,
                name = row.name,
                folder = row.folder,
                description = row.description,
                body = row.body,
                updatedAt = row.updated_at,
                scope = row.scope,
                ownerId = row.owner_id,
            )
        }

    /**
     * MEM-3: the notes of [projectId] whose name, description or body contain [query], matched by the
     * generated `searchMemoryNotes` query (SQL `LIKE`). This is the durable half of
     * [MemoryStore.search]: the term is wrapped in LIKE's `%...%` wildcards here, and the query's own
     * `project_id` clause is what enforces isolation.
     */
    override fun search(projectId: String, query: String): List<MemoryNote> {
        val pattern = "%${escapeLike(query)}%"
        return database.memory_notesQueries.searchMemoryNotes(
            projectId = projectId,
            namePattern = pattern,
            descriptionPattern = pattern,
            bodyPattern = pattern,
        ).executeAsList().map { row ->
            MemoryNote(
                id = row.id,
                projectId = row.project_id,
                name = row.name,
                folder = row.folder,
                description = row.description,
                body = row.body,
                updatedAt = row.updated_at,
                scope = row.scope,
                ownerId = row.owner_id,
            )
        }
    }

    /**
     * MEM-6: escape the LIKE metacharacters in a user-supplied term so `%` and `_` are matched
     * literally instead of as wildcards. The backslash is escaped first, then the two wildcards, and
     * the generated query pairs each pattern with `ESCAPE '\'`. Without this a search for "%" (wrapped
     * to `%%%`) matched every note.
     */
    private fun escapeLike(term: String): String = term
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    override fun upsert(note: MemoryNote) {
        val now = System.currentTimeMillis()
        val inserted = database.memory_notesQueries.insertMemoryNoteIgnore(
            id = note.id,
            project_id = note.projectId,
            name = note.name,
            folder = note.folder,
            description = note.description,
            body = note.body,
            updated_at = note.updatedAt,
            created_at = now,
            scope = note.scope,
            owner_id = note.ownerId,
        ).value
        // The insert reports how many rows it actually wrote: 1 for a create, 0 when the id was
        // already there -- and SQLDelight drops every statement after the first in a label, so the
        // update is its own query rather than a second statement under one label.
        if (inserted == 0L) {
            database.memory_notesQueries.updateMemoryNote(
                project_id = note.projectId,
                name = note.name,
                folder = note.folder,
                description = note.description,
                body = note.body,
                updated_at = note.updatedAt,
                scope = note.scope,
                owner_id = note.ownerId,
                id = note.id,
            )
        }
    }

    override fun delete(ids: List<String>) {
        ids.forEach { database.memory_notesQueries.deleteMemoryNote(it) }
    }
}
