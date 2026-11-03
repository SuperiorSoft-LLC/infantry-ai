package com.superiorsoftllc.infantry.ui.shell.blocks

import androidx.compose.runtime.Composable
import com.superiorsoftllc.infantry.ui.LocalShell
import com.superiorsoftllc.infantry.ui.ServerUpdateConfirm
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope

/** SERVER-UPDATE-4: the server update's confirmation, while one is asked. */
object ShellServerUpdate : Block("infantry.shell.serverUpdate", "infantry.shell.serverUpdate") {
    @Composable
    override fun Render(scope: BlockScope) {
        ServerUpdateConfirm(LocalShell.current.serverUpdate)
    }
}
