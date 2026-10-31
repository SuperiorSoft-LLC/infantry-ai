package com.superiorsoftllc.agenthq.mvp

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Story
import com.superiorsoftllc.agenthq.protocol.StoryMove
import com.superiorsoftllc.agenthq.protocol.StorySave
import com.superiorsoftllc.agenthq.protocol.StoryStatus
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.stories.StoryMigration
import kotlinx.serialization.json.Json

/**
 * DP-5g: the ticket operations the story shims need. The core's TicketBoard adapts to it (it is not in this module yet);
 * every answer is the ticket itself, and [moveWithoutStart] is a plain column change (no hand-out, no run).
 */
interface StoryTickets {
    fun get(id: String): Ticket
    fun save(request: TicketSave): Ticket
    fun moveWithoutStart(request: TicketMove): Ticket
}

/**
 * E1-c (contract 1.62.0): `story_save` / `story_get` / `story_move` are DEPRECATED SHIMS over the ticket board.
 *
 * There is one work model now: a story IS a ticket. Every story frame reads and writes [TicketBoard], and every
 * write is pushed to clients as the ordinary `ticket` frame the board already pushes, so a story saved here is
 * visible on `tickets` with no extra step. The `story` frame these answer is the ticket seen through the old
 * shape ([asStory]): the story's id IS the ticket id.
 *
 * - `story_save` with no id creates a ticket in `todo` (unassigned, as the E1-a migration does, so nothing
 *   starts it by itself); with an id it edits the title/project of the ticket that id resolves to.
 * - `story_get` / `story_move` take a ticket id OR a pre-migration story id: [resolve] maps the latter through
 *   the `story_migration` table (E1-a), and a raw id that maps to nothing is used as a ticket id as it is.
 * - `story_move doing` puts the ticket in `in_progress` as a plain column change (no hand-out, no run): a move
 *   was always a status change and nothing more.
 *
 * The epic a create names is the ticket's own [Ticket.epicId]; an epic's `storyIds` are derived from the board
 * ([com.superiorsoftllc.agenthq.epics.EpicStore]), so this handler only reports the epic that changed through
 * [onEpicChanged], for the daemon to push.
 */
class StoryHandler(
    private val board: StoryTickets,
    /** Pre-migration story id -> ticket id, or null when the id is not an old story's. */
    private val resolve: (String) -> String? = { null },
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    /** Called with the epic id a CREATE named, after the ticket committed; the daemon pushes that epic's frame. */
    private val onEpicChanged: (String) -> Unit = {},
) {
    /** Answer one frame, or null when the frame is not this handler's. */
    fun answer(envelope: Envelope): Envelope? = when (envelope.type) {
        Envelope.STORY_SAVE -> storyRequest(envelope.type) {
            val request = StorySave.decode(envelope.data)
            val editing = request.id?.trim()?.takeIf { it.isNotEmpty() }
            val saved = if (editing == null) {
                require(!request.title.isNullOrBlank()) { "title is required to create a story" }
                require(!request.projectId.isNullOrBlank()) {
                    "projectId is required to create a story: a story belongs to one project"
                }
                val epicId = request.epicId?.trim()?.takeIf { it.isNotEmpty() }
                board.save(
                    TicketSave(title = request.title, projectId = request.projectId, epicId = epicId, agentId = ""),
                ).also { if (epicId != null) onEpicChanged(epicId) }
            } else {
                board.save(TicketSave(id = ticketId(editing), title = request.title, projectId = request.projectId))
            }
            storyEnvelope(saved.asStory())
        }

        // The id travels as the raw payload, not as JSON, exactly as `ticket_get` and `project_get` do.
        Envelope.STORY_GET -> storyRequest(envelope.type) {
            storyEnvelope(board.get(ticketId(envelope.data)).asStory())
        }

        Envelope.STORY_MOVE -> storyRequest(envelope.type) {
            val request = StoryMove.decode(envelope.data)
            val id = ticketId(request.id)
            val ticket = request.status?.let { status ->
                require(status in StoryStatus.ALL) {
                    "status '${status.take(40)}' is not one of ${StoryStatus.ALL.joinToString(", ")}"
                }
                board.moveWithoutStart(TicketMove(id = id, status = StoryMigration.ticketStatus(status)))
            } ?: board.get(id)
            storyEnvelope(ticket.asStory())
        }

        else -> null
    }

    /** One story as a `story` frame, every field present. */
    fun storyEnvelope(story: Story): Envelope =
        Envelope(Envelope.STORY, json.encodeToString(Story.serializer(), story))

    /** The ticket an incoming story id names: an old story id through `story_migration`, else the id itself. */
    private fun ticketId(raw: String): String {
        val id = raw.trim()
        require(id.isNotEmpty()) { "expects a story id" }
        val mapped = resolve(id) ?: id
        require(runCatching { board.get(mapped) }.isSuccess) { "no story '${id.take(80)}'" }
        return mapped
    }

    private inline fun storyRequest(frame: String, block: () -> Envelope): Envelope = try {
        block()
    } catch (e: IllegalArgumentException) {
        Envelope(Envelope.ERROR, "$frame refused: ${e.message ?: "invalid request"}")
    } catch (e: Exception) {
        Envelope(Envelope.ERROR, "$frame failed: ${e.message ?: e::class.simpleName}")
    }

    companion object {
        /** Ticket column -> story column: `in_progress` reads as `doing`; a held (`pending`) ticket has not started. */
        fun storyStatus(ticketStatus: String): String = when (ticketStatus) {
            TicketStatus.IN_PROGRESS -> StoryStatus.DOING
            TicketStatus.DONE -> StoryStatus.DONE
            else -> StoryStatus.TODO
        }

        /** A ticket seen through the deprecated story shape; the story id IS the ticket id. */
        fun Ticket.asStory(): Story = Story(id = id, projectId = projectId ?: "", title = title, status = storyStatus(status))
    }
}
