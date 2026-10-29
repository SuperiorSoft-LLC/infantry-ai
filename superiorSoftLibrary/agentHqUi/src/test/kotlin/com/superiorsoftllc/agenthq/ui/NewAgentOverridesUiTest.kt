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
import androidx.compose.ui.test.onNodeWithText
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
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.shell.blocks.NewAgentErrorLine
import com.superiorsoftllc.agenthq.ui.shell.blocks.NewAgentExecutor
import com.superiorsoftllc.agenthq.ui.shell.blocks.NewAgentRole
import com.superiorsoftllc.agenthq.ui.shell.blocks.NewAgentTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/** BLOCKS-LAYOUT L11: a New agent modal step (`infantry.shell.center.newAgent.<name>`) is replaced or wrapped by a module, in the real window. */
@OptIn(ExperimentalTestApi::class)
class NewAgentOverridesUiTest {
    private fun factory(node: String, mode: String, override: BlockOverride): TabFactory {
        val decl = ShellOverrideDeclaration(node, "acme", mode)
        val module = AgentHqModule(
            ModuleManifest("Acme_NewAgent", "1.0.0", author = "Acme", license = "NOASSERTION", shell = listOf(decl)),
            tier = Tier.COMMUNITY, shell = mapOf("acme" to override),
        )
        val f = TabFactory(CoreModules.all + com.superiorsoftllc.agenthq.plugins.official.chat.InfantryChat + com.superiorsoftllc.agenthq.plugins.official.header.InfantryHeader + com.superiorsoftllc.agenthq.plugins.official.InfantryAgents + module, shellOverrides = listOf(AcceptedShellOverride("Acme_NewAgent", decl.nodeId, decl.name, decl.mode)))
        assertEquals(emptyList(), f.problems)
        return f
    }

    private fun withOpenDialog(tabs: TabFactory, body: ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 860) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null, tabs = tabs) }
                link.push(Envelope(Envelope.READY, "contract=1.92.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                waitForIdle()
                onNodeWithTag(SIDEBAR_NEW_AGENT_TAG).performClick()
                waitForIdle()
                body()
            }
        } finally {
            scope.cancel()
        }
    }

    private val inDialog = hasAnyAncestor(hasTestTag(NEW_AGENT_DIALOG_TAG))

    @Test fun replacingTheRoleCatalogDrawsTheModuleBlockAboveTheCoreSteps() {
        withOpenDialog(factory("infantry.shell.center.newAgent.role", OverrideModes.REPLACE, BlockOverride { scope, inner ->
            Text(if (inner == null) "acme roles" else "bad", scope.modifier.testTag("acme-roles"))
        })) {
            onNode(hasTestTag("acme-roles") and inDialog).assertExists()
            // The core catalog is gone; the other steps and the frame's Cancel stay, and Cancel still closes the modal.
            onNodeWithTag(NEW_AGENT_ROLE_SEARCH_TAG).assertDoesNotExist()
            onNode(hasTestTag(NEW_AGENT_TASK_TAG) and inDialog).assertExists()
            onNodeWithText("Cancel").performClick()
            waitForIdle()
            onNodeWithTag(NEW_AGENT_DIALOG_TAG).assertDoesNotExist()
        }
    }

    @Test fun wrappingTheTaskStepDrawsTheCoreFieldInsideTheModuleFrame() {
        withOpenDialog(factory("infantry.shell#infantry.shell.center.newAgent.task", OverrideModes.WRAP, BlockOverride { scope, inner ->
            Box(scope.modifier.testTag("acme-frame")) { inner!!.Render(scope) }
        })) {
            onNode(hasTestTag(NEW_AGENT_TASK_TAG) and hasAnyAncestor(hasTestTag("acme-frame"))).assertExists()
            onNode(hasTestTag("acme-frame") and inDialog).assertExists()
            onNode(hasTestTag(NEW_AGENT_ROLE_SEARCH_TAG) and inDialog).assertExists()
        }
    }

    @Test fun aStepDrawnOutsideTheModalDrawsNothingAndDoesNotCrash() = runDesktopComposeUiTest(400, 300) {
        setContent { for (b in listOf(NewAgentRole, NewAgentExecutor, NewAgentErrorLine, NewAgentTask)) b.Render(BlockScope(Modifier, null)) }
        waitForIdle()
        onNodeWithTag(NEW_AGENT_ROLE_SEARCH_TAG).assertDoesNotExist()
        onNodeWithTag(NEW_AGENT_TASK_TAG).assertDoesNotExist()
    }
}
