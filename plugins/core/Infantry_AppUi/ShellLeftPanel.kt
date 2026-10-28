package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.ProjectsSidebar
import com.superiorsoftllc.agenthq.ui.ResizeHandle
import com.superiorsoftllc.agenthq.ui.Shell
import com.superiorsoftllc.agenthq.ui.Sidebar
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.LocalBlockSize
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.Show
import com.superiorsoftllc.agenthq.ui.model.CompactScreen
import com.superiorsoftllc.agenthq.ui.model.PanelLayout
import com.superiorsoftllc.agenthq.ui.model.SidebarWidth
import com.superiorsoftllc.agenthq.ui.plugin.PanelSections
import com.superiorsoftllc.agenthq.ui.plugin.Panels
import kotlinx.coroutines.launch

/**
 * BLOCKS-SHELL-1: the left panel and its resize handle, docked at the start of the middle region. BLOCKS-LAYOUT L4: it is
 * `show Wide`, so below PanelLayout.COMPACT_BELOW_DP its parent leaves it out; the compact drawer shows the same content.
 */
object ShellLeftPanel : Block("infantry.shell.left", "infantry.shell.left", Place(show = Show.Wide)) {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = appShell
        val compact = dockCompact()
        // PANEL-TOGGLE-1: a hidden side panel and its resize handle leave the composition; the centre column's
        // weight then takes the whole width.
        if (s.state.panels.forWindow(compact).leftShown) {
            LeftPanelContent(s, compact, s.ui.sidebarWidth)
            // M2-9: the left edge drags like the right one (drag right = wider).
            ResizeHandle("sidebar-resize-handle") { s.ui.sidebarWidth = SidebarWidth.dragged(s.ui.sidebarWidth, it) }
        }
    }
}

/** RUNTIME-15: the board overlay's shell slot; the official Infantry_Board fills it (no jar, no fill, no Board button). */
private const val BOARD_SLOT = "infantry.shell.board"

/** RUNTIME-16: the official Infantry_Review tab; the sidebar's "To review" counter shows only when it is loaded. */
private const val REVIEW_TAB = "infantry.review"

/** MOBILE-LAYOUT-1: below PanelLayout.COMPACT_BELOW_DP (600dp) of the middle region the window shows ONE pane. */
@Composable
internal fun dockCompact(): Boolean = PanelLayout.isCompact(LocalBlockSize.current.width.value)

/**
 * MOBILE-DRAWERS-1: the sidebar, docked (wide) or in the compact drawer.
 * PLUGINS-3: the left panel is its modules' sections; Infantry_Agents' one draws the agents list below.
 */
@Composable
internal fun LeftPanelContent(s: Shell, compact: Boolean, sidebarWidthDp: Float) {
    val state = s.state
    val controller = s.controller
    val ui = s.ui
    Column(Modifier.width(sidebarWidthDp.dp).fillMaxHeight().background(Palette.Panel).testTag("left-sidebar")) {
        PanelSections(s.tabs, Panels.LEFT, s.tabContext({}, agentsList = { Sidebar(
            state, s.visible, ui.query, { ui.query = it }, ui.roleFilter, { ui.roleFilter = it },
            s.searchFocus,
            onSelect = { controller.select(it); s.chat.spawnMode = false; if (compact) controller.showCompactScreen(CompactScreen.OUTPUT) },
            onNew = { s.chat.spawnMode = false; ui.showNewAgent = true; if (compact) controller.showCompactScreen(CompactScreen.OUTPUT) },
            onRemove = { id -> s.actionScope.launch { controller.removeSession(id) } },
            onAssign = { id, projectId -> s.actionScope.launch { controller.assignSessionProject(id, projectId) } },
            projects = { ProjectsSidebar(state, onDismiss = { controller.dismissProjectRefusal() }) },
            onOpenBoard = if (BOARD_SLOT in s.tabs.fillModules) ({ ui.showBoard = true }) else null,
            showReview = s.tabs.find(REVIEW_TAB) != null,
        ) }))
    }
}
