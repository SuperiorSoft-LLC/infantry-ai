package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.board.*

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ShellOverrideDeclaration
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.AcceptedShellOverride
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
import com.superiorsoftllc.agenthq.ui.block.layout.BlockOverride
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.OverrideModes
import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.model.ConnectionInfo
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.net.DaemonClient
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.TicketColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BLOCKS-LAYOUT L16: a board pane section (`infantry.shell.board.pane.<name>`) or the card template
 * (`infantry.shell.board.card` and its sections) is replaced or wrapped by a module, in the real window.
 */
@OptIn(ExperimentalTestApi::class)
class BoardPaneOverridesUiTest {
    private fun factory(node: String, mode: String, override: BlockOverride): TabFactory {
        val decl = ShellOverrideDeclaration(node, "acme", mode)
        val module = AgentHqModule(
            ModuleManifest("Acme_Board", "1.0.0", author = "Acme", license = "NOASSERTION", shell = listOf(decl)),
            tier = Tier.COMMUNITY, shell = mapOf("acme" to override),
        )
        val f = TabFactory(CoreModules.all + InfantryBoard + com.superiorsoftllc.agenthq.plugins.official.InfantryAgents + module, shellOverrides = listOf(AcceptedShellOverride("Acme_Board", decl.nodeId, decl.name, decl.mode)))
        assertEquals(emptyList(), f.problems)
        return f
    }

    /**
     * A fake link that never dials (frontend/ui-test-flake-causes): a real dial to ws://127.0.0.1:1 fails about 1 s in and
     * reports RECONNECTING over the pushed `ready`, which turns the board's buttons off mid-test. It records what the
     * window sends.
     */
    private class NoDialLink(scope: CoroutineScope) : DaemonClient(scope) {
        val sent = CopyOnWriteArrayList<Envelope>()
        private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
        override val frames: SharedFlow<Envelope> get() = incoming
        override val info: StateFlow<ConnectionInfo> = MutableStateFlow(ConnectionInfo(state = ConnState.CONNECTED))
        override fun connect(url: String, token: String?) {}
        override fun disconnect() {}
        override suspend fun send(envelope: Envelope): Boolean { sent += envelope; return true }
        fun push(e: Envelope) {
            val deadline = System.currentTimeMillis() + 10_000
            while (incoming.subscriptionCount.value == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            check(incoming.tryEmit(e)) { "frame buffer full" }
        }
        fun sentOf(type: String) = sent.filter { it.type == type }
    }

