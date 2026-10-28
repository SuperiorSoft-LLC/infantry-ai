package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.sound.IncomingCommsSoundEffect

/** BLOCKS-SHELL-1: the incoming-comms sound; it draws nothing. */
object ShellSound : Block("infantry.shell.sound", "infantry.shell.sound") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = appShell
        IncomingCommsSoundEffect(s.controller.state, s.ui.flags.commsSound)
    }
}
