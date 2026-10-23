package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.net.DaemonClient
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.MemoryHit
import com.superiorsoftllc.agenthq.ui.protocol.MemoryIndexEntry
import com.superiorsoftllc.agenthq.ui.protocol.MemoryNote
import com.superiorsoftllc.agenthq.ui.protocol.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

private class MemoryFakeDaemonClient(scope: CoroutineScope) : DaemonClient(scope) {
    val sent = CopyOnWriteArrayList<Envelope>()
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    override val frames: SharedFlow<Envelope> get() = incoming
    override suspend fun send(envelope: Envelope): Boolean { sent += envelope; return true }
    fun push(e: Envelope) {
        val deadline = System.currentTimeMillis() + 10_000
        while (incoming.subscriptionCount.value == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        check(incoming.tryEmit(e)) { "frame buffer full" }
    }
    fun sentOf(type: String) = sent.filter { it.type == type }
}

/**
 * UI-SHELL-3: the memory pane of the selected project, driven through a fake daemon link. Typing a query
 * and searching sends `memory_search` naming the project and the query, and the `memory_hits` the daemon
 * answers with are drawn, each hit with its snippet.
 */
@OptIn(ExperimentalTestApi::class)
class MemoryPaneUiTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun `typing a query sends the search and the hits it answers with render`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = MemoryFakeDaemonClient(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(900, 900) {
                setContent {
                    val state by controller.state.collectAsState()
                    MemoryPane(state, controller)
                }
                link.push(Envelope(Envelope.READY, "contract=1.21.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_LIST).isNotEmpty() }
                link.push(Envelope(Envelope.PROJECTS, json.encodeToString(ListSerializer(Project.serializer()), listOf(Project("p-beta", "Beta")))))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.projects.size == 1 }

                // Selecting a project makes the pane ask for its memory index, which lists each note.
                controller.selectProject("p-beta")
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.MEMORY_LIST).any { it.data == "p-beta" } }
                link.push(
                    Envelope(
                        Envelope.MEMORY_NOTES,
                        json.encodeToString(ListSerializer(MemoryIndexEntry.serializer()), listOf(MemoryIndexEntry("cart-notes", "cart rules"))),
                    ),
                )
                waitUntil(timeoutMillis = 10_000) { controller.state.value.memoryNotes["p-beta"]?.size == 1 }
                onNodeWithText("cart-notes").assertExists()

                // Typing a query and searching sends `memory_search` for the selected project.
                onNodeWithTag("memory-search-input").performTextInput("token")
                onNodeWithTag("memory-search-go").performClick()
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.MEMORY_SEARCH).isNotEmpty() }
                val search = link.sentOf(Envelope.MEMORY_SEARCH).single().data
                assertTrue(search.contains("\"p-beta\"") && search.contains("token"), "memory_search names the project and the query: $search")

                // The hits the daemon answers with are drawn, each with its snippet.
                link.push(
                    Envelope(
                        Envelope.MEMORY_HITS,
                        json.encodeToString(ListSerializer(MemoryHit.serializer()), listOf(MemoryHit("auth-notes", "how auth works", "…the token…"))),
                    ),
                )
                waitUntil(timeoutMillis = 10_000) { controller.state.value.memorySearch?.hits?.size == 1 }
                onNodeWithText("auth-notes").assertExists()
                onNodeWithText("…the token…").assertExists()
            }
        } finally {
            scope.cancel()
        }
    }

    /** Drives the pane to a live, selected project whose index lists [notes]. */
    private suspend fun ComposeUiTest.liveMemoryPane(link: MemoryFakeDaemonClient, controller: Controller, vararg notes: MemoryIndexEntry) {
        setContent {
            val state by controller.state.collectAsState()
            MemoryPane(state, controller)
        }
        link.push(Envelope(Envelope.READY, "contract=1.92.0 provider=stub instance=i1"))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_LIST).isNotEmpty() }
        link.push(Envelope(Envelope.PROJECTS, json.encodeToString(ListSerializer(Project.serializer()), listOf(Project("p-beta", "Beta")))))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projects.size == 1 }
        controller.selectProject("p-beta")
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.MEMORY_LIST).any { it.data == "p-beta" } }
        link.push(Envelope(Envelope.MEMORY_NOTES, json.encodeToString(ListSerializer(MemoryIndexEntry.serializer()), notes.toList())))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.memoryNotes["p-beta"]?.size == notes.size }
    }

    private fun storedNote(name: String, description: String, body: String) = Envelope(
        Envelope.MEMORY_NOTE,
        json.encodeToString(MemoryNote.serializer(), MemoryNote(id = "id-$name", projectId = "p-beta", name = name, description = description, body = body)),
    )

    /**
     * MEMORY-UI-1: no add-note form sits under the list (it was pushed off screen as the list grew). An icon-only
     * "+" in the header opens a modal; Save sends `memory_save`, closes it, and the stored note shows in the list;
     * Cancel discards what was typed and sends nothing.
     */
    @Test fun `the plus icon opens the add-note modal and save adds the note while cancel discards`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = MemoryFakeDaemonClient(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(900, 900) {
                liveMemoryPane(link, controller, MemoryIndexEntry("cart-notes", "cart rules"))

                // No inline Name field under the list; search sits above it.
                onNodeWithTag(MEMORY_NOTE_NAME_TAG).assertDoesNotExist()
                onNodeWithTag(MEMORY_NOTE_DIALOG_TAG).assertDoesNotExist()
                val searchTop = onNodeWithTag("memory-search-input").fetchSemanticsNode().boundsInRoot.top
                val listTop = onNodeWithText("cart-notes").fetchSemanticsNode().boundsInRoot.top
                assertTrue(searchTop < listTop, "search ($searchTop) is above the list ($listTop)")

                // The "+" is icon-only, named for screen readers, and opens the modal.
                onNodeWithContentDescription("Add note").assertExists()
                onNodeWithTag(MEMORY_ADD_NOTE_BUTTON_TAG).performClick()
                onNodeWithTag(MEMORY_NOTE_DIALOG_TAG).assertExists()

                // Cancel discards: nothing sent, and a reopened modal is empty.
                onNodeWithTag(MEMORY_NOTE_NAME_TAG).performTextInput("scratch")
                onNodeWithTag(MEMORY_NOTE_CANCEL_TAG).performClick()
                onNodeWithTag(MEMORY_NOTE_DIALOG_TAG).assertDoesNotExist()
                assertTrue(link.sentOf(Envelope.MEMORY_SAVE).isEmpty(), "cancel sends nothing")
                onNodeWithTag(MEMORY_ADD_NOTE_BUTTON_TAG).performClick()
                onNodeWithText("scratch").assertDoesNotExist()

                // Save sends memory_save and closes; the stored note shows in the list.
                onNodeWithTag(MEMORY_NOTE_NAME_TAG).performTextInput("deploy-notes")
                onNodeWithTag(MEMORY_NOTE_DESCRIPTION_TAG).performTextInput("how we deploy")
                onNodeWithTag(MEMORY_NOTE_BODY_TAG).performTextInput("caddy then fastify")
                onNodeWithTag(MEMORY_NOTE_SAVE_TAG).performClick()
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.MEMORY_SAVE).isNotEmpty() }
                val save = link.sentOf(Envelope.MEMORY_SAVE).single().data
                assertTrue(save.contains("\"deploy-notes\"") && save.contains("caddy then fastify"), "memory_save carries the note: $save")
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(MEMORY_NOTE_DIALOG_TAG).fetchSemanticsNodes().isEmpty() }
                link.push(storedNote("deploy-notes", "how we deploy", "caddy then fastify"))
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("deploy-notes").fetchSemanticsNodes().isNotEmpty() }
            }
        } finally {
            scope.cancel()
        }
    }

    /**
     * MEMORY-UI-1: clicking a note opens the same modal for editing. The index has no body, so the pane asks for
     * the note (`memory_get`) and fills the body in when `memory_note` answers; the name is fixed, and Save
     * re-sends `memory_save` under the same name (the daemon's default replace mode).
     */
    @Test fun `clicking a note opens the same modal pre-filled and save replaces it by name`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = MemoryFakeDaemonClient(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(900, 900) {
                liveMemoryPane(link, controller, MemoryIndexEntry("cart-notes", "cart rules"))

                onNodeWithText("cart-notes").performClick()
                onNodeWithTag(MEMORY_NOTE_DIALOG_TAG).assertExists()
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.MEMORY_GET).isNotEmpty() }
                val get = link.sentOf(Envelope.MEMORY_GET).single().data
                assertTrue(get.contains("\"p-beta\"") && get.contains("\"cart-notes\""), "memory_get names the project and note: $get")
                link.push(storedNote("cart-notes", "cart rules", "never trust the client price"))
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("never trust the client price").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag(MEMORY_NOTE_DESCRIPTION_TAG).assertTextContains("cart rules")
                onNodeWithTag(MEMORY_NOTE_NAME_TAG).assertIsNotEnabled()

                onNodeWithTag(MEMORY_NOTE_BODY_TAG).performTextInput(" -- cents only")
                onNodeWithTag(MEMORY_NOTE_SAVE_TAG).performClick()
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.MEMORY_SAVE).isNotEmpty() }
                val save = link.sentOf(Envelope.MEMORY_SAVE).single().data
                assertTrue(save.contains("\"cart-notes\"") && save.contains("cents only") && save.contains("never trust"), "edit re-saves the whole note by name: $save")
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(MEMORY_NOTE_DIALOG_TAG).fetchSemanticsNodes().isEmpty() }
            }
        } finally {
            scope.cancel()
        }
    }
}
