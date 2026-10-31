package com.superiorsoftllc.agenthq.persistence

import com.superiorsoftllc.agenthq.daemon.api.board.StoredLink
import com.superiorsoftllc.agenthq.daemon.api.board.StoredTicket
import com.superiorsoftllc.agenthq.persistence.TicketLinkRow
import com.superiorsoftllc.agenthq.persistence.TicketRow

/** DP-5f-3a: the TicketRow <-> StoredTicket adapter, shared with `TicketBoard`. */
fun StoredTicket.toRow() = TicketRow(
    id = id, title = title, description = description, ticketType = ticketType, status = status, position = position,
    wontFix = wontFix, createdAt = createdAt, updatedAt = updatedAt, agentId = agentId, provider = provider, model = model,
    useWorktree = useWorktree, worktreeBranch = worktreeBranch, includeLinkedTasksContext = includeLinkedTasksContext,
    queuedFor = queuedFor, queuedAt = queuedAt, sessionId = sessionId, projectId = projectId, epicId = epicId, branch = branch,
    base = base, skillIds = skillIds, skillsMode = skillsMode, includeSkillsContext = includeSkillsContext, startedBy = startedBy,
    images = images, weight = weight, contextLimit = contextLimit, effort = effort,
)

fun TicketRow.toStored() = StoredTicket(
    id = id, title = title, description = description, ticketType = ticketType, status = status, position = position,
    wontFix = wontFix, createdAt = createdAt, updatedAt = updatedAt, agentId = agentId, provider = provider, model = model,
    useWorktree = useWorktree, worktreeBranch = worktreeBranch, includeLinkedTasksContext = includeLinkedTasksContext,
    queuedFor = queuedFor, queuedAt = queuedAt, sessionId = sessionId, projectId = projectId, epicId = epicId, branch = branch,
    base = base, skillIds = skillIds, skillsMode = skillsMode, includeSkillsContext = includeSkillsContext, startedBy = startedBy,
    images = images, weight = weight, contextLimit = contextLimit, effort = effort,
)

fun StoredLink.toRow() = TicketLinkRow(ticketId, relatedId, type, createdAt)
fun TicketLinkRow.toStored() = StoredLink(ticketId, relatedId, type, createdAt)
