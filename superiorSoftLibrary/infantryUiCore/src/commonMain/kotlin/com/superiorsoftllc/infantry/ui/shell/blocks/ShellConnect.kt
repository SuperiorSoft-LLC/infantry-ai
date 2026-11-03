// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell.blocks

import androidx.compose.runtime.Composable
import com.superiorsoftllc.infantry.ui.ConnectDialog
import com.superiorsoftllc.infantry.ui.LocalShell
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope

/** BLOCKS-SHELL-1: the connection dialog (Ctrl+L, a refused token), while it is open. */
object ShellConnect : Block("infantry.shell.connect", "infantry.shell.connect") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val ui = s.ui
        if (ui.showConnect) {
            ConnectDialog(ui.url, ui.token, s.state, s.controller::reconnectNow, { u, t -> ui.url = u; ui.token = t }, onDismiss = { ui.showConnect = false }) {
                ui.showConnect = false
                s.controller.connect(ui.url, ui.token)
                s.root.requestFocus()
            }
        }
    }
}
