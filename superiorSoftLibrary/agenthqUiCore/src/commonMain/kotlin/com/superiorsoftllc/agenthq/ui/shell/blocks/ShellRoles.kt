package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.RoleManagerDialog
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope

/** BLOCKS-SHELL-1: the role manager (Configuration > Roles), while it is open. */
object ShellRoles : Block("infantry.shell.roles", "infantry.shell.roles") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        if (s.ui.showRoles) RoleManagerDialog(s.state, s.controller, onDismiss = { s.ui.showRoles = false })
    }
}
