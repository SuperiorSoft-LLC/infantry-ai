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
        val g = PluginGuard("Slow", null, 10_000, {}, onDisabled = { told += it })
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
        val g = PluginGuard("Slow", null, 10_000, {}, onDisabled = { told += it }, timeoutStrikes = 2)
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
}
