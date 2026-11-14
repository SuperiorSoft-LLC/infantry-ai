// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

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
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.PromptView
import com.superiorsoftllc.infantry.ui.protocol.SkillView
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

private class LibraryFakeDaemonClient(scope: CoroutineScope) : DaemonClient(scope) {
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
 * FORM-MODAL-1: the Prompts and Skills tabs keep no create / edit form under their list (it was pushed off screen
 * as the list grew). An icon-only "+" in the header opens the shared [EditorModal]; Save sends the `*_save` frame
 * and closes; Cancel discards and sends nothing; an item's Edit opens the same modal pre-filled, its name fixed.
 * Tags are string literals so this test also compiles (and fails RED) against the pre-modal panes.
 */
@OptIn(ExperimentalTestApi::class)
class PromptsSkillsModalUiTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun ComposeUiTest.goLive(link: LibraryFakeDaemonClient, controller: Controller) {
        link.push(Envelope(Envelope.READY, "contract=1.92.0 provider=stub instance=i1"))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
        dismissSetupWizard(controller)
    }

    @Test fun `prompts - the plus icon opens the modal, save adds the prompt, cancel discards, edit pre-fills`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = LibraryFakeDaemonClient(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(900, 900) {
                setContent {
                    val state by controller.state.collectAsState()
                    PromptsPane(state, controller, onInsert = {})
                }
                goLive(link, controller)
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROMPT_LIST).isNotEmpty() }
                link.push(Envelope(Envelope.PROMPTS, json.encodeToString(ListSerializer(PromptView.serializer()), listOf(PromptView("p1", "review", "check the diff", listOf("qa"))))))
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("review").fetchSemanticsNodes().isNotEmpty() }

                // RED on the old pane: the Name field sat under the list, always visible.
                onNodeWithTag("prompt-name-input").assertDoesNotExist()
                onNodeWithTag("prompt-dialog").assertDoesNotExist()

                // "+" is icon-only, named for screen readers, and opens the modal.
                onNodeWithContentDescription("Add prompt").assertExists()
                onNodeWithTag("prompts-add").performClick()
                onNodeWithTag("prompt-dialog").assertExists()

                // Cancel discards: nothing sent, and a reopened modal is empty.
                onNodeWithTag("prompt-name-input").performTextInput("scratch")
                onNodeWithTag("prompt-cancel").performClick()
                onNodeWithTag("prompt-dialog").assertDoesNotExist()
                assertTrue(link.sentOf(Envelope.PROMPT_SAVE).isEmpty(), "cancel sends nothing")
                onNodeWithTag("prompts-add").performClick()
                onNodeWithText("scratch").assertDoesNotExist()

                // Save sends prompt_save and closes; the stored prompt shows in the list.
                onNodeWithTag("prompt-name-input").performTextInput("deploy")
                onNodeWithTag("prompt-body-input").performTextInput("ship it")
                onNodeWithTag("prompt-tags-input").performTextInput("ops")
                onNodeWithTag("prompt-save").performClick()
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROMPT_SAVE).isNotEmpty() }
                val save = link.sentOf(Envelope.PROMPT_SAVE).single().data
                assertTrue(save.contains("\"deploy\"") && save.contains("ship it") && save.contains("ops"), "prompt_save carries the prompt: $save")
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("prompt-dialog").fetchSemanticsNodes().isEmpty() }
                link.push(Envelope(Envelope.PROMPT_ITEM, json.encodeToString(PromptView.serializer(), PromptView("p2", "deploy", "ship it", listOf("ops")))))
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("deploy").fetchSemanticsNodes().isNotEmpty() }

                // Edit opens the same modal pre-filled, name fixed; Save re-sends under the same name.
                onNodeWithTag("prompt-edit-review").performClick()
                onNodeWithTag("prompt-dialog").assertExists()
                onNodeWithTag("prompt-name-input").assertIsNotEnabled()
                onNodeWithTag("prompt-body-input").assertTextContains("check the diff")
                onNodeWithTag("prompt-body-input").performTextInput(" twice")
                onNodeWithTag("prompt-save").performClick()
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROMPT_SAVE).size == 2 }
                val edit = link.sentOf(Envelope.PROMPT_SAVE).last().data
                assertTrue(edit.contains("\"review\"") && edit.contains("twice"), "edit re-saves by name: $edit")
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `skills - the plus icon opens the modal, save adds the skill, cancel discards, edit pre-fills`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = LibraryFakeDaemonClient(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(900, 900) {
                setContent {
                    val state by controller.state.collectAsState()
                    SkillsPane(state, controller, onInsert = {})
                }
                goLive(link, controller)
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.SKILL_LIST).isNotEmpty() }
                link.push(Envelope(Envelope.SKILLS, json.encodeToString(ListSerializer(SkillView.serializer()), listOf(SkillView("s1", "tdd", "red first", "write the test", listOf("qa"))))))
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("tdd").fetchSemanticsNodes().isNotEmpty() }

                onNodeWithTag("skill-name-input").assertDoesNotExist()
                onNodeWithTag("skill-dialog").assertDoesNotExist()

                onNodeWithContentDescription("Add skill").assertExists()
                onNodeWithTag("skills-add").performClick()
                onNodeWithTag("skill-dialog").assertExists()

                onNodeWithTag("skill-name-input").performTextInput("scratch")
                onNodeWithTag("skill-cancel").performClick()
                onNodeWithTag("skill-dialog").assertDoesNotExist()
                assertTrue(link.sentOf(Envelope.SKILL_SAVE).isEmpty(), "cancel sends nothing")
                onNodeWithTag("skills-add").performClick()
                onNodeWithText("scratch").assertDoesNotExist()

                onNodeWithTag("skill-name-input").performTextInput("refactor")
                onNodeWithTag("skill-description-input").performTextInput("small steps")
                onNodeWithTag("skill-body-input").performTextInput("one move at a time")
                onNodeWithTag("skill-save").performClick()
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.SKILL_SAVE).isNotEmpty() }
                val save = link.sentOf(Envelope.SKILL_SAVE).single().data
                assertTrue(save.contains("\"refactor\"") && save.contains("small steps") && save.contains("one move"), "skill_save carries the skill: $save")
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("skill-dialog").fetchSemanticsNodes().isEmpty() }
                link.push(Envelope(Envelope.SKILL_ITEM, json.encodeToString(SkillView.serializer(), SkillView("s2", "refactor", "small steps", "one move at a time"))))
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("refactor").fetchSemanticsNodes().isNotEmpty() }

                onNodeWithTag("skill-edit-tdd").performClick()
                onNodeWithTag("skill-dialog").assertExists()
                onNodeWithTag("skill-name-input").assertIsNotEnabled()
                onNodeWithTag("skill-description-input").assertTextContains("red first")
                onNodeWithTag("skill-body-input").performTextInput(" always")
                onNodeWithTag("skill-save").performClick()
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.SKILL_SAVE).size == 2 }
                val edit = link.sentOf(Envelope.SKILL_SAVE).last().data
                assertTrue(edit.contains("\"tdd\"") && edit.contains("always"), "edit re-saves by name: $edit")
            }
        } finally {
            scope.cancel()
        }
    }
}
