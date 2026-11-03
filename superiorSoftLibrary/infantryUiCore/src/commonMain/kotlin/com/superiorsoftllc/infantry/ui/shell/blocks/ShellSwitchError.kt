package com.superiorsoftllc.infantry.ui.shell.blocks

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.superiorsoftllc.infantry.ui.LocalShell
import com.superiorsoftllc.infantry.ui.TextButton
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope

/** SWITCH-1: a server switch that failed, until it is acknowledged. */
object ShellSwitchError : Block("infantry.shell.switchError", "infantry.shell.switchError") {
    @Composable
    override fun Render(scope: BlockScope) {
        val ui = LocalShell.current.ui
        ui.switchError?.let { msg ->
            AlertDialog(
                onDismissRequest = { ui.switchError = null }, title = { Text("Could not switch server") }, text = { Text(msg) },
                confirmButton = { TextButton({ ui.switchError = null }) { Text("OK") } },
            )
        }
    }
}
