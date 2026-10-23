package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.OwnerQuestionView
import com.superiorsoftllc.agenthq.ui.protocol.PromptOptionView
import com.superiorsoftllc.agenthq.ui.protocol.QuestionAnswerWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ASK-1: the owner-question window and the question history. Rendered from the model the app uses (the daemon's
 * `questions` / `question` pushes through [reduce]), and each pick is the exact `question_answer` the app sends.
 */
@OptIn(ExperimentalTestApi::class)
class QuestionWindowUiTest {
    private val openJson =
        """{"id":"q1","sessionId":"s1","agentId":"worker-1","role":"Dev","question":"Which store?",""" +
            """"options":[{"label":"sqlite","description":"one file"},{"label":"postgres","description":"a server"}],""" +
            """"recommended":"postgres","reason":"concurrent writers","allowOther":true,"state":"open","createdAt":1}"""

    private fun stateWithQuestion(): AppState = reduce(AppState(), Envelope(Envelope.QUESTIONS, "[$openJson]"), 1)

    @Test fun `a questions push opens the window with the recommended option marked`() = runDesktopComposeUiTest(900, 700) {
        val state = stateWithQuestion()
        assertEquals(setOf("s1"), state.openQuestionSessions)
        setContent { Box(Modifier.fillMaxSize()) { QuestionWindow(state.shownQuestions, state.openQuestions.size, {}, {}, {}, {}) } }
        onNodeWithTag(QUESTION_WINDOW_TAG).assertExists()
        onNodeWithTag(QUESTION_ASKER_TAG, useUnmergedTree = true).assertExists()
        onNodeWithText("Dev · worker-1").assertExists()
        onNodeWithText("Which store?").assertExists()
        onNodeWithTag(QUESTION_OPTION_TAG_PREFIX + 0).assertExists()
        onNodeWithTag(QUESTION_OPTION_TAG_PREFIX + 1).assertExists()
        onNodeWithTag(QUESTION_RECOMMENDED_TAG_PREFIX + 1, useUnmergedTree = true).assertExists()
        onNodeWithTag(QUESTION_RECOMMENDED_TAG_PREFIX + 0, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Why: concurrent writers").assertExists()
    }

    @Test fun `one click on an option sends that label, and an answered push closes the window`() = runDesktopComposeUiTest(900, 700) {
        var state by mutableStateOf(stateWithQuestion())
        val sent = mutableListOf<QuestionAnswerWire>()
        setContent { Box(Modifier.fillMaxSize()) { QuestionWindow(state.shownQuestions, state.openQuestions.size, { sent += it }, {}, {}, {}) } }
        onNodeWithTag(QUESTION_OPTION_TAG_PREFIX + 0).performClick()
        waitForIdle()
        assertEquals(listOf(QuestionAnswerWire("q1", answer = "sqlite")), sent)
        state = reduce(state, Envelope(Envelope.QUESTION, openJson.replace(""""state":"open"""", """"state":"answered","answer":"sqlite","resolvedBy":"owner"""")), 2)
        waitForIdle()
        onNodeWithTag(QUESTION_WINDOW_TAG).assertDoesNotExist()
        onNodeWithTag(QUESTION_PILL_TAG).assertDoesNotExist()
        assertTrue(state.openQuestionSessions.isEmpty())
    }

    @Test fun `the other field sends the owner's own text`() = runDesktopComposeUiTest(900, 700) {
        val state = stateWithQuestion()
        val sent = mutableListOf<QuestionAnswerWire>()
        setContent { Box(Modifier.fillMaxSize()) { QuestionWindow(state.shownQuestions, 1, { sent += it }, {}, {}, {}) } }
        onNodeWithTag(QUESTION_OTHER_FIELD_TAG).performTextInput("  mysql  ")
        onNodeWithTag(QUESTION_SEND_OTHER_TAG).performClick()
        waitForIdle()
        assertEquals(listOf(QuestionAnswerWire("q1", otherText = "mysql")), sent)
    }

    @Test fun `Later hides the window behind a pill that brings it back, and Cancel sends the id`() = runDesktopComposeUiTest(900, 700) {
        var state by mutableStateOf(stateWithQuestion())
        val cancelled = mutableListOf<String>()
        setContent {
            Box(Modifier.fillMaxSize()) {
                QuestionWindow(
                    state.shownQuestions, state.openQuestions.size, {}, { cancelled += it },
                    onLater = { state = state.copy(dismissedQuestions = state.openQuestions.mapTo(HashSet()) { it.id }) },
                    onShow = { state = state.copy(dismissedQuestions = emptySet()) },
                )
            }
        }
        onNodeWithTag(QUESTION_LATER_TAG).performClick()
        waitForIdle()
        onNodeWithTag(QUESTION_WINDOW_TAG).assertDoesNotExist()
        onNodeWithText("? 1 question waiting").assertExists()
        // A hidden question still needs the owner: it keeps counting toward Needs-input.
        assertEquals(setOf("s1"), state.openQuestionSessions)
        onNodeWithTag(QUESTION_PILL_TAG).performClick()
        waitForIdle()
        onNodeWithTag(QUESTION_WINDOW_TAG).assertExists()
        onNodeWithTag(QUESTION_CANCEL_TAG).performClick()
        waitForIdle()
        assertEquals(listOf("q1"), cancelled)
    }

    @Test fun `the history offers an override on an auto-picked question`() = runDesktopComposeUiTest(900, 700) {
        val auto = OwnerQuestionView(
            "q2", sessionId = "s2", role = "QA", question = "Ship it?",
            options = listOf(PromptOptionView("yes", "ship"), PromptOptionView("no", "hold")),
            recommended = "yes", state = "auto", answer = "yes", resolvedBy = "auto", defaultAfterMin = 5,
        )
        val sent = mutableListOf<QuestionAnswerWire>()
        setContent { QuestionHistory(listOf(auto), live = true, onOverride = { sent += it }, onShow = {}) }
        onNodeWithTag(QUESTION_HISTORY_TAG_PREFIX + "q2").assertExists()
        onNodeWithText("→ yes (auto-selected)").assertExists()
        onNodeWithTag("${QUESTION_OVERRIDE_TAG_PREFIX}q2-0").assertDoesNotExist()
        onNodeWithTag("${QUESTION_OVERRIDE_TAG_PREFIX}q2-1").performClick()
        waitForIdle()
        assertEquals(listOf(QuestionAnswerWire("q2", answer = "no")), sent)
    }
}
