package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.block.Block
import com.superiorsoftllc.agenthq.ui.block.DockScope
import com.superiorsoftllc.agenthq.ui.block.Edge
import com.superiorsoftllc.agenthq.ui.sound.IncomingCommsSoundEffect

/** BLOCKS-SHELL-1: the incoming-comms sound; it draws nothing. */
object ShellSound : Block("infantry.shell.sound", "infantry.shell.sound", Edge.OVERLAY) {
    @Composable
    override fun Render(dock: DockScope) {
        val s = LocalShell.current
        IncomingCommsSoundEffect(s.controller.state, s.ui.flags.commsSound)
    }
}
