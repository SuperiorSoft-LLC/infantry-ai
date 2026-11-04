// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.AgentActivity
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import androidx.compose.runtime.CompositionLocalProvider
import com.superiorsoftllc.infantry.ui.shell.ConfigContext
import com.superiorsoftllc.infantry.ui.shell.ConfigValues
import com.superiorsoftllc.infantry.ui.shell.EmptyConfig
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import com.superiorsoftllc.infantry.ui.shell.ShellContext
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * UI-ACT-2: the chat pane shows a collapsible Activity box above the chat input, reading the session's
 * [AgentView.activityLog] (UI-ACT-1), newest first.
 *
 * ACTIVITY-BOX-1: the box now starts COLLAPSED, running or idle (the owner found it popping open annoying), so the
 * tests that read entries open it with a header click first. The setting `activityBox.autoOpenWhileRunning` brings
 * back the old open-while-running behaviour; a header click is remembered across sessions and turns.
 */
@OptIn(ExperimentalTestApi::class)
class ActivityBoxUiTest {
    private val t0 = 3_723_000L

    @BeforeTest
    fun fresh() = resetActivityBoxChoice()

    /** A shell context whose only live part is the config (`activityBox.autoOpenWhileRunning`); the box reads nothing else. */
    private fun shellWith(autoOpen: Boolean): ShellContext {
        val values = object : ConfigValues by EmptyConfig {
            override fun boolean(key: String): Boolean? = if (key == "activityBox.autoOpenWhileRunning") autoOpen else null
        }
        val cfg = object : ConfigContext by EmptyConfig {
            override fun values(nodeId: String): ConfigValues = values
        }
        return java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ShellContext::class.java)) { _, m, _ ->
            if (m.name == "getConfig") cfg else null
        } as ShellContext
    }

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
            onNodeWithContentDescription(ACTIVITY_LABEL).performClick()
            mainClock.advanceTimeByFrame()
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

            onNodeWithText("read: Model.kt").assertDoesNotExist()
            onNodeWithContentDescription(ACTIVITY_LABEL).performClick()
            mainClock.advanceTimeByFrame()
            onNodeWithText("read: Model.kt").assertExists()
            onNodeWithContentDescription(ACTIVITY_LABEL).performClick()
            mainClock.advanceTimeByFrame()

            onNodeWithContentDescription(ACTIVITY_LABEL).assertExists()
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

            onNodeWithContentDescription(ACTIVITY_LABEL).assertExists()
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

            onNodeWithContentDescription(ACTIVITY_LABEL).performClick()
            mainClock.advanceTimeByFrame()
            onNodeWithText("read: Model.kt").assertExists()

            current.value = b
            mainClock.advanceTimeByFrame()

            // The open choice is remembered across the session switch.
            onNodeWithContentDescription(ACTIVITY_LABEL).assertExists()
            onNodeWithText("grep: TicketBoard").assertExists()
            onNodeWithText("read: Model.kt").assertDoesNotExist()
        }
    }

    @Test
    fun aRunningSessionStaysCollapsedByDefault() {
        val a = agent("d", SessionState.BUSY, listOf(activity("read", "Model.kt", t0)))
        runDesktopComposeUiTest(900, 600) {
            mainClock.autoAdvance = false
            setContent { AgentChat(a, nowProvider = { t0 }) }
            mainClock.advanceTimeByFrame()
            onNodeWithContentDescription(ACTIVITY_LABEL).assertExists()
            onNodeWithText("read: Model.kt").assertDoesNotExist()
        }
    }

    @Test
    fun theAutoOpenSettingOpensTheBoxWhileRunningButNotWhenIdle() {
        val busy = agent("e", SessionState.BUSY, listOf(activity("read", "Model.kt", t0)))
        val idle = agent("f", SessionState.IDLE, listOf(activity("read", "Model.kt", t0)))
        val current = mutableStateOf(busy)
        runDesktopComposeUiTest(900, 600) {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalShellContext provides shellWith(true)) { AgentChat(current.value, nowProvider = { t0 }) }
            }
            mainClock.advanceTimeByFrame()
            onNodeWithText("read: Model.kt").assertExists()
            current.value = idle
            mainClock.advanceTimeByFrame()
            onNodeWithText("read: Model.kt").assertDoesNotExist()
        }
    }
}
