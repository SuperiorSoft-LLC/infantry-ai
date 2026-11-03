// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.header.*

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.window.WindowPlacement
import com.superiorsoftllc.infantry.ui.model.AgentScope
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import androidx.compose.ui.window.WindowState
import java.awt.Rectangle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M2-6: the undecorated desktop window. [DesktopWindowChrome] maps the title bar's buttons onto Compose's
 * [WindowState]; [resizedBounds] is the edge-resize maths the custom resize handles apply (an undecorated
 * AWT frame has no native resize border on Windows).
 */
@OptIn(ExperimentalTestApi::class)
class DesktopWindowChromeTest {
    @Test
    fun minimizeMaximizeRestoreAndCloseDriveTheWindowState() {
        val state = WindowState(placement = WindowPlacement.Floating)
        var closed = 0
        val chrome = DesktopWindowChrome(state, onClose = { closed++ })

        chrome.minimize()
        assertTrue(state.isMinimized, "minimize must minimize the window")

        assertFalse(chrome.isMaximized)
        chrome.toggleMaximize()
        assertEquals(WindowPlacement.Maximized, state.placement)
        assertTrue(chrome.isMaximized)
        chrome.toggleMaximize()
        assertEquals(WindowPlacement.Floating, state.placement, "a second toggle restores")

        chrome.close()
        assertEquals(1, closed)
    }

    @Test
    fun rightAndBottomEdgesGrowTheWindow() {
        val start = Rectangle(100, 100, 1000, 700)
        assertEquals(Rectangle(100, 100, 1050, 700), resizedBounds(start, ResizeEdge.RIGHT, 50, 30, 600, 400))
        assertEquals(Rectangle(100, 100, 1000, 730), resizedBounds(start, ResizeEdge.BOTTOM, 50, 30, 600, 400))
        assertEquals(Rectangle(100, 100, 1050, 730), resizedBounds(start, ResizeEdge.BOTTOM_RIGHT, 50, 30, 600, 400))
    }

    @Test
    fun leftAndTopEdgesMoveTheOriginAndKeepTheOppositeEdge() {
        val start = Rectangle(100, 100, 1000, 700)
        assertEquals(Rectangle(80, 100, 1020, 700), resizedBounds(start, ResizeEdge.LEFT, -20, 0, 600, 400))
        assertEquals(Rectangle(100, 130, 1000, 670), resizedBounds(start, ResizeEdge.TOP, 0, 30, 600, 400))
        // Shrinking past the minimum stops at it, and the right edge stays where it was.
        assertEquals(Rectangle(500, 100, 600, 700), resizedBounds(start, ResizeEdge.LEFT, 900, 0, 600, 400))
    }

    @Test
    fun bothDesktopEntriesOpenTheUndecoratedWindow() {
        for (path in listOf("Main.kt", "RealDaemonMain.kt")) {
            val src = File("src/main/kotlin/com/superiorsoftllc/infantry/ui/$path").readText()
            assertTrue("InfantryWindow(" in src, "$path must open the shared undecorated InfantryWindow")
        }
    }

    /**
     * DESKTOP-NARROW-CHROME-1: the 138dp of caption buttons (3 x 46dp) only fit because the window never gets narrower
     * than [MIN_SIZE]. Measured at 360dp with chrome: Close shows 15 of its 46dp (CONNECTED) or none (RECONNECTING).
     * At the floor, in the widest-chip state, all three buttons are whole and the bar keeps a drag area to move the window.
     */
    @Test
    fun atTheMinimumWidthTheCaptionButtonsAreWholeAndTheBarStillDrags() {
        for (conn in listOf(ConnState.RECONNECTING, ConnState.CONNECTED)) {
            val state = AppState(connection = ConnectionInfo(state = conn, url = "ws://127.0.0.1:1/ws", attempt = 3))
            val width = MIN_SIZE.width
            runDesktopComposeUiTest(width, 40) {
                setContent { TitleBar(state, AgentScope.GLOBAL, onConnect = {}, onAttention = {}, chrome = InertChrome, onTogglePanel = {}) }
                waitForIdle()
                fun node(tag: String) = onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag(TITLE_BAR_TAG))).fetchSemanticsNodes().single()
                for (tag in listOf(TITLE_BAR_MINIMIZE_TAG, TITLE_BAR_MAXIMIZE_TAG, TITLE_BAR_CLOSE_TAG)) {
                    val n = node(tag)
                    val right = n.positionInRoot.x + n.size.width
                    assertTrue(
                        n.size.width == 46 && n.boundsInRoot.width == 46f && right <= width,
                        "$conn at ${width}dp: $tag spans ${n.positionInRoot.x}..$right, ${n.boundsInRoot.width} of 46dp on screen",
                    )
                }
                val drag = node(TITLE_BAR_DRAG_TAG).boundsInRoot.width
                assertTrue(drag >= 48f, "$conn at ${width}dp: the drag area is ${drag}dp, under 48dp")
            }
        }
    }

    private object InertChrome : WindowChrome {
        override val isMaximized = false
        override fun minimize() {}
        override fun toggleMaximize() {}
        override fun close() {}
    }
}
