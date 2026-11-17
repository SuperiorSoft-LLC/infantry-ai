// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.triggers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * TRIG-guard behaviour test (not an import guard). Rule: a trigger's hourly ceiling counts starts; past the ceiling a
 * fire is refused (`REFUSED_CEILING`) and the trigger trips (`TRIPPED`), raising one `ceiling` attention; a tripped
 * trigger suppresses later fires until it is reset.
 * Why: a runaway event source must not start unbounded runs. The ceiling is a named setting, so the test pins the
 * behaviour, not a number.
 * Fix a failure: the trigger state machine or its counting changed. Fix the code to keep the rule; update the test
 * only if the rule itself was changed on purpose, and say so in the commit message.
 */
class TriggerGuardTest {
    private class FakeClock(var now: Long = 0) : TriggerClock {
        override fun nowMillis(): Long = now
    }

    @Test
    fun `hourly ceiling counts starts and refuses then trips at the limit`() {
        val clock = FakeClock()
        val starts = mutableListOf<TriggerRun>()
        val trips = mutableListOf<TriggerTrip>()
        val guard = TriggerGuard(
            clock = clock,
            launch = starts::add,
            onTrip = trips::add,
            coalesceMs = 0,
        )

        repeat(6) { index ->
            assertEquals(TriggerFireResult.STARTED, guard.fire("event-$index"))
            assertTrue(guard.complete(starts.last().runId, TriggerOutcome.SUCCEEDED))
        }

        assertEquals(TriggerFireResult.REFUSED_CEILING, guard.fire("event-6"))
        assertEquals(6, starts.size)
        assertEquals(TriggerState.TRIPPED, guard.state())
        assertEquals(1, trips.size)
        assertEquals("ceiling", trips.single().attentionKind)
        assertEquals(TriggerEventDisposition.REFUSED_CEILING, guard.dispositions.last().disposition)
    }

    @Test
    fun `ceiling window expires at one hour and permits another start`() {
        val clock = FakeClock()
        val starts = mutableListOf<TriggerRun>()
        val guard = TriggerGuard(clock = clock, launch = starts::add, coalesceMs = 0)

        repeat(6) { index ->
            assertEquals(TriggerFireResult.STARTED, guard.fire("event-$index"))
            guard.complete(starts.last().runId, TriggerOutcome.SUCCEEDED)
        }
        clock.now = TriggerGuard.DEFAULT_CEILING_WINDOW_MS

        assertEquals(TriggerFireResult.STARTED, guard.fire("event-6"))
        assertEquals(7, starts.size)
    }

    @Test
    fun `every non-success outcome trips once and never retries or starts later events`() {
        TriggerOutcome.entries.filterNot { it == TriggerOutcome.SUCCEEDED }.forEach { outcome ->
            val clock = FakeClock()
            val starts = mutableListOf<TriggerRun>()
            val trips = mutableListOf<TriggerTrip>()
            val guard = TriggerGuard(clock = clock, launch = starts::add, onTrip = trips::add, coalesceMs = 0)

            assertEquals(TriggerFireResult.STARTED, guard.fire("initial", "trigger-$outcome"))
            val run = starts.single()
            assertTrue(guard.complete(run.runId, outcome))

            clock.now += 1_000_000
            assertEquals(TriggerFireResult.SUPPRESSED_TRIPPED, guard.fire("later", "trigger-$outcome"))
            assertFalse(guard.pump())
            assertEquals(1, starts.size)
            assertEquals(TriggerState.TRIPPED, guard.state("trigger-$outcome"))
            assertEquals(1, trips.size)
            assertEquals(outcome.name, trips.single().outcome)
            assertEquals(TriggerEventDisposition.SUPPRESSED_TRIPPED, guard.dispositions.last().disposition)
        }
    }

    @Test
    fun `acknowledging attention does not arm trigger but explicit arm does`() {
        val starts = mutableListOf<TriggerRun>()
        val trips = mutableListOf<TriggerTrip>()
        val guard = TriggerGuard(launch = starts::add, onTrip = trips::add, coalesceMs = 0)
        guard.fire("first", "t")
        guard.complete(starts.single().runId, TriggerOutcome.FAILED)
        assertEquals(TriggerState.TRIPPED, guard.state("t"))
        assertEquals(TriggerFireResult.SUPPRESSED_TRIPPED, guard.fire("still-tripped", "t"))

        assertTrue(guard.arm("t"))
        assertEquals(TriggerState.ARMED, guard.state("t"))
        assertEquals(TriggerFireResult.STARTED, guard.fire("after-arm", "t"))
        assertEquals(2, starts.size)
    }

    @Test
    fun `events during a failed run are dropped and no follow up is launched`() {
        val starts = mutableListOf<TriggerRun>()
        val guard = TriggerGuard(launch = starts::add, coalesceMs = 0)
        guard.fire("initial", "t")
        guard.fire("queued", "t")

        guard.complete(starts.single().runId, TriggerOutcome.FAILED)
        assertFalse(guard.pump())
        assertEquals(1, starts.size)
        assertEquals(TriggerEventDisposition.DROPPED_AFTER_FAILURE, guard.dispositions.last().disposition)
    }

    @Test
    fun `arming an unknown trigger returns false and leaves no state`() {
        val guard = TriggerGuard(launch = {})
        assertFalse(guard.arm("missing"))
        assertNull(guard.stateOrNull("missing"))
    }

    @Test
    fun `trip report persists and publishes attention`() {
        var pushed: com.superiorsoftllc.infantry.daemon.api.attention.AttentionEntry? = null
        val attention = com.superiorsoftllc.infantry.daemon.MemAttention(clock = { 42L }).also { it.eventSink = { e -> pushed = e } }
        val trip = TriggerTrip("trigger-x", "trigger_tripped", "FAILED", "run failed")

        trip.reportTo(attention)

        val persisted = attention.rows.singleOrNull()
        assertEquals(persisted, pushed)
        assertNotNull(persisted)
        assertEquals("trigger_tripped", persisted.kind)
        assertEquals("trigger-x", persisted.triggerId)
        assertEquals("FAILED", persisted.outcome)
    }
}
