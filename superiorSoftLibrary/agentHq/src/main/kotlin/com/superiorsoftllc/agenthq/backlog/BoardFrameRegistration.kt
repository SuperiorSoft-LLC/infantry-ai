package com.superiorsoftllc.agenthq.backlog

import com.superiorsoftllc.agenthq.daemon.FrameTable
import com.superiorsoftllc.agenthq.daemon.SpawnGuard
import com.superiorsoftllc.agenthq.daemon.StartMoves
import com.superiorsoftllc.agenthq.daemon.TicketStartCharge
import com.superiorsoftllc.agenthq.epics.EpicHandler
import com.superiorsoftllc.agenthq.mvp.BoardHandler
import com.superiorsoftllc.agenthq.mvp.ProjectHandler
import com.superiorsoftllc.agenthq.mvp.StoryHandler
import com.superiorsoftllc.agenthq.mvp.WorkspaceHandler
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketLinkRequest
import com.superiorsoftllc.agenthq.protocol.TicketListQuery
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketReorder
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.session.SessionManager
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val boardJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Registers every board-family frame (tickets, stories, epics, projects, workspaces) into [frames]. */
fun registerBoardFrames(
    frames: FrameTable,
    board: TicketBoard,
    manager: SessionManager,
    spawnGuard: SpawnGuard,
    projects: ProjectHandler,
    workspaces: WorkspaceHandler,
    epics: EpicHandler,
    stories: StoryHandler,
    storyBoard: BoardHandler,
) {
    frames.register(Envelope.TICKET_SAVE) { e, direct -> boardRequest(e.type) { board.save(TicketSave.decode(e.data)).reply(direct) } }
    frames.register(Envelope.TICKET_GET) { e, _ -> boardRequest(e.type) { ticketEnvelope(board.get(e.data)) } }
    frames.register(Envelope.TICKET_LIST) { e, _ ->
        boardRequest(e.type) {
            // UI-BOARD-PROJ: a payload naming a project lists only its tickets; empty data keeps
            // the whole-board answer a legacy client expects. E1-b (1.66.0): a JSON object
            // `{projectId?, epicId?}` also filters by epic; an unknown epic answers an empty board.
            val query = TicketListQuery.decode(e.data)
            ticketsEnvelope(board.list(query.projectId, query.epicId))
        }
    }
    frames.register(Envelope.TICKET_MOVE) { e, direct ->
        boardRequest(e.type) {
            // BUG-MAIL-2: a move run from inside a session (`agenthq ticket move`, which names its session on
            // the frame) records that session as the ticket's starter. A UI move names none: nothing recorded.
            val starter = e.sessionId?.takeIf { manager.get(it) != null }
            // RUNTIME-LOAD-6: a start from inside a session is charged to its pod like a spawn (depth, pod cap).
            TicketStartCharge(spawnGuard, { board.asStartMoves() }, manager::get, manager::setSpawner)
                .move(TicketMove.decode(e.data), starter).reply(direct)
        }
    }
    frames.register(Envelope.TICKET_LINK) { e, direct ->
        boardRequest(e.type) { board.link(TicketLinkRequest.decode(e.data, e.type)).reply(direct) }
    }
    frames.register(Envelope.TICKET_UNLINK) { e, direct ->
        boardRequest(e.type) { board.unlink(TicketLinkRequest.decode(e.data, e.type)).reply(direct) }
    }
    frames.register(Envelope.TICKET_REORDER) { e, direct ->
        boardRequest(e.type) { board.reorder(TicketReorder.decode(e.data)).reply(direct) }
    }
    frames.register(Envelope.TICKET_DELETE) { e, direct -> boardRequest(e.type) { board.delete(e.data).reply(direct) } }
    frames.register(Envelope.TICKET_START_NEXT) { e, direct -> boardRequest(e.type) { board.startNext().reply(direct) } }

    frames.register(
        Envelope.PROJECT_SAVE, Envelope.PROJECT_GET, Envelope.PROJECT_LIST,
        // BASE-1a (contract 1.81.0): read, set or clear a project's base branch.
        Envelope.PROJECT_BASE,
        Envelope.PROJECT_CONFIG,
    ) { e, _ -> projects.answer(e) }
    frames.register(Envelope.WORKSPACE_SAVE, Envelope.WORKSPACE_LIST) { e, _ -> workspaces.answer(e) }
    // EPIC-4 / BASE-1c: a project's epics, and `epic_base` over the epic's project.
    frames.register(Envelope.EPIC_SAVE, Envelope.EPIC_LIST, Envelope.EPIC_BASE) { e, _ -> epics.answer(e) }
    frames.register(Envelope.STORY_SAVE, Envelope.STORY_GET, Envelope.STORY_MOVE) { e, _ -> stories.answer(e) }
    frames.register(Envelope.STORY_LIST) { e, _ -> storyBoard.answer(e) }
}

/** Contract 1.5.0: one ticket as a `ticket` frame, every field present. */
internal fun ticketEnvelope(ticket: Ticket): Envelope =
    Envelope(Envelope.TICKET, boardJson.encodeToString(Ticket.serializer(), ticket))

/** Contract 1.5.0: the whole board as a `tickets` frame, in board order. */
internal fun ticketsEnvelope(tickets: List<Ticket>): Envelope =
    Envelope(Envelope.TICKETS, boardJson.encodeToString(ListSerializer(Ticket.serializer()), tickets))

/** The requester's own answer, or null when the push to every client already carried it. */
private fun TicketAnswer.reply(direct: Boolean = false): Envelope? = if (pushed && !direct) null else ticketEnvelope(ticket)

private fun BoardAnswer.reply(direct: Boolean = false): Envelope? = if (pushed && !direct) null else ticketsEnvelope(tickets)

/**
 * Run one backlog request for frame [frame]: a refusal ([IllegalArgumentException], a malformed payload
 * included) answers `<frame> refused: <why>`; anything else unexpected answers `<frame> failed: <what>`.
 */
private inline fun boardRequest(frame: String, block: () -> Envelope?): Envelope? = try {
    block()
} catch (e: IllegalArgumentException) {
    Envelope(Envelope.ERROR, "$frame refused: ${e.message ?: "invalid request"}")
} catch (e: Exception) {
    Envelope(Envelope.ERROR, "$frame failed: ${e.message ?: e::class.simpleName}")
}

/** Adapts the board to the plain moves [TicketStartCharge] takes (it names no board package). */
internal fun TicketBoard.asStartMoves(): StartMoves<TicketAnswer> = object : StartMoves<TicketAnswer> {
    override fun get(id: String) = this@asStartMoves.get(id)
    override fun move(move: TicketMove, startedBy: String?) = this@asStartMoves.move(move, startedBy = startedBy)
    override fun ticketOf(answer: TicketAnswer) = answer.ticket
}
