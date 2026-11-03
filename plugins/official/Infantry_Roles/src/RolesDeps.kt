// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.roles

import com.superiorsoftllc.infantry.ui.*

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.shell.CoreExecutor
import com.superiorsoftllc.infantry.ui.shell.CoreLink
import com.superiorsoftllc.infantry.ui.shell.NoNotes
import com.superiorsoftllc.infantry.ui.shell.NoRoles
import com.superiorsoftllc.infantry.ui.shell.CoreSession
import com.superiorsoftllc.infantry.ui.shell.NoTraits
import com.superiorsoftllc.infantry.ui.shell.ExecutorContext
import com.superiorsoftllc.infantry.ui.shell.LinkContext
import com.superiorsoftllc.infantry.ui.shell.NotesContext
import com.superiorsoftllc.infantry.ui.shell.RolesContext
import com.superiorsoftllc.infantry.ui.shell.SessionContext
import com.superiorsoftllc.infantry.ui.shell.ShellContext
import com.superiorsoftllc.infantry.ui.shell.TraitsContext

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
