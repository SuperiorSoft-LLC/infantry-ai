package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlaced
import com.superiorsoftllc.agenthq.ui.block.layout.Show
import com.superiorsoftllc.agenthq.ui.model.RightPanelWidth
import com.superiorsoftllc.agenthq.ui.plugin.PanelSections
import com.superiorsoftllc.agenthq.ui.plugin.Panels
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers

private const val RIGHT = "infantry.shell.right"

/**
 * BLOCKS-SHELL-1: the right panel and its resize handle, docked at the end of the middle region. BLOCKS-LAYOUT L4: it is
 * `show Wide`, so below PanelLayout.COMPACT_BELOW_DP its parent leaves it out; the compact drawer shows the same content.
 * BLOCKS-LAYOUT L9a: a container of child blocks `infantry.shell.right.<name>`, left to right. It draws no node of its own:
 * in the panes' row its children go straight into that row ([BlockScope.row]); drawn outside a row they get a row of
 * their own.
 */
class RightPanelBlock(children: List<Block>, place: Place = Place(show = Show.Wide)) : Block("rightPanel", RIGHT, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        // PANEL-TOGGLE-1: a hidden side panel and its resize handle leave the composition.
        if (!s.state.panels.forWindow(dockCompact()).rightShown) return
        val row = scope.row
        if (row == null) Row(scope.modifier) { RenderPlaced(children) } else with(row) { RenderPlaced(children) }
    }

    override fun withChildren(children: List<Block>) = RightPanelBlock(children, place)
}

/** M2-9: the ↔ cursor and a grip, not the hand (drag left = wider). */
object RightPanelResize : Block("resizeHandle", "$RIGHT.resize") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        ResizeHandle("right-panel-resize-handle", scope.modifier) { s.ui.rightPanelWidth = RightPanelWidth.dragged(s.ui.rightPanelWidth, it) }
    }
}

/** The panel's column at the dragged width: its module sections around the tab strip and the picked tab. */
object RightPanelBody : Block("rightPanelBody", "$RIGHT.body") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        RightPanelContent(s, scope.modifier.width(s.ui.rightPanelWidth.dp), null)
    }
}

val ShellRightPanel = RightPanelBlock(listOf(RightPanelResize, RightPanelBody))

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
