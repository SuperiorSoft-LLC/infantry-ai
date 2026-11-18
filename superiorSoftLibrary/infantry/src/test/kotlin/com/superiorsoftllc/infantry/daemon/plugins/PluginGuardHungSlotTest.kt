// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * START-HANG-1: a call that hangs where an interrupt cannot reach it (waiting to enter a monitor another thread holds) must not
 * keep its pool slot once it timed out. On the owner's daemon four hung ticket starts plus the hooks' board reads filled the
 * Board pool (8) and every board call was refused as "waited in the queue" for ~40 min.
 */
class PluginGuardHungSlotTest {
    @Test
    fun `a timed-out call stuck on a monitor frees its slot for the next call`() = runBlocking {
        val monitor = Any()
        val holderIn = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread { synchronized(monitor) { holderIn.countDown(); release.await() } }.apply { isDaemon = true; start() }
        assertTrue(holderIn.await(5, TimeUnit.SECONDS))
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val g = PluginGuard("Board", null, 60_000, { logs += it }, hangCeilingMs = 300, poolSize = 1, queueLimit = 10, timeoutStrikes = 100)
        try {
            val hung = g.callSuspending("hung start", 300) { synchronized(monitor) { 1 } }
            assertTrue(hung.isFailure, "the hung call timed out")
            val next = g.callSuspending("board read", 300) { 7 }
            assertEquals(7, next.getOrNull(), "the next call must run, not wait behind the hung one: ${next.exceptionOrNull()?.message}")
        } finally {
            release.countDown()
            holder.join(5_000)
        }
        // Once the hung call finally ends, the pool is back to its own size: the stand-in slot is gone.
        val back = (1..50).any { Thread.sleep(50); g.extraThreads == 0 }
        g.shutdown()
        assertTrue(back, "the stand-in slot is returned once the abandoned call ends")
        assertTrue(logs.any { "abandoned" in it }, "the abandonment is logged: $logs")
    }

    @Test
    fun `stand-ins are capped at the pool size, so a runaway of hung calls cannot spawn threads without end`() = runBlocking {
        val monitor = Any()
        val holderIn = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread { synchronized(monitor) { holderIn.countDown(); release.await() } }.apply { isDaemon = true; start() }
        assertTrue(holderIn.await(5, TimeUnit.SECONDS))
        val g = PluginGuard("Board", null, 60_000, {}, hangCeilingMs = 200, poolSize = 1, queueLimit = 10, timeoutStrikes = 100)
        try {
            repeat(3) { assertTrue(g.callSuspending("hung $it", 200) { synchronized(monitor) { 1 } }.isFailure) }
            assertEquals(1, g.extraThreads, "at most poolSize stand-ins")
        } finally {
            release.countDown()
            holder.join(5_000)
            g.shutdown()
        }
    }
}
