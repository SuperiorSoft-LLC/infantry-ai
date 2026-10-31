package com.superiorsoftllc.agenthq.stories

import com.superiorsoftllc.agenthq.protocol.Story
import com.superiorsoftllc.agenthq.protocol.StoryMove
import com.superiorsoftllc.agenthq.protocol.StorySave
import com.superiorsoftllc.agenthq.protocol.StoryStatus
import java.util.UUID

/**
 * MVP Sprint-Zero, slice B -- the STORY board: the minimum rules over the frozen seam of slice 0.
 *
 * Slice 0 (`b2fbb94d`) froze `Story` / `StorySave` / `StoryMove` and the [StoryStatus] vocabulary in
 * `protocol/Stories.kt`; these classes are the first behaviour behind those names. The corrected seam
 * (`0822d8f`, "align MVP Sprint-Zero seam on Project/Story") makes a story deliberately minimal --
 * `{ id, projectId, title, status }` and NOTHING else -- and every rule here honours that:
 *
 * - A create needs a **title** and the **project** the story belongs to, and lands in
 *   [StoryStatus.TODO]. The daemon chooses the id.
 * - An edit leaves a null field as it is. An unknown id is refused, so an edit racing a delete never
 *   brings the story back.
 * - A **status is one of [StoryStatus.ALL] and nothing else**: an unknown one is REFUSED, never
 *   silently normalised to the nearest column.
 * - A story carries **no place of its own**. There is no `position` field to renumber and no
 *   `updatedAt` to stamp, so a move is a status change and nothing more.
 *
 * THERE IS NO ORDERING HERE, ON PURPOSE. Where a story sits inside its column, when it has one,
 * comes from [com.superiorsoftllc.agenthq.epics.Epic.storyIds] (RETIRED, contract 1.29.0: this used
 * to be `Project.storyIds`, which slice A's store owned and I0 maintained). This class owns
 * [Story] rows only; deriving an order from them would write another store's field from a store
 * that does not own it, and the two halves would drift. A board listing that needs a within-column
 * order therefore composes this store with the epic's list -- it does not read one back out of it.
 *
 * ## The seam, and the two halves behind it
 *
 * [StoryStore] is deliberately an INTERFACE with two implementations, the
 * [com.superiorsoftllc.agenthq.session.SessionStore] pattern in this module:
 * [InMemoryStoryStore] for a process that must not touch the disk, and [JsonStoryStore] for the
 * daemon, so a restart does not lose the board. The seam is the three operations -- a caller names
 * the type it needs, and swapping one for the other touches a construction site, not a caller.
 *
 * ## WHY NOT SQLITE, and when that changes
 *
 * The `stories` table has NOT been added to the schema, deliberately: taking the shared
 * `AgentHQDatabase.Schema.version` bump (v7 -> v8) belongs to the integrating slice I0, and slice
 * A's `projects` migration will need the SAME bump and the SAME migration-chain step, because
 * `Schema.version` is the number of `.sqm` files plus one -- it cannot be taken twice. A JSON file
 * gives B2 the restart persistence this leaf owns without moving a number two slices also depend on.
 * When I0 takes the shared bump the draft, SCOPED TO STORY STORAGE ONLY, is one table:
 *
 * ```sql
 * -- NEXT MIGRATION (I0 owns the number): vN -> vN+1, the MVP story board.
 * CREATE TABLE stories (
 *     id TEXT NOT NULL PRIMARY KEY,
 *     project_id TEXT NOT NULL,
 *     title TEXT NOT NULL,
 *     -- todo, doing or done. TEXT with no CHECK, exactly like tickets.status: a column added
 *     -- later must not need a table rebuild, and the daemon validates the vocabulary anyway.
 *     status TEXT NOT NULL,
 *     created_at INTEGER NOT NULL
 * );
 * CREATE INDEX index_stories_on_project_id ON stories(project_id, created_at);
 * ```
 *
 * `created_at` is the one column the wire does not carry: it orders a story inside its project
 * (creation order) for the listing, which was the MVP's order of record until I0 settled
 * `Project.storyIds` (RETIRED, contract 1.29.0: that ordering now lives on `Epic.storyIds`
 * instead). `project_id` has no foreign key to `projects` for the same reason as above --
 * the table does not exist yet either, and a join enforced before both halves land would freeze
 * slice A into B's migration.
 *
 * DELIBERATELY NOT [com.superiorsoftllc.agenthq.protocol.TicketStatus]: the story board's three
 * columns are the MVP's whole vocabulary and an MVP story never moves itself onto the daemon's own
 * backlog, which is a different board with a different life.
 */
interface StoryStore {
    /** Create a story (no [StorySave.id]) or edit one (see [StorySave]). */
    fun save(request: StorySave): Story

    /** Story [id]; throws [IllegalArgumentException] when there is none. */
    fun get(id: String): Story

    /** Put story [StoryMove.id] in column [StoryMove.status] (null = stay in its column). */
    fun move(request: StoryMove): Story

    /**
     * Every story the store holds, in creation order -- a READ, and the only one this interface
     * carries.
     *
     * Added by I0 (integration) because the board LISTING needs it: `story_list` answers over the
     * daemon's own store, so the rows it lists must be the rows the daemon actually holds -- a board
     * written before a restart is listed after one. Nothing here can write: the write path stays
     * [save] / [move], and a listing that needs an order WITHIN a column reads the epic's
     * [com.superiorsoftllc.agenthq.epics.Epic.storyIds], which this store does not own.
     */
    fun stories(): List<Story>
}

