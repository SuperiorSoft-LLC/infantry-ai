package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.ConnectDialog
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.block.Block
import com.superiorsoftllc.agenthq.ui.block.DockScope
import com.superiorsoftllc.agenthq.ui.block.Edge

/** BLOCKS-SHELL-1: the connection dialog (Ctrl+L, a refused token), while it is open. */
object ShellConnect : Block("infantry.shell.connect", "infantry.shell.connect", Edge.WINDOW) {
    @Composable
    override fun Render(dock: DockScope) {
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
