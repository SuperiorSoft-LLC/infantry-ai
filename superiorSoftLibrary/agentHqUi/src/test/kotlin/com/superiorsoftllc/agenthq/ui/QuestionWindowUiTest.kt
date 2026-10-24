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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.UiFlags
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

    private fun answeredJson() = openJson.replace(""""state":"open"""", """"state":"answered","answer":"sqlite","resolvedBy":"owner"""")

    @Test fun `the inline panel exists only while this session has a pending question`() = runDesktopComposeUiTest(900, 700) {
        var state by mutableStateOf(AppState())
        setContent { QuestionPanel(state.shownQuestionsFor("s1"), {}, {}, {}) }
        onNodeWithTag(QUESTION_WINDOW_TAG).assertDoesNotExist()
        state = stateWithQuestion()
        waitForIdle()
        onNodeWithTag(QUESTION_WINDOW_TAG).assertExists()
        onNodeWithText("Dev · worker-1").assertExists()
        onNodeWithText("Which store?").assertExists()
        onNodeWithTag(QUESTION_OPTION_TAG_PREFIX + 0).assertExists()
        onNodeWithTag(QUESTION_OPTION_TAG_PREFIX + 1).assertExists()
        onNodeWithTag(QUESTION_RECOMMENDED_TAG_PREFIX + 1, useUnmergedTree = true).assertExists()
        onNodeWithTag(QUESTION_RECOMMENDED_TAG_PREFIX + 0, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Why: concurrent writers").assertExists()
    }

    @Test fun `a click answers with that label and the answered push collapses the panel`() = runDesktopComposeUiTest(900, 700) {
        var state by mutableStateOf(stateWithQuestion())
        val sent = mutableListOf<QuestionAnswerWire>()
        setContent { QuestionPanel(state.shownQuestionsFor("s1"), { sent += it }, {}, {}) }
        onNodeWithTag(QUESTION_OPTION_TAG_PREFIX + 0).performClick()
        waitForIdle()
        assertEquals(listOf(QuestionAnswerWire("q1", answer = "sqlite")), sent)
        state = reduce(state, Envelope(Envelope.QUESTION, answeredJson()), 2)
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithTag(QUESTION_WINDOW_TAG).assertDoesNotExist()
        assertTrue(state.openQuestionSessions.isEmpty())
    }

    @Test fun `the other field sends the owner's own text`() = runDesktopComposeUiTest(900, 700) {
        val state = stateWithQuestion()
        val sent = mutableListOf<QuestionAnswerWire>()
        setContent { QuestionPanel(state.shownQuestionsFor("s1"), { sent += it }, {}, {}) }
        onNodeWithTag(QUESTION_OTHER_FIELD_TAG).performTextInput("  mysql  ")
        onNodeWithTag(QUESTION_SEND_OTHER_TAG).performClick()
        waitForIdle()
        assertEquals(listOf(QuestionAnswerWire("q1", otherText = "mysql")), sent)
    }

    @Test fun `Later hides the panel, the pill brings it back, and Cancel sends the id`() = runDesktopComposeUiTest(900, 700) {
        var state by mutableStateOf(stateWithQuestion())
        val cancelled = mutableListOf<String>()
        setContent {
            Box(Modifier.fillMaxSize()) {
                QuestionPanel(
                    state.shownQuestionsFor("s1"), {}, { cancelled += it },
                    onLater = { state = state.copy(dismissedQuestions = state.openQuestions.mapTo(HashSet()) { it.id }) },
                )
                QuestionPill(
                    state.openQuestions.count { it.id in state.dismissedQuestions },
                    onShow = { state = state.copy(dismissedQuestions = emptySet()) },
                )
            }
        }
        onNodeWithTag(QUESTION_PILL_TAG).assertDoesNotExist()
        onNodeWithTag(QUESTION_LATER_TAG).performClick()
        mainClock.advanceTimeBy(1_000)
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

    @Test fun `shownQuestionsFor is per session, skips put-off ones and keeps the oldest first`() {
        val two = "[" + openJson.replace(""""id":"q1"""", """"id":"q0"""").replace(""""createdAt":1""", """"createdAt":0""") + "," + openJson + "," +
            openJson.replace(""""id":"q1"""", """"id":"q9"""").replace(""""sessionId":"s1"""", """"sessionId":"s2"""") + "]"
        val state = reduce(AppState(), Envelope(Envelope.QUESTIONS, two), 1)
        assertEquals(listOf("q0", "q1"), state.shownQuestionsFor("s1").map { it.id })
        assertEquals(listOf("q9"), state.shownQuestionsFor("s2").map { it.id })
        assertTrue(state.shownQuestionsFor("s3").isEmpty())
        assertTrue(state.copy(dismissedQuestions = setOf("q0", "q1")).shownQuestionsFor("s1").isEmpty())
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

/** ASK-2: the Config "Send a test question" action. */
@OptIn(ExperimentalTestApi::class)
class TestQuestionUiTest {
    @Test fun `the Notifications tab sends the sample question for the open session`() = runDesktopComposeUiTest(900, 900) {
        var sent = 0
        setContent { ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}, onSendTestQuestion = { sent++ }) }
        onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.NOTIFICATIONS.name).performClick()
        onNodeWithTag(TEST_QUESTION_TAG).performScrollTo().performClick()
        assertEquals(1, sent)
    }

    @Test fun `the sample is a plain question_ask for the session with its recommendation among the options`() {
        val frame = com.superiorsoftllc.agenthq.ui.protocol.WireRequests.questionAskTest("s1")
        assertEquals(Envelope.QUESTION_ASK, frame.type)
        assertEquals("s1", frame.sessionId)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(frame.data) as kotlinx.serialization.json.JsonObject
        val labels = (json.getValue("options") as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonObject).getValue("label").toString().trim('"') }
        assertTrue(json.getValue("recommended").toString().trim('"') in labels)
    }
}
