package com.superiorsoftllc.agenthq.daemon.plugins

import com.superiorsoftllc.agenthq.daemon.api.usage.MeterReading
import com.superiorsoftllc.agenthq.daemon.api.usage.Meters
import com.superiorsoftllc.agenthq.daemon.api.usage.PacingRule
import com.superiorsoftllc.agenthq.daemon.api.usage.LimitWindow
import com.superiorsoftllc.agenthq.usage.PacingMeters
import com.superiorsoftllc.agenthq.usage.ProviderLimits
import com.superiorsoftllc.agenthq.usage.UsagePacing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** PACE-TREND-1: api.meters is read-only, the pacing rule's own projection, and denied to a community plugin without `usage.read`. */
class GrantedMetersTest {
    private val reading = MeterReading("claude", "Weekly limit", 10080, 10.0, 1, 1, 0.0, 10.0)
    private val meters = object : Meters { override fun read() = listOf(reading) }

    @Test fun `a community plugin without usage_read is refused, with it reads`() {
        val denied = GrantedMeters({ meters }) { false }
        assertEquals("missing grant 'usage.read' in modules.json", assertFailsWith<IllegalStateException> { denied.read() }.message)
        assertEquals(listOf(reading), GrantedMeters({ meters }) { it == "usage.read" }.read())
    }

    @Test fun `PacingMeters lists every live window with the pace projection whatever the worker rule mode`() {
        val now = 84L * 3_600_000
        val window = LimitWindow("claude", "Weekly limit", 10080, 53.5, 168L * 3_600_000, now, "live", 3.5)
        val pacing = UsagePacing(
            limits = { listOf(ProviderLimits("claude", "ok", listOf(window), null, now)) },
            rule = { PacingRule(mode = PacingRule.MODE_BANDS) },
            manual = { emptyMap() }, overrides = { emptyMap() }, workerMeters = { emptyList() }, clock = { now },
        )
        val r = PacingMeters(pacing) { now }.read().single()
        assertEquals("claude", r.provider)
        assertEquals("Weekly limit", r.window)
        assertEquals(107.0, r.projectedPercent!!, 1e-9)
        assertEquals(3.5, r.paceDelta)
    }
}
