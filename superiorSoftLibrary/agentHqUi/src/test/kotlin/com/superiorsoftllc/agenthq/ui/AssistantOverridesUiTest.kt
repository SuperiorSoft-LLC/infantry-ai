package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.approvals.*
import com.superiorsoftllc.agenthq.plugins.official.assistant.*
import com.superiorsoftllc.agenthq.plugins.official.changes.*
import com.superiorsoftllc.agenthq.plugins.official.chat.*
import com.superiorsoftllc.agenthq.plugins.official.chatfeed.*

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
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

/** BLOCKS-LAYOUT L11: an Assistant popup child (`infantry.shell.assistant.<name>`) is replaced or wrapped by a module, in the real window. */
@OptIn(ExperimentalTestApi::class)
class AssistantOverridesUiTest {
    private fun factory(node: String, mode: String, override: BlockOverride): TabFactory {
        val decl = ShellOverrideDeclaration(node, "acme", mode)
        val module = AgentHqModule(
            ModuleManifest("Acme_Assistant", "1.0.0", author = "Acme", license = "NOASSERTION", shell = listOf(decl)),
            tier = Tier.COMMUNITY, shell = mapOf("acme" to override),
        )
        val f = TabFactory(CoreModules.all + com.superiorsoftllc.agenthq.plugins.official.chat.InfantryChat + com.superiorsoftllc.agenthq.plugins.official.chatfeed.InfantryChatFeed + com.superiorsoftllc.agenthq.plugins.official.header.InfantryHeader + com.superiorsoftllc.agenthq.plugins.official.InfantryAgents + com.superiorsoftllc.agenthq.plugins.official.assistant.InfantryAssistant + module, shellOverrides = listOf(AcceptedShellOverride("Acme_Assistant", decl.nodeId, decl.name, decl.mode)))
        assertEquals(emptyList(), f.problems)
        return f
    }

    private fun withOpenAssistant(tabs: TabFactory, body: ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 860) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null, tabs = tabs) }
                // 1.115: the first contract with the Assistant (its title-bar entry shows from there).
                link.push(Envelope(Envelope.READY, "contract=1.115.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                waitForIdle()
                onNodeWithTag(TITLE_BAR_ASSISTANT_TAG).performClick()
                waitForIdle()
                body()
            }
        } finally {
            scope.cancel()
        }
    }

    private val inPopup = hasAnyAncestor(hasTestTag(ASSISTANT_POPUP_TAG))

    @Test fun replacingTheAssistantChatDrawsTheModuleBlockUnderTheAssistantHeader() {
        withOpenAssistant(factory("infantry.shell.assistant.body.chat", OverrideModes.REPLACE, BlockOverride { scope, inner ->
            Text(if (inner == null) "acme assistant" else "bad", scope.modifier.testTag("acme-assistant"))
        })) {
            onNode(hasTestTag("acme-assistant") and inPopup).assertExists()
            // The core chat (here its "Starting the Assistant..." placeholder) is gone; the header stays and still closes the popup.
            onNodeWithTag(ASSISTANT_STARTING_TAG).assertDoesNotExist()
            onNodeWithTag(ASSISTANT_RESIZE_TAG).assertExists()
            onNodeWithTag(ASSISTANT_CLOSE_TAG).performClick()
            waitForIdle()
            onNodeWithTag(ASSISTANT_POPUP_TAG).assertDoesNotExist()
        }
    }

    @Test fun wrappingTheAssistantHeaderDrawsTheCoreHeaderInsideTheModuleFrame() {
        withOpenAssistant(factory("infantry.shell#infantry.shell.assistant.body.header", OverrideModes.WRAP, BlockOverride { scope, inner ->
            Box(scope.modifier.testTag("acme-frame")) { inner!!.Render(scope) }
        })) {
            onNode(hasTestTag(ASSISTANT_CLOSE_TAG) and hasAnyAncestor(hasTestTag("acme-frame"))).assertExists()
            onNode(hasTestTag("acme-frame") and inPopup).assertExists()
            onNode(hasTestTag(ASSISTANT_STARTING_TAG) and inPopup).assertExists()
        }
    }
}
