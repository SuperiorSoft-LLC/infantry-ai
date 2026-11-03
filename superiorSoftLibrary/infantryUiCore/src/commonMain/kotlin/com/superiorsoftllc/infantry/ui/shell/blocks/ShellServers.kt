// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell.blocks

import androidx.compose.runtime.Composable
import com.superiorsoftllc.infantry.ui.LocalShell
import com.superiorsoftllc.infantry.ui.ServersDialog
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope

/** SWITCH-1: the saved servers dialog (the server chip's Manage), while it is open. */
object ShellServers : Block("infantry.shell.servers", "infantry.shell.servers") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val switcher = s.switcher
        if (s.ui.showServers && switcher != null) {
            ServersDialog(
                switcher,
                onRemoved = { wasActive -> if (wasActive) switcher.active()?.let(s.switchTo) },
                onEdited = { c -> if (c.url == switcher.active()?.url) s.switchTo(c) },
                onDismiss = { s.ui.showServers = false },
            )
        }
    }
}
