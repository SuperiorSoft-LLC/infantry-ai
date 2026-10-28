package com.superiorsoftllc.agenthq.plugins.official.roles

import com.superiorsoftllc.agenthq.ui.*

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.shell.CoreExecutor
import com.superiorsoftllc.agenthq.ui.shell.CoreLink
import com.superiorsoftllc.agenthq.ui.shell.NoNotes
import com.superiorsoftllc.agenthq.ui.shell.NoRoles
import com.superiorsoftllc.agenthq.ui.shell.CoreSession
import com.superiorsoftllc.agenthq.ui.shell.NoTraits
import com.superiorsoftllc.agenthq.ui.shell.ExecutorContext
import com.superiorsoftllc.agenthq.ui.shell.LinkContext
import com.superiorsoftllc.agenthq.ui.shell.NotesContext
import com.superiorsoftllc.agenthq.ui.shell.RolesContext
import com.superiorsoftllc.agenthq.ui.shell.SessionContext
import com.superiorsoftllc.agenthq.ui.shell.ShellContext
import com.superiorsoftllc.agenthq.ui.shell.TraitsContext

/**
 * S4c (docs/SHELL-LEAKS.md): what the roles window and the Roles pane read and send, all of it [ShellContext] domains,
 * so neither reads `AppState` or calls the `Controller`.
 */
class RolesDeps(
    val link: LinkContext,
    val session: SessionContext,
    val roles: RolesContext,
    val traits: TraitsContext,
    val executor: ExecutorContext,
    val notes: NotesContext,
) {
    constructor(ctx: ShellContext) : this(ctx.link, ctx.session, ctx.roles, ctx.traits, ctx.executor, ctx.notes)

    /**
     * RUNTIME-14: for a pane drawn outside a shell: [state]'s link, session and harnesses, and no roles, traits or notes
     * requests (the official jar never reaches the window's Controller).
     */
    constructor(state: AppState) : this(CoreLink(state), CoreSession(state), NoRoles, NoTraits, CoreExecutor(state), NoNotes)
}
