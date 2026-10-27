package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.CompactNavRow
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.ResizeHandle
import com.superiorsoftllc.agenthq.ui.Shell
import com.superiorsoftllc.agenthq.ui.TabBar
import com.superiorsoftllc.agenthq.ui.block.Block
import com.superiorsoftllc.agenthq.ui.block.DockScope
import com.superiorsoftllc.agenthq.ui.block.Edge
import com.superiorsoftllc.agenthq.ui.model.RightPanelWidth
import com.superiorsoftllc.agenthq.ui.plugin.PanelSections
import com.superiorsoftllc.agenthq.ui.plugin.Panels
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers

/**
 * BLOCKS-SHELL-1: the right panel and its resize handle, docked at the end of the middle region. Below
 * PanelLayout.COMPACT_BELOW_DP it docks nothing: the compact drawer shows the same content.
 */
object ShellRightPanel : Block("infantry.shell.right", "infantry.shell.right", Edge.END) {
    @Composable
    override fun Render(dock: DockScope) {
        val s = LocalShell.current
        val compact = dockCompact()
        if (!compact && s.state.panels.forWindow(compact).rightShown) {
            // M2-9: the ↔ cursor and a grip, not the hand (drag left = wider).
            ResizeHandle("right-panel-resize-handle") { s.ui.rightPanelWidth = RightPanelWidth.dragged(s.ui.rightPanelWidth, it) }
            RightPanelContent(s, Modifier.width(s.ui.rightPanelWidth.dp), null)
        }
    }
}

/** MOBILE-DRAWERS-1: the right panel, docked (wide, [onClose] null) or in the compact drawer. */
@Composable
internal fun RightPanelContent(s: Shell, mod: Modifier, onClose: (() -> Unit)?) {
    val tabs = s.tabs
    val ui = s.ui
    Column(mod.fillMaxHeight().testTag("right-panel")) {
        if (onClose != null) CompactNavRow(backLabel = "Close", onBack = onClose)
        // PLUGINS-3: the right panel's module sections, around its tab strip.
        PanelSections(tabs, Panels.RIGHT, s.tabContext({})) {
            val strip = tabs.tabs(TabContainers.RIGHT)
            TabBar(
                hPad = 4.dp,
                fontSize = 11.sp,
                tabs = strip.map { tabs.title(it, s.state) to (ui.rightTab == it.id) },
                tagOf = { strip[it].testTag },
            ) { ui.rightTab = strip[it].id }
            tabs.Content(ui.rightTab, s.tabContext({}))
        }
    }
}
