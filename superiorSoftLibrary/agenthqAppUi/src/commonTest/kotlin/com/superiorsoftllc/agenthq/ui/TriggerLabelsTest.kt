package com.superiorsoftllc.agenthq.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** S4c: the trigger card's time labels, moved out of core's TriggerDraftTest with them. */
class TriggerLabelsTest {
    @Test
    fun cardTimes() {
        val now = 10_000_000L
        assertEquals("in 12m", nextFireLabel(now + 12 * 60_000L, now))
        assertEquals("due now", nextFireLabel(now - 1_000, now))
        assertNull(nextFireLabel(null, now))
        assertEquals("5m ago", lastFireLabel(now - 5 * 60_000L, now))
        assertEquals("never", lastFireLabel(null, now))
    }
}
