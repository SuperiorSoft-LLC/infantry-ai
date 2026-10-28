package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.CompactNavRow
import com.superiorsoftllc.agenthq.ui.ResizeHandle
import com.superiorsoftllc.agenthq.ui.Shell
import com.superiorsoftllc.agenthq.ui.TabBar
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.OverrideBlock
import com.superiorsoftllc.agenthq.ui.block.layout.OverrideModes
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlaced
import com.superiorsoftllc.agenthq.ui.block.layout.Show
import com.superiorsoftllc.agenthq.ui.model.RightPanelWidth
import com.superiorsoftllc.agenthq.ui.plugin.PanelSections
import com.superiorsoftllc.agenthq.ui.plugin.Panels
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers

private const val RIGHT = "infantry.shell.right"

private const val BODY = "$RIGHT.body"

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
        val s = appShell
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
        val s = appShell
        ResizeHandle("right-panel-resize-handle", scope.modifier) { s.ui.rightPanelWidth = RightPanelWidth.dragged(s.ui.rightPanelWidth, it) }
    }
}

/**
 * The panel's column at the dragged width: its module sections around the tab area, whose children are
 * `infantry.shell.right.body.<name>`, top to bottom.
 */
class RightPanelBodyBlock(children: List<Block>, place: Place = Place()) : Block("rightPanelBody", BODY, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = appShell
        RightPanelContent(s, scope.modifier.width(s.ui.rightPanelWidth.dp), null, children)
    }

    override fun withChildren(children: List<Block>) = RightPanelBodyBlock(children, place)
}

/** The right panel's tab strip; it owns the divider under it. */
object RightPanelStrip : Block("tabStrip", "$BODY.strip") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = appShell
        val tabs = s.tabs
        val strip = tabs.tabs(TabContainers.RIGHT)
        TabBar(
            hPad = 4.dp,
            fontSize = 11.sp,
            tabs = strip.map { tabs.title(it, s.state) to (s.ui.rightTab == it.id) },
            tagOf = { strip[it].testTag },
            modifier = scope.modifier,
        ) { s.ui.rightTab = strip[it].id }
    }
}

/**
 * The picked tab's content (TabFactory.Content: its container's slots, interceptors and badge included). The module
 * draws it with no node of its own, so a placement other than the default gets a Box to wear it.
 */
object RightPanelTab : Block("tabContent", "$BODY.tab") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = appShell
        if (scope.modifier == Modifier) s.tabs.Content(s.ui.rightTab, s.tabContext({}))
        else Box(scope.modifier) { s.tabs.Content(s.ui.rightTab, s.tabContext({})) }
    }
}

private val RightPanelBody = RightPanelBodyBlock(listOf(RightPanelStrip, RightPanelTab))

val ShellRightPanel = RightPanelBlock(listOf(RightPanelResize, RightPanelBody))

/**
 * The tab area's children for the compact drawer: the window's own body's children (overrides and injections applied),
 * read from [com.superiorsoftllc.agenthq.ui.plugin.TabFactory.shellRoot], so a module's strip, tab or injected block
 * shows in the drawer too (BLOCKS-INJECTORS J2). If the window has no body of its own (it, or the panel, was replaced),
 * each core child is looked up by id, falling back to the core one.
 */
@Composable
internal fun rightPanelArea(s: Shell): List<Block> = remember(s.tabs) {
    val root = s.tabs.shellRoot
    (root.find(BODY) as? RightPanelBodyBlock)?.children ?: RightPanelBody.children.map { root.find(it.id) ?: it }
}

/** The first node [id] in this tree, the outermost override for an overridden node; a wrap's own block is still looked into. */
private fun Block.find(id: String): Block? = when {
    this.id == id -> this
    this is OverrideBlock && mode == OverrideModes.WRAP -> inner.find(id)
    else -> children.firstNotNullOfOrNull { it.find(id) }
}

/** MOBILE-DRAWERS-1: the right panel, docked (wide, [onClose] null) or in the compact drawer; [area] is its tab area. */
@Composable
internal fun RightPanelContent(s: Shell, mod: Modifier, onClose: (() -> Unit)?, area: List<Block>) {
    Column(mod.fillMaxHeight().testTag("right-panel")) {
        if (onClose != null) CompactNavRow(backLabel = "Close", onBack = onClose)
        // PLUGINS-3: the right panel's module sections, around its tab strip.
        PanelSections(s.tabs, Panels.RIGHT, s.tabContext({})) {
            RenderPlaced(area)
        }
    }
}
