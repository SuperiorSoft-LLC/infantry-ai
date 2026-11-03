// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.provider.HarnessModelCatalogs
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.stories.JsonStoryStore
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.TicketColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The real socket client, recording every frame the controller sends so the test can name the request type. */
private class RecordingDaemonClient(scope: CoroutineScope) : DaemonClient(scope) {
    val sent = CopyOnWriteArrayList<Envelope>()
    override suspend fun send(envelope: Envelope): Boolean { sent += envelope; return super.send(envelope) }
    fun sentOf(type: String) = sent.filter { it.type == type }
}

/**
 * E1-d (one work model): the Epics pane works on tickets. A real composed pane over a real in-process daemon
 * (Ktor/WebSocket/SQLite, stub provider): "Add task" sends `ticket_save{projectId, epicId}` (never `story_save`),
 * the epic card lists the tickets carrying that epic in the four board columns, and a move sends `ticket_move`.
 */
@OptIn(ExperimentalTestApi::class)
class EpicsPaneUiTest {
    private fun bootDaemon(closers: MutableList<() -> Unit>): Int {
        val dir = Files.createTempDirectory("infantry-ui-epics-pane").toFile()
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            modelCatalogs = HarnessModelCatalogs.EMPTY,
        )
        daemon.server.start(wait = false)
        closers += { daemon.server.stop(0, 200); daemon.close(); dir.deleteRecursively() }
        return runBlocking {
            repeat(250) {
                daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                delay(20)
            }
            error("no port")
        }
    }

    @Test fun `Add task sends ticket_save with project and epic, the card lists the epic's tickets in 4 columns, and a move sends ticket_move`() {
        val closers = mutableListOf<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = bootDaemon(closers)
            val link = RecordingDaemonClient(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 1000) {
                setContent {
                    val state by controller.state.collectAsState()
                    EpicsPane(state, controller)
                }
                controller.connect("ws://127.0.0.1:$port/ws", null)
                waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive }

                assertIs<CommandResult.Sent>(runBlocking { controller.createProject("Alpha") })
                waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProjectId != null }
                val alpha = controller.state.value.activeProjectId!!
                assertIs<CommandResult.Sent>(runBlocking { controller.saveEpic(alpha, "Checkout") })
                assertIs<CommandResult.Sent>(runBlocking { controller.saveEpic(alpha, "Search") })
                waitUntil(timeoutMillis = 10_000) { controller.state.value.epics[alpha].orEmpty().size == 2 }
                val checkout = controller.state.value.epics.getValue(alpha).first { it.name == "Checkout" }.id
                val search = controller.state.value.epics.getValue(alpha).first { it.name == "Search" }.id
                onNodeWithText("Checkout").assertExists()

                // (a) "Add task" in Checkout -> ticket_save naming the project and the epic; no story_save at all.
                onNodeWithTag("new-task-$checkout").performTextInput("pay with card")
                onNodeWithTag("create-task-$checkout").performClick()
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.TICKET_SAVE).isNotEmpty() }
                val save = Json.parseToJsonElement(link.sentOf(Envelope.TICKET_SAVE).single().data).jsonObject
                assertEquals(alpha, save["projectId"]?.jsonPrimitive?.content, "ticket_save names the project: $save")
                assertEquals(checkout, save["epicId"]?.jsonPrimitive?.content, "ticket_save names the epic: $save")
                assertEquals("pay with card", save["title"]?.jsonPrimitive?.content, "ticket_save carries the typed title: $save")
                assertTrue(link.sentOf(Envelope.STORY_SAVE).isEmpty(), "Add task never sends story_save")
                assertTrue(link.sentOf(Envelope.STORY_LIST).isEmpty(), "the pane never asks for story_list")

                // A task in the other epic, made the same way, must not show under Checkout.
                onNodeWithTag("new-task-$search").performTextInput("find by sku")
                onNodeWithTag("create-task-$search").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.tickets.map { it.title }.containsAll(listOf("pay with card", "find by sku")) }
                val paid = controller.state.value.tickets.first { it.title == "pay with card" }
                assertEquals(checkout, paid.epicId, "the daemon stored the ticket under the epic")

                // (b) The Checkout card draws 4 columns, and the ticket sits in its todo column, not in Search's.
                TicketColumns.ALL.forEach { onNodeWithTag("epic-column-$checkout-$it").assertExists() }
                onNode(hasTestTag("epic-ticket-${paid.id}") and hasAnyAncestor(hasTestTag("epic-column-$checkout-${TicketColumns.TODO}"))).assertExists()
                onNode(hasText("find by sku") and hasAnyAncestor(hasTestTag("epic-column-$checkout-${TicketColumns.TODO}"))).assertDoesNotExist()
                onNode(hasText("find by sku") and hasAnyAncestor(hasTestTag("epic-column-$search-${TicketColumns.TODO}"))).assertExists()

                // (c) A move -> ticket_move with the id and the column; the daemon's answer redraws it under Done.
                onNodeWithTag("move-${paid.id}-${TicketColumns.DONE}").performClick()
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.TICKET_MOVE).isNotEmpty() }
                val move = Json.parseToJsonElement(link.sentOf(Envelope.TICKET_MOVE).single().data).jsonObject
                assertEquals(paid.id, move["id"]?.jsonPrimitive?.content, "ticket_move names the ticket: $move")
                assertEquals(TicketColumns.DONE, move["status"]?.jsonPrimitive?.content, "ticket_move names the column: $move")
                assertTrue(link.sentOf(Envelope.STORY_MOVE).isEmpty(), "a move never sends story_move")
                waitUntil(timeoutMillis = 10_000) { controller.state.value.tickets.first { it.id == paid.id }.status == TicketColumns.DONE }
                onNode(hasTestTag("epic-ticket-${paid.id}") and hasAnyAncestor(hasTestTag("epic-column-$checkout-${TicketColumns.DONE}"))).assertExists()
                onNode(hasTestTag("epic-ticket-${paid.id}") and hasAnyAncestor(hasTestTag("epic-column-$checkout-${TicketColumns.TODO}"))).assertDoesNotExist()
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }

    /**
     * FORM-MODAL-1: no "New epic" field under the epics list. An icon-only "+" in the header opens the shared modal;
     * Cancel discards and sends nothing; Save sends `epic_save`, closes, and the daemon's epic shows in the list.
     * Epics have no edit (`epic_save` carries no id), so the modal is create-only.
     */
    @Test fun `the plus icon opens the new-epic modal, save creates the epic and cancel discards`() {
        val closers = mutableListOf<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = bootDaemon(closers)
            val link = RecordingDaemonClient(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 1000) {
                setContent {
                    val state by controller.state.collectAsState()
                    EpicsPane(state, controller)
                }
                controller.connect("ws://127.0.0.1:$port/ws", null)
                waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive }
                assertIs<CommandResult.Sent>(runBlocking { controller.createProject("Alpha") })
                waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProjectId != null }
                val alpha = controller.state.value.activeProjectId!!
                assertIs<CommandResult.Sent>(runBlocking { controller.saveEpic(alpha, "Checkout") })
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Checkout").fetchSemanticsNodes().isNotEmpty() }

                // RED on the old pane: the "New epic" field sat under the list.
                onNodeWithText("New epic").assertDoesNotExist()
                onNodeWithTag("epic-dialog").assertDoesNotExist()

                onNodeWithContentDescription("New epic").assertExists()
                onNodeWithTag("epics-add").performClick()
                onNodeWithTag("epic-dialog").assertExists()

                val before = link.sentOf(Envelope.EPIC_SAVE).size
                onNodeWithTag("epic-name-input").performTextInput("scratch")
                onNodeWithTag("epic-cancel").performClick()
                onNodeWithTag("epic-dialog").assertDoesNotExist()
                assertEquals(before, link.sentOf(Envelope.EPIC_SAVE).size, "cancel sends nothing")
                onNodeWithTag("epics-add").performClick()
                onNodeWithText("scratch").assertDoesNotExist()

                onNodeWithTag("epic-name-input").performTextInput("Search")
                onNodeWithTag("epic-goal-input").performTextInput("find by sku")
                onNodeWithTag("epic-save").performClick()
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.EPIC_SAVE).size == before + 1 }
                val save = link.sentOf(Envelope.EPIC_SAVE).last().data
                assertTrue(save.contains("\"Search\"") && save.contains("find by sku") && save.contains(alpha), "epic_save carries the epic: $save")
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("epic-dialog").fetchSemanticsNodes().isEmpty() }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.epics[alpha].orEmpty().any { it.name == "Search" } }
                onNodeWithText("Search").assertExists()
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }
}
