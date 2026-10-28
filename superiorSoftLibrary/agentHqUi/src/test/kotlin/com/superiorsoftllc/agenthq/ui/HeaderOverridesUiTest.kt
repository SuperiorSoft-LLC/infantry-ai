package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.header.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
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

/**
 * BLOCKS-LAYOUT L6/L7: a header child (`infantry.shell.header.<name>`, and since L7 a lead child
 * `infantry.shell.header.lead.<name>`; since L8 the compact overflow `infantry.shell.header.lead.overflow` and its
 * entries) is replaced or wrapped by a module, in the real window.
 */
@OptIn(ExperimentalTestApi::class)
class HeaderOverridesUiTest {
    private fun factory(node: String, mode: String, override: BlockOverride): TabFactory {
        val decl = ShellOverrideDeclaration(node, "acme", mode)
        val module = AgentHqModule(
            ModuleManifest("Acme_Header", "1.0.0", author = "Acme", license = "NOASSERTION", shell = listOf(decl)),
            tier = Tier.COMMUNITY, shell = mapOf("acme" to override),
        )
        val f = TabFactory(CoreModules.all + com.superiorsoftllc.agenthq.plugins.official.header.InfantryHeader + module, shellOverrides = listOf(AcceptedShellOverride("Acme_Header", decl.nodeId, decl.name, decl.mode)))
        assertEquals(emptyList(), f.problems)
        return f
    }

    private fun withWindow(tabs: TabFactory, width: Int = 1400, body: ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(width, 860) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null, tabs = tabs) }
                link.push(Envelope(Envelope.READY, "contract=1.92.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                waitForIdle()
                body()
            }
        } finally {
            scope.cancel()
        }
    }

    private val inBar = hasAnyAncestor(hasTestTag(TITLE_BAR_TAG))

    @Test fun replacingTheConnectionChipDrawsTheModuleBlockInItsPlace() = withWindow(
        factory("infantry.shell.header.connection", OverrideModes.REPLACE, BlockOverride { scope, inner ->
            Text(if (inner == null) "acme connection" else "bad", scope.modifier.testTag("acme-connection"))
        }),
    ) {
        onNode(hasTestTag("acme-connection") and inBar).assertExists()
        onNodeWithTag(TITLE_BAR_CONNECTION_CHIP_TAG).assertDoesNotExist()
        // The siblings stay: the side-panel toggle before it.
        onNode(hasTestTag(TITLE_BAR_TOGGLE_RIGHT_TAG) and inBar).assertExists()
    }

    @Test fun wrappingTheSidePanelToggleDrawsTheCoreToggleInsideTheModuleFrame() = withWindow(
        factory("infantry.shell#infantry.shell.header.toggleRight", OverrideModes.WRAP, BlockOverride { scope, inner ->
            Box(Modifier.testTag("acme-frame")) { inner!!.Render(scope) }
        }),
    ) {
        onNode(hasTestTag(TITLE_BAR_TOGGLE_RIGHT_TAG) and hasAnyAncestor(hasTestTag("acme-frame"))).assertExists()
        onNode(hasTestTag("acme-frame") and inBar).assertExists()
        onNodeWithTag(TITLE_BAR_CONNECTION_CHIP_TAG).assertExists()
    }

    @Test fun replacingTheProjectSlotDrawsTheModuleBlockBetweenTheBrandAndTheAgentStrip() = withWindow(
        factory("infantry.shell.header.lead.project", OverrideModes.REPLACE, BlockOverride { scope, inner ->
            Text(if (inner == null) "acme project" else "bad", scope.modifier.testTag("acme-project"))
        }),
    ) {
        onNode(hasTestTag("acme-project") and inBar).assertExists()
        onNodeWithTag(TITLE_BAR_PROJECT_SLOT_TAG).assertDoesNotExist()
        // The lead's other children stay: the left toggle, the logo and the drag area (the agent strip).
        onNode(hasTestTag(TITLE_BAR_TOGGLE_LEFT_TAG) and inBar).assertExists()
        onNode(hasTestTag(TITLE_BAR_LOGO_TAG) and inBar).assertExists()
        onNode(hasTestTag(TITLE_BAR_DRAG_TAG) and inBar).assertExists()
    }

    @Test fun wrappingTheLeftToggleDrawsTheCoreToggleInsideTheModuleFrame() = withWindow(
        factory("infantry.shell#infantry.shell.header.lead.toggleLeft", OverrideModes.WRAP, BlockOverride { scope, inner ->
            Row(Modifier.testTag("acme-frame")) { inner!!.Render(scope) }
        }),
    ) {
        onNode(hasTestTag(TITLE_BAR_TOGGLE_LEFT_TAG) and hasAnyAncestor(hasTestTag("acme-frame"))).assertExists()
        onNode(hasTestTag("acme-frame") and inBar).assertExists()
        onNode(hasTestTag(TITLE_BAR_PROJECT_SLOT_TAG) and inBar).assertExists()
    }

    @Test fun replacingTheOverflowDrawsTheModuleBlockInACompactBarOnly() {
        val tabs = factory("infantry.shell.header.lead.overflow", OverrideModes.REPLACE, BlockOverride { scope, inner ->
            Text(if (inner == null) "acme more" else "bad", scope.modifier.testTag("acme-overflow"))
        })
        withWindow(tabs, width = 800) {
            onNode(hasTestTag("acme-overflow") and inBar).assertExists()
            onNodeWithTag(TITLE_BAR_OVERFLOW_TAG).assertDoesNotExist()
            // The lead's drag area stays, and so do the items after the lead.
            onNode(hasTestTag(TITLE_BAR_DRAG_TAG) and inBar).assertExists()
            onNode(hasTestTag(TITLE_BAR_CONNECTION_CHIP_TAG) and inBar).assertExists()
        }
        // The replacement keeps the node's `show Compact`: a wide bar draws neither.
        withWindow(tabs) {
            onNodeWithTag("acme-overflow").assertDoesNotExist()
            onNodeWithTag(TITLE_BAR_OVERFLOW_TAG).assertDoesNotExist()
        }
    }

    @Test fun wrappingTheOverflowsConnectionEntryDrawsTheCoreEntryInsideTheModuleFrame() = withWindow(
        factory("infantry.shell#infantry.shell.header.lead.overflow.connection", OverrideModes.WRAP, BlockOverride { scope, inner ->
            Column(Modifier.testTag("acme-frame")) { inner!!.Render(scope) }
        }),
        width = 800,
    ) {
        onNode(hasTestTag(TITLE_BAR_OVERFLOW_TAG) and inBar).performClick()
        waitForIdle()
        onNode(hasTestTag(TITLE_BAR_OVERFLOW_CONNECTION_TAG) and hasAnyAncestor(hasTestTag("acme-frame"))).assertExists()
        onNode(hasTestTag("acme-frame") and hasAnyAncestor(hasTestTag(TITLE_BAR_OVERFLOW_MENU_TAG))).assertExists()
        // The menu's other entries stay: the project picker comes first.
        onNode(hasTestTag(TITLE_BAR_PROJECT_SLOT_TAG) and hasAnyAncestor(hasTestTag(TITLE_BAR_OVERFLOW_MENU_TAG))).assertExists()
    }
}
