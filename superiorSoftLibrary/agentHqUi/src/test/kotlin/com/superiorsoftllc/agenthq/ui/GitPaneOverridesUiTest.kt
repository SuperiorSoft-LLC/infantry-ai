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
import androidx.compose.ui.test.performScrollTo
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
import com.superiorsoftllc.agenthq.ui.protocol.ChangeKind
import com.superiorsoftllc.agenthq.ui.protocol.ChangedFile
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.GitCommit
import com.superiorsoftllc.agenthq.ui.protocol.Project
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitHistory
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitStatus
import com.superiorsoftllc.agenthq.ui.shell.blocks.CoreShell
import com.superiorsoftllc.agenthq.ui.shell.blocks.GitBase
import com.superiorsoftllc.agenthq.ui.shell.blocks.GitBranches
import com.superiorsoftllc.agenthq.ui.shell.blocks.GitDiff
import com.superiorsoftllc.agenthq.ui.shell.blocks.GitDirectory
import com.superiorsoftllc.agenthq.ui.shell.blocks.GitHistory
import com.superiorsoftllc.agenthq.ui.shell.blocks.GitPipeline
import com.superiorsoftllc.agenthq.ui.shell.blocks.GitTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/** BLOCKS-LAYOUT L14: a Git pane section (`infantry.shell.git.<name>`) is replaced or wrapped by a module, in the real window. */
@OptIn(ExperimentalTestApi::class)
class GitPaneOverridesUiTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun factory(node: String, mode: String, override: BlockOverride): TabFactory {
        val decl = ShellOverrideDeclaration(node, "acme", mode)
        val module = AgentHqModule(
            ModuleManifest("Acme_Git", "1.0.0", author = "Acme", license = "NOASSERTION", shell = listOf(decl)),
            tier = Tier.COMMUNITY, shell = mapOf("acme" to override),
        )
        val f = TabFactory(CoreModules.all + module, shellOverrides = listOf(AcceptedShellOverride("Acme_Git", decl.nodeId, decl.name, decl.mode)))
        assertEquals(emptyList(), f.problems)
        // The override goes into the Git tab's tree, not the window's.
        assertSame(f.baseRoot, f.shellRoot)
        return f
    }

    /**
     * A fake link that never dials (frontend/ui-test-flake-causes): a real dial to ws://127.0.0.1:1 fails about 1 s in and
     * reports RECONNECTING over the pushed `ready`, which disables Refresh mid-test. It records what the window sends.
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

    /** The Git tab over project Alpha (one modified file, one commit), in the real window over a fake daemon link. */
    private fun withGitTab(tabs: TabFactory, body: ComposeUiTest.(NoDialLink) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = NoDialLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 860) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null, tabs = tabs) }
                link.push(Envelope(Envelope.READY, "contract=1.92.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                link.push(Envelope(Envelope.PROJECTS, json.encodeToString(ListSerializer(Project.serializer()), listOf(Project("p1", "Alpha", directory = "/srv/alpha")))))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProject?.id == "p1" }
                val status = ProjectGitStatus(
                    "p1", "/srv/alpha", available = true, repoRoot = "/srv/alpha", branch = "main", head = "1a2b3c4d5e6f",
                    changes = listOf(ChangedFile("README.md", ChangeKind.MODIFIED, staged = false)),
                )
                link.push(Envelope(Envelope.PROJECT_GIT, json.encodeToString(ProjectGitStatus.serializer(), status)))
                val history = ProjectGitHistory("p1", available = true, commits = listOf(GitCommit("a".repeat(40), "aaaaaaaa", "Ann", 1_700_000_000, "add the readme")))
                link.push(Envelope(Envelope.PROJECT_GIT_HISTORY, json.encodeToString(ProjectGitHistory.serializer(), history)))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGitHistory.containsKey("p1") }
                // Synchronize on the composed UI, not the controller (frontend/ui-test-flake-causes).
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithText("GIT · Alpha").fetchSemanticsNodes().isNotEmpty() || run { onAllNodesWithTag("center-tab-GIT")[0].performClick(); false }
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("branch main @ 1a2b3c4d").fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                body(link)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun replacingTheHistoryDrawsTheModuleBlockInThePaneAndTheCoreSectionsStay() {
        val tabs = factory("infantry.shell.git.history", OverrideModes.REPLACE, BlockOverride { scope, inner ->
            Text(if (inner == null) "acme history" else "bad", scope.modifier.testTag("acme-history"))
        })
        assertIs<OverrideBlock>(tabs.tabTree("infantry.shell.git")!!.children.last())
        withGitTab(tabs) {
            onNodeWithTag("acme-history").performScrollTo().assertExists()
            // The core history is gone; the sections before it stay.
            onNodeWithText("HISTORY").assertDoesNotExist()
            onNodeWithText("add the readme").assertDoesNotExist()
            onNodeWithText("WORKING TREE").assertExists()
            onNodeWithText("BRANCHES").assertExists()
            onNodeWithText("PIPELINE").assertExists()
        }
    }

    @Test fun wrappingTheTreeDrawsTheCoreTreeInsideTheModuleFrameAndItsRefreshStillAsks() {
        withGitTab(factory("infantry.shell#infantry.shell.git.tree", OverrideModes.WRAP, BlockOverride { scope, inner ->
            // The tree draws several items into the pane's column, so its frame is a Column too.
            Column(scope.modifier.testTag("acme-frame")) { inner!!.Render(scope) }
        })) { link ->
            onNode(hasText("WORKING TREE") and hasAnyAncestor(hasTestTag("acme-frame"))).assertExists()
            onNode(hasText("branch main @ 1a2b3c4d") and hasAnyAncestor(hasTestTag("acme-frame"))).assertExists()
            onNodeWithText("HISTORY").assertExists()
            // Refresh, drawn by the wrapped section, still goes through the frame to the daemon.
            val before = link.sentOf(Envelope.PROJECT_GIT_STATUS).size
            onNode(hasText("Refresh") and hasAnyAncestor(hasTestTag("acme-frame"))).performClick()
            waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_STATUS).size > before }
        }
    }

    @Test fun aSectionDrawnOutsideThePaneDrawsNothingAndDoesNotCrash() = runDesktopComposeUiTest(400, 300) {
        val sections = listOf(GitBase, GitDirectory, GitTree, GitDiff, GitBranches, GitPipeline, GitHistory)
        setContent { for (b in sections) b.Render(BlockScope(Modifier, null)) }
        waitForIdle()
        for (text in listOf("BASE BRANCH FOR NEW TASKS", "WORKING TREE", "BRANCHES", "PIPELINE", "HISTORY")) onNodeWithText(text).assertDoesNotExist()
        onNodeWithText("Directory:", substring = true).assertDoesNotExist()
    }
}
