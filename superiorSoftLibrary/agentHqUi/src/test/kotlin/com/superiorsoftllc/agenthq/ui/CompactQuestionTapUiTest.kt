package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click as touchClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.AttentionItem
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BUG 14ca3437 (Android): tapping a pending question in the Attention panel did nothing on the phone. "Show" only
 * un-hid the question; the question is drawn inline in its own session's chat, and in a compact window the Attention
 * drawer covered that chat and the asking session was never selected. A tap must close the drawer, open the asker's
 * chat with the question, and the answer must reach the daemon. The same held for an attention item's "Open agent".
 *
 * The real window ([App]) at phone width against a fake link; the taps are touch input.
 */
@OptIn(ExperimentalTestApi::class)
class CompactQuestionTapUiTest {
    private val here = "s-phone-1"
    private val asker = "s-phone-2"
    private val questionJson =
        """{"id":"q1","sessionId":"$asker","agentId":"worker-1","role":"Dev","question":"Which store?",""" +
            """"options":[{"label":"sqlite","description":"one file"},{"label":"postgres","description":"a server"}],""" +
            """"recommended":"postgres","reason":"concurrent writers","allowOther":true,"state":"open","createdAt":1}"""

    private fun withPhone(width: Int = 400, body: ComposeUiTest.(Controller, ChangesFakeLink) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(width, 800) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null) }
                link.push(Envelope(Envelope.READY, "contract=1.92.0 provider=stub instance=i1"))
                link.push(
                    Envelope(
                        Envelope.SESSIONS,
                        """[{"id":"$here","provider":"claude","workingDir":"/w","spawnedAt":1,"lastHeartbeat":1,"state":"IDLE"},""" +
                            """{"id":"$asker","provider":"claude","workingDir":"/w","spawnedAt":2,"lastHeartbeat":1,"state":"IDLE"}]""",
                    ),
                )
                waitUntil(timeoutMillis = 10_000) { controller.state.value.agents.keys.containsAll(listOf(here, asker)) }
                controller.select(here)
                waitForIdle()
                body(controller, link)
            }
        } finally {
            scope.cancel()
        }
    }

    private fun ComposeUiTest.shown(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.tap(tag: String) {
        tapNode(onNodeWithTag(tag))
    }

    /** A touch tap, scrolled into view first when the node sits in a scrolling column. */
    private fun ComposeUiTest.tapNode(node: SemanticsNodeInteraction) {
        runCatching { node.performScrollTo() }
        node.performTouchInput { touchClick() }
        waitForIdle()
    }

    private fun ComposeUiTest.openAttention() {
        tap("title-bar-toggle-right")
        tap("right-tab-ATTENTION")
        assertTrue(shown("right-panel"), "the Attention drawer is open")
    }

    @Test
    fun `at phone width tapping a pending question opens it in the asker's chat and the pick is sent`() = withPhone { controller, link ->
        link.push(Envelope(Envelope.QUESTIONS, "[$questionJson]"))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.openQuestions.isNotEmpty() }
        waitForIdle()
        openAttention()
        tap(QUESTION_HISTORY_TAG_PREFIX + "q1")
        assertTrue(!shown("right-panel"), "the drawer closes so the chat is on screen")
        assertEquals(asker, controller.state.value.selectedId, "the asking session is open")
        onNodeWithTag(QUESTION_WINDOW_TAG).assertIsDisplayed()
        onNodeWithText("Which store?").assertIsDisplayed()
        tap(QUESTION_OPTION_TAG_PREFIX + 0)
        val answers = link.sentOf(Envelope.QUESTION_ANSWER).map { it.data }
        assertEquals(1, answers.size, "one question_answer: $answers")
        assertTrue(answers.single().contains("\"questionId\":\"q1\"") && answers.single().contains("\"answer\":\"sqlite\""), answers.single())
    }

    @Test
    fun `at phone width the Show button of a question put off with Later opens it too`() = withPhone { controller, link ->
        link.push(Envelope(Envelope.QUESTIONS, "[$questionJson]"))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.openQuestions.isNotEmpty() }
        controller.dismissQuestions()
        waitForIdle()
        openAttention()
        tapNode(onNodeWithText("Show"))
        assertTrue(!shown("right-panel"), "the drawer closes")
        assertEquals(asker, controller.state.value.selectedId)
        onNodeWithTag(QUESTION_WINDOW_TAG).assertIsDisplayed()
    }

    @Test
    fun `at phone width an attention item's Open agent shows that agent's chat`() = withPhone { controller, link ->
        val item = AttentionItem(id = "a-1", at = 1, kind = "trigger", outcome = "tripped", detail = "x", sessionId = asker)
        link.push(Envelope(Envelope.ATTENTION, Json.encodeToString(AttentionItem.serializer(), item)))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.attentionOpenCount == 1 }
        waitForIdle()
        openAttention()
        tapNode(onNodeWithText("Open agent"))
        assertTrue(!shown("right-panel"), "the drawer closes")
        assertEquals(asker, controller.state.value.selectedId)
    }

    @Test
    fun `at phone width the questions waiting pill over the drawer opens the question`() = withPhone { controller, link ->
        link.push(Envelope(Envelope.QUESTIONS, "[$questionJson]"))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.openQuestions.isNotEmpty() }
        waitForIdle()
        openAttention()
        tap(QUESTION_PILL_TAG)
        assertTrue(!shown("right-panel"), "the drawer closes")
        assertEquals(asker, controller.state.value.selectedId)
        onNodeWithTag(QUESTION_WINDOW_TAG).assertIsDisplayed()
    }

    @Test
    fun `at desktop width tapping a pending question opens the asker and keeps the right panel`() = withPhone(1400) { controller, link ->
        link.push(Envelope(Envelope.QUESTIONS, "[$questionJson]"))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.openQuestions.isNotEmpty() }
        waitForIdle()
        tap("right-tab-ATTENTION")
        tap(QUESTION_HISTORY_TAG_PREFIX + "q1")
        assertTrue(shown("right-panel"), "the desktop right panel stays docked")
        assertEquals(asker, controller.state.value.selectedId)
        onNodeWithTag(QUESTION_WINDOW_TAG).assertIsDisplayed()
    }

    private val goneJson = questionJson.replace("\"id\":\"q1\"", "\"id\":\"q-gone\"").replace(asker, "s-removed")

    @Test
    fun `a question whose session no longer exists says so on tap and Dismiss cancels it`() = withPhone { controller, link ->
        link.push(Envelope(Envelope.QUESTIONS, "[$goneJson]"))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.openQuestions.isNotEmpty() }
        waitForIdle()
        tap(QUESTION_PILL_TAG)
        onNodeWithText("This question's session no longer exists").assertIsDisplayed()
        assertEquals(here, controller.state.value.selectedId, "no session to open")
        tap(QUESTION_GONE_DISMISS_TAG)
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.QUESTION_CANCEL).isNotEmpty() }
        assertTrue(link.sentOf(Envelope.QUESTION_CANCEL).single().data.contains("q-gone"))
        assertTrue(!shown(QUESTION_GONE_TAG), "the card closes")
    }

    @Test
    fun `the pill's cross dismisses the question it would open`() = withPhone { controller, link ->
        link.push(Envelope(Envelope.QUESTIONS, "[$goneJson]"))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.openQuestions.isNotEmpty() }
        waitForIdle()
        tap(QUESTION_PILL_DISMISS_TAG)
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.QUESTION_CANCEL).isNotEmpty() }
        assertTrue(link.sentOf(Envelope.QUESTION_CANCEL).single().data.contains("q-gone"))
    }

    @Test
    fun `an open question in the attention history has a Dismiss button`() = withPhone(1400) { controller, link ->
        link.push(Envelope(Envelope.QUESTIONS, "[$goneJson]"))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.openQuestions.isNotEmpty() }
        waitForIdle()
        tap("right-tab-ATTENTION")
        tap(QUESTION_HISTORY_DISMISS_TAG_PREFIX + "q-gone")
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.QUESTION_CANCEL).isNotEmpty() }
    }
}
