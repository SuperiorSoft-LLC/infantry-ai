package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.superiorsoftllc.agenthq.ui.block.Block
import com.superiorsoftllc.agenthq.ui.block.DockScope
import com.superiorsoftllc.agenthq.ui.block.Edge
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope

/** BLOCKS-SHELL-1: the window's status bar, at the bottom. BLOCKS-LAYOUT L2: its items are the one [CoreStatusBar] array. */
object ShellStatusBar : Block("infantry.shell.footer", "infantry.shell.footer", Edge.BOTTOM) {
    @Composable
    override fun Render(dock: DockScope) = CoreStatusBar.Render(BlockScope(Modifier, null))
}
