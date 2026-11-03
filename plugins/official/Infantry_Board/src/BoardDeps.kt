package com.superiorsoftllc.infantry.plugins.official.board

import com.superiorsoftllc.infantry.ui.shell.gate
import androidx.compose.runtime.Composable
import com.superiorsoftllc.infantry.ui.*
import com.superiorsoftllc.infantry.ui.shell.blocks.*

import com.superiorsoftllc.infantry.ui.model.EpicView
import com.superiorsoftllc.infantry.ui.model.ReportFlag
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.reportFlagOf
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import com.superiorsoftllc.infantry.ui.shell.ExecutorContext
import com.superiorsoftllc.infantry.ui.shell.LayoutContext
import com.superiorsoftllc.infantry.ui.shell.LinkContext
import com.superiorsoftllc.infantry.ui.shell.RolesContext
import com.superiorsoftllc.infantry.ui.shell.SessionContext
import com.superiorsoftllc.infantry.ui.shell.ShellContext
import com.superiorsoftllc.infantry.ui.shell.TicketsContext

/**
 * S4d (docs/SHELL-LEAKS.md): what the board reads and sends, all of it [ShellContext] domains, so the board never reads
 * `AppState` or calls the `Controller`. [roles] gives the assignees (profiles, role lanes, sessions), [executor] the
 * harnesses a ticket can pin, [layout] whether the window is compact.
 */
class BoardDeps(
    val link: LinkContext,
    val session: SessionContext,
    val tickets: TicketsContext,
    val roles: RolesContext,
    val executor: ExecutorContext,
    val layout: LayoutContext,
) {
    constructor(ctx: ShellContext) : this(ctx.link, ctx.session, ctx.tickets, ctx.roles, ctx.executor, ctx.layout)

    val live: Boolean get() = link.live
    // DEMO-MERGE-1 / POLICY-1: the generic write (an undeclared action); a control with a named action asks [can].
    val canWrite: Boolean @Composable get() = link.canWrite
    /** POLICY-1: the named action [actionId] may be performed now (live, the daemon's policy and any write policy allow it). */
    @Composable fun can(actionId: String, kind: String = com.superiorsoftllc.infantry.plugin.api.ActionKind.WRITE): Boolean =
        link.gate(actionId, kind).enabled

    /** The session [id] runs, if the sidebar knows it. */
    fun agent(id: String?): AgentView? = id?.let { wanted -> roles.agents.firstOrNull { it.id == wanted } }

    /** M2-14: the epics a ticket can be filed under: the active project's, or every project's when none is selected. */
    val boardEpics: List<EpicView>
        get() = session.activeProjectId?.let { tickets.epics[it].orEmpty() } ?: tickets.epics.values.flatten()

    /** An epic by id from any project, so one outside the active project still reads by name. */
    fun epicName(id: String): String? = tickets.epics.values.flatten().firstOrNull { it.id == id }?.name

    /** The report flag of the session working [ticket]. */
    fun reportFlag(ticket: Ticket): ReportFlag? = agent(ticket.sessionId)?.session?.report.let(::reportFlagOf)
}
