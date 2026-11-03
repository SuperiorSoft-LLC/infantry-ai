// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.SessionPaneAction
import com.superiorsoftllc.infantry.ui.model.StartPlan
import com.superiorsoftllc.infantry.ui.model.sessionPaneAction
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GAP-006b: the empty chat pane's three states -- Start session for a session that never took its first turn (its own
 * settings on a card, nothing runs before Apply, Cancel abandons), Resume for one with a conversation, and never the
 * generic "Start a session" (the New agent box) for a session on screen.
 */
@OptIn(ExperimentalTestApi::class)
class SessionStartPaneUiTest {

    private val plan = StartPlan(
        sessionId = "s1", ticketId = "ticket-e13c0aa3", ticketTitle = "Fix the thing", role = "backend",
        provider = "claude", model = "claude-sonnet-5-5", branch = "ticket/e13c0aa3-fix", worktree = "/wt/e13c0aa3",
    )

    @Test fun startShowsTheSettingsAndRunsNothingBeforeApply() = runComposeUiTest {
        val applied = mutableListOf<StartPlan>()
        setContent { SessionStartPane(SessionPaneAction.Start(plan), onApply = { applied += it }, onResume = {}) }

        onNodeWithTag(START_SESSION_TAG).performClick()
        onNodeWithTag(START_CARD_TAG).assertIsDisplayed()
        for (shown in listOf("backend", "claude", "claude-sonnet-5-5", "ticket/e13c0aa3-fix", "/wt/e13c0aa3")) onNodeWithText(shown).assertIsDisplayed()
        assertTrue(applied.isEmpty(), "opening the card starts nothing")

        onNodeWithTag(START_CANCEL_TAG).performClick()
        onNodeWithTag(START_SESSION_TAG).assertIsDisplayed()
        assertTrue(applied.isEmpty(), "Cancel abandons the start")

        onNodeWithTag(START_SESSION_TAG).performClick()
        onNodeWithTag(START_APPLY_TAG).performClick()
        assertEquals(listOf(plan), applied, "Apply starts exactly what the card showed")
    }

    @Test fun resumeReattachesTheSession() = runComposeUiTest {
        val resumed = mutableListOf<String>()
        setContent { SessionStartPane(SessionPaneAction.Resume("s1"), onApply = {}, onResume = { resumed += it }) }

        onNodeWithTag(RESUME_SESSION_TAG).performClick()
        assertEquals(listOf("s1"), resumed)
    }

    @Test fun aPhantomOnScreenOffersStartSessionNotTheNewAgentBox() = runComposeUiTest {
        val phantom = SessionRecord("s1", "claude", "/repo", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, runCount = 1, role = "backend")
        var newAgentOpened = false
        setContent {
            OutputPane(
                AgentView(phantom), spawnMode = false, onStartSession = { newAgentOpened = true },
                paneAction = sessionPaneAction(phantom, emptyList()),
            )
        }

        onNodeWithTag(START_SESSION_TAG).assertIsDisplayed()
        onNodeWithText("Start a session").assertDoesNotExist()
        onNodeWithTag(START_SESSION_TAG).performClick()
        onNodeWithTag(START_CARD_TAG).assertIsDisplayed()
        assertTrue(!newAgentOpened, "the generic New agent box would cut a second instance")
    }
}
