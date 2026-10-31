package com.superiorsoftllc.agenthq.memory

import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * P10-K1 (K-D5): a `memory_save` refusal with a stable [code]. The message STARTS with the code
 * (`anchor_ambiguous: 'find' occurs 3 times ...`) so a test, the CLI and an agent can match on it; the wire
 * prefixes it with the frame name (`memory_save refused: anchor_ambiguous: ...`). It is an
 * [IllegalArgumentException], so every existing `refused` path carries it unchanged, and nothing is written.
 */
class MemoryRefusal(val code: String, detail: String) : IllegalArgumentException("$code: $detail") {
    companion object {
        /** `append` / `edit` (or `memory_get`) named a note the project does not hold. */
        const val NOTE_NOT_FOUND = "note_not_found"

        /** `edit`: `find` occurs nowhere in the body. */
        const val ANCHOR_NOT_FOUND = "anchor_not_found"

        /** `edit`: `find` occurs two or more times (overlaps counted). */
        const val ANCHOR_AMBIGUOUS = "anchor_ambiguous"
    }
}

/**
 * Where memory notes outlive the store. The in-memory default mirrors
 * [com.superiorsoftllc.agenthq.mvp.InMemoryProjectPersistence]: the store owns every rule, this
 * surface only reads rows back and writes a note that really changed, so a durable caller (the
 * daemon) can later persist through SQL without the rules moving.
 */
interface MemoryPersistence {
    /** Every stored note, in the order [MemoryStore] must answer. Empty for a fresh store. */
    fun loadAll(): List<MemoryNote>

    /** Write [note] whole: insert its row the first time, overwrite it in place after. */
    fun upsert(note: MemoryNote)

    /**
     * MEM-3: the notes of [projectId] whose name, description or body contain [query]. The durable
     * surface answers this as a query (SQL `LIKE`), which is the reason the database is the source of
     * truth; the in-memory default filters the same way so both surfaces agree.
     */
    fun search(projectId: String, query: String): List<MemoryNote>

    /** NOTES-1: remove the rows with these ids. */
    fun delete(ids: List<String>)
}

/** The default surface: notes live in an insertion-ordered map and die with the store. */
class InMemoryMemoryPersistence : MemoryPersistence {
    private val rows = LinkedHashMap<String, MemoryNote>()

    override fun loadAll(): List<MemoryNote> = rows.values.toList()

    override fun upsert(note: MemoryNote) {
        rows[note.id] = note
    }

    override fun delete(ids: List<String>) {
        ids.forEach { rows.remove(it) }
    }

    /** The same narrowing [SqlMemoryPersistence] runs in SQL, done over the held rows. */
    override fun search(projectId: String, query: String): List<MemoryNote> =
        rows.values.filter { note ->
            note.projectId == projectId && (
                note.name.contains(query, ignoreCase = true) ||
                    note.description.contains(query, ignoreCase = true) ||
                    note.body.contains(query, ignoreCase = true)
                )
        }
}

/**
 * MEM-1: the local store of project-memory notes.
 *
 * Shaped after [com.superiorsoftllc.agenthq.mvp.ProjectStore]: in memory by default, with an injected
 * [MemoryPersistence] for a durable caller, so a daemon restart is a new store over the same database
 * and every other caller keeps the in-memory default. [index] is the daemon's brief half: it lists a
 * project's notes by [MemoryNote.name] and [MemoryNote.description] only, so an agent's brief never
 * carries note bodies it did not ask for.
 *
 * A note is identified by its ([projectId], [name]) pair: saving the same name into the same project
 * updates that note in place rather than minting a second one, which is what keeps an index free of
 * duplicate names. A refusal throws [IllegalArgumentException] and nothing is written.
 */
