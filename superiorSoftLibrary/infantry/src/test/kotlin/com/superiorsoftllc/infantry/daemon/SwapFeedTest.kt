package com.superiorsoftllc.infantry.daemon

import kotlin.test.Test
import kotlin.test.assertEquals

class SwapFeedTest {
    @Test fun `events before the first attach are queued and delivered in order before live ones`() {
        val feed = SwapFeed<Int>()
        feed.emit(1); feed.emit(2)
        val got = mutableListOf<Int>()
        feed.attach { got += it }
        feed.emit(3)
        assertEquals(listOf(1, 2, 3), got)
        assertEquals(0, feed.pending)
    }

    @Test fun `events during a reload swap are queued for the new board not lost`() {
        val feed = SwapFeed<Int>()
        val old = mutableListOf<Int>()
        feed.attach { old += it }
        feed.emit(1)
        feed.detach()
        feed.emit(2); feed.emit(3)
        val new = mutableListOf<Int>()
        feed.attach { new += it }
        feed.emit(4)
        assertEquals(listOf(1), old)
        assertEquals(listOf(2, 3, 4), new)
    }

    @Test fun `a full queue drops the oldest and counts them`() {
        val feed = SwapFeed<Int>(limit = 2)
        feed.emit(1); feed.emit(2); feed.emit(3)
        val got = mutableListOf<Int>()
        feed.attach { got += it }
        assertEquals(listOf(2, 3), got)
        assertEquals(1, feed.dropped)
    }

    @Test fun `a throwing target does not stop the queue drain`() {
        val feed = SwapFeed<Int>()
        feed.emit(1); feed.emit(2)
        val got = mutableListOf<Int>()
        feed.attach { if (it == 1) error("boom"); got += it }
        assertEquals(listOf(2), got)
    }
}
