package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AgentActivity
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import kotlin.test.Test

/**
 * UI-ACT-2: the chat pane shows a collapsible Activity box above the chat input, reading the session's
 * [AgentView.activityLog] (UI-ACT-1), newest first. It starts expanded while the session runs and
 * collapsed once it is idle.
 */
@OptIn(ExperimentalTestApi::class)
class ActivityBoxUiTest {
    private val t0 = 3_723_000L

    private fun activity(tool: String, detail: String, at: Long) = AgentActivity(tool, detail, at)

    private fun agent(id: String, state: SessionState, log: List<AgentActivity>) = AgentView(
        session = SessionRecord(id = id, provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = state, providerSessionId = "p"),
        activityLog = log,
    )

    @Test
    fun theBoxListsEveryEntryNewestFirst() {
        val a = agent(
            "a", SessionState.BUSY,
            listOf(activity("bash", "gradle test", t0), activity("grep", "TicketBoard", t0 + 1), activity("read", "Model.kt", t0 + 2)),
        )
        runDesktopComposeUiTest(900, 600) {
            mainClock.autoAdvance = false
            setContent { AgentChat(a, nowProvider = { t0 }) }
            mainClock.advanceTimeByFrame()

            onNodeWithTag(ACTIVITY_BOX_TAG).assertExists()
            onNodeWithText("Activity (3)").assertExists()
            onNodeWithText("bash: gradle test").assertExists()
            onNodeWithText("grep: TicketBoard").assertExists()
            onNodeWithText("read: Model.kt").assertExists()

            // Newest first: the last activity sits at the top.
            onAllNodesWithTag(ACTIVITY_LINE_TAG).assertCountEquals(3)
            onAllNodesWithTag(ACTIVITY_LINE_TAG)[0].assertTextEquals("read: Model.kt")
            onAllNodesWithTag(ACTIVITY_LINE_TAG)[1].assertTextEquals("grep: TicketBoard")
            onAllNodesWithTag(ACTIVITY_LINE_TAG)[2].assertTextEquals("bash: gradle test")
        }
    }

    @Test
    fun collapsingTheBoxHidesTheEntries() {
        val a = agent(
            "b", SessionState.BUSY,
            listOf(activity("bash", "gradle test", t0), activity("read", "Model.kt", t0 + 1)),
        )
        runDesktopComposeUiTest(900, 600) {
            mainClock.autoAdvance = false
            setContent { AgentChat(a, nowProvider = { t0 }) }
            mainClock.advanceTimeByFrame()

            onNodeWithText("read: Model.kt").assertExists()
            onNodeWithText("Activity (2)").performClick()
            mainClock.advanceTimeByFrame()

            onNodeWithText("Activity (2)").assertExists()
            onNodeWithText("read: Model.kt").assertDoesNotExist()
            onNodeWithText("bash: gradle test").assertDoesNotExist()
        }
    }

    @Test
    fun anIdleSessionStartsCollapsed() {
        val a = agent(
            "c", SessionState.IDLE,
            listOf(activity("bash", "gradle test", t0), activity("read", "Model.kt", t0 + 1)),
        )
        runDesktopComposeUiTest(900, 600) {
            mainClock.autoAdvance = false
            setContent { AgentChat(a, nowProvider = { t0 }) }
            mainClock.advanceTimeByFrame()

            onNodeWithText("Activity (2)").assertExists()
            onNodeWithText("read: Model.kt").assertDoesNotExist()
        }
    }

    @Test
    fun switchingSessionShowsOnlyThatSessionsEntries() {
        val a = agent(
            "a", SessionState.BUSY,
            listOf(activity("bash", "gradle test", t0), activity("read", "Model.kt", t0 + 1)),
        )
        val b = agent(
            "b", SessionState.BUSY,
            listOf(activity("grep", "TicketBoard", t0)),
        )
        val current = mutableStateOf(a)
        runDesktopComposeUiTest(900, 600) {
            mainClock.autoAdvance = false
            setContent { AgentChat(current.value, nowProvider = { t0 }) }
            mainClock.advanceTimeByFrame()

            onNodeWithText("Activity (2)").assertExists()
            onNodeWithText("read: Model.kt").assertExists()

            current.value = b
            mainClock.advanceTimeByFrame()

            onNodeWithText("Activity (1)").assertExists()
            onNodeWithText("grep: TicketBoard").assertExists()
            onNodeWithText("read: Model.kt").assertDoesNotExist()
        }
    }
}
