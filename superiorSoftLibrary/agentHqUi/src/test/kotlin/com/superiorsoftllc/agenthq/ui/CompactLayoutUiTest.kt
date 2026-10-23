package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click as touchClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.AttentionItem
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MOBILE-LAYOUT-1 (gap G1 of MOBILE-ANDROID-1): below the compact breakpoint the window shows ONE pane at a time (the
 * agent list, the selected agent's output, or the right panel) instead of a 3-pane Row that leaves the output ~0dp
 * wide on a ~411dp phone. At desktop width the 3-pane layout is unchanged.
 *
 * The real window ([App]) against a fake link. Tags are spelled out (not the production constants) so this file is
 * the spec the UI must meet.
 */
@OptIn(ExperimentalTestApi::class)
class CompactLayoutUiTest {
    private val sid = "s-phone-1"
    /** The fake link of the window [withWindow] opened, for tests that push more envelopes. */
    private lateinit var windowLink: ChangesFakeLink

    private fun withWindow(width: Int, height: Int = 800, body: ComposeUiTest.(Controller) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope).also { windowLink = it }
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(width, height) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null) }
                link.push(Envelope(Envelope.READY, "contract=1.92.0 provider=stub instance=i1"))
                link.push(
                    Envelope(
                        Envelope.SESSIONS,
                        """[{"id":"$sid","provider":"claude","workingDir":"/w","spawnedAt":1,"lastHeartbeat":1,"state":"IDLE"}]""",
                    ),
                )
                waitUntil(timeoutMillis = 10_000) { controller.state.value.agents.containsKey(sid) }
                waitForIdle()
                body(controller)
            }
        } finally {
            scope.cancel()
        }
    }

    /**
     * MOBILE-LAYOUT-2: the real window inside a box whose width the test changes, which is what a rotation or a resize
     * across the breakpoint does to [App]'s BoxWithConstraints. [resize] takes the new width in dp (density 1 here).
     */
    private fun withResizableWindow(startWidth: Int, body: ComposeUiTest.(Controller, ChangesFakeLink, resize: (Int) -> Unit) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope)
            val controller = Controller(scope, link)
            var width by mutableStateOf(startWidth)
            runDesktopComposeUiTest(1400, 900) {
                setContent { Box(Modifier.width(width.dp).fillMaxHeight()) { App(controller, "ws://127.0.0.1:1/ws", null) } }
                link.push(Envelope(Envelope.READY, "contract=1.92.0 provider=stub instance=i1"))
                link.push(
                    Envelope(
                        Envelope.SESSIONS,
                        """[{"id":"$sid","provider":"claude","workingDir":"/w","spawnedAt":1,"lastHeartbeat":1,"state":"IDLE"}]""",
                    ),
                )
                waitUntil(timeoutMillis = 10_000) { controller.state.value.agents.containsKey(sid) }
                waitForIdle()
                body(controller, link) { w -> width = w; waitForIdle() }
            }
        } finally {
            scope.cancel()
        }
    }

    private fun ComposeUiTest.shown(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.width(tag: String): Float = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.width

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    /** MOBILE-DRAWERS-1: the chat is the full-width surface; both panels are closed drawers. */
    @Test
    fun `at phone width the chat is full width and both drawers are closed`() = withWindow(400) { _ ->
        assertTrue(shown("center-pane"), "the chat shows")
        assertEquals(400f, width("center-pane"), "the chat is full width")
        assertTrue(!shown("left-sidebar") && !shown("right-panel") && !shown("compact-drawer"), "drawers are closed")
    }

    @Test
    fun `at phone width the agent list opens over the chat, selecting closes it`() = withWindow(400) { controller ->
        click("compact-open-left")
        assertTrue(shown("left-sidebar") && shown("compact-drawer") && shown("compact-drawer-scrim"), "the drawer and its scrim show")
        assertTrue(width("left-sidebar") < 400f, "the drawer leaves a strip of the chat visible")
        assertEquals(400f, width("center-pane"), "the chat keeps its full width under the drawer")

        click("agent-row-$sid")
        assertEquals(sid, controller.state.value.selectedId)
        assertTrue(!shown("left-sidebar"), "selecting closes the drawer")
        assertTrue(width("agent-header-title") > 0f, "the selected agent's header is on screen")
    }

    @Test
    fun `at phone width tapping the scrim closes the agent list drawer`() = withWindow(400) { _ ->
        click("compact-open-left")
        onNodeWithTag("compact-drawer-scrim").performTouchInput { touchClick(Offset(width - 5f, height / 2f)) }
        waitForIdle()
        assertTrue(!shown("left-sidebar"), "the scrim tap closes the drawer")
        assertTrue(shown("center-pane"))
    }

    @Test
    fun `at phone width the right panel opens over the chat from a button and closes`() = withWindow(400) { _ ->
        click("compact-open-right")
        assertTrue(shown("right-panel"), "the button opens the right panel")
        assertTrue(shown("center-pane") && !shown("left-sidebar"), "the chat stays under it")
        assertTrue(width("right-panel") < 400f, "the panel is a drawer, not full width")
        assertEquals(400f, width("center-pane"), "the chat keeps its full width")

        click("compact-back")
        assertTrue(!shown("right-panel"), "the close button closes the drawer")
    }

    /**
     * MOBILE-LAYOUT-2 problem 1: wide with both side panels open, the window narrows (rotation to portrait), the owner
     * opens an agent, the window widens again. The wide layout must come back as it was: list, output and right panel.
     */
    @Test
    fun `a wide layout comes back as it was after a compact visit that opened an agent`() = withResizableWindow(1400) { _, _, resize ->
        assertTrue(shown("left-sidebar") && shown("center-pane") && shown("right-panel"), "wide: three panes before narrowing")
        resize(400)
        assertTrue(!shown("left-sidebar") && shown("center-pane"), "compact: the chat")
        click("compact-open-left")
        click("agent-row-$sid")
        assertTrue(shown("center-pane") && !shown("left-sidebar"), "compact: the agent's output")
        resize(1400)
        val panes = listOf("left-sidebar", "center-pane", "right-panel").associateWith { shown(it) }
        assertEquals(mapOf("left-sidebar" to true, "center-pane" to true, "right-panel" to true), panes, "wide again: the panes on screen")
    }

    /**
     * MOBILE-LAYOUT-2 problem 2: at phone width the Board opens from the list; a ticket's "Agent" button must be on
     * screen with a real width, and pressing it must close the Board on that agent's output, not leave the list showing.
     * The in-progress column may sit right of the phone's width: when its header is cut off, the test swipes the board to
     * that column first, as a thumb would. A column already fully on screen is left alone.
     */
    @Test
    fun `at phone width the Board's Agent button opens that agent's output`() = withResizableWindow(400) { controller, link, _ ->
        link.push(
            Envelope(
                Envelope.TICKETS,
                """[{"id":"t-phone-1","title":"Phone ticket","status":"in_progress","sessionId":"$sid"}]""",
            ),
        )
        waitUntil(timeoutMillis = 10_000) { controller.state.value.tickets.any { it.id == "t-phone-1" } }
        click("compact-open-left")
        click("sidebar-board-button")
        val inProgress = onNode(hasText("IN PROGRESS") and hasAnyAncestor(hasTestTag("board-modal")))
        val header = inProgress.fetchSemanticsNode()
        if (header.boundsInRoot.width < header.size.width) {
            inProgress.performScrollTo()
            waitForIdle()
        }
        val agentButton = onNode(hasText("Agent") and hasAnyAncestor(hasTestTag("board-modal")))
        val bounds = agentButton.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width >= 24f && bounds.height >= 24f, "the Agent button on screen at 400px: $bounds")
        agentButton.performClick()
        waitForIdle()
        assertTrue(!shown("board-modal"), "the Board closes")
        assertEquals(sid, controller.state.value.selectedId, "the ticket's agent is selected")
        val panes = listOf("left-sidebar", "center-pane", "right-panel").associateWith { shown(it) }
        assertEquals(mapOf("left-sidebar" to false, "center-pane" to true, "right-panel" to false), panes, "compact: the chat after Agent")
    }

    @Test
    fun `at desktop width the three panes are side by side as before`() = withWindow(1400, 1000) { _ ->
        assertTrue(shown("left-sidebar") && shown("center-pane") && shown("right-panel"), "three panes")
        assertTrue(shown("sidebar-resize-handle") && shown("right-panel-resize-handle"), "both resize handles")
        assertEquals(260f, width("left-sidebar"), "sidebar default width")
        assertEquals(320f, width("right-panel"), "right panel default width")
        assertTrue(!shown("compact-back") && !shown("compact-open-right"), "no compact navigation on desktop")

        click("agent-row-$sid")
        assertTrue(shown("left-sidebar") && shown("center-pane") && shown("right-panel"), "selecting keeps the three panes")
    }

    // ---- MOBILE-TITLEBAR-1 (gap G2): the title bar at phone width ----

    private fun ComposeUiTest.pushAttention(controller: Controller) {
        val item = AttentionItem(id = "a-phone-1", at = 1, kind = "trigger", outcome = "tripped", detail = "x")
        windowLink.push(Envelope(Envelope.ATTENTION, Json.encodeToString(AttentionItem.serializer(), item)))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.attentionOpenCount == 1 }
        waitForIdle()
    }

    /** The node's laid-out box, unclipped: where it starts and how wide it really is (boundsInRoot is clipped). */
    private fun ComposeUiTest.extent(node: androidx.compose.ui.semantics.SemanticsNode): Pair<Float, Float> =
        node.positionInRoot.x to node.positionInRoot.x + node.size.width

    /**
     * RED on the base (measured at 360: the connection chip is 186 wide and only 33 of it is on screen, the avatar strip
     * is 0 wide). The key nodes must be displayed whole, and no node of the bar may extend past the window's right edge.
     */
    @Test
    fun `at 360dp the title bar's key nodes are displayed and none extend past the window`() = withWindow(360) { controller ->
        pushAttention(controller)
        val key = mapOf(
            "logo" to onNodeWithTag("title-bar-logo"),
            "title" to onNode(hasText("AgentHQ") and hasAnyAncestor(hasTestTag("title-bar"))),
            "connection chip" to onNodeWithTag("title-bar-connection-chip"),
            "overflow" to onNodeWithTag("title-bar-overflow"),
        )
        key.forEach { (name, n) ->
            n.assertIsDisplayed()
            val node = n.fetchSemanticsNode()
            val (left, right) = extent(node)
            assertTrue(node.size.width > 0 && right <= 360f, "$name spans $left..$right at 360dp")
            assertEquals(node.size.width.toFloat(), node.boundsInRoot.width, "$name is clipped: ${node.boundsInRoot} of ${node.size}")
        }
        onAllNodes(hasAnyAncestor(hasTestTag("title-bar"))).fetchSemanticsNodes().forEach { node ->
            val (left, right) = extent(node)
            assertTrue(right <= 360f, "a title-bar node spans $left..$right, past 360dp: ${node.config}")
        }
        // Squeezed into what is left, a one-line text truncates inside a box that still fits: every text must lay out whole.
        onAllNodes(hasAnyAncestor(hasTestTag("title-bar")) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes().forEach { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                layouts.forEach {
                    // Vertical glyph overflow (the logo's tight line height) is styling; a dropped character is a cut.
                    val text = it.layoutInput.text
                    val shownTo = it.getLineEnd(it.lineCount - 1, visibleEnd = true)
                    assertTrue(it.lineCount == 1 && shownTo == text.length, "'$text' is cut off at 360dp: ${it.lineCount} line(s), shows ${text.take(shownTo)}")
                }
            }
    }

    /** The secondary items go into the overflow menu: the project picker, the attention entry, the side-panel toggle. */
    @Test
    fun `at 360dp the overflow menu holds the project picker, the attention entry and the side panel toggle`() = withWindow(360) { controller ->
        pushAttention(controller)
        assertTrue(!shown("title-bar-project-slot") && !shown("title-bar-attention-chip") && !shown("title-bar-toggle-right"), "secondary items are not in the bar")
        click("title-bar-overflow")
        listOf("title-bar-project-slot", "title-bar-overflow-attention", "title-bar-toggle-right").forEach { onNodeWithTag(it).assertIsDisplayed() }
        click("title-bar-toggle-right")
        assertTrue(!shown("title-bar-toggle-right"), "picking an entry closes the menu")
        assertTrue(controller.state.value.panels.rightShown, "the side panel entry opens the closed right drawer")
        assertTrue(shown("right-panel"), "the drawer is on screen")
    }

    /**
     * BUG-COMPACT-ATTN-1: on the list screen at 360dp, the overflow's attention entry must SHOW the attention panel. It
     * used to set the right-panel flag only, and the list (which wins in compactScreen()) stayed on screen.
     */
    @Test
    fun `at 360dp the overflow attention entry shows the right panel instead of the list`() = withWindow(360) { controller ->
        pushAttention(controller)
        assertTrue(!shown("left-sidebar") && !shown("right-panel"), "drawers start closed")
        click("title-bar-overflow")
        click("title-bar-overflow-attention")
        val panes = listOf("left-sidebar", "center-pane", "right-panel").associateWith { shown(it) }
        assertEquals(mapOf("left-sidebar" to false, "center-pane" to true, "right-panel" to true), panes, "compact: the screen after attention")
        onNodeWithTag("right-panel").assertIsDisplayed()
    }

    /** BUG-COMPACT-ATTN-1: the Ctrl right-tab shortcuts go through the same path; Ctrl+6 (attention) at 360dp shows the right panel. */
    @Test
    fun `at 360dp the Ctrl+6 attention shortcut shows the right panel instead of the list`() = withWindow(360) { _ ->
        assertTrue(!shown("left-sidebar") && !shown("right-panel"), "drawers start closed")
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Six) } }
        waitForIdle()
        val panes = listOf("left-sidebar", "center-pane", "right-panel").associateWith { shown(it) }
        assertEquals(mapOf("left-sidebar" to false, "center-pane" to true, "right-panel" to true), panes, "compact: the screen after Ctrl+6")
    }

    /** The wide bar is unchanged: no overflow, and the chips, toggle, picker and avatar strip in the bar itself. */
    @Test
    fun `at desktop width the title bar has no overflow menu and keeps its chips`() = withWindow(1400, 1000) { controller ->
        pushAttention(controller)
        assertTrue(!shown("title-bar-overflow"), "no overflow on desktop")
        listOf("title-bar-project-slot", "title-bar-attention-chip", "title-bar-toggle-right", "title-bar-connection-chip", "title-bar-avatar-$sid")
            .forEach { onNodeWithTag(it).assertIsDisplayed() }
        assertEquals(186, onNodeWithTag("title-bar-connection-chip").fetchSemanticsNode().size.width, "the chip keeps its URL")
    }
}
