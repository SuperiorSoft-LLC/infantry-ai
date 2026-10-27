package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.ProcessesBadge
import com.superiorsoftllc.agenthq.ui.block.Block
import com.superiorsoftllc.agenthq.ui.block.DockScope
import com.superiorsoftllc.agenthq.ui.block.Edge

/**
 * BLOCKS-SHELL-3: the status bar's processes badge (ROGUE-1: rogue background processes / low memory; hidden while
 * nothing is wrong), taken out of the static StatusBar into a child block. Same ProcessesBadge, same behavior; it reads
 * the shell from its context.
 */
object StatusProcessesBadge : Block("processesBadge", "infantry.shell.footer.processesBadge", Edge.END) {
    @Composable
    override fun Render(dock: DockScope) {
        val s = LocalShell.current
        ProcessesBadge(s.state, s.controller)
    }
}
