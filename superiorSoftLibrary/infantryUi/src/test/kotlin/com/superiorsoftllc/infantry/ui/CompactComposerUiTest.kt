// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.superiorsoftllc.infantry.ui.model.AgentActivity
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * CHAT-COMPACT-1: the composer is one compact row. Its default height is at most [MAX_DEFAULT_DP]: a 56 dp input
 * (Material's one-line field) plus the 10 dp padding on both sides is 76 dp, and 88 dp leaves a little room for font
 * scaling without letting a second row (another 40+ dp) pass. On a phone width the secondary actions sit in "...";
 * the Activity header is a chip over the transcript, not a row.
 */
@OptIn(ExperimentalTestApi::class)
class CompactComposerUiTest {
    private val MAX_DEFAULT_DP = 88

    @BeforeTest
    fun fresh() = resetActivityBoxChoice()

    private fun agent() = AgentView(
        session = SessionRecord(id = "a", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "p"),
        activityLog = listOf(AgentActivity("bash", "gradle test", 1L), AgentActivity("read", "Model.kt", 2L)),
    )

    private fun withComposer(widthDp: Int, a: AgentView?, check: androidx.compose.ui.test.ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runDesktopComposeUiTest(widthDp * 2, 600) {
                val controller = Controller(scope)
                setContent {
                    androidx.compose.foundation.layout.Box(Modifier.width(widthDp.dp)) {
                        PromptBox("", {}, {}, FocusRequester(), false, a, controller, {})
                    }
                }
                check()
            }
        } finally { scope.cancel() }
    }

    @Test
    fun theDefaultComposerIsOneRowAtDesktopAndPhoneWidth() {
        for (w in listOf(900, 360)) withComposer(w, agent()) {
            val h = onNodeWithTag(COMPOSER_TAG).getUnclippedBoundsInRoot().height
            kotlin.test.assertTrue(h <= MAX_DEFAULT_DP.dp, "composer at ${w}dp is $h")
        }
    }

    @Test
    fun theSecondaryActionsMoveIntoTheOverflowMenuOnAPhone() {
        withComposer(360, agent()) {
            onNodeWithTag(COMPOSER_MORE_TAG).assertExists()
            onNodeWithText(SWITCH_AGENT_LABEL).assertDoesNotExist()
            onNodeWithTag(COMPOSER_MORE_TAG).performClick()
            onNodeWithText(SWITCH_AGENT_LABEL).assertExists()
        }
        withComposer(900, agent()) {
            onNodeWithTag(COMPOSER_MORE_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun theActivityChipTakesNoRowOfItsOwn() {
        runDesktopComposeUiTest(360, 600) {
            mainClock.autoAdvance = false
            setContent { AgentChat(agent(), nowProvider = { 1L }) }
            mainClock.advanceTimeByFrame()
            onNodeWithText("Activity (2)").assertExists()
            onNodeWithText("read: Model.kt").assertDoesNotExist()
            onNodeWithText("Activity (2)").performClick()
            mainClock.advanceTimeByFrame()
            onNodeWithText("read: Model.kt").assertExists()
        }
    }
}
