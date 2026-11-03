// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.triggers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TriggerCoalescerTest {
    private class FakeClock(var now: Long = 0) : TriggerClock {
        override fun nowMillis(): Long = now
    }

    @Test
    fun `manual events in the fixed window become one run with an event summary`() {
        val clock = FakeClock()
        val starts = mutableListOf<TriggerRun>()
        val coalescer = TriggerCoalescer(clock, { starts += TriggerRun("run-${starts.size}", it.triggerId, it.events) })

        coalescer.fire("manual-1")
        clock.now = 60_000
        coalescer.fire("manual-2")
        clock.now = 119_999
        assertFalse(coalescer.pump())
        clock.now = 120_000

        assertTrue(coalescer.pump())
        assertEquals(1, starts.size)
        assertEquals(2, starts.single().events.count)
        assertEquals("manual-1", starts.single().events.firstEventId)
        assertEquals("manual-2", starts.single().events.lastEventId)
    }

    @Test
    fun `window is fixed from first event and does not slide`() {
        val clock = FakeClock()
        val starts = mutableListOf<TriggerRun>()
        val coalescer = TriggerCoalescer(clock, { starts += TriggerRun("run-${starts.size}", it.triggerId, it.events) })
        coalescer.fire("e1")
        clock.now = 110_000
        coalescer.fire("e2")
        clock.now = 120_000

        assertTrue(coalescer.pump())
        assertEquals(2, starts.single().events.count)
    }

    @Test
    fun `events during a run occupy one follow up slot regardless of event count`() {
        val clock = FakeClock()
        val starts = mutableListOf<TriggerRun>()
        val coalescer = TriggerCoalescer(clock, starts::add)
        coalescer.fire("initial")
        clock.now = 120_000
        coalescer.pump()
        val firstRun = starts.single()

        coalescer.fire("follow-1")
        clock.now += 1
        coalescer.fire("follow-2")
        clock.now += 1
        coalescer.fire("follow-3")

        assertEquals(1, starts.size)
        assertEquals(1, coalescer.inFlightCount)
        assertTrue(coalescer.complete(firstRun.runId, succeeded = true))
        assertEquals(1, starts.size)
        clock.now += 120_000
        assertTrue(coalescer.pump())
        assertEquals(2, starts.size)
        assertEquals(3, starts.last().events.count)
        assertEquals("follow-1", starts.last().events.firstEventId)
        assertEquals("follow-3", starts.last().events.lastEventId)
        assertEquals(1, coalescer.inFlightCount)
    }

    @Test
    fun `failed run drops its single follow up slot`() {
        val clock = FakeClock()
        val starts = mutableListOf<TriggerRun>()
        val coalescer = TriggerCoalescer(clock, starts::add)
        coalescer.fire("initial")
        clock.now = 120_000
        coalescer.pump()
        coalescer.fire("queued")

        assertTrue(coalescer.complete(starts.single().runId, succeeded = false))
        clock.now += 1_000_000
        assertFalse(coalescer.pump())
        assertEquals(1, starts.size)
        assertEquals(0, coalescer.inFlightCount)
        assertEquals(TriggerEventDisposition.DROPPED_AFTER_FAILURE, coalescer.dispositions.single { it.eventId == "queued" }.disposition)
    }

    @Test
    fun `zero coalesce delay starts immediately but still only one run at a time`() {
        val clock = FakeClock()
        val starts = mutableListOf<TriggerRun>()
        val coalescer = TriggerCoalescer(clock, starts::add, coalesceMs = 0)

        coalescer.fire("first")
        assertEquals(1, starts.size)
        coalescer.fire("second")
        coalescer.fire("third")
        assertEquals(1, starts.size)
        assertEquals(1, coalescer.inFlightCount)
    }
}
