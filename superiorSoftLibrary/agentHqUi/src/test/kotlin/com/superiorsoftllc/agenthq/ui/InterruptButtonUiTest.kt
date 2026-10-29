package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.chat.*

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.QueuedPrompt
import com.superiorsoftllc.agenthq.ui.model.TranscriptEntry
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test

/** UI-INTERRUPT: the Interrupt control is live only while busy, and queued messages are visibly waiting. */
@OptIn(ExperimentalTestApi::class)
class InterruptButtonUiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun agent(state: SessionState) = AgentView(
        session = SessionRecord("s", "stub", "/w", spawnedAt = 1, lastHeartbeat = 1, state = state, providerSessionId = "p"),
        entries = listOf(TranscriptEntry(1, LineKind.USER, "scan my directory", 1)),
    )

    @Test fun `interrupt is enabled while busy and disabled while idle, when connected`() {
        runDesktopComposeUiTest(900, 300) {
            setContent { InterruptButton(agent(SessionState.BUSY), live = true, controller = Controller(scope)) }
            onNodeWithTag(INTERRUPT_BUTTON_TAG).assertIsEnabled()
        }
        runDesktopComposeUiTest(900, 300) {
            setContent { InterruptButton(agent(SessionState.IDLE), live = true, controller = Controller(scope)) }
            onNodeWithTag(INTERRUPT_BUTTON_TAG).assertIsNotEnabled()
        }
    }

    @Test fun `interrupt is disabled while disconnected even if busy`() {
        runDesktopComposeUiTest(900, 300) {
            setContent { InterruptButton(agent(SessionState.BUSY), live = false, controller = Controller(scope)) }
            onNodeWithTag(INTERRUPT_BUTTON_TAG).assertIsNotEnabled()
        }
    }

    @Test fun `queued messages are listed as waiting, and nothing is shown when the queue is empty`() {
        runDesktopComposeUiTest(900, 300) {
            setContent { QueuedMessages(listOf(QueuedPrompt("then check the logs", 2))) }
            onNodeWithTag(QUEUED_MESSAGES_TAG).assertExists()
            onNodeWithText("1. then check the logs").assertExists()
        }
        runDesktopComposeUiTest(900, 300) {
            setContent { QueuedMessages(emptyList()) }
            onNodeWithTag(QUEUED_MESSAGES_TAG).assertDoesNotExist()
        }
    }
}
