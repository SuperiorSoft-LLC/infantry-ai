package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.notes.*

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
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ShellOverrideDeclaration
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.AcceptedShellOverride
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.block.layout.BlockOverride
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.OverrideBlock
import com.superiorsoftllc.infantry.ui.block.layout.OverrideModes
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.MemoryHit
import com.superiorsoftllc.infantry.ui.protocol.MemoryIndexEntry
import com.superiorsoftllc.infantry.ui.protocol.Project
import com.superiorsoftllc.infantry.ui.shell.blocks.CoreShell
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

/** BLOCKS-LAYOUT L17: a Notes pane section (`infantry.shell.notes.<name>`) or row template is replaced or wrapped by a module, in the real window. */
@OptIn(ExperimentalTestApi::class)
class MemoryPaneOverridesUiTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun factory(node: String, mode: String, override: BlockOverride): TabFactory {
        val decl = ShellOverrideDeclaration(node, "acme", mode)
        val module = InfantryModule(
            ModuleManifest("Acme_Notes", "1.0.0", author = "Acme", license = "NOASSERTION", shell = listOf(decl)),
            tier = Tier.COMMUNITY, shell = mapOf("acme" to override),
        )
        val f = TabFactory(CoreModules.all + com.superiorsoftllc.infantry.plugins.official.chat.InfantryChat + com.superiorsoftllc.infantry.plugins.official.chatfeed.InfantryChatFeed + com.superiorsoftllc.infantry.plugins.official.header.InfantryHeader + com.superiorsoftllc.infantry.plugins.official.InfantryAgents + InfantryNotes + module, shellOverrides = listOf(AcceptedShellOverride("Acme_Notes", decl.nodeId, decl.name, decl.mode)))
        assertEquals(emptyList(), f.problems)
        // The override goes into the Notes tab's tree, not the window's.
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

