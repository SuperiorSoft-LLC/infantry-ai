package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
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

/** BLOCKS-LAYOUT L6: a header child (`infantry.shell.header.<name>`) is replaced or wrapped by a module, in the real window. */
@OptIn(ExperimentalTestApi::class)
class HeaderOverridesUiTest {
    private fun factory(node: String, mode: String, override: BlockOverride): TabFactory {
        val decl = ShellOverrideDeclaration(node, "acme", mode)
        val module = AgentHqModule(
            ModuleManifest("Acme_Header", "1.0.0", author = "Acme", license = "NOASSERTION", shell = listOf(decl)),
            tier = Tier.COMMUNITY, shell = mapOf("acme" to override),
        )
        val f = TabFactory(CoreModules.all + module, shellOverrides = listOf(AcceptedShellOverride("Acme_Header", decl.nodeId, decl.name, decl.mode)))
        assertEquals(emptyList(), f.problems)
        return f
    }

    private fun withWindow(tabs: TabFactory, body: ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 860) {
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
}
