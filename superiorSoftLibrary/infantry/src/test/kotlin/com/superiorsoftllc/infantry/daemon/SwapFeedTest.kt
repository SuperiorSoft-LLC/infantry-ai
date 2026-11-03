package com.superiorsoftllc.infantry.daemon

import java.lang.management.ManagementFactory
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /**
     * DEADLOCK-1 (ticket f3389c81), the live freeze: a board hand-out emits a session event while it holds the board lock, and
     * an event thread's delivery takes the board lock. Delivering under the feed's own lock made those two locks a cycle.
     */
    @Test fun `the target is called with the feed lock free so a target lock never cycles with it`() {
        val board = Any()
        val feed = SwapFeed<Int>()
        feed.attach { synchronized(board) { } }
        val pool = Executors.newFixedThreadPool(2) { r -> Thread(r).apply { isDaemon = true } }
        try {
            repeat(ROUNDS) { round ->
                val barrier = CyclicBarrier(2)
                val handOut = pool.submit { barrier.await(); synchronized(board) { Thread.yield(); feed.emit(round) } }
                val eventThread = pool.submit { barrier.await(); feed.emit(-round) }
                val done = runCatching { handOut.get(LIMIT_SECONDS, TimeUnit.SECONDS); eventThread.get(LIMIT_SECONDS, TimeUnit.SECONDS) }
                val deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads()
                assertNull(deadlocked, "round $round: deadlocked threads ${deadlocked?.size}")
                assertTrue(done.isSuccess, "round $round did not finish: ${done.exceptionOrNull()}")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `events emitted while an attach drains queue behind the drain`() {
        val feed = SwapFeed<Int>()
        feed.emit(1); feed.emit(2)
        val got = mutableListOf<Int>()
        // The target emits while it is handed a queued event (an event that triggers another, on the same thread).
        feed.attach { e -> got += e; if (e == 1) feed.emit(10) }
        feed.emit(3)
        assertEquals(listOf(1, 2, 10, 3), got)
    }

    private companion object {
        const val ROUNDS = 500
        const val LIMIT_SECONDS = 10L
    }
}
