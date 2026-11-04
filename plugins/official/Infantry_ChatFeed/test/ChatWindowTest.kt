// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chatfeed

import com.superiorsoftllc.infantry.ui.model.ChatItem
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.OutputLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CHAT-SCROLL-1: the chat's paging window: newest page on open, older pages on demand, follow / unread, reveal, search. */
class ChatWindowTest {
    @Test
    fun opensOnTheNewestPageAndLoadsOlderPagesDownToTheFirstRow() {
        val w = ChatWindow(80, 3000)
        assertEquals(2920, w.start)
        assertTrue(w.loadOlder()); assertEquals(2840, w.start)
        repeat(100) { w.loadOlder() }
        assertEquals(0, w.start)
        assertFalse(w.loadOlder())
    }

    @Test
    fun aShortChatShowsEverythingAndATinyPageSizeIsRaised() {
        assertEquals(0, ChatWindow(80, 30).start)
        assertEquals(CHAT_PAGE_SIZE_MIN, ChatWindow(1, 100).pageSize)
    }

    @Test
    fun scrolledUpNewRowsKeepTheSliceAndCountAsUnreadUncapped() {
        val w = ChatWindow(80, 3000)
        w.follow = false
        w.onTotal(3500)
        assertEquals(2920, w.start, "rows on screen never move")
        assertEquals(500, w.unread(3500), "no 200 cap")
        w.jumpToLatest(3500)
        assertTrue(w.follow)
        assertEquals(0, w.unread(3500))
        assertEquals(3420, w.start)
        assertEquals(ChatWindow.JUMP_TO_END, w.pendingJump)
    }

    @Test
    fun followingTrimsTheSliceBackOnceItPassesTwoPages() {
        val w = ChatWindow(80, 1000)
        w.onTotal(1080)
        assertEquals(920, w.start)
        w.onTotal(1161)
        assertEquals(1081, w.start)
    }

    @Test
    fun revealLoadsTheHitsPageAndStopsFollowing() {
        val w = ChatWindow(80, 3000)
        w.reveal(100)
        assertEquals(80, w.start)
        assertFalse(w.follow)
        assertEquals(100, w.pendingJump)
    }

    @Test
    fun aTranscriptThatShrankRestartsOnItsNewestPage() {
        val w = ChatWindow(80, 3000)
        assertEquals(20, w.startFor(100))
        w.onTotal(100)
        assertEquals(20, w.start)
    }

    @Test
    fun searchMatchesTextAndTicketIdsCaseInsensitively() {
        val rows = listOf(
            ChatItem.Line(OutputLine(LineKind.USER, "look at ticket-eae50d7a", 1)),
            ChatItem.Reasoning(listOf(OutputLine(LineKind.REASONING, "Scroll position", 2))),
            ChatItem.Line(OutputLine(LineKind.TEXT, "done", 3)),
        )
        assertEquals(listOf(0), chatSearchHits(rows, "EAE50D7A"))
        assertEquals(listOf(1), chatSearchHits(rows, "scroll"))
        assertEquals(emptyList(), chatSearchHits(rows, "  "))
    }
}
