// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.superiorsoftllc.infantry.ui.model.AgentActivity
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals

/** CHAT-ONE-1: the Assistant (a global session) goes through the same feed rules as every chat. */
@OptIn(ExperimentalTestApi::class)
class AssistantSharedFeedUiTest {
    private fun assistant(state: SessionState, pid: String?) = AgentView(
        session = SessionRecord(id = "asst", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = state, providerSessionId = pid, global = true),
        activityLog = listOf(AgentActivity("bash", "ls", 1L)),
    )

    @Test fun endSessionOnTheAssistantShowsTheResumeSplash() = runComposeUiTest {
        val a = assistant(SessionState.EXITED, "p")
        val resumed = mutableListOf<String>()
        setContent { OutputPane(a, spawnMode = false, onStartSession = null, paneAction = feedPaneAction(a, emptyList()), onResume = { resumed += it }) }
        onNodeWithTag(RESUME_SESSION_TAG).performClick()
        assertEquals(listOf("asst"), resumed)
    }

    @Test fun aFreshAssistantNeverOffersStart() {
        assertEquals(null, feedPaneAction(assistant(SessionState.IDLE, null), emptyList()))
    }

    @Test fun theAssistantShowsTheActivityBoxCollapsed() = runComposeUiTest {
        resetActivityBoxChoice()
        setContent { AgentChat(assistant(SessionState.BUSY, "p"), nowProvider = { 1L }) }
        onNodeWithTag(ACTIVITY_BOX_TAG).assertExists()
        onNodeWithText("bash: ls").assertDoesNotExist()
    }
}
