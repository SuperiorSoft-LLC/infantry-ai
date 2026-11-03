// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.agents

import com.superiorsoftllc.infantry.persistence.TicketRow
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.protocol.SkillsMode

/**
 * P10-K13 (docs/INFANTRY-PHASE10-KNOWLEDGE.md K-D9): the ONE place a session's effective skill ids are computed. Pure: no
 * store, no clock -- the caller hands it the three levels as they are at spawn, and the result is frozen on the
 * session's [com.superiorsoftllc.infantry.session.RoleInstance.skillIds] (K-D11), never recomputed from the live rows.
 *
 * - `includeSkillsContext` resolves ticket, then project, then true. False = no skills at all (empty list).
 * - `replace` (the ticket's [TicketRow.skillsMode]): only the ticket's ids apply.
 * - `merge` (the default, and the only mode without a ticket): the agent's ids, then the project's, then the ticket's,
 *   de-duplicated so the FIRST occurrence wins.
 */
object SkillAttachment {
    /** The project baseline: settings `context.skillIds` and `context.includeSkillsContext` (null = never stored). */
    data class Project(val skillIds: List<String> = emptyList(), val includeSkillsContext: Boolean? = null)

    fun effective(agent: AgentProfile?, project: Project, ticket: TicketRow?): List<String> {
        val include = ticket?.includeSkillsContext ?: project.includeSkillsContext ?: true
        if (!include) return emptyList()
        val ticketIds = ticket?.skillIds.orEmpty()
        if (ticket != null && ticket.skillsMode == SkillsMode.REPLACE) return ticketIds.distinct()
        return LinkedHashSet<String>().apply {
            addAll(agent?.skillIds.orEmpty())
            addAll(project.skillIds)
            addAll(ticketIds)
        }.toList()
    }
}
