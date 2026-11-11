// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.TranscriptEntry
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.Test

/** SESSION-FAIL-VISIBLE-1: a failed start or turn keeps the session on screen and shows the exact error first. */
@OptIn(ExperimentalTestApi::class)
class SessionFailVisibleUiTest {
    private val why = "the claude process did not start: Claude Code is not signed in. Run `claude` once in a terminal and sign in, then restart this agent."

    private fun failed(psid: String?, lines: List<TranscriptEntry> = emptyList()) = AgentView(
        session = SessionRecord(
            id = "s1", provider = "claude", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1,
            state = SessionState.FAILED, providerSessionId = psid, lastError = why,
        ),
        entries = lines,
    )

    @Test fun aSessionThatFailedAfterATurnShowsTheErrorNotTheEmptySplash() = runComposeUiTest {
        val a = failed("p", listOf(TranscriptEntry(1, LineKind.USER, "hello", 1)))
        setContent { OutputPane(a, spawnMode = false, onStartSession = null, paneAction = feedPaneAction(a, emptyList())) }
        onNodeWithText(why, substring = true).assertExists()
        onNodeWithText("hello", substring = true).assertExists()
    }

    @Test fun aSessionThatNeverStartedShowsTheError() = runComposeUiTest {
        val a = failed(null)
        setContent { OutputPane(a, spawnMode = false, onStartSession = null, paneAction = feedPaneAction(a, emptyList())) }
        onNodeWithText(why, substring = true).assertExists()
    }

    @Test fun retryOnTheCardRunsInPlace() = runComposeUiTest {
        val resumed = mutableListOf<String>()
        var restarted = 0
        val withConversation = failed("p")
        val plain = failed(null)
        setContent {
            androidx.compose.foundation.layout.Column {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) {
                    OutputPane(withConversation, spawnMode = false, onStartSession = null,
                        onRetry = failedSessionRetry(withConversation, feedPaneAction(withConversation, emptyList()), { resumed += it }, {}) { restarted++ })
                }
            }
        }
        onNodeWithTag(SESSION_RETRY_TAG).performClick()
        assertEquals(listOf("s1"), resumed)
        failedSessionRetry(plain, feedPaneAction(plain, emptyList()), { resumed += it }, {}) { restarted++ }!!.invoke()
        assertEquals(1, restarted, "a plain session that never ran restarts in place")
        assertNull(failedSessionRetry(failed("p").let { it.copy(session = it.session.copy(state = SessionState.IDLE)) }, null, {}, {}) {})
    }
}