    /** The open board with two tickets (one in Backlog, one in To do), in the real window over a fake daemon link. */
    private fun withBoard(tabs: TabFactory, body: ComposeUiTest.(NoDialLink) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = NoDialLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 860) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null, tabs = tabs) }
                link.push(Envelope(Envelope.READY, "contract=1.136.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                link.push(
                    Envelope(
                        Envelope.TICKETS,
                        """[{"id":"t-one","title":"Ticket one","status":"backlog"},{"id":"t-two","title":"Ticket two","status":"todo"}]""",
                    ),
                )
                waitUntil(timeoutMillis = 10_000) { controller.state.value.tickets.size == 2 }
                onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).performClick()
                // Synchronize on the composed UI, not the controller (frontend/ui-test-flake-causes).
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(BOARD_COLUMN_TAG_PREFIX + TicketColumns.TODO).fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                body(link)
            }
        } finally {
            scope.cancel()
        }
    }

    private val inBoard = hasAnyAncestor(hasTestTag(BOARD_MODAL_TAG))

    @Test fun replacingTheFilterRowDrawsTheModuleBlockInThePaneAndTheColumnsAndCardsStay() {
        withBoard(factory("infantry.shell.board.pane.filters", OverrideModes.REPLACE, BlockOverride { scope, inner ->
            Text(if (inner == null) "acme filters" else "bad", scope.modifier.testTag("acme-filters"))
        })) {
            onNode(hasTestTag("acme-filters") and inBoard).assertExists()
            // The core filter row is gone; the columns and both cards stay.
            onNodeWithTag(BOARD_EPIC_FILTER_TAG).assertDoesNotExist()
            onNode(hasText("Start next") and inBoard).assertDoesNotExist()
            onNodeWithTag(BOARD_COLUMN_TAG_PREFIX + TicketColumns.BACKLOG).assertExists()
            onNodeWithTag(TICKET_CARD_TAG_PREFIX + "t-one").assertExists()
            onNodeWithTag(TICKET_CARD_TAG_PREFIX + "t-two").assertExists()
        }
    }

    @Test fun wrappingTheCardActionsWrapsThemOnEveryCardAndAMoveStillGoesThroughTheCard() {
        withBoard(factory("infantry.shell#infantry.shell.board.card.actions", OverrideModes.WRAP, BlockOverride { scope, inner ->
            Column(scope.modifier.testTag("acme-frame")) { inner!!.Render(scope) }
        })) { link ->
            // One frame per card, each around that card's own buttons.
            assertEquals(2, onAllNodesWithTag("acme-frame").fetchSemanticsNodes().size)
            onNode(hasText("Start") and hasAnyAncestor(hasTestTag("acme-frame"))).assertExists()
            onNode(hasText("To do") and hasAnyAncestor(hasTestTag("acme-frame"))).performClick()
            waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.TICKET_MOVE).isNotEmpty() }
            val move = link.sentOf(Envelope.TICKET_MOVE).single().data
            assertTrue("t-one" in move && TicketColumns.TODO in move, move)
            // The rest of the card is the core's, outside the frame.
            onNode(hasText("Ticket one") and hasAnyAncestor(hasTestTag("acme-frame"))).assertDoesNotExist()
            onNodeWithTag(TICKET_CARD_TAG_PREFIX + "t-one").assertExists()
        }
    }

    @Test fun replacingTheCardTemplateDrawsTheModuleCardOncePerTicket() {
        withBoard(factory("infantry.shell.board.card", OverrideModes.REPLACE, BlockOverride { scope, inner ->
            Text(if (inner == null) "acme card" else "bad", scope.modifier.testTag("acme-card"))
        })) {
            assertEquals(2, onAllNodesWithTag("acme-card").fetchSemanticsNodes().size)
            onNode(hasTestTag("acme-card") and hasAnyAncestor(hasTestTag(BOARD_COLUMN_TAG_PREFIX + TicketColumns.BACKLOG))).assertExists()
            onNode(hasTestTag("acme-card") and hasAnyAncestor(hasTestTag(BOARD_COLUMN_TAG_PREFIX + TicketColumns.TODO))).assertExists()
            // The core cards are gone; the filter row and the columns' counts stay.
            onNodeWithTag(TICKET_CARD_TAG_PREFIX + "t-one").assertDoesNotExist()
            onNodeWithTag(TICKET_HANDLE_TAG_PREFIX + "t-two").assertDoesNotExist()
            onNodeWithTag(BOARD_EPIC_FILTER_TAG).assertExists()
        }
    }

    @Test fun replacingACardSectionAppliesToEveryCardAndKeepsTheRestOfTheCard() {
        withBoard(factory("infantry.shell.board.card.summary.title", OverrideModes.REPLACE, BlockOverride { scope, _ ->
            Text("acme title", scope.modifier.testTag("acme-title"))
        })) {
            // The summary is clickable, so it merges its children: look in the unmerged tree.
            assertEquals(2, onAllNodesWithTag("acme-title", useUnmergedTree = true).fetchSemanticsNodes().size)
            onNode(hasTestTag("acme-title") and hasAnyAncestor(hasTestTag(TICKET_CARD_TAG_PREFIX + "t-two")), useUnmergedTree = true).assertExists()
            assertEquals(0, onAllNodesWithText("Ticket one", useUnmergedTree = true).fetchSemanticsNodes().size)
            onNodeWithTag(TICKET_HANDLE_TAG_PREFIX + "t-one").assertExists()
            onNode(hasText("Start") and inBoard).assertExists()
        }
    }

    @Test fun aSectionDrawnOutsideThePaneOrACardDrawsNothingAndDoesNotCrash() = runDesktopComposeUiTest(400, 300) {
        val sections = listOf(
            BoardFilters, BoardUnassigned, BoardNewTicket, BoardEmpty, ShellBoard.children[1].children.last(), ShellTicketCard,
        ) + ShellTicketCard.children + listOf(TicketCardTitle, TicketCardBadges, TicketCardDetails, TicketCardNote, TicketCardEdit, TicketCardActions)
        setContent { Column { for (b in sections) b.Render(BlockScope(Modifier, null)) } }
        waitForIdle()
        for (tag in listOf(BOARD_EPIC_FILTER_TAG, BOARD_COLUMN_TAG_PREFIX + TicketColumns.TODO)) onNodeWithTag(tag).assertDoesNotExist()
        for (text in listOf("Start next", "+ New ticket", "The backlog is empty.", "Connect to a daemon to load its backlog.", "Start", "Reopen")) {
            assertEquals(0, onAllNodesWithText(text).fetchSemanticsNodes().size, text)
        }
    }
}
