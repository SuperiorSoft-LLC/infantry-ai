// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.plugins.official.agents.*
import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.TimelineItem
import com.superiorsoftllc.infantry.ui.model.activityTimestamp
import com.superiorsoftllc.infantry.ui.model.withTimeline
import com.superiorsoftllc.infantry.ui.protocol.AutomationView
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.Test

/** SCRIPT-VIS-1c: the AUTOMATION row, the stats panel, the sidebar badge and wake line; the chat works with no, failed or stats-only contributions. */
@OptIn(ExperimentalTestApi::class)
class AutomationVisUiTest {
    private fun agent(automation: AutomationView? = null) = AgentView(
        session = SessionRecord(id = "a", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "p", automation = automation),
        role = "pm",
    )
    private val run = TimelineItem("run-1", "a", 3, "automation", "AUTOMATION - pong", "AUTOMATION - pong - handled", details = listOf("trigger: m"))
    private val failed = run.copy(id = "run-2", ok = false, text = "AUTOMATION - pong - FAILED")
    private val stats = TimelineItem("stats-a", "a", 5, "automation-stats", "AUTOMATION stats", "x",
        meta = mapOf("turnsSaved" to "2", "wakes" to "1", "wakesByReason" to "owner:1", "lastWake" to "owner"))

    @Test fun theAutomationRowExpandsAndAFailureStillShows() = runDesktopComposeUiTest(900, 600) {
        setContent { AgentChat(agent(), contributions = listOf(run, failed)) }
        onAllNodesWithTag(CONTRIBUTION_ROW_TAG).assertCountEquals(2)
        onNodeWithText("AUTOMATION - pong - handled").performClick()
        onNodeWithTag(CONTRIBUTION_DETAILS_TAG).assertExists()
        onNodeWithText("AUTOMATION - pong - FAILED").assertExists()
    }

    @Test fun theAutomationRowShowsTheRunTimeLikeAMessageCard() = runDesktopComposeUiTest(900, 600) {
        setContent { AgentChat(agent(), contributions = listOf(run.copy(at = 1_700_000_000_000L))) }
        onNodeWithTag(CARD_TIMESTAMP_TAG, useUnmergedTree = true).assertExists()
        onNodeWithText(activityTimestamp(1_700_000_000_000L)).assertExists()
    }

    @Test fun theAutomationRowShowsTheRunDurationNextToTheTime() = runDesktopComposeUiTest(900, 600) {
        setContent { AgentChat(agent(), contributions = listOf(run.copy(id = "run-9", at = 1_700_000_000_000L, meta = mapOf("durationMs" to "400")))) }
        onNodeWithText(activityTimestamp(1_700_000_000_000L) + " · 0.4 s").assertExists()
    }

    @Test fun thePanelShowsTurnsSavedAndWakeReasonsButNoRow() = runDesktopComposeUiTest(900, 600) {
        // CHAT-FEED-SPLIT-1: the stats line is the chat frame's (above the feed slot); the feed draws no row for it.
        setContent { androidx.compose.foundation.layout.Column { AutomationStatsPanel(stats); AgentChat(agent(), contributions = listOf(stats)) } }
        onNodeWithTag(AUTOMATION_STATS_TAG).assertExists()
        onNodeWithText("turns saved 2", substring = true).assertExists()
        onNodeWithText("owner 1", substring = true).assertExists()
        onAllNodesWithTag(CONTRIBUTION_ROW_TAG).assertCountEquals(0)
    }

    @Test fun chatWithNoContributionsShowsNeitherRowNorPanel() = runDesktopComposeUiTest(900, 600) {
        setContent { AgentChat(agent()) }
        onAllNodesWithTag(CONTRIBUTION_ROW_TAG).assertCountEquals(0)
        onAllNodesWithTag(AUTOMATION_STATS_TAG).assertCountEquals(0)
    }

    @Test fun theSidebarRowShowsTheBadgeOnlyWhileMarkedAndTheLastWakeReason() = runDesktopComposeUiTest(900, 600) {
        val marked = agent(AutomationView("pong", "m", 1))
        val s = AppState(agents = mapOf("a" to marked)).withTimeline(listOf(stats))
        setContent { AgentRow(marked, s, false, {}, {}) }
        onNodeWithTag(AUTOMATION_BADGE_TAG_PREFIX + "a").assertExists()
        onNodeWithText("woken by owner", substring = true).assertExists()
    }

    @Test fun noBadgeWithoutAMark() = runDesktopComposeUiTest(900, 600) {
        val plain = agent()
        setContent { AgentRow(plain, AppState(agents = mapOf("a" to plain)), false, {}, {}) }
        onAllNodesWithTag(AUTOMATION_BADGE_TAG_PREFIX + "a").assertCountEquals(0)
    }
}
