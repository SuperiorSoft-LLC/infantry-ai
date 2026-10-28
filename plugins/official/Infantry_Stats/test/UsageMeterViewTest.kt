package com.superiorsoftllc.agenthq.plugins.official.stats

import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterRow
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageMeterViewTest {
    private fun row(day: String, source: String, input: Long, cacheRead: Long, turns: Long = 2) =
        UsageMeterRow(day, "pm", "claude", "m", source, 1, turns, input, 10, cacheRead, 0, input + cacheRead, 1.0)

    @Test fun `weeks start on Monday and day math round trips`() {
        assertEquals("2026-10-26", UsageMeterView.periodKey("2026-10-28", true)) // Wed -> Mon
        assertEquals("2026-10-26", UsageMeterView.periodKey("2026-11-01", true)) // Sun -> Mon
        assertEquals("2026-11-02", UsageMeterView.periodKey("2026-11-02", true))
        assertEquals("2024-02-29", UsageMeterView.dayString(UsageMeterView.epochDay("2024-02-29")))
        assertEquals(0L, UsageMeterView.epochDay("1970-01-01"))
    }

    @Test fun `periods newest first, split by source, cache hit and avg context`() {
        val rows = listOf(row("2026-10-27", "pm", 100, 900), row("2026-10-28", "worker", 100, 300), row("2026-10-28", "pm", 100, 100))
        val days = UsageMeterView.periods(rows, false)
        assertEquals(listOf("2026-10-28", "2026-10-27"), days.map { it.first })
        val t = UsageMeterView.total(days[0].second)
        assertEquals(200 + 20 + 400, t.tokens)
        assertEquals("66.7%", UsageMeterView.cacheHit(t)) // 400 / 600
        assertEquals("150", UsageMeterView.avgContext(t)) // 600 / 4
        assertEquals(listOf("worker", "pm"), UsageMeterView.split(days[0].second, MeterSplit.SOURCE).map { it.first })
        assertEquals("0%", UsageMeterView.delta(100, 100))
        assertEquals("+50%", UsageMeterView.delta(150, 100))
        assertEquals("-", UsageMeterView.delta(1, null))
        assertEquals(1, UsageMeterView.periods(rows, true).size)
    }
}
