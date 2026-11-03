// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.brief

import com.superiorsoftllc.infantry.protocol.Project
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.protocol.TicketStatus
import com.superiorsoftllc.infantry.session.ContextSource

/**
 * MVP Sprint-Zero leaf 4: what the LEAD hands a worker.
 *
 * [ticket] is the task. [project] is the project it belongs to, carried in full so the composer can
 * prove what it drops. [otherTickets] is the rest of the board, in whatever order the caller holds
 * it -- the lead's context, and the reason this composer exists at all.
 */
data class BriefInput(
    val ticket: Ticket,
    val project: Project,
    /** The lead's own words for this one task: the only task-specific content beyond the ticket. */
    val instruction: String = "",
    val otherTickets: List<Ticket> = emptyList(),
)

/**
 * The whole worker payload. Nothing outside this type reaches a worker, so every field here is a
 * field a worker is allowed to see.
 */
data class WorkerBrief(
    val taskId: String,
    val projectId: String,
    val title: String,
    val status: String,
    val instruction: String,
    /** Every field above, in order, verbatim. */
    val prompt: String,
    /** What each field cost in characters, so a lead can log what it actually handed out. */
    val fields: List<ContextSource>,
)

/**
 * Turns one ticket into the brief its worker receives, and nothing else.
 *
 * `lead + relay` is one of the three ways Sprint-Zero runs one story, and this is where its
 * economics are decided: a lead holds the project, decomposes it, and hands each worker exactly one
 * task. The composer therefore has one job -- copy the task across and drop everything around it.
 *
 * What crosses, and why each piece is allowed to:
 *  - [WorkerBrief.taskId]     the ticket id, the worker's handle on the task and the only thing the
 *                             lead can match a result back against;
 *  - [WorkerBrief.projectId]  the join key of the MVP. A worker must be able to record its result
 *                             against the right project, so the id crosses even though the project
 *                             around it does not;
 *  - [WorkerBrief.title]      the task, verbatim, whitespace included;
 *  - [WorkerBrief.status]     the column the task starts in, so a worker knows what "done" means
 *                             from the board's vocabulary ([TicketStatus]) rather than inventing one;
 *  - [WorkerBrief.instruction] the lead's own words for this task, verbatim, dropped entirely when
 *                             blank so a brief never carries an empty section.
 *
 * What does NOT cross, because it is the lead's to reason over and the worker's to be spared of:
 * the project's [Project.name] and [Project.goal] (project-level context), and
 * [BriefInput.otherTickets] (the rest of the board, its order and every other story's id, title and
 * status). Handing a worker the board is how a $0.30 task becomes a $3 one, so the exclusion is
 * structural: those values are read by no branch of this function and appear in no field of
 * [WorkerBrief].
 *
 * Pure: no clock, no I/O, no daemon, no store. A wrong or incoherent input is REFUSED, never
 * repaired -- the project's standing convention, carried over from `ticket_get` and `story_move`.
 */
object LeadBriefComposer {

    fun compose(input: BriefInput): WorkerBrief {
        val ticket = input.ticket
        require(ticket.id.isNotBlank()) {
            "brief refused: a task needs an id of its own, got '${ticket.id}'"
        }
        require(ticket.title.isNotBlank()) {
            "brief refused: task '${ticket.id}' has no title, so there is nothing to hand a worker"
        }
        require(ticket.projectId == input.project.id) {
            "brief refused: task '${ticket.id}' names project '${ticket.projectId}', " +
                "but the lead holds '${input.project.id}'"
        }
        require(ticket.status in TicketStatus.ALL) {
            "brief refused: task '${ticket.id}' has status '${ticket.status}', " +
                "expected one of ${TicketStatus.ALL}"
        }

        val instruction = input.instruction.takeIf(String::isNotBlank) ?: ""
        val sections = buildList {
            add("taskId" to ticket.id)
            add("projectId" to input.project.id)
            add("title" to ticket.title)
            add("status" to ticket.status)
            if (instruction.isNotEmpty()) add("instruction" to instruction)
        }

        return WorkerBrief(
            taskId = ticket.id,
            projectId = input.project.id,
            title = ticket.title,
            status = ticket.status,
            instruction = instruction,
            prompt = sections.joinToString("\n\n") { it.second },
            fields = sections.map { (name, value) -> ContextSource(source = name, chars = value.length) },
        )
    }
}
