package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.progressDisplay
import com.superiorsoftllc.infantry.ui.protocol.ProgressView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressCountdownDisplayTest {
    private val min = 60_000L

    @Test
    fun estimateShowsPercentAndTimeLeft() {
        val p = ProgressView(mode = "indeterminate", label = "gradle :a:test", startedAt = 0, updatedAt = 0, labelStartedAt = 0, estimateMs = 4 * min, estimateSource = "learned")
        val d = progressDisplay(p, 2 * min)
        assertEquals("50% · ~2m left", d.headline)
        assertEquals(0.5f, d.fraction)
        assertFalse("2m" in d.detail && "00s" in d.detail, d.detail)
    }

    @Test
    fun overrunSaysSoAndNeverGoesNegative() {
        val p = ProgressView(mode = "indeterminate", label = "x", startedAt = 0, updatedAt = 4 * min, estimateMs = 2 * min)
        val d = progressDisplay(p, 3 * min + 1_000)
        assertTrue("over estimate by 1m" in d.headline, d.headline)
        assertEquals(0.99f, d.fraction)
    }

    @Test
    fun noHistoryHidesElapsedAndShowsLastOutput() {
        val p = ProgressView(mode = "indeterminate", label = "gradle :a:test", startedAt = 0, updatedAt = 0, lastOutputAt = 595_000)
        val d = progressDisplay(p, 10 * min)
        assertEquals("no estimate yet", d.headline)
        assertEquals(null, d.fraction)
        assertTrue("last output 5s ago" in d.detail, d.detail)
        assertFalse("10m" in d.detail, d.detail)
    }

    @Test
    fun counterWinsOverEstimate() {
        val p = ProgressView(mode = "count", percent = 25.0, done = 25, total = 100, label = "tests", startedAt = 0, updatedAt = 2 * min, labelStartedAt = 0, estimateMs = 60_000)
        val d = progressDisplay(p, 2 * min)
        assertTrue(d.headline.startsWith("25% · ~6m left"), d.headline)
        assertEquals(0.25f, d.fraction)
    }
}

class ProgressSleepWaitLabelTest {
    private fun wait(now: Long) = progressDisplay(
        ProgressView(mode = "indeterminate", label = "waiting", startedAt = 0, updatedAt = 0, labelStartedAt = 0, estimateMs = 120_000, estimateSource = "sleep"),
        now,
    )

    @Test
    fun countsDownAsWaiting() {
        assertEquals("waiting 2m", wait(0).headline)
        assertEquals("waiting 1m 20s", wait(40_000).headline)
        assertEquals("waiting 1m 20s", wait(40_500).headline)
        assertEquals("waiting 1m 19s", wait(41_000).headline)
        assertEquals("", wait(40_000).detail)
    }

    @Test
    fun neverGoesNegativeOrOver() {
        assertEquals("waiting", wait(120_000).headline)
        assertEquals("waiting", wait(500_000).headline)
    }
}
