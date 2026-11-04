// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CHAT-MINIMIZE-1: Minimize removes the composer (not rendered); the floating button brings it back with the same draft.
 * CHAT-MINIMIZE-5: Expand / Minimize / Restore are floaters handed over a slot-args map (as the chat frame hands them to the feed),
 * and a narrow composer moves the tools that do not fit into "More actions" instead of clipping them.
 */
@OptIn(ExperimentalTestApi::class)
class MinimizeComposerUiTest {
    @Test
    fun minimizeRemovesTheComposerAndRestoreKeepsTheDraft() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runDesktopComposeUiTest(900, 600) {
                val controller = Controller(scope)
                val chat = ChatPanelState().also { it.draft = "half-written prompt" }
                setContent {
                    // The slot-args map carries the floaters as an Any?, exactly as ChildSlot does; the feed casts it back.
                    val floaters = FloatingChips {
                        ComposerFloaters(chat.composerMinimized, chat.composerExpanded, { chat.composerExpanded = !chat.composerExpanded },
                            { chat.composerMinimized = true }, { chat.composerMinimized = false })
                    }
                    val args: Map<String, Any?> = mapOf("floaters" to floaters)
                    Box(Modifier.width(450.dp)) {
                        Column {
                            FloatRow((args["floaters"] as? FloatingChips)?.content ?: {})
                            if (!chat.composerMinimized) PromptBox(chat.draft, chat::edit, {}, chat.focus, false, null, controller, {})
                        }
                    }
                }
                onNodeWithTag(PROMPT_BOX_TAG).assertTextContains("half-written prompt")
                onNodeWithTag(COMPOSER_MINIMIZE_TAG).performClick()
                onNodeWithTag(COMPOSER_TAG).assertDoesNotExist()
                onNodeWithTag(PROMPT_BOX_TAG).assertDoesNotExist()
                onNodeWithTag(COMPOSER_EXPAND_TAG).assertDoesNotExist()
                onNodeWithTag(COMPOSER_RESTORE_TAG).performClick()
                onNodeWithTag(PROMPT_BOX_TAG).assertTextContains("half-written prompt")
                onNodeWithTag(COMPOSER_EXPAND_TAG).assertIsDisplayed()
            }
        } finally { scope.cancel() }
    }

    @Test
    fun aNarrowComposerMovesTheLastToolsIntoMore() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val agent = AgentView(SessionRecord(id = "a", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.BUSY, providerSessionId = "p"))
        try {
            runDesktopComposeUiTest(900, 600) {
                val controller = Controller(scope)
                val chat = ChatPanelState()
                setContent { Box(Modifier.width(350.dp)) { PromptBox(chat.draft, chat::edit, {}, chat.focus, false, agent, controller, {}) } }
                onNodeWithTag(COMPOSER_MORE_TAG).assertIsDisplayed()
                onNodeWithTag(CHAT_IMAGE_ATTACH_TAG).assertIsDisplayed()
            }
        } finally { scope.cancel() }
    }

    @Test
    fun theSplitKeepsTheFirstToolsAndGivesTheLastSlotToMore() {
        assertEquals(6 to 0, splitComposerTools(6, 6))
        assertEquals(6 to 0, splitComposerTools(6, 9))
        assertEquals(3 to 3, splitComposerTools(6, 4))
        assertEquals(0 to 6, splitComposerTools(6, 1))
        assertEquals(0 to 6, splitComposerTools(6, 0))
        // 350dp of chat, two send buttons: (350 - 52 - 24 - 96) / 48 = 3 slots.
        assertEquals(3, composerToolsThatFit(350, 2))
    }
}
