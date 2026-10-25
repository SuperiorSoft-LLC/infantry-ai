package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.LimitInfo
import com.superiorsoftllc.agenthq.ui.model.PacingView
import com.superiorsoftllc.agenthq.ui.protocol.PacingRule
import com.superiorsoftllc.agenthq.ui.protocol.PacingTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ticket c2f0b0fd: the pacing editor bounds worker counts by the daemon's `limits.pacing.maxWorkersCeiling`, not a UI constant. */
class PacingCeilingTest {
    private val pace = PacingRule(true, listOf(PacingTier(null, 1)), PacingRule.MODE_PACE, 5, 100.0, 95.0)

    private fun catalog(value: Long) = listOf(
        LimitInfo("limits.concurrency.laneDefault", 1, 1, "agents", "", 1, null, null, "X"),
        LimitInfo(PacingView.CEILING_KEY, value, 50, "workers", "", 1, null, null, "AGENTHQ_LIMIT_PACING_MAX_WORKERS_CEILING"),
    )

    @Test
    fun the_ceiling_comes_from_the_limit_catalog() {
        assertEquals(80, PacingView.ceilingOf(catalog(80)))
        assertNull(PacingView.ceilingOf(null))
        assertNull(PacingView.ceilingOf(emptyList()))
    }

    @Test
    fun a_raised_ceiling_accepts_counts_above_fifty() {
        val c = PacingView.ceilingOf(catalog(80))
        assertEquals(75, PacingView.buildRule(pace, true, "60:70, 1", PacingRule.MODE_PACE, "75", "100", "95", c).getOrThrow().maxWorkers)
        assertEquals(80, PacingView.parseWorkers("80", c)?.workers)
        assertNull(PacingView.parseWorkers("81", c))
    }

    @Test
    fun a_lowered_ceiling_refuses_and_names_it() {
        val c = PacingView.ceilingOf(catalog(10))
        val maxErr = PacingView.buildRule(pace, true, "1", PacingRule.MODE_PACE, "11", "100", "95", c).exceptionOrNull()?.message
        assertTrue(maxErr!!.contains("1..10"), maxErr)
        val tierErr = PacingView.parseRule("50:11, 1", ceiling = c).exceptionOrNull()?.message
        assertTrue(tierErr!!.contains("0..10"), tierErr)
        assertNull(PacingView.parseWorkers("11", c))
    }

    @Test
    fun an_unknown_ceiling_leaves_the_top_to_the_daemon() {
        assertNotNull(PacingView.buildRule(pace, true, "60:200, 1", PacingRule.MODE_PACE, "500", "100", "95", null).getOrNull())
        assertEquals(500, PacingView.parseWorkers("500")?.workers)
        assertNull(PacingView.parseWorkers("-1"))
        assertTrue(PacingView.buildRule(pace, true, "1", PacingRule.MODE_PACE, "0", "100", "95", null).isFailure)
    }
}
