// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.persistence

import com.superiorsoftllc.infantry.daemon.api.board.StoredLink
import com.superiorsoftllc.infantry.daemon.api.board.StoredTicket
import com.superiorsoftllc.infantry.persistence.TicketLinkRow
import com.superiorsoftllc.infantry.persistence.TicketRow

/** DP-5f-3a: the TicketRow <-> StoredTicket adapter, shared with `TicketBoard`. */
fun StoredTicket.toRow() = TicketRow(
    id = id, title = title, description = description, ticketType = ticketType, status = status, position = position,
    wontFix = wontFix, createdAt = createdAt, updatedAt = updatedAt, agentId = agentId, provider = provider, model = model,
    useWorktree = useWorktree, worktreeBranch = worktreeBranch, includeLinkedTasksContext = includeLinkedTasksContext,
    queuedFor = queuedFor, queuedAt = queuedAt, sessionId = sessionId, projectId = projectId, epicId = epicId, branch = branch,
    base = base, skillIds = skillIds, skillsMode = skillsMode, includeSkillsContext = includeSkillsContext, startedBy = startedBy,
    images = images, weight = weight, contextLimit = contextLimit, effort = effort,
    queuedAgent = queuedAgent, queueRank = queueRank, runner = runner,
)

fun TicketRow.toStored() = StoredTicket(
    id = id, title = title, description = description, ticketType = ticketType, status = status, position = position,
    wontFix = wontFix, createdAt = createdAt, updatedAt = updatedAt, agentId = agentId, provider = provider, model = model,
    useWorktree = useWorktree, worktreeBranch = worktreeBranch, includeLinkedTasksContext = includeLinkedTasksContext,
    queuedFor = queuedFor, queuedAt = queuedAt, sessionId = sessionId, projectId = projectId, epicId = epicId, branch = branch,
    base = base, skillIds = skillIds, skillsMode = skillsMode, includeSkillsContext = includeSkillsContext, startedBy = startedBy,
    images = images, weight = weight, contextLimit = contextLimit, effort = effort,
    queuedAgent = queuedAgent, queueRank = queueRank, runner = runner,
)

fun StoredLink.toRow() = TicketLinkRow(ticketId, relatedId, type, createdAt)
fun TicketLinkRow.toStored() = StoredLink(ticketId, relatedId, type, createdAt)
