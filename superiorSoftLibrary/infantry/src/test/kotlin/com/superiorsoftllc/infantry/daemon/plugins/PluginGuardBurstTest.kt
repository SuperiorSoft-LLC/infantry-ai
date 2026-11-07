// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BOARD-BREAKER-1: the owner's board opened ~150 cards, each card read its ticket at once, and the calls queued behind the
 * board plugin's pool. The guard counted the queue wait as the call's own time, so three fast reads "timed out" and the breaker
 * tripped (board and hooks refused for minutes). Time in the queue is the plugin being busy, never a strike.
 */
class PluginGuardBurstTest {
    @Test
    fun `a burst larger than the pool finishes and never trips the breaker`() = runBlocking {
        val tripped = mutableListOf<String>()
        // Pool 2, ceiling 1.5 s, one strike trips: 8 calls of 400 ms; the last two wait 1.2 s in the queue (under the ceiling) and
        // finish 1.6 s after they were submitted (over it). Before the fix the queue wait counted and they "timed out"; now each
        // is timed from its own start (400 ms).
        val g = PluginGuard("Board", null, 10_000, {}, timeoutStrikes = 1, hangCeilingMs = 1_500, poolSize = 2, cooldownMs = 30_000,
            onTripped = { tripped += it })
        val started = System.nanoTime()
        val results = (1..8).map { i -> async { g.callSuspending("get $i", 500) { Thread.sleep(400); i } } }.awaitAll()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(results.all { it.isSuccess }, "every call of the burst answers: ${results.mapNotNull { it.exceptionOrNull()?.message }}")
        assertEquals((1..8).toList(), results.map { it.getOrThrow() })
        assertFalse(g.cooling, "a burst is not a hang: the breaker stays closed")
        assertTrue(tripped.isEmpty())
        assertTrue(tookMs < 10_000, "the burst drains in bounded time ($tookMs ms)")
        g.shutdown()
    }

    @Test
    fun `a call still queued at the ceiling fails alone as busy with no strike`() = runBlocking {
        val tripped = mutableListOf<String>()
        val g = PluginGuard("Board", null, 10_000, {}, timeoutStrikes = 1, hangCeilingMs = 400, poolSize = 1, cooldownMs = 30_000,
            onTripped = { tripped += it })
        // The single worker is held 300 ms by each call; the 3rd waits 600 ms > 400 ms and is refused as busy, not struck.
        val results = (1..3).map { i -> async { g.callSuspending("get $i", 200) { Thread.sleep(300); i } } }.awaitAll()
        val failed = results.mapNotNull { it.exceptionOrNull()?.message }
        assertTrue(failed.isNotEmpty() && failed.all { "busy" in it }, "queued too long = busy: $failed")
        assertFalse(g.cooling)
        assertTrue(tripped.isEmpty())
        assertEquals(9, g.call("next", 2_000) { 9 }.getOrNull())
        g.shutdown()
    }

    @Test
    fun `a call that really hangs after it started still strikes`() {
        val tripped = mutableListOf<String>()
        val g = PluginGuard("Board", null, 10_000, {}, timeoutStrikes = 1, hangCeilingMs = 200, poolSize = 1, cooldownMs = 30_000,
            onTripped = { tripped += it })
        assertTrue(g.call("hang", 100) { Thread.sleep(5_000) }.isFailure)
        assertTrue(g.cooling)
        assertEquals(1, tripped.size)
        g.shutdown()
    }
}
