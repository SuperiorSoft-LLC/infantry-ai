package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.agents.*

import com.superiorsoftllc.agenthq.plugins.official.header.*

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * UX-POLISH-1: the owner's two layout complaints, measured from semantics bounds. The sidebar's Board row and
 * search field sat flush; the title bar's "+" was a text glyph with no tooltip, off-centre in its box.
 */
@OptIn(ExperimentalTestApi::class)
class UxPolishLayoutUiTest {
    private val DpRect.centerX get() = (left + right) / 2
    private val DpRect.centerY get() = (top + bottom) / 2

    @Test fun `the sidebar search field sits at least one spacing token below the Board row`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runDesktopComposeUiTest(1700, 1000) {
                setContent { App(Controller(scope), "ws://127.0.0.1:1/ws", null) }
                val board = onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).getBoundsInRoot()
                val search = onNodeWithTag(SIDEBAR_SEARCH_TAG).getBoundsInRoot()
                val gap = search.top - board.bottom
                assertTrue(gap >= SidebarSpacing, "search top - Board bottom = $gap, want >= $SidebarSpacing")
            }
        } finally { scope.cancel() }
    }

    @Test fun `the New project button is an Add icon centred in a box as tall as the project drop-down, with a tooltip`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = Controller(scope)
            runDesktopComposeUiTest(900, 300) {
                setContent {
                    val state by controller.state.collectAsState()
                    ProjectPicker(state, controller)
                }
                val button = onNodeWithTag(NEW_PROJECT_BUTTON_TAG)
                val tooltip = button.fetchSemanticsNode().config.getOrElseNullable(IconActionTooltip) { null }
                assertTrue(!tooltip.isNullOrBlank(), "the + names its action in a tooltip, got '$tooltip'")
                val desc = button.fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }
                assertTrue(!desc.isNullOrEmpty() && desc.all { it.isNotBlank() }, "the + has a content description")

                val box = button.getBoundsInRoot()
                val icon = onNodeWithTag(NEW_PROJECT_ICON_TAG, useUnmergedTree = true).getBoundsInRoot()
                val picker = onNodeWithTag(PROJECT_PICKER_TAG).getBoundsInRoot()
                assertTrue(abs((icon.centerX - box.centerX).value) <= 1f, "icon x-centre ${icon.centerX} vs button ${box.centerX}")
                assertTrue(abs((icon.centerY - box.centerY).value) <= 1f, "icon y-centre ${icon.centerY} vs button ${box.centerY}")
                assertTrue(abs((box.centerY - picker.centerY).value) <= 1f, "button y-centre ${box.centerY} vs drop-down ${picker.centerY}")
                assertTrue(abs((box.height - picker.height).value) <= 1f, "button height ${box.height} vs drop-down ${picker.height}")
                assertTrue(abs((box.width - box.height).value) <= 1f, "the button is square: ${box.width} x ${box.height}")
            }
        } finally { scope.cancel() }
    }
}
