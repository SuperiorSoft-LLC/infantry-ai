package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.agents.*

import com.superiorsoftllc.agenthq.plugins.official.board.*

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ShellOverrideDeclaration
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.AcceptedShellOverride
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
import com.superiorsoftllc.agenthq.ui.block.layout.BlockOverride
import com.superiorsoftllc.agenthq.ui.block.layout.OverrideModes
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/** BLOCKS-LAYOUT L10: a board child (`infantry.shell.board.<name>`) is replaced or wrapped by a module, in the real window. */
@OptIn(ExperimentalTestApi::class)
class BoardOverridesUiTest {
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

    private fun withOpenBoard(tabs: TabFactory, body: ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 860) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null, tabs = tabs) }
                link.push(Envelope(Envelope.READY, "contract=1.92.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                waitForIdle()
                onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).performClick()
                // The modal (and its content) lands a beat after the click: wait on the node, not on idle.
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(BOARD_MODAL_TAG)).fetchSemanticsNodes().isNotEmpty() }
                body()
            }
        } finally {
            scope.cancel()
        }
    }

    private val inBoard = hasAnyAncestor(hasTestTag(BOARD_MODAL_TAG))

    @Test fun replacingTheBoardPaneDrawsTheModuleBlockUnderTheBoardHeader() {
        withOpenBoard(factory("infantry.shell.board.pane", OverrideModes.REPLACE, BlockOverride { scope, inner ->
            Text(if (inner == null) "acme board" else "bad", scope.modifier.testTag("acme-board"))
        })) {
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("acme-board") and inBoard).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasTestTag("acme-board") and inBoard).assertExists()
            // The core board is gone; the header (and its close button) stays, and still closes the modal.
            onNode(hasText("The backlog is empty.") and inBoard).assertDoesNotExist()
            onNodeWithTag(BOARD_MODAL_CLOSE_TAG).performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(BOARD_MODAL_TAG)).fetchSemanticsNodes().isEmpty() }
            onNodeWithTag(BOARD_MODAL_TAG).assertDoesNotExist()
        }
    }

    @Test fun wrappingTheBoardPaneDrawsTheCoreBoardInsideTheModuleFrame() {
        withOpenBoard(factory("infantry.shell#infantry.shell.board.pane", OverrideModes.WRAP, BlockOverride { scope, inner ->
            Box(scope.modifier.testTag("acme-frame")) { inner!!.Render(scope) }
        })) {
            val coreInFrame = hasText("The backlog is empty.") and hasAnyAncestor(hasTestTag("acme-frame"))
            // Controller state reaches the composition a beat after waitForIdle on a cold run: wait on the node itself.
            waitUntil(timeoutMillis = 10_000) { onAllNodes(coreInFrame).fetchSemanticsNodes().isNotEmpty() }
            onNode(coreInFrame).assertExists()
            onNode(hasTestTag("acme-frame") and inBoard).assertExists()
        }
    }
}
