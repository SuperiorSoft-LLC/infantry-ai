// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.agents.*

import com.superiorsoftllc.infantry.plugins.official.board.*

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.protocol.ProviderModels
import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.provider.HarnessModelCatalogs
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.stories.JsonStoryStore
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.protocol.AgentProfile
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * TICKET-EXEC-UI: a ticket's assignee, provider and model, set on create and changed later from its card, in the REAL
 * window against a REAL in-process daemon (Ktor/WebSocket/SQLite, stub transport). opencode is the configured provider
 * with a fixed catalogue; claude comes from the default harness registry and lists no models (free text), as on the
 * owner's daemon. Every step asserts what the DAEMON stored, then what the card says it runs on.
 */
@OptIn(ExperimentalTestApi::class)
class TicketExecutorUiTest {
    private class Opencode(private val ids: List<String>) : AgentProvider by StubProvider(StubScript.default(), delayMs = 0) {
        override val id = "opencode"
        override fun models() = listOf(ProviderModels(id, id, id, ids))
        override fun knownModels(): List<String> = ids
    }

    private val chat = "deepseek/deepseek-chat"
    private val bunny = "opencode/space-bunny-free"

    @Test
    fun `create with pins, edit them, a provider change resets the model, then clear back to inherit`() {
        val dir = Files.createTempDirectory("infantry-ticket-exec").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val opencode = Opencode(listOf(bunny, chat))
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = opencode, runner = opencode.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            additionalProviders = emptyList(),
            // Hermetic: never this host's real `pi --list-models` / `opencode models`.
            modelCatalogs = HarnessModelCatalogs.EMPTY,
        )
        try {
            daemon.server.start(wait = false)
            val port = runBlocking {
                repeat(250) {
                    daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                    delay(20)
                }
                error("no port")
            }
            val controller = Controller(scope)
            fun ticket(id: String): Ticket = controller.state.value.tickets.first { it.id == id }
            runDesktopComposeUiTest(1800, 1600) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                waitUntil(timeoutMillis = 20_000) {
                    val s = controller.state.value
                    s.connection.isLive && s.roles.isNotEmpty() && listOf("opencode", "claude").all { h -> s.providers.any { it.id == h } }
                }
                // A profile, so the board offers its role lane and the profile itself as assignees.
                val role = controller.state.value.roles.first().id
                assertIs<CommandResult.Sent>(runBlocking {
                    controller.saveProfile(AgentProfile(name = "Tex", role = role, projectId = controller.state.value.activeProjectId))
                })
                waitUntil(timeoutMillis = 10_000) { controller.state.value.profiles.any { it.name == "Tex" } }
                val profileId = controller.state.value.profiles.first { it.name == "Tex" }.id!!

                onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).performClick()

                // 1. Create with pins: role lane, opencode, deepseek-chat.
                onNodeWithText("+ New ticket").performClick()
                onNodeWithText("Title").performTextInput("pinned work")
                onNodeWithTag(TICKET_ASSIGNEE_TAG_PREFIX + "role:$role").performClick()
                onNodeWithTag("ticket-harness-opencode").performClick()
                waitForIdle()
                onNodeWithTag("ticket-model-$chat").performClick()
                waitForIdle()
                onNodeWithText("Create ticket").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.tickets.any { it.title == "pinned work" } }
                val id = controller.state.value.tickets.first { it.title == "pinned work" }.id
                assertEquals("role:$role", ticket(id).agentId)
                assertEquals("opencode", ticket(id).provider, "create stored the provider pin")
                assertEquals(chat, ticket(id).model, "create stored the model pin")
                onNodeWithTag(TICKET_EXECUTOR_LINE_TAG_PREFIX + id, useUnmergedTree = true)
                    .assertTextContains("opencode (ticket) / $chat (ticket)", substring = true)

                fun save(until: (Ticket) -> Boolean) {
                    onNodeWithTag(TICKET_EDIT_SAVE_TAG).performClick()
                    runCatching { waitUntil(timeoutMillis = 10_000) { until(ticket(id)) || controller.state.value.ticketError != null } }
                    assertNull(controller.state.value.ticketError, "the daemon refused the edit")
                    waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(TICKET_EDIT_SAVE_TAG)).fetchSemanticsNodes().isEmpty() }
                }

                // 2. Edit: another model of the same provider, and the profile as assignee.
                onNodeWithTag(TICKET_CARD_TAG_PREFIX + id).performClick()
                onNodeWithTag(TICKET_ASSIGNEE_TAG_PREFIX + profileId).performClick()
                onNodeWithTag("ticket-model-$bunny").performClick()
                waitForIdle()
                save { it.model == bunny && it.agentId == profileId }
                assertEquals(profileId, ticket(id).agentId, "the edit stored the new assignee")
                assertEquals("opencode", ticket(id).provider, "an unchanged provider stays pinned")
                assertEquals(bunny, ticket(id).model, "the edit stored the new model")

                // 3. A provider change resets the model: claude does not list opencode's model, so it goes back to inherit.
                onNodeWithTag(TICKET_CARD_TAG_PREFIX + id).performClick()
                onNodeWithTag("ticket-harness-claude").performClick()
                waitForIdle()
                onNodeWithTag(TICKET_MODEL_INHERIT_TAG).assertDoesNotExist()
                save { it.provider == "claude" }
                assertEquals("claude", ticket(id).provider, "the edit stored the new provider")
                assertNull(ticket(id).model, "the incompatible model was cleared, not carried to claude")
                onNodeWithTag(TICKET_EXECUTOR_LINE_TAG_PREFIX + id, useUnmergedTree = true)
                    .assertTextContains("claude (ticket)", substring = true)

                // 4. Clear to inherit: the harness "inherit" chip drops both pins ("" on the wire).
                onNodeWithTag(TICKET_CARD_TAG_PREFIX + id).performClick()
                onNodeWithTag(TICKET_EXECUTOR_TAGS.daemonDefault).performClick()
                waitForIdle()
                save { it.provider == null }
                assertNull(ticket(id).provider, "inherit cleared the provider pin")
                assertNull(ticket(id).model, "inherit leaves no model pin")
                assertEquals(profileId, ticket(id).agentId, "clearing the executor kept the assignee")
                val line = ticket(id).effectiveExecutor
                assertEquals(false, line?.provider?.source == "ticket", "the provider no longer comes from the ticket: $line")
            }
        } finally {
            scope.cancel()
            daemon.server.stop(0, 200)
            daemon.close()
            dir.deleteRecursively()
        }
    }
}
