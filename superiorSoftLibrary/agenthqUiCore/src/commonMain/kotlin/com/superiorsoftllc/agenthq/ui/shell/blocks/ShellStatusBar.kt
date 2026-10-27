package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.ProcessesBadge
import com.superiorsoftllc.agenthq.ui.StatusBar
import com.superiorsoftllc.agenthq.ui.block.Block
import com.superiorsoftllc.agenthq.ui.block.DockScope
import com.superiorsoftllc.agenthq.ui.block.Edge

/** BLOCKS-SHELL-1: the window's status bar, at the bottom. */
object ShellStatusBar : Block("infantry.shell.footer", "infantry.shell.footer", Edge.BOTTOM) {
    @Composable
    override fun Render(dock: DockScope) {
        val s = LocalShell.current
        StatusBar(s.state, s.serverMismatch, s.host.appBuild, onConfig = { tab -> if (tab != null) s.ui.configTab = tab; s.ui.showConfig = true }, CoreShell.statusBarChildren) {
            // ROGUE-1 (1.120.0): rogue background processes / low memory; hidden while nothing is wrong.
            ProcessesBadge(s.state, s.controller)
        }
    }
}
