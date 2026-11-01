package com.superiorsoftllc.agenthq.daemon.plugins

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
    fun `a success resets the strikes and repeated timeouts disable the plugin once with a notice`() {
        val told = mutableListOf<String>()
        val g = PluginGuard("Slow", null, 10_000, {}, onDisabled = { told += it }, timeoutStrikes = 2, hangCeilingMs = 50)
        g.call("slow", 50) { Thread.sleep(2_000) }
        assertEquals(1, g.call("ok", 2_000) { 1 }.getOrNull())
        g.call("slow", 50) { Thread.sleep(2_000) }
        assertFalse(g.disabled, "strikes were reset by the success")
        g.call("slow", 50) { Thread.sleep(2_000) }
        assertTrue(g.disabled)
        assertEquals(1, told.size)
        assertTrue(g.call("after", 2_000) { 1 }.isFailure)
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
        assertTrue(g.disabled)
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
