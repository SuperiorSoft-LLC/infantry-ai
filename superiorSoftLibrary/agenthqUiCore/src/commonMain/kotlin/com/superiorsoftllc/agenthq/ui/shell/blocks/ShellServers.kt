package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.ServersDialog
import com.superiorsoftllc.agenthq.ui.block.Block
import com.superiorsoftllc.agenthq.ui.block.DockScope
import com.superiorsoftllc.agenthq.ui.block.Edge

/** SWITCH-1: the saved servers dialog (the server chip's Manage), while it is open. */
object ShellServers : Block("infantry.shell.servers", "infantry.shell.servers", Edge.WINDOW) {
    @Composable
    override fun Render(dock: DockScope) {
        val s = LocalShell.current
        val switcher = s.switcher
        if (s.ui.showServers && switcher != null) {
            ServersDialog(
                switcher,
                onRemoved = { wasActive -> if (wasActive) switcher.active()?.let(s.switchTo) },
                onEdited = { c -> if (c.url == switcher.active()?.url) s.switchTo(c) },
                onDismiss = { s.ui.showServers = false },
            )
        }
    }
}
