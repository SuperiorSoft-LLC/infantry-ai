// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.PromptCards
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.PromptOptionView
import com.superiorsoftllc.infantry.ui.protocol.PromptRequestView
import com.superiorsoftllc.infantry.ui.protocol.PromptResponseWire
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PROMPT-2: the chat prompt card. Rendered from the model (`AppState.promptsFor`) the window uses, and each answer is
 * the exact `prompt_response` the window sends.
 */
@OptIn(ExperimentalTestApi::class)
class PromptCardUiTest {
    private fun approval(id: String, tool: String = "Bash", input: String = "git status") =
        PromptRequestView(id, "s1", PromptCards.KIND_TOOL_APPROVAL, createdAt = 1, tool = tool, input = input)

    private val question = PromptRequestView(
        "q1", "s1", PromptCards.KIND_QUESTION, question = "Which databases?",
        options = listOf(PromptOptionView("Postgres", "relational"), PromptOptionView("Mongo", "documents")),
        multiSelect = true, allowOther = true,
    )

    private fun clickDecision(decision: String): PromptResponseWire? {
        var sent: PromptResponseWire? = null
        runDesktopComposeUiTest(900, 500) {
            setContent { PromptCard(listOf(approval("p1")), allowAlways = false, onAnswer = { sent = it }, onDismiss = {}) }
            onNodeWithTag(PROMPT_CHOICE_TAG_PREFIX + decision).performClick()
            waitForIdle()
        }
        return sent
    }

    @Test fun `each approval button sends its own decision`() {
        assertEquals(PromptResponseWire("p1", decision = "allow_once"), clickDecision("allow_once"))
        assertEquals(PromptResponseWire("p1", decision = "allow_session"), clickDecision("allow_session"))
        assertEquals(PromptResponseWire("p1", decision = "deny"), clickDecision("deny"))
    }

    @Test fun `the card shows the tool and its input summary`() = runDesktopComposeUiTest(900, 500) {
        setContent { PromptCard(listOf(approval("p1")), allowAlways = false, onAnswer = {}, onDismiss = {}) }
        onNodeWithText("Bash").assertExists()
        onNodeWithText("git status").assertExists()
        onNodeWithText("Allow once").assertExists()
        onNodeWithText("Allow for this session").assertExists()
        onNodeWithText("Deny").assertExists()
    }

    @Test fun `a question sends the chosen answers and the Other text`() {
        var sent: PromptResponseWire? = null
        runDesktopComposeUiTest(900, 600) {
            setContent { PromptCard(listOf(question), allowAlways = false, onAnswer = { sent = it }, onDismiss = {}) }
            onNodeWithText("Which databases?").assertExists()
            onNodeWithText("relational").assertExists()
            onNodeWithTag(PROMPT_OPTION_TAG_PREFIX + 1).performClick()
            onNodeWithTag(PROMPT_OPTION_TAG_PREFIX + 0).performClick()
            onNodeWithTag(PROMPT_OTHER_FIELD_TAG).performTextInput("Redis")
            onNodeWithTag(PROMPT_SUBMIT_TAG).performClick()
            waitForIdle()
        }
        assertEquals(PromptResponseWire("q1", answers = listOf("Postgres", "Mongo"), otherText = "Redis"), sent)
    }

    @Test fun `a queue of two shows 1 of 2 and answers the oldest`() {
        var sent: PromptResponseWire? = null
        runDesktopComposeUiTest(900, 500) {
            setContent { PromptCard(listOf(approval("p1"), approval("p2", input = "ls")), allowAlways = false, onAnswer = { sent = it }, onDismiss = {}) }
            onNodeWithTag(PROMPT_POSITION_TAG).assertTextEquals("1 of 2")
            onNodeWithText("git status").assertExists()
            onNodeWithTag(PROMPT_CHOICE_TAG_PREFIX + "allow_once").performClick()
            waitForIdle()
        }
        assertEquals("p1", sent?.promptId)
    }

    @Test fun `prompt_resolved closes the card, even when another client answered`() = runDesktopComposeUiTest(900, 500) {
        var state by mutableStateOf(
            reduce(AppState(), Envelope(Envelope.PROMPT_REQUEST, """{"promptId":"p1","sessionId":"s1","kind":"tool_approval","createdAt":1,"tool":"Bash","input":"ls"}""", "s1"), 1),
        )
        setContent { PromptCard(state.promptsFor("s1"), allowAlways = false, onAnswer = {}, onDismiss = {}) }
        onNodeWithTag(PROMPT_CARD_TAG).assertExists()
        state = reduce(state, Envelope(Envelope.PROMPT_RESOLVED, """{"promptId":"p1","resolvedBy":"client:7","outcome":"allow_once"}"""), 2)
        waitForIdle()
        onNodeWithTag(PROMPT_CARD_TAG).assertDoesNotExist()
    }

    @Test fun `Esc denies, arrows move and Enter submits the highlighted button`() {
        val sent = mutableListOf<PromptResponseWire>()
        runDesktopComposeUiTest(900, 500) {
            setContent { PromptCard(listOf(approval("p1")), allowAlways = false, onAnswer = { sent += it }, onDismiss = {}) }
            onNodeWithTag(PROMPT_CARD_TAG).performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag(PROMPT_CARD_TAG).performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.Enter) }
            waitForIdle()
        }
        assertEquals(listOf(PromptResponseWire("p1", decision = "deny"), PromptResponseWire("p1", decision = "allow_session")), sent)
    }

    @Test fun `Always allow shows only when the daemon takes it and names the command prefix`() {
        var sent: PromptResponseWire? = null
        runDesktopComposeUiTest(900, 500) {
            var allow by mutableStateOf(PromptCards.offersAllowAlways("1.77.0"))
            setContent { PromptCard(listOf(approval("p1", input = "git push")), allowAlways = allow, onAnswer = { sent = it }, onDismiss = {}) }
            onNodeWithTag(PROMPT_CHOICE_TAG_PREFIX + PromptCards.ALLOW_ALWAYS).assertDoesNotExist()
            allow = PromptCards.offersAllowAlways("1.78.0")
            waitForIdle()
            onNodeWithText("Always allow `git` commands").assertExists()
            onNodeWithTag(PROMPT_CHOICE_TAG_PREFIX + PromptCards.ALLOW_ALWAYS).performClick()
            waitForIdle()
        }
        assertEquals(PromptResponseWire("p1", decision = "allow_always"), sent)
    }
}
