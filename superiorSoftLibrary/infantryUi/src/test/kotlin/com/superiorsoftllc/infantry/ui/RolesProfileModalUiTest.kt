// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.roles.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.protocol.AgentProfile
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class RolesFakeDaemonClient(scope: CoroutineScope) : DaemonClient(scope) {
    val sent = CopyOnWriteArrayList<Envelope>()
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    override val frames: SharedFlow<Envelope> get() = incoming
    override suspend fun send(envelope: Envelope): Boolean { sent += envelope; return true }
    fun push(e: Envelope) {
        val deadline = System.currentTimeMillis() + 10_000
        while (incoming.subscriptionCount.value == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        check(incoming.tryEmit(e)) { "frame buffer full" }
    }
    fun saves() = sent.filter { it.type == Envelope.AGENT_SAVE }
}

/**
 * FORM-MODAL-2: a role's profile list keeps no create form under it (it was pushed off screen as the list grew). An
 * icon-only "+" on the profiles header opens the shared [EditorModal]; Save sends `agent_save` and closes; Cancel
 * discards and sends nothing; a profile's Edit opens the same modal pre-filled and saves under the same id.
 * Tags are string literals so this test also compiles (and fails RED) against the old inline editor.
 */
@OptIn(ExperimentalTestApi::class)
class RolesProfileModalUiTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val backend = Role("backend", "Backend", "#3b82f6", "You build APIs.", "BUILT_IN")
    private val existing = AgentProfile("a1", "API builder", "backend", provider = "stub", instructions = "be terse")

    private fun body(e: Envelope) = Json.parseToJsonElement(e.data).jsonObject

    @Test fun `the plus icon opens the profile modal, save adds the profile, cancel discards, edit pre-fills`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = RolesFakeDaemonClient(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1100, 1400) {
                setContent {
                    val state by controller.state.collectAsState()
                    // RUNTIME-14: the official pane takes plugin API domains only; the test builds them over its controller.
                    RolesPane(
                        RolesDeps(
                            com.superiorsoftllc.infantry.ui.shell.CoreLink(state), com.superiorsoftllc.infantry.ui.shell.CoreSession(state),
                            com.superiorsoftllc.infantry.ui.shell.CoreRoles(state, controller), com.superiorsoftllc.infantry.ui.shell.CoreTraits(state, controller),
                            com.superiorsoftllc.infantry.ui.shell.CoreExecutor(state), com.superiorsoftllc.infantry.ui.shell.CoreNotes(state, controller),
                        ),
                        onSessionStarted = {}, onOpenAgent = {},
                    )
                }
                link.push(Envelope(Envelope.READY, "contract=1.92.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                link.push(Envelope(Envelope.ROLES, json.encodeToString(ListSerializer(Role.serializer()), listOf(backend))))
                link.push(Envelope(Envelope.AGENTS, json.encodeToString(ListSerializer(AgentProfile.serializer()), listOf(existing))))
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("role-row-name:backend", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("role-row-name:backend", useUnmergedTree = true).performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("API builder").fetchSemanticsNodes().isNotEmpty() }

                // RED on the old detail: a text "+ New profile in Backend" button toggling a form under the list.
                onNodeWithText("+ New profile in Backend").assertDoesNotExist()
                onNodeWithTag("profile-dialog").assertDoesNotExist()

                // "+" opens the modal empty, instructions seeded from the persona; Save sends agent_save and closes.
                onNodeWithTag("profile-add").performClick()
                onNodeWithTag("profile-dialog").assertExists()
                onNodeWithTag("profile-instructions-input").assertTextContains("You build APIs.", substring = true)
                onNodeWithTag("profile-name-input").performTextInput("Reviewer")
                onNodeWithTag("profile-save").performClick()
                waitUntil(timeoutMillis = 10_000) { link.saves().size == 1 }
                val created = body(link.saves().single())
                assertEquals("Reviewer", created["name"]!!.jsonPrimitive.content)
                assertEquals("backend", created["role"]!!.jsonPrimitive.content)
                assertNull(created["id"]?.jsonPrimitive?.contentOrNullSafe(), "a new profile carries no id")
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("profile-dialog").fetchSemanticsNodes().isEmpty() }

                // Cancel discards: nothing sent, modal gone, and the next "+" opens empty again.
                onNodeWithTag("profile-add").performClick()
                onNodeWithTag("profile-name-input").performTextInput("Discard me")
                onNodeWithTag("profile-cancel").performClick()
                onNodeWithTag("profile-dialog").assertDoesNotExist()
                waitForIdle()
                assertEquals(1, link.saves().size, "cancel sends nothing")

                // Edit opens the same modal pre-filled; Save updates under the same id.
                onNodeWithTag("profile-edit-a1").performClick()
                onNodeWithTag("profile-dialog").assertExists()
                onNodeWithTag("profile-name-input").assertTextContains("API builder", substring = true)
                onNodeWithTag("profile-instructions-input").assertTextContains("be terse", substring = true)
                onNodeWithTag("profile-save").performClick()
                waitUntil(timeoutMillis = 10_000) { link.saves().size == 2 }
                val updated = body(link.saves()[1])
                assertEquals("a1", updated["id"]!!.jsonPrimitive.content)
                assertEquals("API builder", updated["name"]!!.jsonPrimitive.content)
                assertEquals("stub", updated["provider"]!!.jsonPrimitive.content)
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("profile-dialog").fetchSemanticsNodes().isEmpty() }
            }
        } finally {
            scope.cancel()
        }
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content
