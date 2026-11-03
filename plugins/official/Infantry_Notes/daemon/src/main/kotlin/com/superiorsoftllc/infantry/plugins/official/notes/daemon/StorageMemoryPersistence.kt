package com.superiorsoftllc.infantry.plugins.official.notes.daemon

import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage
import com.superiorsoftllc.infantry.memory.MemoryNote

/**
 * DP-7f: the durable surface for memory notes, rows in the adopted `memory_notes` table (module.json `daemon.storage.adopt`;
 * core creates the table) reached through the plugin storage, so a note survives the daemon being stopped and started again.
 *
 * The [MemoryStore] owns every rule; this layer writes the [MemoryNote] it is handed and reads rows back in first-save order.
 * `created_at` is stamped on the FIRST write only, so an edit that keeps the note's id can never reorder the listing.
 * [MemoryNote.updatedAt] is stored verbatim.
 */
class StorageMemoryPersistence(
    private val db: PluginStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) : MemoryPersistence {

    override fun loadAll(): List<MemoryNote> =
        db.query("SELECT * FROM memory_notes ORDER BY created_at ASC, id ASC").map { it.toNote() }

    /**
     * MEM-3 / MEM-6: the notes of [projectId] whose name, description or body contain [query] (SQL `LIKE`). The `project_id`
     * clause enforces isolation; `ESCAPE '\'` makes a literal `%`, `_` or `\` in the term match itself, not every note.
     */
    override fun search(projectId: String, query: String): List<MemoryNote> {
        val pattern = "%${escapeLike(query)}%"
        return db.query(
            "SELECT * FROM memory_notes WHERE project_id = ? AND (name LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\') " +
                "ORDER BY created_at ASC, id ASC",
            listOf(projectId, pattern, pattern, pattern),
        ).map { it.toNote() }
    }

    private fun escapeLike(term: String): String = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    override fun upsert(note: MemoryNote) {
        db.transaction {
            val inserted = db.execute(
                "INSERT OR IGNORE INTO memory_notes(id, project_id, name, folder, description, body, updated_at, created_at, scope, owner_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(note.id, note.projectId, note.name, note.folder, note.description, note.body, note.updatedAt, clock(), note.scope, note.ownerId),
            )
            if (inserted == 0) {
                db.execute(
                    "UPDATE memory_notes SET project_id = ?, name = ?, folder = ?, description = ?, body = ?, updated_at = ?, scope = ?, owner_id = ? WHERE id = ?",
                    listOf(note.projectId, note.name, note.folder, note.description, note.body, note.updatedAt, note.scope, note.ownerId, note.id),
                )
            }
        }
    }

    override fun delete(ids: List<String>) {
        db.transaction { ids.forEach { db.execute("DELETE FROM memory_notes WHERE id = ?", listOf(it)) } }
    }

    private fun Map<String, Any?>.toNote() = MemoryNote(
        id = this["id"] as String,
        projectId = this["project_id"] as String,
        name = this["name"] as String,
        folder = this["folder"] as String,
        description = this["description"] as String,
        body = this["body"] as String,
        updatedAt = this["updated_at"] as String,
        scope = this["scope"] as String,
        ownerId = this["owner_id"] as String,
    )
}
