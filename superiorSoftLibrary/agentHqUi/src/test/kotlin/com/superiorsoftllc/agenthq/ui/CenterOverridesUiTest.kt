package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Column
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

/**
 * BLOCKS-LAYOUT L9b: a centre child (`infantry.shell.center.chat.<name>`) is replaced or wrapped by a module, in the real
 * window, at desktop width and compact.
 */
@OptIn(ExperimentalTestApi::class)
class CenterOverridesUiTest {
    private fun factory(node: String, mode: String, override: BlockOverride): TabFactory {
        val decl = ShellOverrideDeclaration(node, "acme", mode)
        val module = AgentHqModule(
            ModuleManifest("Acme_Center", "1.0.0", author = "Acme", license = "NOASSERTION", shell = listOf(decl)),
            tier = Tier.COMMUNITY, shell = mapOf("acme" to override),
        )
        val f = TabFactory(CoreModules.all + com.superiorsoftllc.agenthq.plugins.official.chat.InfantryChat + module, shellOverrides = listOf(AcceptedShellOverride("Acme_Center", decl.nodeId, decl.name, decl.mode)))
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

    private val inCenter = hasAnyAncestor(hasTestTag(CENTER_PANE_TAG))

    private val replaceTabs = BlockOverride { scope, inner ->
        Text(if (inner == null) "acme tabs" else "bad", scope.modifier.testTag("acme-tabs"))
    }

    @Test fun replacingTheCentreTabStripDrawsTheModuleBlockAboveTheTranscript() {
        withWindow(factory("infantry.shell.center.chat.tabs", OverrideModes.REPLACE, replaceTabs)) {
            onNode(hasTestTag("acme-tabs") and inCenter).assertExists()
            onNodeWithTag(CENTER_TAB_BAR_TAG).assertDoesNotExist()
            // The chat's other children stay: the transcript and the composer.
            onNode(hasTestTag(CENTER_TRANSCRIPT_TAG) and inCenter).assertExists()
            onNode(hasTestTag(PROMPT_BOX_TAG) and inCenter).assertExists()
        }
    }

    @Test fun wrappingTheCentreTabStripDrawsTheCoreStripInsideTheModuleFrame() {
        withWindow(factory("infantry.shell#infantry.shell.center.chat.tabs", OverrideModes.WRAP, BlockOverride { scope, inner ->
            Column(Modifier.testTag("acme-frame")) { inner!!.Render(scope) }
        })) {
            onNode(hasTestTag(CENTER_TAB_BAR_TAG) and hasAnyAncestor(hasTestTag("acme-frame"))).assertExists()
            onNode(hasTestTag("acme-frame") and inCenter).assertExists()
        }
    }

    @Test fun theCompactWindowDrawsTheReplacedCentreTabStripToo() {
        withWindow(factory("infantry.shell.center.chat.tabs", OverrideModes.REPLACE, replaceTabs), width = 400) {
            onNode(hasTestTag("acme-tabs") and inCenter).assertExists()
            onNodeWithTag(CENTER_TAB_BAR_TAG).assertDoesNotExist()
        }
    }
}
