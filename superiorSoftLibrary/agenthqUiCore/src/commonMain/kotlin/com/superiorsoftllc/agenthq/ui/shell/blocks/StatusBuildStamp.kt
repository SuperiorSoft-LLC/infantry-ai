package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.ui.ConfigTab
import com.superiorsoftllc.agenthq.ui.FooterBuild
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.block.Block
import com.superiorsoftllc.agenthq.ui.block.DockScope
import com.superiorsoftllc.agenthq.ui.block.Edge

/**
 * BLOCKS-SHELL-2: the status bar's build stamp (BUILD-STAMP-1), the first item taken out of the static StatusBar into a
 * child block. Same FooterBuild, same data, same click (opens Configuration > About); it reads the shell from its context.
 */
object StatusBuildStamp : Block("buildStamp", "infantry.shell.footer.buildStamp", Edge.END) {
    @Composable
    override fun Render(dock: DockScope) {
        val s = LocalShell.current
        val c = s.state.connection
        FooterBuild(
            s.host.appBuild, if (c.isLive) c.daemonBuild else null,
            Modifier.padding(start = 10.dp).clickable(role = Role.Button) { s.ui.configTab = ConfigTab.ABOUT; s.ui.showConfig = true },
        )
    }
}
