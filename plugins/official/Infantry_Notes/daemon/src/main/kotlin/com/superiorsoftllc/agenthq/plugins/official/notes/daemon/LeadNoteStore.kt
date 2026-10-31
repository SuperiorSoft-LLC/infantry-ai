package com.superiorsoftllc.agenthq.plugins.official.notes.daemon

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

/**
 * MVP 7: one lead note -- the observation a lead records while running one story of one project.
 *
 * DELIBERATELY MINIMAL, for the same reason [com.superiorsoftllc.agenthq.protocol.Story] is: Sprint-Zero
 * wants to know whether a lead's notes are worth keeping, not to ship a notes application. A note
 * carries its own id, the id of the [com.superiorsoftllc.agenthq.protocol.Project] it belongs to and
 * its body, and nothing else -- no priority, no tags, no timestamps. Ordering is the store's
 * insertion order, which is what a lead reads back.
 *
 * [projectId] is a PLAIN STRING, and this store deliberately holds no [com.superiorsoftllc.agenthq.protocol.Project]
 * and calls no project store: the slice that owns projects (MVP slice A) is built separately, and a
 * lead note never has to wait for it. The join happens at integration, exactly as slice B's tests
 * pass a plain project-id string rather than sending `project_save`.
 */
@Serializable
data class LeadNote(
    val id: String,
    val projectId: String,
    val body: String,
)

/**
 * `save` a lead note: create one (no [id]) or edit one (the [id] of an existing note).
 *
 * Shaped after [com.superiorsoftllc.agenthq.protocol.StorySave]: a create needs a [projectId] and a
 * [body], and the store chooses the id; an edit leaves a null field as it is. An unknown [id] is
 * REFUSED, so an edit racing a delete never brings a note back. [projectId] is not editable: a note
 * never moves to another project, which is what makes "one project's notes" a closed set.
 */
@Serializable
data class LeadNoteSave(
    val id: String? = null,
    val projectId: String? = null,
    val body: String? = null,
)

/**
 * MVP 7: the lead-notes storage API, keyed by project id.
 *
 * An INTERFACE with two implementations, the way [com.superiorsoftllc.agenthq.session.SessionStore] is
 * one interface with one on-disk implementation: every rule of the store lives in
 * [InMemoryLeadNoteStore], and the durable half only has to answer "what do I do when a note really
 * changed". Swapping the JSON file for a database later touches the durable half and its construction
 * site, not the rules and not [LeadNoteStore]'s callers.
 *
 * CONTRACT: implementations must be safe to call from several threads and must never lose a saved
 * note because one write failed.
 */
interface LeadNoteStore {
    /** Create or edit a note; the stored note. Throws [IllegalArgumentException] naming what is wrong. */
    fun save(request: LeadNoteSave): LeadNote

    /** Note [id]; throws [IllegalArgumentException] when there is none. */
    fun get(id: String): LeadNote

    /** Every note of project [projectId], in insertion order; empty when that project holds none. */
    fun list(projectId: String): List<LeadNote>
}

/**
 * The rules of the lead-notes store, in memory: id generation, what a create needs, what an edit may
 * change, and the refusal for an unknown id.
 *
 * NOT thread-safe on its own -- callers reach it through [LeadNoteStore.save]/[get]/[list], which are
 * `@Synchronized`. [afterWrite] is the persistence seam: it fires ONLY when a note really changed, so
 * a no-op save rewrites nothing and a refused save (thrown before anything is stored) leaves the
 * durable half alone. [restore] and [all] are how the durable half reads back and writes out without
 * going through the rules.
 */
