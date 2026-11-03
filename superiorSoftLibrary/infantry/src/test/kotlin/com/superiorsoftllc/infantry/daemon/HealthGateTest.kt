// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Ticket 55d60a5e: `/health` must answer while the database is stuck, and a daemon whose `/health` keeps failing must
 * halt so Docker's restart policy restarts it.
 */
class HealthGateTest {

    @Test(timeout = 20_000)
    fun aReadThatAnswersInTimeIsOk() {
        HealthGate(timeoutMs = 2_000).use { gate ->
            assertEquals(HealthGate.Outcome.Ok(42), gate.read { 42 })
        }
    }

    @Test(timeout = 20_000)
    fun aStuckReadIsReportedWithinTheBudgetAndLaterCallersDoNotQueueAnotherRead() {
        val release = CountDownLatch(1)
        val started = AtomicInteger()
        HealthGate(timeoutMs = 300).use { gate ->
            val stuck: () -> Int = { started.incrementAndGet(); release.await(); 1 }
            val t0 = System.nanoTime()
            assertIs<HealthGate.Outcome.Stuck>(gate.read(stuck))
            val firstMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
            assertTrue(firstMs < 2_000, "the first caller waited $firstMs ms, more than its budget allows")
            // Past the budget: answered at once, and no second read was started behind the stuck one.
            val t1 = System.nanoTime()
            val again = gate.read(stuck)
            assertIs<HealthGate.Outcome.Stuck>(again)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1) < 200, "a later caller waited on the stuck read")
            assertEquals(1, started.get())
            // Once the database answers again, the next read is fresh.
            release.countDown()
            Thread.sleep(100)
            assertEquals(HealthGate.Outcome.Ok(7), gate.read { 7 })
        }
    }

    @Test(timeout = 20_000)
    fun aReadThatThrowsIsReportedAsFailed() {
        HealthGate(timeoutMs = 2_000).use { gate ->
            val outcome = gate.read<Int> { throw IllegalStateException("database is locked") }
            assertEquals(HealthGate.Outcome.Failed("database is locked"), outcome)
        }
    }

    @Test
    fun theWatchdogHaltsOnlyAfterTheConfiguredFailuresInARow() {
        val answers = ArrayDeque(listOf(503, -1, 200, 503, 503, 503))
        var tripped: String? = null
        val dog = HealthWatchdog(
            URI("http://127.0.0.1:1/health"), intervalMs = 1_000, timeoutMs = 1_000, failures = 3,
            check = { _, _ -> answers.removeFirst().also { if (it < 0) throw java.net.ConnectException("refused") } },
            onTrip = { tripped = it },
        )
        dog.use {
            repeat(2) { dog.tick() }
            assertEquals(2, dog.consecutiveFailures)
            dog.tick() // 200 resets the count
            assertEquals(0, dog.consecutiveFailures)
            repeat(2) { dog.tick() }
            assertEquals(null, tripped, "halted before three failures in a row")
            dog.tick()
            assertTrue(tripped!!.contains("3 checks in a row"), tripped)
        }
    }

    @Test
    fun theWatchdogAsksLoopbackWhenBoundToEveryInterface() {
        assertEquals(URI("http://127.0.0.1:8787/health"), HealthWatchdog.selfUrl("0.0.0.0", 8787))
        assertEquals(URI("http://10.0.0.5:9000/health"), HealthWatchdog.selfUrl("10.0.0.5", 9000))
        assertEquals(URI("http://[::1]:9000/health"), HealthWatchdog.selfUrl("::1", 9000))
    }
}
