// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BOARD-COOLDOWN-STUCK-1: under memory pressure the breaker stayed open for hours, because (1) the recovery probe was the
 * first caller's own call (a full board read) cut at the probe limit, and (2) every in-flight call that timed out after the
 * trip pushed the cooldown window out again. The guard now half-opens with a cheap health probe and only a failed probe
 * re-arms the window.
 */
class PluginGuardRecoveryTest {
    @Test
    fun `a call that hangs N times then recovers closes the guard within one cooldown after the first good probe`() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val healthy = AtomicBoolean(false)
        val g = PluginGuard("Board", null, 10_000, { logs += it }, timeoutStrikes = 1, hangCeilingMs = 150, cooldownMs = 200, probeMs = 100,
            healthProbe = { if (!healthy.get()) Thread.sleep(2_000) })
        val hangs = AtomicInteger()
        val board = { if (hangs.getAndIncrement() < 3) Thread.sleep(2_000); "board" }
        assertTrue(g.call("board", 100, board).isFailure)
        assertTrue(g.cooling)
        // two half-opens while still sick: each failed probe re-arms the window
        repeat(2) { Thread.sleep(250); assertTrue(g.call("board", 100, board).isFailure); assertTrue(g.cooling) }
        healthy.set(true)
        val t0 = System.nanoTime()
        var answer: String? = null
        while (answer == null && (System.nanoTime() - t0) / 1_000_000 < 200 + 150) {
            answer = g.call("board", 1_000) { "board" }.getOrNull()
            if (answer == null) Thread.sleep(20)
        }
        assertEquals("board", answer, "guard must close within one cooldown after health returns")
        assertFalse(g.cooling)
        assertTrue(logs.any { "closed -> open" in it }, "$logs")
        assertTrue(logs.any { "open -> half-open" in it }, "$logs")
        assertTrue(logs.any { "half-open -> open (strike 2" in it }, "$logs")
        assertTrue(logs.any { "half-open -> closed" in it }, "$logs")
        g.shutdown()
    }

    @Test
    fun `the caller's heavy call is not the probe and is not cut at the probe limit`() {
        var recovered = 0
        val g = PluginGuard("Board", null, 10_000, {}, timeoutStrikes = 1, hangCeilingMs = 3_000, cooldownMs = 100, probeMs = 100, onRecovered = { recovered++ })
        assertTrue(g.call("hang", 50) { Thread.sleep(10_000) }.isFailure)
        Thread.sleep(150)
        // slower than the probe limit, faster than the hang ceiling: the old probe cut this off and re-opened forever
        assertEquals(7, g.call("board", 50) { Thread.sleep(400); 7 }.getOrNull())
        assertFalse(g.cooling)
        assertEquals(1, recovered)
        g.shutdown()
    }

    @Test
    fun `late timeouts of calls already in flight do not extend the cooldown window`() {
        val g = PluginGuard("Board", null, 10_000, {}, timeoutStrikes = 1, hangCeilingMs = 200, cooldownMs = 400, probeMs = 100)
        val ex = Executors.newFixedThreadPool(4)
        // four hung calls start together; the first timeout trips, the other three end inside the window
        val started = System.nanoTime()
        repeat(4) { i -> ex.submit { Thread.sleep(i * 100L); g.call("hung$i", 50) { Thread.sleep(5_000) } } }
        ex.shutdown(); ex.awaitTermination(5, TimeUnit.SECONDS)
        assertTrue(g.cooling)
        // the window ends ~250 + 400 ms after start; the last late timeout (~550 ms) must not have pushed it to ~950 ms
        val waitMs = 800 - (System.nanoTime() - started) / 1_000_000
        if (waitMs > 0) Thread.sleep(waitMs)
        assertEquals(1, g.call("probe", 1_000) { 1 }.getOrNull())
        assertFalse(g.cooling)
        g.shutdown()
    }

    @Test
    fun `a refused call says since when and which strike`() {
        val g = PluginGuard("Board", null, 10_000, {}, timeoutStrikes = 1, hangCeilingMs = 50, cooldownMs = 10_000)
        g.call("hang", 50) { Thread.sleep(2_000) }
        val msg = g.call("ticket_comments", 1_000) { 1 }.exceptionOrNull()!!.message!!
        assertTrue(Regex("""cooling down since \d\d:\d\d \(strike 1\), retry in \d+s; ticket_comments refused""").containsMatchIn(msg), msg)
        g.shutdown()
    }
}
