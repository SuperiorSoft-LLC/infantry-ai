// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.TranscriptEntry
import com.superiorsoftllc.infantry.ui.protocol.BackgroundWorkView
import com.superiorsoftllc.infantry.ui.protocol.BgJobView
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CHAT-BG-1: an idle agent whose background jobs still run shows ONE quiet row at the very end of the chat
 * ("Background: N running · <newest job>"), never a message; it stays single and last when new messages arrive, and goes
 * away when the work ends or a turn starts.
 */
@OptIn(ExperimentalTestApi::class)
class ChatBackgroundRowUiTest {
    private val t0 = 10_000_000L

    private fun agent(
        jobs: List<BgJobView> = emptyList(),
        turnActive: Boolean = false,
        lines: List<TranscriptEntry> = listOf(TranscriptEntry(1, LineKind.TEXT, "started the build", t0 - 5_000)),
        bgWork: BackgroundWorkView? = null,
    ) = AgentView(
        session = SessionRecord(
            id = "a", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1,
            state = if (jobs.isNotEmpty() || bgWork != null || turnActive) SessionState.BUSY else SessionState.IDLE,
            providerSessionId = "p", bgJobs = jobs, backgroundWork = bgWork, turnActive = turnActive,
        ),
        entries = lines,
    )

    private val gate = BgJobView("j1", "gate", startedAt = t0 - 120_000)
    private val build = BgJobView("j2", "bash ./gradlew :superiorSoftLibrary:infantryUi:test --tests ChatBackgroundRowUiTest", startedAt = t0 - 30_000)

    @Test
    fun idleWithJobsShowsOneRowLastWithItsCountAndNewestJob() {
        var a by mutableStateOf(agent(listOf(gate, build)))
        runDesktopComposeUiTest(900, 600) {
            mainClock.autoAdvance = false
            setContent { AgentChat(a, nowProvider = { t0 }) }
            mainClock.advanceTimeByFrame()

            onAllNodesWithTag(CHAT_BG_ROW_TAG, useUnmergedTree = true).assertCountEquals(1)
            onNodeWithText("Background: 2 running · bash ./gradlew", substring = true, useUnmergedTree = true).assertExists()
            // The row is the transcript's last item.
            onNodeWithTag(CHAT_TRANSCRIPT_TAG).onChildren().onLast().assert(hasAnyDescendant(hasTestTag(CHAT_BG_ROW_TAG)))

            // Expanded: one line per job with its elapsed time.
            onNodeWithTag(CHAT_BG_HEADER_TAG, useUnmergedTree = true).performClick()
            mainClock.advanceTimeByFrame()
            onAllNodesWithTag(CHAT_BG_JOB_TAG, useUnmergedTree = true).assertCountEquals(2)
            onNodeWithText("gate  2m 00s", useUnmergedTree = true).assertExists()

            // A new message: still exactly one row, and still the last item.
            a = a.copy(entries = a.entries + TranscriptEntry(2, LineKind.USER, "how is the build going", t0))
            mainClock.advanceTimeByFrame()
            onNodeWithText("how is the build going", substring = true, useUnmergedTree = true).assertExists()
            onAllNodesWithTag(CHAT_BG_ROW_TAG, useUnmergedTree = true).assertCountEquals(1)
            onNodeWithTag(CHAT_TRANSCRIPT_TAG).onChildren().onLast().assert(hasAnyDescendant(hasTestTag(CHAT_BG_ROW_TAG)))

            // The count follows the session live.
            a = a.copy(session = a.session.copy(bgJobs = listOf(gate)))
            mainClock.advanceTimeByFrame()
            onNodeWithText("Background: 1 running · gate", useUnmergedTree = true).assertExists()

            // A turn starts: the busy indicator takes over.
            a = a.copy(session = a.session.copy(turnActive = true))
            mainClock.advanceTimeByFrame()
            onAllNodesWithTag(CHAT_BG_ROW_TAG, useUnmergedTree = true).assertCountEquals(0)

            // Turn over, work over: nothing.
            a = a.copy(session = a.session.copy(turnActive = false, bgJobs = emptyList(), state = SessionState.IDLE))
            mainClock.advanceTimeByFrame()
            onAllNodesWithTag(CHAT_BG_ROW_TAG, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun noJobsNoRow() {
        runDesktopComposeUiTest(900, 600) {
            mainClock.autoAdvance = false
            setContent { AgentChat(agent(), nowProvider = { t0 }) }
            mainClock.advanceTimeByFrame()
            onAllNodesWithTag(CHAT_BG_ROW_TAG, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun observedBackgroundProcessesCountWhenNoJobIsRegistered() {
        val s = agent(bgWork = BackgroundWorkView(pid = 7, label = "bash gradlew test\nsecond line", startedAt = t0, count = 3)).session
        val w = trailingBackgroundWork(s, null)!!
        assertEquals("Background: 3 running · bash gradlew test", bgHeaderText(w))
        assertNull(trailingBackgroundWork(s.copy(turnActive = true), null))
    }
}
