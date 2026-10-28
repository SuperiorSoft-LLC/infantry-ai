package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.ui.DRAWER_SCRIM_TAG
import com.superiorsoftllc.agenthq.ui.DRAWER_TAG
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.LocalBlockSize
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.Show
import com.superiorsoftllc.agenthq.ui.model.CompactScreen

/**
 * BLOCKS-SHELL-1 / MOBILE-DRAWERS-1: in a compact window the sidebar or the right panel slides over the middle
 * region as a drawer, with a scrim. BLOCKS-LAYOUT L4: it is `show Compact`, so at full width its parent leaves it out.
 */
object ShellCompactDrawer : Block("infantry.shell.drawer", "infantry.shell.drawer", Place(show = Show.Compact)) {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val windowWidthDp = LocalBlockSize.current.width.value
        val compact = dockCompact()
        val screen = s.state.panels.forWindow(compact).compactScreen()
        if (screen != CompactScreen.OUTPUT) with(scope) {
            val drawerWidth = minOf(windowWidthDp * 0.88f, 420f).dp
            val close = { s.controller.showCompactScreen(CompactScreen.OUTPUT) }
            // Scrim: a tap outside closes the drawer.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).pointerInput(Unit) { detectTapGestures { close() } }.testTag(DRAWER_SCRIM_TAG))
            // A swipe toward the screen edge it came from closes it too.
            val toEdge = if (screen == CompactScreen.LIST) -1f else 1f
            Box(
                Modifier.aligned(if (screen == CompactScreen.LIST) Alignment.CenterStart else Alignment.CenterEnd).width(drawerWidth).fillMaxHeight()
                    .background(Palette.Bg)
                    .pointerInput(screen) {
                        var dx = 0f
                        detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (dx * toEdge > 80f) close() }) { _, d -> dx += d }
                    }
                    .testTag(DRAWER_TAG),
            ) {
                if (screen == CompactScreen.LIST) LeftPanelContent(s, drawerWidth.value) else RightPanelContent(s, Modifier.fillMaxWidth(), close, rightPanelArea(s))
            }
        }
    }
}
