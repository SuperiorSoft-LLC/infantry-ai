// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.TranscriptEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CHAT-SCROLL-1: a long chat (the seeded 3200-row [LongChatFixture]) opens on its newest page only, loads older pages on
 * scroll-up, keeps a scrolled-up view still while new rows arrive (with an uncapped "Latest · N new" button), always shows the
 * owner's own new message, and searches the whole transcript, loading a hit's page.
 */
@OptIn(ExperimentalTestApi::class)
class LongChatScrollUiTest {
    private val turns = 800 // 5 rows a turn: 4000 rows

    private fun SemanticsNodeInteraction.rowCount(): Int =
        fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount

    private fun logEntry(seq: Long, text: String) = TranscriptEntry(seq, LineKind.LOG, text, 1_900_000_000_000L + seq)

    @Test
    fun opensOnTheNewestPageAndLoadsOlderOnScrollUp() {
        val agent = LongChatFixture.agent(turns)
        runDesktopComposeUiTest(900, 700) {
            setContent { AgentChat(agent) }
            waitForIdle()
            val list = onNodeWithTag(CHAT_TRANSCRIPT_TAG)
            assertEquals(CHAT_PAGE_SIZE_DEFAULT, list.rowCount(), "only the newest page is in the list on open")
            onNodeWithText("answer 799", substring = true).assertIsDisplayed()

            // Scroll to the top of the page: the next older page joins it and the row read stays on screen.
            list.performScrollToIndex(0)
            waitForIdle()
            assertEquals(CHAT_PAGE_SIZE_DEFAULT * 2, list.rowCount(), "scrolling up loads one older page")
            onNodeWithText("owner message 784", substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun scrolledUpViewIsNotMovedByNewItemsAndCountsThem() {
        val agent = mutableStateOf(LongChatFixture.agent(turns))
        runDesktopComposeUiTest(900, 700) {
            setContent { AgentChat(agent.value) }
            waitForIdle()
            onNodeWithTag(CHAT_TRANSCRIPT_TAG).performScrollToIndex(20) // row 3940: turn 788's owner message
            waitForIdle()
            onNodeWithText("owner message 788", substring = true).assertIsDisplayed()
            var seq = 100_000L
            repeat(7) { i ->
                agent.value = agent.value.copy(entries = agent.value.entries + logEntry(seq++, "live line $i"))
                waitForIdle()
            }
            onNodeWithText("owner message 788", substring = true).assertIsDisplayed()
            onNodeWithText("Latest · 7 new").assertIsDisplayed()

            onNodeWithTag(JUMP_LATEST_TAG).performClick()
            waitForIdle()
            onNodeWithText("live line 6").assertIsDisplayed()
            onNodeWithTag(JUMP_LATEST_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun theOwnersSentMessageIsLastAndInViewEvenWhenScrolledUp() {
        val agent = mutableStateOf(LongChatFixture.agent(turns))
        runDesktopComposeUiTest(900, 700) {
            setContent { AgentChat(agent.value) }
            waitForIdle()
            onNodeWithTag(CHAT_TRANSCRIPT_TAG).performScrollToIndex(5)
            waitForIdle()
            onNodeWithTag(JUMP_LATEST_TAG).assertExists()
            agent.value = agent.value.copy(entries = agent.value.entries + TranscriptEntry(null, LineKind.USER, "my brand new message", 2_000_000_000_000L))
            waitForIdle()
            onNodeWithText("my brand new message").assertIsDisplayed()
            onNodeWithTag(JUMP_LATEST_TAG).assertDoesNotExist()
            val list = onNodeWithTag(CHAT_TRANSCRIPT_TAG)
            assertTrue(list.rowCount() >= 1)
        }
    }

    @Test
    fun searchJumpsToAHitOutsideTheLoadedPage() {
        val agent = LongChatFixture.agent(turns)
        runDesktopComposeUiTest(900, 700) {
            setContent { AgentChat(agent) }
            waitForIdle()
            onNodeWithTag(CHAT_SEARCH_CHIP_TAG).performClick()
            waitForIdle()
            // Turn 0's answer names ticket-00001000: 3200 rows up, far outside the newest page.
            onNodeWithTag(CHAT_SEARCH_FIELD_TAG).performTextInput("ticket-00001000")
            waitForIdle()
            onNodeWithText("1 of 1").assertIsDisplayed()
            onNodeWithText("answer 0:", substring = true).assertIsDisplayed()
            onNodeWithTag(CHAT_SEARCH_HIT_TAG, useUnmergedTree = true).assertIsDisplayed()
        }
    }
}
