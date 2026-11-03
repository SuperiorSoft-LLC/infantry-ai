// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.ConfigCogLabel
import com.superiorsoftllc.infantry.ui.LocalShell
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.TextButton
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope

/** S4c: the slot whose fill draws the window's own way into Configuration (the title bar's and the footer's cog). */
const val HEADER_SLOT = "infantry.shell.header"

/** S4c: the bare shell's Configuration button ([ShellConfigEntry]). */
const val CONFIG_ENTRY_TAG = "shell-config-entry"

/**
 * S4c: the core's way into Configuration (its trust half: plugins, secrets, devices, about) when no module fills the
 * title bar ([HEADER_SLOT]); the bare shell has no header (since RUNTIME-18 it has the shell's own status bar and its cog). Ctrl+, opens it too. With the app UI
 * the header is filled and this draws nothing, so the window is unchanged. Locked ([CoreShell.locked]): no module hides it.
 */
object ShellConfigEntry : Block("infantry.shell.configEntry", "infantry.shell.configEntry") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        if (s.tabs.fills(HEADER_SLOT)) return
        Row(scope.modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { s.ui.showConfig = true }, modifier = Modifier.testTag(CONFIG_ENTRY_TAG)) {
                ConfigCogLabel(Palette.Muted, 14.dp, 6.dp, color = Palette.Muted, fontSize = 12.sp)
            }
        }
    }
}
