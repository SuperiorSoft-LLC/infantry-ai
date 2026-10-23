package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.AttentionItem
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PANEL-TOGGLE-1: the title bar's two panel toggles hide and show the left sidebar and the right panel, and anything
 * that targets a hidden panel shows it first (docs/AGENTHQ-MOBILE-UI-PLAN.md section 2.3, rules 5 and 6).
 *
 * The real window ([App]) against a fake link: panel state is client-only, so no daemon is needed. Tags are spelled
 * out (not the production constants) so this file is the spec the UI must meet.
 */
@OptIn(ExperimentalTestApi::class)
class PanelToggleUiTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun withWindow(body: ComposeUiTest.(ChangesFakeLink, Controller) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1700, 1000) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null) }
                waitForIdle()
                body(link, controller)
            }
        } finally {
            scope.cancel()
        }
    }

    private fun ComposeUiTest.shown(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.assertShown(tag: String, shown: Boolean, why: String) {
        waitForIdle()
        assertTrue(shown(tag) == shown, "$why: expected '$tag' ${if (shown) "on screen" else "gone"}")
    }

    /** Sent through a node of the main window: a toggle's tooltip popup is a second root, so onRoot() is ambiguous. */
    private fun ComposeUiTest.ctrl(key: Key) {
        onNodeWithTag("title-bar").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(key) } }
        waitForIdle()
    }

    /**
     * BUG-PANEL-TOGGLE-1: launch focus lands on the window root, not on the first title-bar toggle, so no toggle tooltip
     * is open (nothing is hovered) and the window has exactly one compose root.
     */
    @Test
    fun `no toggle tooltip is open at launch and the window has one root`() = withWindow { _, _ ->
        assertEquals(0, onAllNodes(hasTestTag("icon-action-tooltip"), useUnmergedTree = true).fetchSemanticsNodes().size, "tooltip open at launch")
        assertEquals(1, onAllNodes(isRoot()).fetchSemanticsNodes().size, "compose roots at launch")
    }

    @Test
    fun `the left toggle hides and restores the sidebar and the right toggle hides and restores the right panel`() = withWindow { _, _ ->
        assertShown("left-sidebar", true, "first launch")
        assertShown("right-panel", true, "first launch")

        onNodeWithTag("title-bar-toggle-left").performClick()
        assertShown("left-sidebar", false, "left toggle, first click")
        assertShown("sidebar-resize-handle", false, "left toggle, first click")
        assertShown("right-panel", true, "the left toggle leaves the right panel alone")
        onNodeWithTag("title-bar-toggle-left").performClick()
        assertShown("left-sidebar", true, "left toggle, second click")
        assertShown("sidebar-resize-handle", true, "left toggle, second click")

        onNodeWithTag("title-bar-toggle-right").performClick()
        assertShown("right-panel", false, "right toggle, first click")
        assertShown("right-panel-resize-handle", false, "right toggle, first click")
        assertShown("left-sidebar", true, "the right toggle leaves the sidebar alone")
        onNodeWithTag("title-bar-toggle-right").performClick()
        assertShown("right-panel", true, "right toggle, second click")
        assertShown("right-panel-resize-handle", true, "right toggle, second click")
    }

    @Test
    fun `the attention chip and Ctrl+3 4 6 7 reveal a hidden right panel`() = withWindow { link, controller ->
        val item = AttentionItem(id = "a1", at = 1, kind = "trigger", outcome = "tripped", detail = "x")
        link.push(Envelope(Envelope.ATTENTION, json.encodeToString(AttentionItem.serializer(), item)))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.attentionOpenCount == 1 }

        onNodeWithTag("title-bar-toggle-right").performClick()
        assertShown("right-panel", false, "hidden before the chip")
        onNodeWithTag("title-bar-attention-chip").performClick()
        assertShown("right-panel", true, "the attention chip targets the right panel")

        for (key in listOf(Key.Three, Key.Four, Key.Six, Key.Seven)) {
            onNodeWithTag("title-bar-toggle-right").performClick()
            assertShown("right-panel", false, "hidden before Ctrl+$key")
            ctrl(key)
            assertShown("right-panel", true, "Ctrl+$key targets the right panel")
        }
    }

    @Test
    fun `Ctrl+K reveals a hidden sidebar and focuses its search box`() = withWindow { _, _ ->
        onNodeWithTag("title-bar-toggle-left").performClick()
        assertShown("left-sidebar", false, "hidden before Ctrl+K")
        // Focusing a requester whose node is not composed throws IllegalStateException: Ctrl+K must show first.
        ctrl(Key.K)
        assertShown("left-sidebar", true, "Ctrl+K targets the sidebar")
        onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("sidebar-search"))).assertIsFocused()
    }
}
