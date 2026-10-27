package com.superiorsoftllc.agenthq.ui

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
import com.superiorsoftllc.agenthq.ui.block.layout.OverrideBlock
import com.superiorsoftllc.agenthq.ui.block.layout.OverrideModes
import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.model.ConnectionInfo
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.net.DaemonClient
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.shell.blocks.CoreShell
import com.superiorsoftllc.agenthq.ui.shell.blocks.RunConfirm
import com.superiorsoftllc.agenthq.ui.shell.blocks.RunEditor
import com.superiorsoftllc.agenthq.ui.shell.blocks.RunEmpty
import com.superiorsoftllc.agenthq.ui.shell.blocks.RunNotices
import com.superiorsoftllc.agenthq.ui.shell.blocks.RunOutput
import com.superiorsoftllc.agenthq.ui.shell.blocks.RunPills
import com.superiorsoftllc.agenthq.ui.shell.blocks.RunToolbar
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
import kotlin.test.assertIs
import kotlin.test.assertSame

/** BLOCKS-LAYOUT L15: a Run pane section (`infantry.shell.run.<name>`) is replaced or wrapped by a module, in the real window. */
@OptIn(ExperimentalTestApi::class)
class RunPaneOverridesUiTest {
    private fun factory(node: String, mode: String, override: BlockOverride): TabFactory {
        val decl = ShellOverrideDeclaration(node, "acme", mode)
        val module = AgentHqModule(
            ModuleManifest("Acme_Run", "1.0.0", author = "Acme", license = "NOASSERTION", shell = listOf(decl)),
            tier = Tier.COMMUNITY, shell = mapOf("acme" to override),
        )
        val f = TabFactory(CoreModules.all + module, shellOverrides = listOf(AcceptedShellOverride("Acme_Run", decl.nodeId, decl.name, decl.mode)))
        assertEquals(emptyList(), f.problems)
        // The override goes into the Run tab's tree, not the window's.
        assertSame(f.baseRoot, f.shellRoot)
        return f
    }

    /**
     * A fake link that never dials (frontend/ui-test-flake-causes): a real dial to ws://127.0.0.1:1 fails about 1 s in and
     * reports RECONNECTING over the pushed `ready`, which turns the pane's buttons off mid-test. It records what the window
     * sends.
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

    /** The Run tab with one saved command (Build web), in the real window over a fake daemon link. */
    private fun withRunTab(tabs: TabFactory, body: ComposeUiTest.(NoDialLink) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = NoDialLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 860) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null, tabs = tabs) }
                link.push(Envelope(Envelope.READY, "contract=1.94.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                link.push(Envelope(Envelope.COMMANDS, """[{"id":"build-web","name":"Build web","argv":["gradlew.bat","build"],"graceMillis":5000}]"""))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.runs.commands.isNotEmpty() }
                // Synchronize on the composed UI, not the controller (frontend/ui-test-flake-causes).
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag(RUN_PANE_TAG).fetchSemanticsNodes().isNotEmpty() || run { onAllNodesWithTag("center-tab-RUN")[0].performClick(); false }
                }
                waitForIdle()
                body(link)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun replacingTheToolbarDrawsTheModuleBlockInThePaneAndTheCoreSectionsStay() {
        val tabs = factory("infantry.shell.run.toolbar", OverrideModes.REPLACE, BlockOverride { scope, inner ->
            Text(if (inner == null) "acme toolbar" else "bad", scope.modifier.testTag("acme-toolbar"))
        })
        assertIs<OverrideBlock>(tabs.tabTree("infantry.shell.run")!!.children[1])
        withRunTab(tabs) {
            onNode(hasTestTag("acme-toolbar") and hasAnyAncestor(hasTestTag(RUN_PANE_TAG))).assertExists()
            // The core toolbar is gone; the title and the output stay.
            onNodeWithTag(RUN_START_TAG).assertDoesNotExist()
            onNodeWithTag(RUN_COMMAND_PICKER_TAG).assertDoesNotExist()
            onNodeWithText("RUN").assertExists()
            onNodeWithTag(RUN_OUTPUT_TAG).assertExists()
        }
    }

    @Test fun wrappingTheToolbarDrawsTheCoreToolbarInsideTheModuleFrameAndItsDeleteStillGoesThroughThePane() {
        withRunTab(factory("infantry.shell#infantry.shell.run.toolbar", OverrideModes.WRAP, BlockOverride { scope, inner ->
            Column(scope.modifier.testTag("acme-frame")) { inner!!.Render(scope) }
        })) { link ->
            onNode(hasTestTag(RUN_START_TAG) and hasAnyAncestor(hasTestTag("acme-frame"))).assertExists()
            onNode(hasText("Build web") and hasAnyAncestor(hasTestTag("acme-frame"))).assertExists()
            // Delete, drawn by the wrapped section, asks through the pane: the confirm (another section) shows, outside the frame.
            onNode(hasTestTag(RUN_DELETE_TAG) and hasAnyAncestor(hasTestTag("acme-frame"))).performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(RUN_DELETE_CONFIRM_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasTestTag(RUN_DELETE_CONFIRM_TAG) and hasAnyAncestor(hasTestTag("acme-frame"))).assertDoesNotExist()
            onNodeWithTag(RUN_DELETE_CONFIRM_TAG).performClick()
            waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.COMMAND_DELETE).isNotEmpty() }
            onNodeWithTag(RUN_OUTPUT_TAG).assertExists()
        }
    }

    @Test fun aSectionDrawnOutsideThePaneDrawsNothingAndDoesNotCrash() = runDesktopComposeUiTest(400, 300) {
        val sections = listOf(RunEmpty, RunToolbar, RunPills, RunConfirm, RunEditor, RunNotices, RunOutput)
        setContent { Column { for (b in sections) b.Render(BlockScope(Modifier, null)) } }
        waitForIdle()
        onNodeWithText("No saved commands on this daemon.").assertDoesNotExist()
        for (tag in listOf(RUN_START_TAG, RUN_ADD_TAG, RUN_PILLS_TAG, RUN_DELETE_CONFIRM_TAG, RUN_FORM_TAG, RUN_OUTPUT_TAG)) {
            onNodeWithTag(tag).assertDoesNotExist()
        }
        assertEquals(0, onAllNodesWithText("RUN").fetchSemanticsNodes().size)
    }
}
