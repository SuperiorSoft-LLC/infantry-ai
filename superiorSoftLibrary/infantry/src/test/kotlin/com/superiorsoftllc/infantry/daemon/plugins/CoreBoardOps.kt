// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.backlog.PromptLaunch
import com.superiorsoftllc.infantry.backlog.TicketBoard
import com.superiorsoftllc.infantry.daemon.api.board.PromptStart
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorLink
import com.superiorsoftllc.infantry.epics.Epic
import com.superiorsoftllc.infantry.epics.EpicStore
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.protocol.TicketLinkRequest
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketReorder
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.session.SessionRecord

/** DP-5h-4: test fixture. [BoardOps] over an in-process [TicketBoard] and [EpicStore] (core no longer builds a board; the plugin does). */
class CoreBoardOps(
    private val board: TicketBoard,
    private val epics: EpicStore,
    private val baseCheck: ((Epic, String) -> Unit)? = null,
) : BoardOps {
    override fun list(projectId: String?, epicId: String?) = board.list(projectId, epicId)
    override fun get(id: String) = board.get(id)
    override fun save(request: TicketSave) = board.save(request).ticket
    override fun move(request: TicketMove, startedBy: String?) = board.move(request, startedBy = startedBy).ticket
    override fun delete(id: String) { board.delete(id) }
    override fun link(request: TicketLinkRequest) = board.link(request).ticket
    override fun unlink(request: TicketLinkRequest) = board.unlink(request).ticket
    override fun reorder(request: TicketReorder) { board.reorder(request) }
    override fun startNext(agentFilter: String?) = board.startNextUnattended(agentFilter)?.ticket
    override fun startPrompt(agentId: String, prompt: String, projectId: String?): PromptStart =
        when (val l = board.startPromptUnattended(agentId, prompt, projectId)) {
            is PromptLaunch.Started -> PromptStart.Started(l.sessionId)
            is PromptLaunch.Refused -> PromptStart.Refused(l.detail)
        }
    override fun brief(sessionId: String) = board.brief(sessionId)
    override fun epicGet(id: String) = epics.get(id)
    override fun epicList(projectId: String) = epics.list(projectId)
    override fun epicSave(projectId: String, name: String, goal: String) = epics.save(projectId, name, goal)
    override fun epicSetBase(id: String, branch: String?): Epic {
        if (branch == null) return epics.setBaseBranch(id, null)
        val check = baseCheck ?: throw IllegalArgumentException("this daemon has no repository to check a base branch against")
        return epics.setBaseBranch(id, branch, check)
    }
}

/** The services over an in-process board (what the removed secondary constructor did). */
fun LocalBoardServices(
    board: () -> TicketBoard?, epicStore: () -> EpicStore?, sessions: () -> List<SessionRecord>, link: () -> OrchestratorLink?,
    gate: (type: String, data: String) -> String?, baseCheck: ((Epic, String) -> Unit)? = null,
    chargedMove: ((TicketMove, String?) -> Ticket)? = null,
) = LocalBoardServices(
    ops = { val b = board(); val e = epicStore(); if (b != null && e != null) CoreBoardOps(b, e, baseCheck) else null },
    sessions = sessions, link = link, gate = gate, chargedMove = chargedMove,
)