    /** The Notes tab over project Beta with two notes and one search hit, in the real window over a fake daemon link. */
    private fun withNotesTab(tabs: TabFactory, body: ComposeUiTest.(NoDialLink) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = NoDialLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 860) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null, tabs = tabs) }
                link.push(Envelope(Envelope.READY, "contract=1.94.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                link.push(Envelope(Envelope.PROJECTS, json.encodeToString(ListSerializer(Project.serializer()), listOf(Project("p-beta", "Beta")))))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.projects.size == 1 }
                scope.launch { controller.selectProject("p-beta") }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProject?.id == "p-beta" }
                // Synchronize on the composed UI, not the controller (frontend/ui-test-flake-causes).
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag(NOTES_PANE_TAG).fetchSemanticsNodes().isNotEmpty() || run { onAllNodesWithTag("center-tab-MEMORY")[0].performClick(); false }
                }
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.MEMORY_LIST).any { it.data == "p-beta" } }
                val notes = listOf(MemoryIndexEntry("cart-notes", "cart rules"), MemoryIndexEntry("deploy-notes", "how we ship"))
                link.push(Envelope(Envelope.MEMORY_NOTES, json.encodeToString(ListSerializer(MemoryIndexEntry.serializer()), notes)))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.memoryNotes["p-beta"]?.size == 2 }
                scope.launch { controller.searchMemory("p-beta", "token") }
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.MEMORY_SEARCH).isNotEmpty() }
                link.push(Envelope(Envelope.MEMORY_HITS, json.encodeToString(ListSerializer(MemoryHit.serializer()), listOf(MemoryHit("auth-notes", "how auth works", "the token")))))
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("auth-notes").fetchSemanticsNodes().isNotEmpty() }
                // The second note row is drawn (by the core template or a module's).
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithText("deploy-notes").fetchSemanticsNodes().isNotEmpty() || onAllNodesWithTag("acme-note").fetchSemanticsNodes().size == 2
                }
                waitForIdle()
                body(link)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun replacingTheSearchDrawsTheModuleBlockInThePaneAndTheCoreSectionsStay() {
        val tabs = factory("infantry.shell.notes.search", OverrideModes.REPLACE, BlockOverride { scope, inner ->
            Text(if (inner == null) "acme search" else "bad", scope.modifier.testTag("acme-search"))
        })
        assertIs<OverrideBlock>(tabs.tabTree("infantry.shell.notes")!!.children[5])
        withNotesTab(tabs) {
            onNode(hasTestTag("acme-search") and hasAnyAncestor(hasTestTag(NOTES_PANE_TAG))).assertExists()
            // The core search is gone; the header, the hits and the notes stay.
            onNodeWithTag("memory-search-input").assertDoesNotExist()
            onNodeWithTag("memory-search-go").assertDoesNotExist()
            onNodeWithText("NOTES · Beta").assertExists()
            onNodeWithText("auth-notes").assertExists()
            onNodeWithText("cart-notes").assertExists()
        }
    }

    @Test fun wrappingTheHeaderDrawsTheCoreHeaderInsideTheModuleFrameAndItsAddStillOpensThePaneModal() {
        withNotesTab(factory("infantry.shell#infantry.shell.notes.header", OverrideModes.WRAP, BlockOverride { scope, inner ->
            Column(scope.modifier.testTag("acme-frame")) { inner!!.Render(scope) }
        })) {
            onNode(hasText("NOTES · Beta") and hasAnyAncestor(hasTestTag("acme-frame"))).assertExists()
            // Add, drawn by the wrapped section, opens the modal (another section) through the pane, outside the frame.
            onNode(hasTestTag(MEMORY_ADD_NOTE_BUTTON_TAG) and hasAnyAncestor(hasTestTag("acme-frame"))).performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(MEMORY_NOTE_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasTestTag(MEMORY_NOTE_DIALOG_TAG) and hasAnyAncestor(hasTestTag("acme-frame"))).assertDoesNotExist()
            onNode(hasTestTag(MEMORY_NOTE_DIALOG_TAG) and hasAnyAncestor(hasTestTag(NOTES_PANE_TAG))).assertExists()
            onNodeWithTag(MEMORY_NOTE_CANCEL_TAG).performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(MEMORY_NOTE_DIALOG_TAG).fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test fun replacingTheNoteTemplateRedrawsEveryNoteAndTheHitsStay() {
        val tabs = factory("infantry.shell.notes.note", OverrideModes.REPLACE, BlockOverride { scope, _ ->
            Text("acme note", scope.modifier.testTag("acme-note"))
        })
        withNotesTab(tabs) {
            assertEquals(2, onAllNodesWithTag("acme-note").fetchSemanticsNodes().size)
            onNodeWithText("cart-notes").assertDoesNotExist()
            onNodeWithText("deploy-notes").assertDoesNotExist()
            onNodeWithText("auth-notes").assertExists()
        }
    }

    @Test fun wrappingTheNoteTemplateWrapsEveryNoteAndAClickStillOpensIt() {
        withNotesTab(factory("infantry.shell.notes.note", OverrideModes.WRAP, BlockOverride { scope, inner ->
            Column(scope.modifier.testTag("acme-row")) { inner!!.Render(scope) }
        })) { link ->
            assertEquals(2, onAllNodesWithTag("acme-row").fetchSemanticsNodes().size)
            onNode(hasText("deploy-notes") and hasAnyAncestor(hasTestTag("acme-row"))).performClick()
            waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.MEMORY_GET).any { it.data.contains("deploy-notes") } }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(MEMORY_NOTE_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun aSectionDrawnOutsideThePaneDrawsNothingAndDoesNotCrash() = runDesktopComposeUiTest(400, 300) {
        val sections = listOf(NotesHeader, NotesEmpty, NotesScopes, NotesBanner, NotesNoOwner, NotesSearch, NotesError, NotesModal, NotesHit, NotesNote) +
            ShellNotes.children.filter { it.children.isNotEmpty() }
        assertEquals(2, sections.size - 10)
        setContent { Column { for (b in sections) b.Render(BlockScope(Modifier, null)) } }
        waitForIdle()
        onNodeWithText("Select a project to see its notes.").assertDoesNotExist()
        for (tag in listOf(MEMORY_ADD_NOTE_BUTTON_TAG, NOTES_SCOPE_PROJECT_TAG, NOTES_SCOPE_BANNER_TAG, "memory-search-input", MEMORY_NOTE_DIALOG_TAG)) {
            onNodeWithTag(tag).assertDoesNotExist()
        }
        assertEquals(0, onAllNodesWithText("NOTES", substring = true).fetchSemanticsNodes().size)
    }
}
