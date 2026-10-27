package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.ServerUpdateConfirm
import com.superiorsoftllc.agenthq.ui.block.Block
import com.superiorsoftllc.agenthq.ui.block.DockScope
import com.superiorsoftllc.agenthq.ui.block.Edge

/** SERVER-UPDATE-4: the server update's confirmation, while one is asked. */
object ShellServerUpdate : Block("infantry.shell.serverUpdate", "infantry.shell.serverUpdate", Edge.WINDOW) {
    @Composable
    override fun Render(dock: DockScope) {
        ServerUpdateConfirm(LocalShell.current.serverUpdate)
    }
}
