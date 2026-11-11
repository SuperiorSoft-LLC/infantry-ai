// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BOARD-DOWN-1: one timed-out call fails only itself; the plugin keeps answering until it times out repeatedly. */
class PluginGuardTimeoutTest {
    @Test
    fun `one timeout fails only that call and the plugin answers the next`() {
        val told = mutableListOf<String>()
        val g = PluginGuard("Slow", null, 10_000, {}, onDisabled = { told += it }, hangCeilingMs = 50)
        assertTrue(g.call("slow", 50) { Thread.sleep(2_000) }.isFailure)
        assertFalse(g.disabled)
        assertNull(g.disabledReason)
        assertEquals(7, g.call("next", 2_000) { 7 }.getOrNull())
        assertTrue(told.isEmpty())
        g.shutdown()
    }

    @Test
    fun `repeated timeouts open the breaker with one notice and a probe after the cooldown closes it with one notice`() {
        val tripped = mutableListOf<String>(); var recovered = 0; var disabledTold = 0
        val g = PluginGuard("Slow", null, 10_000, {}, onDisabled = { disabledTold++ }, timeoutStrikes = 2, hangCeilingMs = 50, cooldownMs = 600,
            onTripped = { tripped += it }, onRecovered = { recovered++ })
        g.call("slow", 50) { Thread.sleep(2_000) }
        assertEquals(1, g.call("ok", 2_000) { 1 }.getOrNull())
        g.call("slow", 50) { Thread.sleep(2_000) }
        assertFalse(g.cooling, "strikes were reset by the success")
        g.call("slow", 50) { Thread.sleep(2_000) }
        assertTrue(g.cooling)
        assertFalse(g.disabled)
        repeat(3) { assertTrue(g.call("refused", 2_000) { 1 }.exceptionOrNull()!!.message!!.contains("cooling down")) }
        assertEquals(1, tripped.size)
        Thread.sleep(700)
        assertEquals(1, g.call("probe", 2_000) { 1 }.getOrNull())
        assertFalse(g.cooling)
        assertEquals(1, recovered)
        assertEquals(0, disabledTold)
        g.shutdown()
    }

    @Test
    fun `a failed probe reopens the breaker without a second notice`() {
        val tripped = mutableListOf<String>()
        val g = PluginGuard("Slow", null, 10_000, {}, timeoutStrikes = 1, hangCeilingMs = 50, cooldownMs = 300, onTripped = { tripped += it })
        g.call("slow", 50) { Thread.sleep(2_000) }
        Thread.sleep(400)
        g.call("probe", 50) { Thread.sleep(2_000) }
        assertTrue(g.cooling)
        assertTrue(g.call("refused", 2_000) { 1 }.exceptionOrNull()!!.message!!.contains("cooling down"))
        assertEquals(1, tripped.size)
        g.shutdown()
    }

    @Test
    fun `a starved call slower than the mark but under the ceiling is no strike`() {
        val logs = mutableListOf<String>()
        val g = PluginGuard("Busy", null, 10_000, { logs += it }, timeoutStrikes = 1, hangCeilingMs = 5_000)
        repeat(3) { assertEquals(5, g.call("starved", 100) { Thread.sleep(400); 5 }.getOrNull()) }
        assertFalse(g.disabled)
        assertTrue(logs.any { it.contains("is slow") })
        g.shutdown()
    }

    @Test
    fun `a call blocked past the ceiling is one strike`() {
        val g = PluginGuard("Hung", null, 10_000, {}, timeoutStrikes = 2, hangCeilingMs = 300)
        assertTrue(g.call("hung", 100) { Thread.sleep(5_000) }.isFailure)
        assertFalse(g.disabled)
        assertTrue(g.call("hung", 100) { Thread.sleep(5_000) }.isFailure)
        assertTrue(g.cooling)
        g.shutdown()
    }

    @Test
    fun `a spinning call is disabled at once`() {
        val g = PluginGuard("Spin", null, 200, {}, timeoutStrikes = 3, hangCeilingMs = 30_000)
        val r = g.call("spin", 100) { var x = 0L; while (!Thread.currentThread().isInterrupted) x++; x }
        assertTrue(r.isFailure)
        assertTrue(g.disabled)
        assertTrue(g.disabledReason!!.contains("spinning"))
        g.shutdown()
    }
}

/** BOARD-HANG-1: a hung recovery probe is cut at the probe limit (not the hang ceiling) and the plugin then recovers. */
class PluginGuardProbeHangTest {
    @Test
    fun `a hung probe is cut short and the next probe recovers the plugin`() {
        var recovered = 0
        val g = PluginGuard("Slow", null, 10_000, {}, timeoutStrikes = 1, hangCeilingMs = 1_500, cooldownMs = 200, probeMs = 100, onRecovered = { recovered++ })
        assertTrue(g.call("hang", 50) { Thread.sleep(5_000) }.isFailure)
        assertTrue(g.cooling)
        Thread.sleep(300)
        val t0 = System.nanoTime()
        assertTrue(g.call("probe", 50) { Thread.sleep(5_000) }.isFailure)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 1_000, "probe must be cut at the probe limit, took $ms ms")
        assertTrue(g.cooling)
        Thread.sleep(300)
        assertEquals(1, g.call("probe2", 2_000) { 1 }.getOrNull())
        assertFalse(g.cooling)
        assertEquals(1, recovered)
        g.shutdown()
    }
}
