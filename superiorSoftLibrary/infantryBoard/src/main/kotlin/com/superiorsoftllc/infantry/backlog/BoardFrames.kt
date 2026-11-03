// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.FrameBinding
import com.superiorsoftllc.infantry.daemon.api.spawns.SpawnCharge
import com.superiorsoftllc.infantry.epics.EpicHandler
import com.superiorsoftllc.infantry.mvp.BoardHandler
import com.superiorsoftllc.infantry.mvp.StoryHandler
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.protocol.TicketLinkRequest
import com.superiorsoftllc.infantry.protocol.TicketListQuery
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketUnassign
import com.superiorsoftllc.infantry.protocol.AgentQueueRequest
import com.superiorsoftllc.infantry.protocol.AgentSwitch
import com.superiorsoftllc.infantry.protocol.TicketAssign
import com.superiorsoftllc.infantry.protocol.AgentQueueState
import com.superiorsoftllc.infantry.protocol.TicketReorder
import com.superiorsoftllc.infantry.protocol.TicketSave
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val boardJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * DP-5h-3a: a session-originated ticket start charged through the [SpawnCharge] port (depth, pod cap), board side: the port
 * is what `TicketStartCharge` + `SpawnGuard` were, so a board plugin needs neither. A move by nobody, or one that runs no worker, is not charged.
 */
class BoardStartCharge(private val charge: SpawnCharge, private val board: TicketBoard) {
    fun move(move: TicketMove, startedBy: String?): TicketAnswer {
        val starter = startedBy?.trim()?.takeIf { it.isNotEmpty() && charge.isSession(it) }
        return charge.admitIf(starter, starter != null && board.runsWorker(move)) { placed ->
            board.move(move, startedBy = starter).also { answer ->
                val worker = answer.ticket.takeIf { it.start?.ok == true }?.sessionId
                if (worker != null && starter != null) {
                    if (placed) charge.record(worker, starter)
                    charge.setSpawner(worker, starter)
                }
            }
        }
    }
}

/**
 * Registers every board-family frame (tickets, stories, epics) through the plugin API's [FrameBinding]. No core type: the
 * start charge is the [SpawnCharge] port, the frames are typed [Frame]s.
 */
fun registerBoardFrames(
    frames: FrameBinding,
    board: TicketBoard,
    charge: SpawnCharge,
    epics: EpicHandler?,
    stories: StoryHandler?,
    storyBoard: BoardHandler?,
) {
    fun reg(vararg types: String, h: (Envelope, Boolean) -> Envelope?) =
        frames.register(*types) { f, direct ->
            h(Envelope(f.type, f.data, f.sessionId, requestId = f.requestId), direct)?.let { Frame(it.type, it.data, it.sessionId, it.requestId) }
        }
    val startCharge = BoardStartCharge(charge, board)
    reg(Envelope.TICKET_SAVE) { e, direct -> boardRequest(e.type) { board.save(TicketSave.decode(e.data)).reply(direct) } }
    reg(Envelope.TICKET_GET) { e, _ -> boardRequest(e.type) { ticketEnvelope(board.get(e.data)) } }
    reg(Envelope.TICKET_LIST) { e, _ ->
        boardRequest(e.type) {
            // UI-BOARD-PROJ / E1-b: a payload naming a project (and/or epic) lists only its tickets; empty data = the whole board.
            val query = TicketListQuery.decode(e.data)
            ticketsEnvelope(board.list(query.projectId, query.epicId))
        }
    }
    reg(Envelope.TICKET_MOVE) { e, direct ->
        boardRequest(e.type) {
            // BUG-MAIL-2: a move run from inside a session records that session as the ticket's starter; RUNTIME-LOAD-6: charged to its pod.
            startCharge.move(TicketMove.decode(e.data), e.sessionId).reply(direct)
        }
    }
    reg(Envelope.TICKET_UNASSIGN) { e, direct ->
        boardRequest(e.type) {
            val req = TicketUnassign.decode(e.data)
            board.unassign(req.id, req.now, req.note).reply(direct)
        }
    }
    reg(Envelope.TICKET_ASSIGN) { e, direct ->
        boardRequest(e.type) {
            val req = TicketAssign.decode(e.data)
            board.assign(req.id, req.agentId, if (req.queue) AssignMode.QUEUE else AssignMode.START, req.at).reply(direct)
        }
    }
    reg(Envelope.AGENT_SWITCH) { e, direct ->
        boardRequest(e.type) {
            val req = AgentSwitch.decode(e.data)
            board.switch(req.agentId, req.ticketId, req.now).reply(direct)
        }
    }
    reg(Envelope.AGENT_QUEUE) { e, _ ->
        boardRequest(e.type) {
            val req = AgentQueueRequest.decode(e.data)
            val state = board.queue(req.agentId, req.action, req.ticketId, req.at)
            Envelope(Envelope.AGENT_QUEUE, kotlinx.serialization.json.Json.encodeToString(AgentQueueState.serializer(), state))
        }
    }
    reg(Envelope.TICKET_LINK) { e, direct -> boardRequest(e.type) { board.link(TicketLinkRequest.decode(e.data, e.type)).reply(direct) } }
    reg(Envelope.TICKET_UNLINK) { e, direct -> boardRequest(e.type) { board.unlink(TicketLinkRequest.decode(e.data, e.type)).reply(direct) } }
    reg(Envelope.TICKET_REORDER) { e, direct -> boardRequest(e.type) { board.reorder(TicketReorder.decode(e.data)).reply(direct) } }
    reg(Envelope.TICKET_DELETE) { e, direct -> boardRequest(e.type) { board.delete(e.data).reply(direct) } }
    reg(Envelope.TICKET_START_NEXT) { e, direct -> boardRequest(e.type) { board.startNext().reply(direct) } }
    // Epics and the story shims need the epic store and the story migration table (DP-5h-3b): a null handler leaves those frames unregistered.
    if (epics != null) reg(Envelope.EPIC_SAVE, Envelope.EPIC_LIST, Envelope.EPIC_BASE) { e, _ -> epics.answer(e) }
    if (stories != null) reg(Envelope.STORY_SAVE, Envelope.STORY_GET, Envelope.STORY_MOVE) { e, _ -> stories.answer(e) }
    if (storyBoard != null) reg(Envelope.STORY_LIST) { e, _ -> storyBoard.answer(e) }
}

/** Contract 1.5.0: one ticket as a `ticket` frame, every field present. */
fun ticketEnvelope(ticket: Ticket): Envelope = Envelope(Envelope.TICKET, boardJson.encodeToString(Ticket.serializer(), ticket))

/** Contract 1.5.0: the whole board as a `tickets` frame, in board order. */
fun ticketsEnvelope(tickets: List<Ticket>): Envelope = Envelope(Envelope.TICKETS, boardJson.encodeToString(ListSerializer(Ticket.serializer()), tickets))

private fun TicketAnswer.reply(direct: Boolean = false): Envelope? = if (pushed && !direct) null else ticketEnvelope(ticket)

private fun BoardAnswer.reply(direct: Boolean = false): Envelope? = if (pushed && !direct) null else ticketsEnvelope(tickets)

/** Run one backlog request for frame [frame]: a refusal answers `<frame> refused: <why>`, anything else `<frame> failed: <what>`. */
private inline fun boardRequest(frame: String, block: () -> Envelope?): Envelope? = try {
    block()
} catch (e: IllegalArgumentException) {
    Envelope(Envelope.ERROR, "$frame refused: ${e.message ?: "invalid request"}")
} catch (e: Exception) {
    Envelope(Envelope.ERROR, "$frame failed: ${e.message ?: e::class.simpleName}")
}
