package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import com.superiorsoftllc.agenthq.ui.block.layout.OverrideModes
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.protocol.ChangeKind
import com.superiorsoftllc.agenthq.ui.protocol.ChangedFile
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.Project
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** BLOCKS-LAYOUT L11: a diff modal child (`infantry.shell.diff.<name>`) is replaced or wrapped by a module, in the real window. */
@OptIn(ExperimentalTestApi::class)
class DiffOverridesUiTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun factory(node: String, mode: String, override: BlockOverride): TabFactory {
        val decl = ShellOverrideDeclaration(node, "acme", mode)
        val module = AgentHqModule(
            ModuleManifest("Acme_Diff", "1.0.0", author = "Acme", license = "NOASSERTION", shell = listOf(decl)),
            tier = Tier.COMMUNITY, shell = mapOf("acme" to override),
        )
        val f = TabFactory(CoreModules.all + module, shellOverrides = listOf(AcceptedShellOverride("Acme_Diff", decl.nodeId, decl.name, decl.mode)))
        assertEquals(emptyList(), f.problems)
        return f
    }

    /** The window over a fake daemon with one project and one modified file; its row in the Changes tab opens the diff. */
    private fun withOpenDiff(tabs: TabFactory, body: ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 860) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null, tabs = tabs) }
                // 1.87: the project checkout's own status (the worktree picker of 1.88 is not needed here).
                link.push(Envelope(Envelope.READY, "contract=1.87.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                link.push(Envelope(Envelope.PROJECTS, json.encodeToString(ListSerializer(Project.serializer()), listOf(Project("p1", "Alpha", directory = "/srv/alpha")))))
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_STATUS).isNotEmpty() }
                val dirty = ProjectGitStatus(
                    "p1", "/srv/alpha", available = true, repoRoot = "/srv/alpha", branch = "main", head = "1a2b3c4d5e6f",
                    changes = listOf(ChangedFile("README.md", ChangeKind.MODIFIED, staged = false)),
                )
                link.push(Envelope(Envelope.PROJECT_GIT, json.encodeToString(ProjectGitStatus.serializer(), dirty)))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGit.containsKey("p1") }
                waitForIdle()
                onNodeWithText("README.md", substring = true).performClick()
                waitForIdle()
                onNodeWithTag(DIFF_MODAL_TAG).assertExists()
                body()
            }
        } finally {
            scope.cancel()
        }
    }

    private val inModal = hasAnyAncestor(hasTestTag(DIFF_MODAL_TAG))

    @Test fun replacingTheDiffViewDrawsTheModuleBlockUnderTheDiffHeader() {
        withOpenDiff(factory("infantry.shell.diff.body", OverrideModes.REPLACE, BlockOverride { scope, inner ->
            Text(if (inner == null) "acme diff" else "bad", scope.modifier.testTag("acme-diff"))
        })) {
            onNode(hasTestTag("acme-diff") and inModal).assertExists()
            // The core view (here its "Loading diff…" notice) is gone; the header stays, and its ✕ still closes the modal.
            onNode(hasText("Loading diff…") and inModal).assertDoesNotExist()
            onNodeWithTag(DIFF_MODAL_CLOSE_TAG).performClick()
            waitForIdle()
            onNodeWithTag(DIFF_MODAL_TAG).assertDoesNotExist()
        }
    }

    @Test fun wrappingTheDiffHeaderDrawsTheCoreHeaderInsideTheModuleFrame() {
        withOpenDiff(factory("infantry.shell#infantry.shell.diff.header", OverrideModes.WRAP, BlockOverride { scope, inner ->
            Box(scope.modifier.testTag("acme-frame")) { inner!!.Render(scope) }
        })) {
            onNode(hasTestTag(DIFF_MODAL_CLOSE_TAG) and hasAnyAncestor(hasTestTag("acme-frame"))).assertExists()
            onNode(hasTestTag("acme-frame") and inModal).assertExists()
            onNode(hasText("Loading diff…") and inModal).assertExists()
        }
    }
}
