// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.daemon.FrameTable
import com.superiorsoftllc.infantry.daemon.SpawnGuard
import com.superiorsoftllc.infantry.daemon.StartMoves
import com.superiorsoftllc.infantry.daemon.GuardSpawnCharge
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.FrameBinding
import com.superiorsoftllc.infantry.epics.EpicHandler
import com.superiorsoftllc.infantry.mvp.BoardHandler
import com.superiorsoftllc.infantry.mvp.ProjectHandler
import com.superiorsoftllc.infantry.mvp.StoryHandler
import com.superiorsoftllc.infantry.mvp.WorkspaceHandler
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.protocol.TicketLinkRequest
import com.superiorsoftllc.infantry.protocol.TicketListQuery
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketReorder
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.session.SessionManager
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val boardJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Registers every board-family frame into core's [frames] table. DP-5h-3a: the registration itself is the board's
 * (`registerBoardFrames` over `FrameBinding` + `SpawnCharge`, infantryBoard); this is only core's adapter from its
 * `FrameTable` and its spawn guard to those two ports.
 */
fun registerBoardFrames(
    frames: FrameTable,
    board: TicketBoard,
    manager: SessionManager,
    spawnGuard: SpawnGuard,
    epics: EpicHandler,
    stories: StoryHandler,
    storyBoard: BoardHandler,
) {
    val binding = FrameBinding { types, handler ->
        frames.register(*types.toTypedArray()) { e, direct ->
            handler(Frame(e.type, e.data, e.sessionId, e.requestId), direct)?.let { Envelope(it.type, it.data, it.sessionId, requestId = it.requestId) }
        }
    }
    registerBoardFrames(binding, board, GuardSpawnCharge(spawnGuard, manager), epics, stories, storyBoard)
}

/** DP-5h-2: project and workspace frames are core's: registered whether or not a board is wired. */
fun registerCoreProjectFrames(frames: FrameTable, projects: ProjectHandler, workspaces: WorkspaceHandler) {
    frames.register(
        Envelope.PROJECT_SAVE, Envelope.PROJECT_GET, Envelope.PROJECT_LIST,
        // BASE-1a (contract 1.81.0): read, set or clear a project's base branch.
        Envelope.PROJECT_BASE,
        Envelope.PROJECT_CONFIG,
    ) { e, _ -> projects.answer(e) }
    frames.register(Envelope.WORKSPACE_SAVE, Envelope.WORKSPACE_LIST) { e, _ -> workspaces.answer(e) }
}

/** Adapts the board to the plain moves [TicketStartCharge] takes (it names no board package). */
internal fun TicketBoard.asStartMoves(): StartMoves<TicketAnswer> = object : StartMoves<TicketAnswer> {
    override fun get(id: String) = this@asStartMoves.get(id)
    override fun move(move: TicketMove, startedBy: String?) = this@asStartMoves.move(move, startedBy = startedBy)
    override fun ticketOf(answer: TicketAnswer) = answer.ticket
}