/**
 * Every rule above, held in a [LinkedHashMap]: the half for a process that must not touch the disk.
 *
 * This is leaf B1's whole scope, kept as the in-memory half now that a durable one exists, so a
 * rule is written once and both halves meet it. It is also the delegate [JsonStoryStore] hydrates
 * and flushes, which is why [save] / [get] / [move] are `open`: [JsonStoryStore] is the same class
 * of behaviour plus one write, never a second place where the rules can drift.
 */
open class InMemoryStoryStore(
    private val idFactory: () -> String = { "story-" + UUID.randomUUID().toString().take(8) },
) : StoryStore {

    /**
     * Every story by id, in creation order. The map IS the store: no second source of truth to drift,
     * and a reader that walks it sees the board exactly as it is.
     */
    protected val stories = LinkedHashMap<String, Story>()

    /** Create a story (no [StorySave.id]) or edit one (see [StorySave]). */
    @Synchronized
    override fun save(request: StorySave): Story {
        val editing = request.id?.trim()?.takeIf { it.isNotEmpty() }
        val title = request.title?.let { checkTitle(it) }
        val projectId = request.projectId?.let { checkProjectId(it) }

            if (editing != null) {
                val existing = stories[editing] ?: throw IllegalArgumentException(
                    "no story '${editing.take(80)}' (story_save creates a story only when it carries no id)",
                )
                val updated = existing.copy(
                    projectId = projectId ?: existing.projectId,
                    title = title ?: existing.title,
                )
                // An edit that says nothing new wrote nothing, so a durable half need not rewrite.
                if (updated != existing) {
                    stories[editing] = updated
                    afterWrite()
                }
                return stories.getValue(editing)
            }

        // A create is a join, not a title: a story that belongs to no project would be unreadable later.
        require(title != null) { "title is required to create a story" }
        require(projectId != null) { "projectId is required to create a story: a story belongs to one project" }
        val id = newId()
        stories[id] = Story(id = id, projectId = projectId, title = title, status = StoryStatus.TODO)
        afterWrite()
        return stories.getValue(id)
    }

    /** Story [id]; throws [IllegalArgumentException] when there is none. */
    @Synchronized
    override fun get(id: String): Story {
        val wanted = checkId(id)
        return stories[wanted] ?: throw IllegalArgumentException("no story '${wanted.take(80)}'")
    }

    /**
     * Put story [StoryMove.id] in column [StoryMove.status] (null = stay in its column).
     *
     * An unknown status is refused BEFORE anything is written, so a refused move leaves the story
     * exactly where it was -- same column, same title. There is no place to disturb: a story's order
     * inside its column, when it has one, belongs to [com.superiorsoftllc.agenthq.epics.Epic.storyIds].
     */
    @Synchronized
    override fun move(request: StoryMove): Story {
        val id = checkId(request.id)
        val existing = stories[id] ?: throw IllegalArgumentException("no story '${id.take(80)}'")
        val status = request.status?.let { checkStatus(it) } ?: existing.status
        if (status == existing.status) return existing
        stories[id] = existing.copy(status = status)
        afterWrite()
        return stories.getValue(id)
    }

    /**
     * Every story, in creation order, as a copy: the reader cannot disturb the board through it.
     *
     * The [LinkedHashMap] is already insertion ordered, so this is the order the board was written
     * in -- the same order [JsonStoryStore] writes to its snapshot and reads back, which is what
     * makes a listing identical before and after a restart. Deliberately a SNAPSHOT rather than a
     * live view: a board listing answers one question at one moment, and handing out the live map
     * would let a reader race a writer mid-flush.
     */
    @Synchronized
    override fun stories(): List<Story> = stories.values.toList()

    /**
     * Called the moment a row really changed, always from inside the operation's lock. The in-memory
     * half has nothing to do afterwards; [JsonStoryStore] rewrites the board here, which is how one
     * set of rules serves both halves without a second copy of the rules.
     */
    protected open fun afterWrite() = Unit

    /** A free id the store has not used yet; [idFactory] ten times, then give up loud. */
    protected fun newId(): String {
        repeat(10) {
            val candidate = idFactory()
            if (candidate !in stories) return candidate
        }
        error("could not find a free story id")
    }

    companion object {
        const val MAX_TITLE_LENGTH = 200

        fun checkId(raw: String): String {
            val id = raw.trim()
            require(id.isNotEmpty()) { "expects a story id" }
            return id
        }

        fun checkTitle(raw: String): String {
            val title = raw.trim()
            require(title.isNotEmpty()) { "title is blank" }
            require(title.length <= MAX_TITLE_LENGTH) { "title is longer than $MAX_TITLE_LENGTH characters" }
            require(title.none { it.isISOControl() }) { "title contains a control character" }
            return title
        }

        fun checkProjectId(raw: String): String {
            val projectId = raw.trim()
            require(projectId.isNotEmpty()) { "projectId is blank" }
            return projectId
        }

        /**
         * The one entry point to the board's vocabulary: a status the frozen [StoryStatus.ALL] does
         * not spell is refused, naming what was asked for and every column there is.
         *
         * It answers a status that IS in the vocabulary or throws -- it never answers something else,
         * which is what "never silently normalised" means in practice: an unknown spelling can never
         * come back out of this function wearing a known column's name.
         */
        fun checkStatus(raw: String): String {
            val status = raw.trim().lowercase()
            require(status in StoryStatus.ALL) {
                "status '${status.take(40)}' is not a column: ${StoryStatus.ALL.joinToString(", ")}"
            }
            return status
        }
    }
}
