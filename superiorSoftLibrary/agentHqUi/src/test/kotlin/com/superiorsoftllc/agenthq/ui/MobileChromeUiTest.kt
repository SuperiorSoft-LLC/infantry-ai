package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.TranscriptEntry
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * QA F5 (390px web): the session title ended in a stray "e" after its ellipsis, because the id beside it was
 * clipped to a sliver; and the tab row ran off the right edge with no cue that it scrolls.
 */
@OptIn(ExperimentalTestApi::class)
class MobileChromeUiTest {
    private val longTitled = AgentView(
        session = SessionRecord(
            id = "f78c10a3b2c1", provider = "opencode", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1,
            state = SessionState.IDLE, providerSessionId = "p", runCount = 1, model = "opencode/deepseek-v4.1-flash-free",
            worktreeBranch = "agenthq/reply-with-the-single-word-pong-f78c10a3",
        ),
        role = null,
        entries = listOf(TranscriptEntry(1, LineKind.TEXT, "finished", 1)),
        usage = emptyList(),
    )

    @Test
    fun at390TheSessionIdIsNeverClippedToAStrayGlyph() = runDesktopComposeUiTest(390, 300) {
        val state = AppState(agents = mapOf(longTitled.id to longTitled))
        setContent { CenterHeader(longTitled, spawnMode = false, state = state, onOpenSwitch = {}) }
        waitForIdle()
        val title = onNodeWithTag(AGENT_HEADER_TITLE_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val id = onNodeWithTag(AGENT_HEADER_ID_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(title.height < 28f, "title is one line high, was ${title.height}px")
        assertTrue(title.width >= 60f, "title keeps a readable width, was ${title.width}px")
        // A clipped one-glyph id is about 7px wide; an ellipsized or whole id is wider.
        assertTrue(id.width >= 40f, "id is whole or ellipsized, not a sliver: was ${id.width}px")
    }

    @Test
    fun at390TheTabRowShowsAScrollCueWhileTabsOverflow() = runDesktopComposeUiTest(390, 200) {
        setContent { CenterTabBar(AppState(), "infantry.cli") {} }
        waitForIdle()
        assertTrue(onAllNodesWithTag(TAB_BAR_MORE_CUE_TAG, useUnmergedTree = true).fetchSemanticsNodes().size == 1, "scroll cue shown while tabs overflow")
    }

    @Test
    fun onAWideWindowTheTabRowHasNoScrollCue() = runDesktopComposeUiTest(1600, 200) {
        setContent { CenterTabBar(AppState(), "infantry.cli") {} }
        waitForIdle()
        assertTrue(onAllNodesWithTag(TAB_BAR_MORE_CUE_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), "no scroll cue when all tabs fit")
    }
}
