package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.usage.InfantryUsage
import com.superiorsoftllc.agenthq.plugins.official.changes.InfantryChanges
import androidx.compose.foundation.layout.Column
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
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BLOCKS-LAYOUT L9a: a right panel child (`infantry.shell.right.<name>`, `infantry.shell.right.body.<name>`) is replaced
 * or wrapped by a module, in the real window: docked at desktop width and in the compact drawer.
 */
@OptIn(ExperimentalTestApi::class)
class RightPanelOverridesUiTest {
    private fun factory(node: String, mode: String, override: BlockOverride): TabFactory {
        val decl = ShellOverrideDeclaration(node, "acme", mode)
        val module = AgentHqModule(
            ModuleManifest("Acme_Right", "1.0.0", author = "Acme", license = "NOASSERTION", shell = listOf(decl)),
            tier = Tier.COMMUNITY, shell = mapOf("acme" to override),
        )
        val f = TabFactory(CoreModules.all + InfantryChanges + InfantryUsage + module, shellOverrides = listOf(AcceptedShellOverride("Acme_Right", decl.nodeId, decl.name, decl.mode)))
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

    private val inPanel = hasAnyAncestor(hasTestTag("right-panel"))

    /** The core strip's first tab, the one a replace removes. */
    private fun TabFactory.firstRightTab(): String = tabs(TabContainers.RIGHT).first().testTag

    private val replaceStrip = BlockOverride { scope, inner ->
        Text(if (inner == null) "acme strip" else "bad", scope.modifier.testTag("acme-strip"))
    }

    @Test fun replacingTheTabStripDrawsTheModuleBlockAboveThePickedTab() {
        val f = factory("infantry.shell.right.body.strip", OverrideModes.REPLACE, replaceStrip)
        withWindow(f) {
            onNode(hasTestTag("acme-strip") and inPanel).assertExists()
            onNodeWithTag(f.firstRightTab()).assertDoesNotExist()
            // The panel's other children stay: its resize handle.
            onNodeWithTag("right-panel-resize-handle").assertExists()
        }
    }

    @Test fun wrappingTheTabStripDrawsTheCoreStripInsideTheModuleFrame() {
        val f = factory("infantry.shell#infantry.shell.right.body.strip", OverrideModes.WRAP, BlockOverride { scope, inner ->
            Column(Modifier.testTag("acme-frame")) { inner!!.Render(scope) }
        })
        withWindow(f) {
            onNode(hasTestTag(f.firstRightTab()) and hasAnyAncestor(hasTestTag("acme-frame"))).assertExists()
            onNode(hasTestTag("acme-frame") and inPanel).assertExists()
        }
    }

    @Test fun theCompactDrawerDrawsTheReplacedTabStripToo() {
        val f = factory("infantry.shell.right.body.strip", OverrideModes.REPLACE, replaceStrip)
        withWindow(f, width = 400) {
            onNodeWithTag(TITLE_BAR_TOGGLE_RIGHT_TAG).performClick()
            waitForIdle()
            onNode(hasTestTag("acme-strip") and inPanel and hasAnyAncestor(hasTestTag(DRAWER_TAG))).assertExists()
            onNodeWithTag(f.firstRightTab()).assertDoesNotExist()
        }
    }
}
