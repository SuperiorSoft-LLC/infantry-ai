package com.superiorsoftllc.infantry.memory

import kotlinx.serialization.Serializable

/* DP-7a: the memory wire DTOs, moved from infantry unchanged (same package, same serializers). The store and handler stay in infantry. */

/**
 * MEM-1: one project-memory note -- a durable observation scoped to exactly one project.
 *
 * The user's 2026-10-11 decision fixes the shape: the DATABASE is the source of truth, each agent
 * reads a generated markdown mirror, and the daemon puts the project's note INDEX into every agent's
 * brief. A note therefore carries its own [id], the [projectId] it belongs to, a [name] that is its
 * identity inside that project, the [folder] it is filed under, a one-line [description] and the
 * [body]. The [name] + [description] pair is what an index lists; the [body] is what a reader fetches
 * afterwards, which is why [MemoryStore.index] never carries it.
 *
 * Serializable since MEM-8: the `memory_save` answer (`memory_note`) carries the whole stored note,
 * the mirror of the `epic` frame carrying an [com.superiorsoftllc.infantry.epics.Epic].
 */
@Serializable
data class MemoryNote(
    val id: String,
    val projectId: String,
    val name: String,
    val folder: String,
    val description: String,
    val body: String,
    val updatedAt: String,
    /** NOTES-1 (contract 1.98.0): [SCOPE_PROJECT] (shared), [SCOPE_ROLE] (one role) or [SCOPE_SESSION] (one session, temporary). */
    val scope: String = SCOPE_PROJECT,
    /** NOTES-1: "" for a project note, the role id for a role note, the session id for a session note. */
    val ownerId: String = "",
)

const val SCOPE_PROJECT = "project"
const val SCOPE_ROLE = "role"
const val SCOPE_SESSION = "session"
val NOTE_SCOPES = listOf(SCOPE_PROJECT, SCOPE_ROLE, SCOPE_SESSION)

/**
 * MEM-3: one hit of a project-memory search -- the shape a ranked result list carries.
 *
 * A search result is deliberately NOT the whole [MemoryNote]: the point of searching is to find a
 * note by its content without paying for every body up front, so a hit carries what a reader needs
 * to decide whether to open the note -- its [name], its one-line [description] and a [snippet]: the
 * fragment of the body around the match. The full body is then fetched by ([projectId], [name]), the
 * same way the index feeds [MemoryStore.get].
 */
@Serializable
data class MemorySearchResult(
    val name: String,
    val description: String,
    val snippet: String,
)

/** `memory_search` payload: the owning project and the query text to match against note content. */
@Serializable
data class MemorySearch(
    val projectId: String,
    val query: String,
    /** NOTES-1 (contract 1.98.0): `project` (default), `role` or `session`; see [MemoryScoped]. */
    override val scope: String? = null,
    override val sessionId: String? = null,
    override val role: String? = null,
) : MemoryScoped

/**
 * MEM-8 `memory_save` payload: the owning project and the note to write.
 *
 * P10-K2 (contract 1.51.0) adds [mode] (`replace` default, `append`, `edit`), [find], [content] and [folder], all
 * optional, so a pre-1.51.0 four-field payload `{projectId, name, description, body}` still decodes and replaces.
 * Which fields a mode takes (K-D5): `replace` needs [description] and [body]; `append` needs [content]; `edit`
 * needs a non-empty [find] and [content] (may be ""). A field that belongs to another mode is refused. A null
 * [folder] keeps the note where it is (a new note goes to the root); a null [description] keeps the stored cue in
 * `append` / `edit`.
 */
@Serializable
data class MemorySave(
    val projectId: String,
    val name: String,
    val description: String? = null,
    val body: String? = null,
    val mode: String? = null,
    val find: String? = null,
    val content: String? = null,
    val folder: String? = null,
    override val scope: String? = null,
    override val sessionId: String? = null,
    override val role: String? = null,
) : MemoryScoped {
    companion object {
        const val MODE_REPLACE = "replace"
        const val MODE_APPEND = "append"
        const val MODE_EDIT = "edit"
        val MODES = listOf(MODE_REPLACE, MODE_APPEND, MODE_EDIT)
    }
}

/** P10-K2 (contract 1.51.0) `memory_get` payload: one note of a project, by name. */
@Serializable
data class MemoryGet(
    val projectId: String,
    val name: String,
    override val scope: String? = null,
    override val sessionId: String? = null,
    override val role: String? = null,
) : MemoryScoped

/** NOTES-1 `memory_list` payload (a bare project id still works and means the project scope). */
@Serializable
data class MemoryList(
    val projectId: String,
    override val scope: String? = null,
    override val sessionId: String? = null,
    override val role: String? = null,
) : MemoryScoped

/** NOTES-1 `memory_delete` payload: one note by name inside a scope. */
@Serializable
data class MemoryDelete(
    val projectId: String,
    val name: String,
    override val scope: String? = null,
    override val sessionId: String? = null,
    override val role: String? = null,
) : MemoryScoped

/**
 * NOTES-1 `memory_purge_role` payload: delete every role note of [role]. [confirm] must equal [role] exactly
 * (a typed confirmation), so a stray call cannot wipe a role's notes.
 */
@Serializable
data class MemoryPurgeRole(
    val role: String,
    val confirm: String,
)

/** NOTES-1: who is asking and which scope they address. A null scope is `project`. */
interface MemoryScoped {
    val scope: String?
    /** The declared calling session (an agent); null = the owner at a terminal / the UI. */
    val sessionId: String?
    /** The role a `role` scope addresses; null = the calling session's own role. */
    val role: String?
}

/**
 * MEM-8: one row of the `memory_list` answer -- a project's note reduced to the pair an index lists,
 * its [name] and its one-line [description], with the body deliberately withheld.
 */
@Serializable
data class MemoryIndexEntry(
    val name: String,
    val description: String,
    /** P10-K2 (contract 1.51.0): the folder the note is filed under ("" = the project root). */
    val folder: String = "",
)

/**
 * P10-K1: how many times [needle] occurs in [haystack], overlaps included (`aa` occurs twice in `aaa`),
 * matched byte for byte. The search restarts one character after each hit, never after the whole hit.
 */
fun memoryOccurrences(haystack: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var from = haystack.indexOf(needle)
    while (from >= 0) {
        count++
        from = haystack.indexOf(needle, from + 1)
    }
    return count
}
