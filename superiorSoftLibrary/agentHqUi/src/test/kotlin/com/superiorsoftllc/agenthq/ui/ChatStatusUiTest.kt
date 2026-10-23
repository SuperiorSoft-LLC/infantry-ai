package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AgentActivity
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.STALE_AFTER_MS
import com.superiorsoftllc.agenthq.ui.model.TranscriptEntry
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import kotlin.test.Test

/**
 * CHATSTATUS-5 / 1c24fdc4: the chat pane shows the working status for as long as the session is in
 * flight (the header badge's state), through text and tool-only stretches, and clears when it goes idle.
 */
@OptIn(ExperimentalTestApi::class)
class ChatStatusUiTest {
    @Test
    fun statusRowStaysOnThroughTextAndToolsAndClearsWhenIdle() {
        val agent = mutableStateOf(thinkingAgent())
        runDesktopComposeUiTest(900, 600) {
            // The pulse never settles; leave the clock still so the test can reach idle.
            mainClock.autoAdvance = false
            setContent { AgentChat(agent.value) }
            mainClock.advanceTimeByFrame()
            onNodeWithTag(CHAT_STATUS_TAG).assertExists()
            onNodeWithText("Thinking...").assertExists()

            // The agent speaks: still BUSY, so the indicator stays under the text.
            agent.value = agent.value.copy(
                entries = agent.value.entries + TranscriptEntry(3, LineKind.TEXT, "because", 2),
            )
            mainClock.advanceTimeByFrame()
            onNodeWithText("because").assertExists()
            onNodeWithTag(CHAT_STATUS_TAG).assertExists()
            onNodeWithText("Thinking...").assertExists()

            // A tool-only stretch after the text: the indicator names the tool.
            agent.value = agent.value.copy(activity = AgentActivity("Bash", "gradlew test", 3))
            mainClock.advanceTimeByFrame()
            onNodeWithText("Running Bash: gradlew test").assertExists()

            // The turn ends: the session goes idle and the indicator is gone.
            agent.value = agent.value.copy(session = agent.value.session.copy(state = SessionState.IDLE))
            mainClock.advanceTimeByFrame()
            onNodeWithTag(CHAT_STATUS_TAG).assertDoesNotExist()
        }
    }

    /**
     * CHATSTATUS-4: the elapsed label must ADVANCE while the agent works, not freeze. The AgentChat
     * one-second ticker is exercised here: with the clock held still the label reads "0s", then the
     * injected clock moves 9s on and one tick makes it read "9s".
     */
    @Test
    fun elapsedLabelAdvancesAsTheClockMoves() {
        val base = 1_000L
        var fakeNow = base
        val agent = AgentView(
            session = SessionRecord(
                id = "b",
                provider = "stub",
                workingDir = "/w",
                spawnedAt = 1,
                lastHeartbeat = 1,
                state = SessionState.BUSY,
                providerSessionId = "p",
            ),
            entries = listOf(TranscriptEntry(2, LineKind.USER, "why?", base)),
        )
        runDesktopComposeUiTest(900, 600) {
            mainClock.autoAdvance = false
            setContent { AgentChat(agent, nowProvider = { fakeNow }) }
            mainClock.advanceTimeByFrame()
            onNodeWithText("0s", substring = true).assertExists()

            fakeNow = base + 9_000
            mainClock.advanceTimeBy(1_000) // the one-second ticker re-reads the clock
            mainClock.advanceTimeByFrame()
            onNodeWithText("9s", substring = true).assertExists()
        }
    }

    private fun thinkingAgent() = AgentView(
        session = SessionRecord(
            id = "a",
            provider = "stub",
            workingDir = "/w",
            spawnedAt = 1,
            lastHeartbeat = 1,
            state = SessionState.BUSY,
            providerSessionId = "p",
        ),
        entries = listOf(TranscriptEntry(2, LineKind.USER, "why?", 1)),
    )

    /** AGENT-UX-12: the row shows WHAT the running tool is doing, not a bare "Thinking...". */
    @Test
    fun theStatusRowShowsTheRunningToolsActivity() {
        val agent = AgentView(
            session = SessionRecord("c", "stub", "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.BUSY, providerSessionId = "p"),
            entries = listOf(TranscriptEntry(2, LineKind.USER, "list it", 1_000)),
            activity = AgentActivity("Bash", "ls -la src", 1_000),
        )
        runDesktopComposeUiTest(900, 600) {
            mainClock.autoAdvance = false
            setContent { AgentChat(agent, nowProvider = { 2_000 }) }
            mainClock.advanceTimeByFrame()
            onNodeWithText("Running Bash: ls -la src").assertExists()
        }
    }

    /** AGENT-UX-12: a run with no sign of life past the threshold reads as STALLED, not merely thinking. */
    @Test
    fun aStalledRunReadsAsStalledNotThinking() {
        val agent = AgentView(
            session = SessionRecord("d", "stub", "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.BUSY, providerSessionId = "p"),
            entries = listOf(TranscriptEntry(2, LineKind.USER, "go", 0)),
        )
        runDesktopComposeUiTest(900, 600) {
            mainClock.autoAdvance = false
            setContent { AgentChat(agent, nowProvider = { STALE_AFTER_MS + 5_000 }) }
            mainClock.advanceTimeByFrame()
            onNodeWithText("no new activity for", substring = true).assertExists()
        }
    }
}
