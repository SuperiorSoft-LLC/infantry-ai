package com.superiorsoftllc.agenthq.plugins.official.triggers

import com.superiorsoftllc.agenthq.ui.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TriggerDurationTest {
    @Test
    fun minutesConvertToMs() {
        assertEquals(120_000L, minutesToMs("2"))
        assertEquals(30_000L, minutesToMs(" 0.5 "))
        assertEquals(0L, minutesToMs("0"))
        assertNull(minutesToMs("-1"))
        assertNull(minutesToMs("abc"))
        assertNull(minutesToMs(""))
    }

    @Test
    fun msRoundTripsThroughMinutesText() {
        assertEquals("2", msToMinutesText(120_000))
        assertEquals("0.5", msToMinutesText(30_000))
        assertEquals("0", msToMinutesText(0))
        assertEquals(120_000L, minutesToMs(msToMinutesText(120_000)))
    }

    @Test
    fun humanLabels() {
        assertEquals("30 min", humanDuration(30 * 60_000L))
        assertEquals("2 h", humanDuration(2 * 3_600_000L))
        assertEquals("1 d", humanDuration(86_400_000L))
        assertEquals("90 min", humanDuration(90 * 60_000L))
        assertEquals("1.5 min", humanDuration(90_000))
    }

    @Test
    fun legacySubMinuteRowsStillRender() {
        assertEquals("45 s", humanDuration(45_000))
        assertEquals("500 ms", humanDuration(500))
    }
}