open class InMemoryLeadNoteStore(
    private val idFactory: () -> String = { "note-" + UUID.randomUUID().toString().take(8) },
) : LeadNoteStore {

    private val notes = LinkedHashMap<String, LeadNote>()

    @Synchronized
    override fun save(request: LeadNoteSave): LeadNote {
        val editing = request.id?.trim()?.takeIf { it.isNotEmpty() }
        val projectId = request.projectId?.trim()?.takeIf { it.isNotEmpty() }
        val body = request.body?.let { checkBody(it) }
        if (editing == null) {
            require(projectId != null) { "projectId is required to save a lead note" }
            require(body != null) { "body is required to save a lead note" }
        }
        val existing = editing?.let { wanted ->
            notes[wanted] ?: throw IllegalArgumentException(
                "no lead note '${wanted.take(80)}' (a save creates a note only when it carries no id)",
            )
        }
        if (projectId != null && existing != null && projectId != existing.projectId) {
            throw IllegalArgumentException(
                "lead note ${existing.id} belongs to project '${existing.projectId}': " +
                    "a note never moves to another project",
            )
        }
        val note = LeadNote(
            id = editing ?: newId(),
            projectId = existing?.projectId ?: projectId!!,
            body = body ?: existing?.body!!,
        )
        val before = existing
        notes[note.id] = note
        if (note != before) afterWrite()
        return note
    }

    @Synchronized
    override fun get(id: String): LeadNote {
        val wanted = checkId(id)
        return notes[wanted] ?: throw IllegalArgumentException("no lead note '${wanted.take(80)}'")
    }

    @Synchronized
    override fun list(projectId: String): List<LeadNote> {
        val wanted = checkProjectId(projectId)
        return notes.values.filter { it.projectId == wanted }
    }

    /** Every note the store holds, in insertion order. */
    protected open fun all(): List<LeadNote> = notes.values.toList()

    /** Seed the store with rows read back off disk. Fires no [afterWrite]: a restore is not a write. */
    protected open fun restore(rows: List<LeadNote>) {
        rows.forEach { notes[it.id] = it }
    }

    /** Called after a mutation that really changed the store. */
    protected open fun afterWrite() {}

    private fun newId(): String {
        repeat(10) {
            val candidate = idFactory()
            if (candidate !in notes) return candidate
        }
        error("could not find a free lead note id")
    }

    companion object {
        const val MAX_BODY_LENGTH = 100_000

        private fun checkId(raw: String): String {
            val id = raw.trim()
            require(id.isNotEmpty()) { "expects a lead note id" }
            return id
        }

        private fun checkProjectId(raw: String): String {
            val id = raw.trim()
            require(id.isNotEmpty()) { "expects a project id" }
            return id
        }

        private fun checkBody(raw: String): String {
            require(raw.isNotBlank()) { "body is blank" }
            require(raw.length <= MAX_BODY_LENGTH) { "body is longer than $MAX_BODY_LENGTH characters" }
            // A note is prose, so newline/tab are part of the text; every other control character is not.
            require(raw.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
                "body contains a control character"
            }
            return raw
        }
    }
}

/**
 * The durable half: [InMemoryLeadNoteStore]'s rules, backed by one JSON file.
 *
 * Same deal as [com.superiorsoftllc.agenthq.session.JsonSessionStore], which is why there is no SQL
 * migration here either -- a whole-file JSON store with a `.tmp` sibling moved into place, so a crash
 * mid-write cannot truncate the real file, and the state lives in the daemon state dir so a `git clean` cannot
 * erase a project's notes.
 *
 * A file that is absent opens as an empty store and is not created until the first save. A file that
 * cannot be read -- truncated mid-array, not JSON at all, or valid JSON with no `notes` array -- opens
 * as an empty store too, and says so on stderr: losing old notes must never stop the daemon from
 * starting. The next save rewrites the whole file, so a corrupt store repairs itself on the first
 * write rather than staying broken forever.
 */
class JsonLeadNoteStore(private val file: File) : InMemoryLeadNoteStore() {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class Snapshot(
        val version: Int = 1,
        val notes: List<LeadNote> = emptyList(),
    )

    init {
        restore(readNotes())
    }

    override fun afterWrite() {
        val text = json.encodeToString(Snapshot.serializer(), Snapshot(notes = all()))
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            // Some filesystems (and OneDrive-synced folders) refuse an atomic move; a plain replace
            // is still better than leaving only the .tmp behind.
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readNotes(): List<LeadNote> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val array = root["notes"] as? JsonArray ?: return emptyList()
            array.map { json.decodeFromJsonElement(LeadNote.serializer(), it) }
        }.getOrElse {
            System.err.println("[agentHq] could not read lead note store ${file.path}: ${it.message}")
            emptyList()
        }
    }

    companion object {
    }
}