class MemoryStore(
    private val persistence: MemoryPersistence = InMemoryMemoryPersistence(),
    private val idFactory: () -> String = { "note-" + UUID.randomUUID().toString().take(8) },
) {
    /** Insertion ordered, so [index] answers in the order notes were first saved. */
    private val notes = LinkedHashMap<String, MemoryNote>()

    init {
        persistence.loadAll().forEach { notes[it.id] = it }
    }

    /**
     * Save a note named [name] inside project [projectId]. An existing note of the same project and
     * name is updated in place (its id is kept); a new one is minted an id. Answered with the whole
     * [MemoryNote]. This is the `replace` mode of `memory_save` (P10-K1). The [folder] is optional: null keeps an
     * existing note where it is (P10-K1, K-D5) and files a new one at the project root ("").
     */
    @Synchronized
    fun save(
        projectId: String,
        name: String,
        description: String,
        body: String,
        folder: String? = null,
        scope: String = SCOPE_PROJECT,
        ownerId: String = "",
    ): MemoryNote {
        val owner = projectId.trim()
        require(owner.isNotEmpty()) { "expects a project id" }
        val checkedName = checkName(name)
        val checkedOwner = checkScope(scope, ownerId)
        val existing = find(owner, scope, checkedOwner, checkedName)
        val note = MemoryNote(
            id = existing?.id ?: mintId(),
            projectId = owner,
            name = checkedName,
            folder = folder?.trim() ?: existing?.folder ?: "",
            description = description.trim(),
            body = body,
            updatedAt = Instant.now().toString(),
            scope = scope,
            ownerId = checkedOwner,
        )
        notes[note.id] = note
        persistence.upsert(note)
        return note
    }

    /** NOTES-1: the note of (project, scope, owner, name), or null. */
    private fun find(project: String, scope: String, ownerId: String, name: String): MemoryNote? =
        notes.values.firstOrNull { it.projectId == project && it.scope == scope && it.ownerId == ownerId && it.name == name }

    /** NOTES-1: refuse an unknown scope or a missing/stray owner id; answers the trimmed owner id. */
    private fun checkScope(scope: String, ownerId: String): String {
        require(scope in NOTE_SCOPES) { "scope '$scope' is not one of ${NOTE_SCOPES.joinToString("|")}" }
        val owner = ownerId.trim()
        if (scope == SCOPE_PROJECT) {
            require(owner.isEmpty()) { "a project note has no owner id" }
        } else {
            require(owner.isNotEmpty()) { "a $scope note expects its ${if (scope == SCOPE_ROLE) "role" else "session"} id" }
        }
        return owner
    }

    /** NOTES-1: delete every session note of [sessionId] (and nothing else); answers how many went. */
    @Synchronized
    fun purgeSession(sessionId: String): Int = purge { it.scope == SCOPE_SESSION && it.ownerId == sessionId }

    /** NOTES-1: delete every role note of [role] in every project; answers how many went. */
    @Synchronized
    fun purgeRole(role: String): Int = purge { it.scope == SCOPE_ROLE && it.ownerId == role }

    private fun purge(match: (MemoryNote) -> Boolean): Int {
        val doomed = notes.values.filter(match)
        doomed.forEach { notes.remove(it.id) }
        if (doomed.isNotEmpty()) persistence.delete(doomed.map { it.id })
        return doomed.size
    }

    /** NOTES-1: delete the one note (project, scope, owner, name); answers whether it existed. */
    @Synchronized
    fun delete(projectId: String, name: String, scope: String = SCOPE_PROJECT, ownerId: String = ""): Boolean {
        val note = find(projectId.trim(), scope, ownerId.trim(), name.trim()) ?: return false
        notes.remove(note.id)
        persistence.delete(listOf(note.id))
        return true
    }

    /**
     * P10-K1 `append`: add [content] after the stored body of the existing note [name], separated by exactly ONE
     * blank line (trailing line breaks of the body are folded into that separator; an empty body takes no
     * separator). Never creates: a missing note is refused with [MemoryRefusal.NOTE_NOT_FOUND]. [description] and
     * [folder] are kept when null. A refusal writes nothing.
     */
    @Synchronized
    fun append(
        projectId: String,
        name: String,
        content: String,
        description: String? = null,
        folder: String? = null,
        scope: String = SCOPE_PROJECT,
        ownerId: String = "",
    ): MemoryNote {
        val existing = existingOrRefuse(projectId, name, scope, ownerId)
        val head = existing.body.trimEnd('\n', '\r')
        val body = if (head.isEmpty()) content else head + APPEND_SEPARATOR + content
        return write(existing, body, description, folder)
    }

    /**
     * P10-K1 `edit`: replace the single occurrence of [find] in the body of the existing note [name] by
     * [content] (`""` removes the passage). [find] is matched byte for byte (no trimming, no case folding, no
     * line-ending normalisation) and occurrences are counted WITH overlap, so `aa` in `aaa` is ambiguous.
     * Refusals, each leaving the note byte-identical: [MemoryRefusal.NOTE_NOT_FOUND],
     * [MemoryRefusal.ANCHOR_NOT_FOUND] (0 matches), [MemoryRefusal.ANCHOR_AMBIGUOUS] (2 or more).
     */
    @Synchronized
    fun edit(
        projectId: String,
        name: String,
        find: String,
        content: String,
        description: String? = null,
        folder: String? = null,
        scope: String = SCOPE_PROJECT,
        ownerId: String = "",
    ): MemoryNote {
        require(find.isNotEmpty()) { "edit expects a non-empty 'find'" }
        val existing = existingOrRefuse(projectId, name, scope, ownerId)
        val count = occurrences(existing.body, find)
        if (count == 0) {
            throw MemoryRefusal(
                MemoryRefusal.ANCHOR_NOT_FOUND,
                "'find' does not occur in note '${existing.name}'; read it with memory_get and pass an exact passage",
            )
        }
        if (count == 1) {
            val at = existing.body.indexOf(find)
            val body = existing.body.substring(0, at) + content + existing.body.substring(at + find.length)
            return write(existing, body, description, folder)
        }
        throw MemoryRefusal(
            MemoryRefusal.ANCHOR_AMBIGUOUS,
            "'find' occurs $count times in note '${existing.name}'; pass a longer passage",
        )
    }

    /** The stored note of ([projectId], [name]), or [MemoryRefusal.NOTE_NOT_FOUND]: append and edit never create. */
    private fun existingOrRefuse(projectId: String, name: String, scope: String, ownerId: String): MemoryNote {
        val owner = projectId.trim()
        require(owner.isNotEmpty()) { "expects a project id" }
        val checkedName = checkName(name)
        return find(owner, scope, checkScope(scope, ownerId), checkedName)
            ?: throw MemoryRefusal(MemoryRefusal.NOTE_NOT_FOUND, "no note '$checkedName' in project '$owner'")
    }

    /** Store [existing] with a new [body]; a null [description] / [folder] keeps the stored one. */
    private fun write(existing: MemoryNote, body: String, description: String?, folder: String?): MemoryNote {
        val note = existing.copy(
            body = body,
            description = description?.trim() ?: existing.description,
            folder = folder?.trim() ?: existing.folder,
            updatedAt = Instant.now().toString(),
        )
        notes[note.id] = note
        persistence.upsert(note)
        return note
    }

    /**
     * The project-memory index of project [projectId]: every note of that project, in insertion
     * order, each carrying its name and description but NOT its body -- the body is what a reader
     * fetches afterwards, so it has no place in the index the daemon puts into an agent's brief.
     */
    @Synchronized
    fun index(projectId: String, scope: String = SCOPE_PROJECT, ownerId: String = ""): List<MemoryNote> {
        val owner = projectId.trim()
        return notes.values.filter { it.projectId == owner && it.scope == scope && it.ownerId == ownerId.trim() }.map { it.copy(body = "") }
    }

    /**
     * The whole note named [name] inside project [projectId], body included, or null when that
     * project holds no such note. This is the read [index] deliberately withholds: the index lists a
     * project's notes by name and description so a brief stays small, and a caller that actually needs
     * the note's content fetches it here. A note is identified by its ([projectId], [name]) pair, so
     * both are given, and trimming mirrors [save]'s own normalisation.
     */
    @Synchronized
    fun get(projectId: String, name: String, scope: String = SCOPE_PROJECT, ownerId: String = ""): MemoryNote? {
        return find(projectId.trim(), scope, ownerId.trim(), name.trim())
    }

    /**
     * MEM-4: write a read-only markdown mirror of project [projectId] into [dir] -- `INDEX.md` listing
     * every note by name and description, and one `<name>.md` per note carrying its body. The database
     * is the source of truth; the folder is a generated copy an agent reads for free.
     *
     * The export is a MIRROR, not an append: a re-export overwrites each note's file with its current
     * body, and an `.md` file left by an earlier export whose note is gone (or renamed) is deleted, so
     * the folder always matches the store exactly. Only `INDEX.md` and `*.md` inside [dir] are touched
     * -- nothing outside [dir] is ever written or removed.
     *
     * MEM-7: [checkName] refuses a separator, a `..` traversal or the `INDEX` stem at SAVE time, so a
     * freshly saved name is always a plain file name inside [dir]. The canonical-path guard below is
     * kept as DEFENCE IN DEPTH, because a row already in the durable store -- written before MEM-7 or
     * by another writer -- is loaded by [init] without passing [checkName], and a note whose name would
     * resolve outside [dir] is skipped rather than escaping the mirror.
     */
    @Synchronized
    fun export(projectId: String, dir: File) {
        val owner = projectId.trim()
        require(owner.isNotEmpty()) { "expects a project id" }
        val projectNotes = notes.values.filter { it.projectId == owner && it.scope == SCOPE_PROJECT }
        dir.mkdirs()
        val root = dir.canonicalPath + File.separator
        val written = HashSet<String>()
        projectNotes.forEach { note ->
            val target = File(dir, "${note.name}.md")
            if (!target.canonicalPath.startsWith(root)) return@forEach
            target.writeText(note.body)
            written += target.name
        }
        val index = projectNotes
            .filter { File(dir, "${it.name}.md").canonicalPath.startsWith(root) }
            .joinToString(separator = "\n", postfix = "\n") { note ->
                "- [${note.name}](${note.name}.md) — ${note.description}"
            }
        File(dir, INDEX_FILE_NAME).writeText(index)
        dir.listFiles { f -> f.isFile && f.name.endsWith(".md") && f.name != INDEX_FILE_NAME }?.forEach { stale ->
            if (stale.name !in written) stale.delete()
        }
    }

    /**
     * MEM-3: the project's notes whose name, description or body contain [query], as a ranked list of
     * [MemorySearchResult] (the body reduced to a snippet around the match). Scoped to project
     * [projectId]: a note of another project is never returned, so one project's memory cannot leak
     * into another's search.
     *
     * The narrowing is pushed to [MemoryPersistence] -- SQL `LIKE` for the durable surface -- so a
     * search does not load every project's notes. What stays here is the shaping: a note whose NAME
     * matches outranks one matching only its description, which outranks a body-only match; ties keep
     * the first-save order the surface returned them in (a stable sort).
     */
    @Synchronized
    fun search(projectId: String, query: String, scope: String = SCOPE_PROJECT, ownerId: String = ""): List<MemorySearchResult> {
        val owner = projectId.trim()
        val term = query.trim()
        // An empty term would match every note (`LIKE '%%'`), so it is refused rather than answered.
        if (owner.isEmpty() || term.isEmpty()) return emptyList()
        return persistence.search(owner, term)
            .filter { it.scope == scope && it.ownerId == ownerId.trim() }
            .sortedByDescending { rankOf(it, term) }
            .map { MemorySearchResult(name = it.name, description = it.description, snippet = snippetFor(it.body, term)) }
    }

    /** 2 when the name matches, 1 when only the description does, 0 for a body-only match. */
    private fun rankOf(note: MemoryNote, term: String): Int = when {
        note.name.contains(term, ignoreCase = true) -> 2
        note.description.contains(term, ignoreCase = true) -> 1
        else -> 0
    }

    /**
     * The fragment of [body] around [term], widened by [SNIPPET_RADIUS] on each side and marked with an
     * ellipsis where it was cut. A match that lives only in the name or description (so not in the
     * body at all) yields the body's head instead of an empty snippet.
     */
    private fun snippetFor(body: String, term: String): String {
        val index = body.indexOf(term, ignoreCase = true)
        if (index < 0) return body.take(SNIPPET_HEAD_LENGTH)
        val start = (index - SNIPPET_RADIUS).coerceAtLeast(0)
        val end = (index + term.length + SNIPPET_RADIUS).coerceAtMost(body.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < body.length) "…" else ""
        return prefix + body.substring(start, end) + suffix
    }

    private fun mintId(): String {
        repeat(10) {
            val candidate = idFactory()
            if (candidate !in notes) return candidate
        }
        error("could not find a free memory note id")
    }

    companion object {
        const val MAX_NAME_LENGTH = 200

        /** P10-K1: what `append` puts between the stored body and the new content -- exactly one blank line. */
        const val APPEND_SEPARATOR = "\n\n"

        fun occurrences(haystack: String, needle: String): Int = memoryOccurrences(haystack, needle)

        /** MEM-7: the mirror's index file name; a note may not be named after it (any case) or it would shadow the index. */
        const val INDEX_FILE_NAME = "INDEX.md"

        /** Characters of body kept on each side of a search match when building a snippet. */
        private const val SNIPPET_RADIUS = 40

        /** Body head kept when the match is not in the body at all (name/description match). */
        private const val SNIPPET_HEAD_LENGTH = 80

        /**
         * MEM-7: a note name is also its file name in the [export] mirror, so it must be safe as one.
         * A name carrying a path separator (`/`, `\`) or a `..` traversal would resolve outside the
         * mirror folder, and a name equal to the index's own stem (`INDEX`, any case) would overwrite
         * the generated `INDEX.md`. Each is refused here, at save time, so the store never holds a note
         * the export would have to skip or that would shadow its index.
         */
        private fun checkName(raw: String): String {
            val name = raw.trim()
            require(name.isNotEmpty()) { "name is blank" }
            require(name.length <= MAX_NAME_LENGTH) { "name is longer than $MAX_NAME_LENGTH characters" }
            require(name.none { it.isISOControl() }) { "name contains a control character" }
            require('/' !in name && '\\' !in name) { "name contains a path separator" }
            require(".." !in name) { "name contains a '..' path traversal" }
            require(!name.equals(INDEX_FILE_NAME.substringBeforeLast('.'), ignoreCase = true)) {
                "name is reserved for the mirror index ($INDEX_FILE_NAME)"
            }
            return name
        }
    }
}
