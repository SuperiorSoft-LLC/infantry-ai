// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.persistence

/** DP-5f-4a: the stored ticket / link row types (moved from core Records.kt, package unchanged). */
/**
 * Story 9.2: one ticket as stored (see tickets.sq). Not the wire type: the `ticket` frame
 * ([com.superiorsoftllc.infantry.protocol.Ticket]) adds what the daemon derives from the links (each
 * ticket's links seen from its own side, and what holds it), which no single row knows.
 */
data class TicketRow(
    val id: String,
    val title: String,
    val description: String = "",
    val ticketType: String? = null,
    val status: String,
    val position: Int,
    val wontFix: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val agentId: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val useWorktree: Boolean? = null,
    val worktreeBranch: String? = null,
    val includeLinkedTasksContext: Boolean? = null,
    val queuedFor: String? = null,
    val queuedAt: Long? = null,
    val sessionId: String? = null,
    /** MVP Sprint-Zero: the project this ticket belongs to; null = none. */
    val projectId: String? = null,
    /** CLI-T3 patch (675e7c4c): the epic this ticket is filed under; null = none. */
    val epicId: String? = null,
    /** BRANCH-PER-TASK 1/3: the `ticket/<short-id>-<slug>` branch a start cut for this ticket, once one did. */
    val branch: String? = null,
    /** BRANCH-PER-TASK 1/3: the commit that branch was cut from. */
    val base: String? = null,
    /** P10-K11: the skill ids this ticket attaches, in attach order; empty = none. */
    val skillIds: List<String> = emptyList(),
    /** P10-K11: `merge` (the ticket's ids follow the agent's and the project's) or `replace` (only the ticket's apply). */
    val skillsMode: String = "merge",
    /** P10-K11: the ticket's skills-section switch; null inherits the project setting. */
    val includeSkillsContext: Boolean? = null,
    /** BUG-MAIL-2: the session that started this ticket (its worker reports there); null = the UI / no session. */
    val startedBy: String? = null,
    /** IMG-3: the ticket's images, relPaths under its project's `.infantry/uploads/ticket/`, in attach order; empty = none. */
    val images: List<String> = emptyList(),
    /** TICKET-WEIGHT: 1..5, or null = unweighted. */
    val weight: Int? = null,
    /** CONTEXT-LIMIT-1: the worker's context limit in tokens; null = none (the harness's own window). */
    val contextLimit: Int? = null,
    /** EFFORT-1: the worker's reasoning effort level; null = the harness default. */
    val effort: String? = null,
    /** AGENT-TASKS-3: the agent profile whose persistent queue holds this ticket, and its place there. */
    val queuedAgent: String? = null,
    val queueRank: Int? = null,
    /** RUNNER-POOL-3: the runner pin `local` | <name> | `any`; null = no pin. */
    val runner: String? = null,
) {
    init {
        require(position >= 0) { "position must be >= 0, was $position" }
    }
}

/**
 * Story 9.2: one link as stored, always in its stored direction: [type] is `blocked_by`, `child_of`,
 * `relates_to` or `duplicates`, read as "[ticketId] [type] [relatedId]".
 */
data class TicketLinkRow(
    val ticketId: String,
    val relatedId: String,
    val type: String,
    val createdAt: Long = 0,
)
