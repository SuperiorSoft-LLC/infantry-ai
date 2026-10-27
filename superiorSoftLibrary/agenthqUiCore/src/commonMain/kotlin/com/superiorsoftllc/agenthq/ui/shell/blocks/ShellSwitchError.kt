package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.TextButton
import com.superiorsoftllc.agenthq.ui.block.Block
import com.superiorsoftllc.agenthq.ui.block.DockScope
import com.superiorsoftllc.agenthq.ui.block.Edge

/** SWITCH-1: a server switch that failed, until it is acknowledged. */
object ShellSwitchError : Block("infantry.shell.switchError", "infantry.shell.switchError", Edge.WINDOW) {
    @Composable
    override fun Render(dock: DockScope) {
        val ui = LocalShell.current.ui
        ui.switchError?.let { msg ->
            AlertDialog(
                onDismissRequest = { ui.switchError = null }, title = { Text("Could not switch server") }, text = { Text(msg) },
                confirmButton = { TextButton({ ui.switchError = null }) { Text("OK") } },
            )
        }
    }
}
