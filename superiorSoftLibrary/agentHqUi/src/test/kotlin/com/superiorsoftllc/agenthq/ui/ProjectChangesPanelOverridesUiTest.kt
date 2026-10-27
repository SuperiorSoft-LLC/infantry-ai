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
import com.superiorsoftllc.agenthq.ui.protocol.ChangeKind
import com.superiorsoftllc.agenthq.ui.protocol.ChangedFile
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.GitCommit
import com.superiorsoftllc.agenthq.ui.protocol.GitWorktree
import com.superiorsoftllc.agenthq.ui.protocol.Project
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitWorktreeList
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitWorktreeStatus
import com.superiorsoftllc.agenthq.ui.shell.blocks.ChangesBranch
import com.superiorsoftllc.agenthq.ui.shell.blocks.ChangesBranchFile
import com.superiorsoftllc.agenthq.ui.shell.blocks.ChangesCommit
import com.superiorsoftllc.agenthq.ui.shell.blocks.ChangesEmpty
import com.superiorsoftllc.agenthq.ui.shell.blocks.ChangesFile
import com.superiorsoftllc.agenthq.ui.shell.blocks.ChangesHeader
import com.superiorsoftllc.agenthq.ui.shell.blocks.ChangesHint
import com.superiorsoftllc.agenthq.ui.shell.blocks.ChangesModeToggle
import com.superiorsoftllc.agenthq.ui.shell.blocks.ChangesNotGit
import com.superiorsoftllc.agenthq.ui.shell.blocks.ChangesTitle
import com.superiorsoftllc.agenthq.ui.shell.blocks.ChangesUnsupported
import com.superiorsoftllc.agenthq.ui.shell.blocks.ChangesWorktree
import com.superiorsoftllc.agenthq.ui.shell.blocks.CoreShell
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/** BLOCKS-LAYOUT L18: a Changes tab section (`infantry.shell.changes.<name>`) or row template is replaced or wrapped by a module, in the real window. */
@OptIn(ExperimentalTestApi::class)
class ProjectChangesPanelOverridesUiTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun factory(node: String, mode: String, override: BlockOverride): TabFactory {
        val decl = ShellOverrideDeclaration(node, "acme", mode)
        val module = AgentHqModule(
            ModuleManifest("Acme_Changes", "1.0.0", author = "Acme", license = "NOASSERTION", shell = listOf(decl)),
            tier = Tier.COMMUNITY, shell = mapOf("acme" to override),
        )
        val f = TabFactory(CoreModules.all + module, shellOverrides = listOf(AcceptedShellOverride("Acme_Changes", decl.nodeId, decl.name, decl.mode)))
        assertEquals(emptyList(), f.problems)
        // The override goes into the Changes tab's tree, not the window's.
        assertSame(f.baseRoot, f.shellRoot)
        return f
    }

    /**
     * A fake link that never dials (frontend/ui-test-flake-causes): a real dial to ws://127.0.0.1:1 fails about 1 s in and
     * reports RECONNECTING over the pushed `ready`, which turns the tab's buttons off mid-test. It records what the window
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

    private val betaWorktrees = ProjectGitWorktreeList(
        "p-beta", available = true, worktrees = listOf(GitWorktree("/srv/beta", branch = "main", main = true, project = true)), selected = "/srv/beta",
    )
    private val betaStatus = ProjectGitWorktreeStatus(
        "p-beta", "/srv/beta", available = true, branch = "main", head = "1a2b3c4d5e6f", uncommitted = 2,
        changes = listOf(ChangedFile("README.md", ChangeKind.MODIFIED, staged = false), ChangedFile("staged.txt", ChangeKind.ADDED, staged = true)),
        commits = listOf(
            GitCommit("a".repeat(40), "aaaa111", "Shots", 1_767_323_045_000, "add the readme"),
            GitCommit("b".repeat(40), "bbbb222", "Shots", 1_767_323_045_000, "add the notes"),
        ),
    )

    /** The Changes tab over project Beta's worktree with a staged and an unstaged file, in the real window over a fake daemon link. */
    private fun withChangesTab(tabs: TabFactory, body: ComposeUiTest.(NoDialLink) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = NoDialLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 860) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null, tabs = tabs) }
                link.push(Envelope(Envelope.READY, "contract=1.94.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                link.push(Envelope(Envelope.PROJECTS, json.encodeToString(ListSerializer(Project.serializer()), listOf(Project("p-beta", "Beta", directory = "/srv/beta")))))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.projects.size == 1 }
                scope.launch { controller.selectProject("p-beta") }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProject?.id == "p-beta" }
                // Synchronize on the composed UI, not the controller (frontend/ui-test-flake-causes).
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag(CHANGES_PANE_TAG).fetchSemanticsNodes().isNotEmpty() || run { onAllNodesWithTag("right-tab-CHANGES")[0].performClick(); false }
                }
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_WORKTREES).any { it.data.contains("p-beta") } }
                link.push(Envelope(Envelope.PROJECT_GIT_WORKTREE_LIST, json.encodeToString(ProjectGitWorktreeList.serializer(), betaWorktrees)))
                controller.selectChangesWorktree("p-beta", "/srv/beta")
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_WORKTREE_STATUS).isNotEmpty() }
                link.push(Envelope(Envelope.PROJECT_GIT_WORKTREE, json.encodeToString(ProjectGitWorktreeStatus.serializer(), betaStatus)))
                // Both files are drawn (by the core template or a module's).
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithText("README.md").fetchSemanticsNodes().isNotEmpty() || onAllNodesWithTag("acme-file").fetchSemanticsNodes().size == 2
                }
                waitForIdle()
                body(link)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun replacingTheHeaderDrawsTheModuleBlockInTheTabAndTheCoreSectionsStay() {
        val tabs = factory("infantry.shell.changes.header", OverrideModes.REPLACE, BlockOverride { scope, inner ->
            Text(if (inner == null) "acme header" else "bad", scope.modifier.testTag("acme-header"))
        })
        assertIs<OverrideBlock>(tabs.tabTree("infantry.shell.changes")!!.children[5])
        withChangesTab(tabs) {
            onNode(hasTestTag("acme-header") and hasAnyAncestor(hasTestTag(CHANGES_PANE_TAG))).assertExists()
            // The core header line is gone; the title, the picker, the toggle and the files stay.
            onNodeWithTag(CHANGES_BRANCH_TAG).assertDoesNotExist()
            onNodeWithTag(CHANGES_UNCOMMITTED_TAG).assertDoesNotExist()
            onNodeWithText("CHANGES · Beta").assertExists()
            onNodeWithTag(WORKTREE_SELECT_TAG).assertExists()
            onNodeWithTag(CHANGES_MODE_HISTORY_TAG).assertExists()
            onNodeWithText("README.md").assertExists()
        }
    }

    @Test fun wrappingTheTitleDrawsTheCoreTitleInsideTheModuleFrameAndItsRefreshStillAsksTheDaemon() {
        withChangesTab(factory("infantry.shell#infantry.shell.changes.title", OverrideModes.WRAP, BlockOverride { scope, inner ->
            Column(scope.modifier.testTag("acme-frame")) { inner!!.Render(scope) }
        })) { link ->
            onNode(hasText("CHANGES · Beta") and hasAnyAncestor(hasTestTag("acme-frame"))).assertExists()
            val before = link.sentOf(Envelope.PROJECT_GIT_WORKTREES).size
            onNode(hasTestTag(PROJECT_CHANGES_REFRESH_TAG) and hasAnyAncestor(hasTestTag("acme-frame"))).performClick()
            waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_WORKTREES).size > before }
            onNode(hasText("README.md") and hasAnyAncestor(hasTestTag("acme-frame"))).assertDoesNotExist()
        }
    }

    @Test fun replacingTheFileTemplateRedrawsEveryFileAndTheSummaryStays() {
        val tabs = factory("infantry.shell.changes.file", OverrideModes.REPLACE, BlockOverride { scope, _ ->
            Text("acme file", scope.modifier.testTag("acme-file"))
        })
        withChangesTab(tabs) {
            assertEquals(2, onAllNodesWithTag("acme-file").fetchSemanticsNodes().size)
            onNodeWithText("README.md").assertDoesNotExist()
            onNodeWithText("staged.txt").assertDoesNotExist()
            onNodeWithText("2 changed files").assertExists()
        }
    }

    @Test fun wrappingTheFileTemplateWrapsEveryFileAndAClickStillOpensItsDiff() {
        withChangesTab(factory("infantry.shell.changes.file", OverrideModes.WRAP, BlockOverride { scope, inner ->
            Column(scope.modifier.testTag("acme-row")) { inner!!.Render(scope) }
        })) {
            assertEquals(2, onAllNodesWithTag("acme-row").fetchSemanticsNodes().size)
            onNode(hasText("README.md") and hasAnyAncestor(hasTestTag("acme-row"))).performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(DIFF_MODAL_TAG).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun replacingTheCommitTemplateRedrawsEveryCommitInTheHistory() {
        withChangesTab(factory("infantry.shell.changes.commit", OverrideModes.REPLACE, BlockOverride { scope, _ ->
            Text("acme commit", scope.modifier.testTag("acme-commit"))
        })) {
            onNodeWithTag(CHANGES_MODE_HISTORY_TAG).performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("acme-commit").fetchSemanticsNodes().size == 2 }
            onNodeWithText("add the readme").assertDoesNotExist()
            onNodeWithText("README.md").assertDoesNotExist()
        }
    }

    @Test fun aSectionDrawnOutsideTheTabDrawsNothingAndDoesNotCrash() = runDesktopComposeUiTest(400, 300) {
        val sections = listOf(
            ChangesEmpty, ChangesTitle, ChangesUnsupported, ChangesNotGit, ChangesWorktree, ChangesHeader, ChangesModeToggle,
            ChangesBranch, ChangesHint, ChangesFile, ChangesBranchFile, ChangesCommit,
        ) + ShellChanges.children.filter { it.children.isNotEmpty() }
        assertEquals(3, sections.size - 12)
        setContent { Column { for (b in sections) b.Render(BlockScope(Modifier, null)) } }
        waitForIdle()
        onNodeWithText("Select a project to see its changes.").assertDoesNotExist()
        for (tag in listOf(PROJECT_CHANGES_REFRESH_TAG, WORKTREE_SELECT_TAG, BRANCH_SELECT_TAG, CHANGES_BRANCH_TAG, CHANGES_MODE_WORKING_TREE_TAG, NOT_A_GIT_REPOSITORY_TAG)) {
            onNodeWithTag(tag).assertDoesNotExist()
        }
        assertEquals(0, onAllNodesWithText("CHANGES", substring = true).fetchSemanticsNodes().size)
    }
}
