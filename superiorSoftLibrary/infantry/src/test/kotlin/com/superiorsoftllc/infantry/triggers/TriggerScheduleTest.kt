// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.triggers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TriggerScheduleTest {
    private class FakeClock(var now: Long) : TriggerClock {
        override fun nowMillis(): Long = now
    }

    @Test
    fun `schedule catches up all downtime slots as one run with missed count`() {
        val clock = FakeClock(0)
        val starts = mutableListOf<TriggerRun>()
        val schedule = TriggerSchedule(
            triggerId = "daily",
            frequency = ScheduleFrequency.EVERY_MINUTES,
            everyMinutes = 10,
            catchUp = true,
            clock = clock,
            launch = starts::add,
        )

        schedule.resume(lastObservedAt = 0, now = 3_600_000)

        assertEquals(1, starts.size)
        assertEquals(6, starts.single().events.count)
        assertEquals(TriggerEventDisposition.STARTED, schedule.dispositions.last().disposition)
        assertEquals(TriggerEventSource.SCHEDULE, schedule.dispositions.last().source)
    }

    @Test
    fun `disabled catch up marks old slots missed but catches a recent slot`() {
        val clock = FakeClock(3_600_000)
        val starts = mutableListOf<TriggerRun>()
        val schedule = TriggerSchedule(
            triggerId = "no-catch-up",
            frequency = ScheduleFrequency.EVERY_MINUTES,
            everyMinutes = 10,
            catchUp = false,
            clock = clock,
            launch = starts::add,
        )

        schedule.resume(lastObservedAt = 0, now = 3_600_000)

        assertEquals(1, starts.size)
        assertEquals(5, schedule.dispositions.count { it.disposition == TriggerEventDisposition.MISSED })
        schedule.resume(lastObservedAt = 3_600_000, now = 3_660_000)
        assertEquals(1, starts.size)
        assertEquals(TriggerEventDisposition.STARTED, schedule.dispositions.last().disposition)
    }

    @Test
    fun `schedule slot during in-flight run folds into one follow-up and outcome controls launch`() {
        val clock = FakeClock(0)
        val starts = mutableListOf<TriggerRun>()
        val schedule = TriggerSchedule(
            triggerId = "hourly",
            frequency = ScheduleFrequency.EVERY_MINUTES,
            everyMinutes = 1,
            coalesceMs = 0,
            clock = clock,
            launch = starts::add,
        )

        schedule.fireSlot("slot-1", 0)
        schedule.fireSlot("slot-2", 60_000)
        assertEquals(TriggerEventDisposition.FOLLOWUP_FOLDED, schedule.dispositions.last().disposition)
        assertEquals(1, starts.size)
        schedule.complete(starts.first().runId, TriggerOutcome.SUCCEEDED)
        schedule.pump()
        assertEquals(2, starts.size)
        assertEquals(TriggerEventDisposition.STARTED, schedule.dispositions.last().disposition)
    }

    @Test
    fun `failed scheduled run drops queued slots without retry`() {
        val clock = FakeClock(0)
        val starts = mutableListOf<TriggerRun>()
        val schedule = TriggerSchedule(
            triggerId = "hourly",
            frequency = ScheduleFrequency.EVERY_MINUTES,
            everyMinutes = 1,
            coalesceMs = 0,
            clock = clock,
            launch = starts::add,
        )
        schedule.fireSlot("slot-1", 0)
        schedule.fireSlot("slot-2", 60_000)

        schedule.complete(starts.single().runId, TriggerOutcome.FAILED)
        schedule.pump()

        assertEquals(1, starts.size)
        assertEquals(TriggerEventDisposition.DROPPED_AFTER_FAILURE, schedule.dispositions.last().disposition)
    }
}
