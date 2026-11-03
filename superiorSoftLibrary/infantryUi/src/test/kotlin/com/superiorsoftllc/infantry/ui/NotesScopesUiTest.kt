// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.notes.*

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.MemoryIndexEntry
import com.superiorsoftllc.infantry.ui.protocol.MemoryNote
import com.superiorsoftllc.infantry.ui.protocol.Project
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
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

private class NotesFakeDaemonClient(scope: CoroutineScope) : DaemonClient(scope) {
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
 * NOTES-1: the Notes tab shows three scopes. Project is the shared list (bare-id `memory_list`, as before); Role is the
 * selected session's role and Session is the selected session, each requested with its scope, each with a banner that
 * says whose notes they are and that session notes are temporary; a note of a scope can be added, edited and deleted
 * with its scope on the wire.
 */
@OptIn(ExperimentalTestApi::class)
class NotesScopesUiTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val session = AgentView(
        session = SessionRecord(id = "s1-abcdef12", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "p"),
        role = "qa",
    )

    private fun SemanticsNodeInteraction.text(): String =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private suspend fun ComposeUiTest.livePane(link: NotesFakeDaemonClient, controller: Controller) {
        setContent {
            val state by controller.state.collectAsState()
            MemoryPane(state.copy(agents = mapOf(session.id to session), selectedId = session.id), controller)
        }
        link.push(Envelope(Envelope.READY, "contract=1.98.0 provider=stub instance=i1"))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_LIST).isNotEmpty() }
        link.push(Envelope(Envelope.PROJECTS, json.encodeToString(ListSerializer(Project.serializer()), listOf(Project("p-beta", "Beta")))))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projects.size == 1 }
        controller.selectProject("p-beta")
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.MEMORY_LIST).any { it.data == "p-beta" } }
        link.push(Envelope(Envelope.MEMORY_NOTES, json.encodeToString(ListSerializer(MemoryIndexEntry.serializer()), listOf(MemoryIndexEntry("shared-rule", "for all")))))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.memoryNotes["p-beta"]?.size == 1 }
    }

    @Test fun `the three scopes each load their own notes with their banner and keep them apart`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = NotesFakeDaemonClient(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(900, 900) {
                livePane(link, controller)

                // Project scope is the default: shared banner, the project list, no scope on the wire.
                assertTrue(onNodeWithTag(NOTES_SCOPE_BANNER_TAG).text().startsWith("Project notes: shared by every role"))
                onNodeWithText("shared-rule").assertExists()

                // Role scope: asks for the selected session's role, with a role banner.
                onNodeWithTag(NOTES_SCOPE_ROLE_TAG).performClick()
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.MEMORY_LIST).any { it.data.contains("\"scope\":\"role\"") } }
                val roleList = link.sentOf(Envelope.MEMORY_LIST).last().data
                assertTrue(roleList.contains("\"role\":\"qa\"") && roleList.contains("\"p-beta\""), "role list names the project and the session's role: $roleList")
                assertTrue(onNodeWithTag(NOTES_SCOPE_BANNER_TAG).text().contains("'qa' role"))
                link.push(Envelope(Envelope.MEMORY_NOTES, json.encodeToString(ListSerializer(MemoryIndexEntry.serializer()), listOf(MemoryIndexEntry("qa-gate", "run the gate once")))))
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(NOTES_SCOPE_BANNER_TAG).fetchSemanticsNodes().isNotEmpty() && controller.state.value.memoryNotes.size == 2 }
                onNodeWithText("qa-gate").assertExists()
                onNodeWithText("shared-rule").assertDoesNotExist()

                // Session scope: asks for the selected session, and says plainly that these notes are temporary.
                onNodeWithTag(NOTES_SCOPE_SESSION_TAG).performClick()
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.MEMORY_LIST).any { it.data.contains("\"scope\":\"session\"") } }
                val sessionList = link.sentOf(Envelope.MEMORY_LIST).last().data
                assertTrue(sessionList.contains("\"sessionId\":\"s1-abcdef12\""), "session list names the session: $sessionList")
                val banner = onNodeWithTag(NOTES_SCOPE_BANNER_TAG).text()
                assertTrue(banner.contains("TEMPORARY") && banner.contains("deleted when that session is removed"), banner)
                onNodeWithText("qa-gate").assertDoesNotExist()

                // Back to project: the shared list is still there.
                onNodeWithTag(NOTES_SCOPE_PROJECT_TAG).performClick()
                onNodeWithText("shared-rule").assertExists()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `a session note is saved with its scope and deleted with its scope`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = NotesFakeDaemonClient(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(900, 900) {
                livePane(link, controller)
                onNodeWithTag(NOTES_SCOPE_SESSION_TAG).performClick()
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.MEMORY_LIST).any { it.data.contains("\"scope\":\"session\"") } }
                link.push(Envelope(Envelope.MEMORY_NOTES, "[]"))

                onNodeWithTag(MEMORY_ADD_NOTE_BUTTON_TAG).performClick()
                onNodeWithTag(MEMORY_NOTE_NAME_TAG).performTextInput("scratch")
                onNodeWithTag(MEMORY_NOTE_DESCRIPTION_TAG).performTextInput("just for now")
                onNodeWithTag(MEMORY_NOTE_BODY_TAG).performTextInput("remember this")
                onNodeWithTag(MEMORY_NOTE_SAVE_TAG).performClick()
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.MEMORY_SAVE).isNotEmpty() }
                val save = link.sentOf(Envelope.MEMORY_SAVE).single().data
                assertTrue(save.contains("\"scope\":\"session\"") && save.contains("\"sessionId\":\"s1-abcdef12\""), "memory_save carries the scope: $save")

                link.push(
                    Envelope(
                        Envelope.MEMORY_NOTE,
                        json.encodeToString(
                            MemoryNote.serializer(),
                            MemoryNote("id-1", "p-beta", "scratch", description = "just for now", body = "remember this", scope = "session", ownerId = "s1-abcdef12"),
                        ),
                    ),
                )
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(MEMORY_NOTE_DIALOG_TAG).fetchSemanticsNodes().isEmpty() }
                onNodeWithText("scratch").assertExists()

                // The stored note landed in the SESSION slice, not the project one.
                assertTrue(controller.state.value.memoryNotes["p-beta"].orEmpty().none { it.name == "scratch" })

                onNodeWithText("scratch").performClick()
                onNodeWithTag(MEMORY_NOTE_DELETE_TAG).performClick()
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.MEMORY_DELETE).isNotEmpty() }
                val delete = link.sentOf(Envelope.MEMORY_DELETE).single().data
                assertTrue(delete.contains("\"scratch\"") && delete.contains("\"scope\":\"session\""), "memory_delete carries the scope: $delete")
                link.push(Envelope(Envelope.MEMORY_NOTES, "[]"))
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(MEMORY_NOTE_DIALOG_TAG).fetchSemanticsNodes().isEmpty() }
                waitUntil(timeoutMillis = 10_000) { runCatching { onNodeWithText("scratch").assertDoesNotExist() }.isSuccess }
            }
        } finally {
            scope.cancel()
        }
    }
}
