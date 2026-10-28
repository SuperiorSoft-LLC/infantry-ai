package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.ProgressTone
import com.superiorsoftllc.agenthq.ui.model.progressDisplay
import com.superiorsoftllc.agenthq.ui.protocol.ProgressView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PROGRESS-ALL: a daemon-computed bar is marked as an estimate, and its staleness is the daemon's flag. */
class AutoProgressDisplayTest {
    private val min = 60_000L

    private fun auto(stale: Boolean? = null, est: Long? = 12 * min) = ProgressView(
        mode = "indeterminate", label = "working (7 tool calls)", estimated = true, estimateMs = est, estimateSource = "history",
        startedAt = 0, updatedAt = 0, labelStartedAt = 0, lastOutputAt = 0, origin = "auto", stale = stale,
    )

    @Test
    fun countdownIsMarkedAsAnEstimate() {
        val d = progressDisplay(auto(), 4 * min)
        assertTrue("~8m left, est." in d.headline, d.headline)
        assertTrue(d.estimated)
    }

    @Test
    fun noHistoryReadsNoEstimateYet() {
        assertTrue("no estimate yet" in progressDisplay(auto(est = null), min).headline)
    }

    @Test
    fun staleComesFromTheDaemonNotTheClientPreference() {
        assertEquals(ProgressTone.Running, progressDisplay(auto(), 60 * min, staleMin = 5).tone)
        val d = progressDisplay(auto(stale = true), 60 * min)
        assertEquals(ProgressTone.Stale, d.tone)
        assertTrue("no activity for 60 min" in d.detail, d.detail)
    }
}
