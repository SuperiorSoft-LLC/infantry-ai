// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.Composable
import com.superiorsoftllc.infantry.plugins.official.board.BoardDeps
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.shell.CoreExecutor
import com.superiorsoftllc.infantry.ui.shell.CoreLink
import com.superiorsoftllc.infantry.ui.shell.CoreRoles
import com.superiorsoftllc.infantry.ui.shell.CoreSession
import com.superiorsoftllc.infantry.ui.shell.CoreTickets
import com.superiorsoftllc.infantry.ui.shell.NoLayout

/**
 * RUNTIME-15: the board drawn outside a shell over a test's own state and controller. The official Infantry_Board never
 * takes the window's Controller (its pane reads [BoardDeps] from the shell context); the tests build those domains here
 * from the shell's Core adapters, as the window's own context does. Never compact.
 */
@Composable
fun BoardPane(state: AppState, controller: Controller, onOpenAgent: (String) -> Unit) =
    com.superiorsoftllc.infantry.plugins.official.board.BoardPane(
        BoardDeps(CoreLink(state), CoreSession(state), CoreTickets(state, controller), CoreRoles(state, controller), CoreExecutor(state), NoLayout),
        onOpenAgent,
    )
