package com.superiorsoftllc.agenthq.triggers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TriggerShipTest {
    private class FakeClock(var now: Long = 0) : TriggerClock {
        override fun nowMillis(): Long = now
    }

    @Test
    fun `ticket ships once even when reopened and closed again`() {
        val starts = mutableListOf<TriggerRun>()
        val ship = TriggerShip("on-done", coalesceMs = 0, launch = starts::add)

        assertEquals(TriggerEventDisposition.STARTED, ship.ticketDone("ticket-1"))
        ship.complete(starts.single().runId, TriggerOutcome.SUCCEEDED)
        assertEquals(TriggerEventDisposition.DUPLICATE, ship.ticketDone("ticket-1"))
        assertEquals(1, starts.size)
        assertEquals(TriggerEventDisposition.STARTED, ship.ticketDone("ticket-2"))
    }

    @Test
    fun `wont fix ticket never ships`() {
        val starts = mutableListOf<TriggerRun>()
        val ship = TriggerShip("on-done", coalesceMs = 0, launch = starts::add)

        assertEquals(TriggerEventDisposition.WONT_FIX, ship.ticketDone("ticket-1", wontFix = true))

        assertTrue(starts.isEmpty())
        assertEquals(TriggerEventDisposition.WONT_FIX, ship.dispositions.single().disposition)
    }

    @Test
    fun `manual replay repeats the last shipped ticket`() {
        val starts = mutableListOf<TriggerRun>()
        val ship = TriggerShip("on-done", coalesceMs = 0, launch = starts::add)
        ship.ticketDone("ticket-1")

        assertEquals(TriggerEventDisposition.STARTED, ship.replayLast())

        assertEquals(2, starts.size)
        assertEquals("ticket-1", starts.last().events.firstEventId)
        assertEquals(TriggerEventSource.MANUAL_REPLAY, ship.dispositions.last().source)
    }

    @Test
    fun `ship events during run fold and failed run drops follow-up`() {
        val starts = mutableListOf<TriggerRun>()
        val ship = TriggerShip("on-done", coalesceMs = 0, launch = starts::add)
        ship.ticketDone("ticket-1")
        ship.ticketDone("ticket-2")
        assertEquals(TriggerEventDisposition.FOLLOWUP_FOLDED, ship.dispositions.last().disposition)

        ship.complete(starts.single().runId, TriggerOutcome.FAILED)
        assertEquals(TriggerEventDisposition.DROPPED_AFTER_FAILURE, ship.dispositions.last().disposition)
        assertEquals(1, starts.size)
        assertFalse(ship.hasShipped("ticket-3"))
    }
}
