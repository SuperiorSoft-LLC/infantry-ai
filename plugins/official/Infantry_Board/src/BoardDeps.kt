package com.superiorsoftllc.agenthq.plugins.official.board

import com.superiorsoftllc.agenthq.ui.*
import com.superiorsoftllc.agenthq.ui.shell.blocks.*

import com.superiorsoftllc.agenthq.ui.model.EpicView
import com.superiorsoftllc.agenthq.ui.model.ReportFlag
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.reportFlagOf
import com.superiorsoftllc.agenthq.ui.protocol.Ticket
import com.superiorsoftllc.agenthq.ui.shell.ExecutorContext
import com.superiorsoftllc.agenthq.ui.shell.LayoutContext
import com.superiorsoftllc.agenthq.ui.shell.LinkContext
import com.superiorsoftllc.agenthq.ui.shell.RolesContext
import com.superiorsoftllc.agenthq.ui.shell.SessionContext
import com.superiorsoftllc.agenthq.ui.shell.ShellContext
import com.superiorsoftllc.agenthq.ui.shell.TicketsContext

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
    // DEMO-MERGE-1: write controls follow canWrite (not live under a demo-locked daemon); reads keep live.
    val canWrite: Boolean get() = link.canWrite

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
