// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.OwnedPluginScope
import com.superiorsoftllc.infantry.plugin.api.PluginEvent
import com.superiorsoftllc.infantry.plugin.api.every
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PLUGIN-HOTSWAP-1: the plugin lifetime ([OwnedPluginScope]), per-owner observers ([SimpleEventBus.dropOwner]) and [LeakWatch]. */
class PluginLifetimeTest {
    @Test
    fun `closing a scope cancels its coroutines and runs its cleanups newest first, once`() = runBlocking {
        val scope = OwnedPluginScope("Acme_Test")
        val order = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        val job = scope.coroutines.launch { started.complete(Unit); awaitCancellation() }
        val timer = scope.every(10) { }
        scope.own(AutoCloseable { order += "socket" })
        scope.onUnload { order += "cache" }
        scope.onUnload { error("a failing cleanup") }
        withTimeout(5_000) { started.await() }
        assertTrue(scope.active)

        val failures = scope.close()
        assertEquals(1, failures.size, "the failing cleanup is reported, not thrown: $failures")
        assertEquals(listOf("cache", "socket"), order, "newest first; one failure does not stop the others")
        withTimeout(5_000) { job.join(); timer.join() }
        assertTrue(job.isCancelled && timer.isCancelled)
        assertFalse(scope.active)
        assertEquals(emptyList(), scope.close(), "a second close does nothing")
        assertEquals(listOf("cache", "socket"), order)

        scope.onUnload { order += "late" }
        assertEquals("late", order.last(), "a cleanup added after unload runs at once")
    }

    @Test
    fun `the bus binds one observer object once and drops one owner's observers only`() {
        val bus = SimpleEventBus()
        var a = 0
        var b = 0
        val obsA: (PluginEvent) -> Unit = { a++ }
        bus.subscribe("Acme_A", "x", obsA)
        bus.subscribe("Acme_A", "x", obsA) // a new TabFactory binds the modules that stayed again
        bus.subscribe("Acme_B", "x") { b++ }
        bus.publish(PluginEvent("x", emptyMap()))
        assertEquals(1, a, "the same observer on the same event is bound once")
        assertEquals(1, b)
        assertEquals(1, bus.dropOwner("Acme_A"))
        bus.publish(PluginEvent("x", emptyMap()))
        assertEquals(1, a, "the dropped module no longer hears events")
        assertEquals(2, b, "the other module keeps its observer")
    }

    @Test
    fun `LeakWatch tells a collected referent from one still held`() {
        var held: Any? = Any()
        val strong = WeakReference(held)
        assertFalse(LeakWatch.collected(strong, timeoutMs = 200), "a strongly held object is reported")
        held = null
        assertTrue(LeakWatch.collected(strong, timeoutMs = 5_000), "a released object is collected within the timeout")
    }
}
