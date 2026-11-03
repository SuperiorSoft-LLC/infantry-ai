// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.pace

import com.superiorsoftllc.infantry.ui.protocol.UsageMeterRow
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PACE-TREND-1: the chart MODEL (series, empty state, scales, clipping, forecast lines), the contrast of every colour it draws with, and the tab's states. */
class PaceChartModelTest {
    private val h = 3_600_000L
    private val now = 1_000 * h

    private fun point(i: Int, used: Double, pace: Double?, proj: Double?, seg: Int = 0, step: Long = h) =
        PacePoint(now - (20 - i) * step, used, pace, proj, seg)

    private fun forecast(ready: Boolean = true) = ForecastView(
        ready, if (ready) null else "not enough data yet", "at the current rate you land at 107%; at the last 6h rate, 96% (falling)", 84.0,
        if (ready) RateView("current rate", 107.0, 73.0) else null, if (ready) RateView("last 6h rate", 96.0, null) else null,
    )

    private fun series(points: List<PacePoint>, ready: Boolean = true, resets: Long? = now + 84 * h) =
        PaceSeries("claude", "Weekly limit", 10080, resets, points.size, points, Trend.FALLING, forecast(ready))

    private val pts = (0..20).map { point(it, 30.0 + it * 1.2, 2.0 + it * 0.1, 100.0 - it * 0.3) }

    // ---- parsing ------------------------------------------------------------------------------------------------------

    @Test fun `the daemon's report parses into series for any provider`() {
        val json = """{"generatedAt":5,"retentionDays":60,"series":[
            {"provider":"brand-new","window":"Monthly","windowMinutes":43200,"resetsAt":9,"samples":2,
             "points":[{"t":1,"u":1.5,"d":null,"j":null,"s":0},{"t":2,"u":2.5,"d":0.5,"j":9.0,"s":1}],
             "momentum":"RISING","forecast":{"ready":false,"reason":"not enough data yet","sentence":"not enough data yet","hoursLeft":null,"current":null,"recent":null}}]}"""
        val r = assertNotNull(PaceReportParser.parse(json))
        val s = r.series.single()
        assertEquals("brand-new", s.provider)
        assertEquals(Trend.RISING, s.trend)
        assertEquals(listOf(0, 1), s.points.map { it.segment })
        assertNull(s.points[0].paceDelta)
        assertFalse(s.forecast.ready)
        assertNull(PaceReportParser.parse("not json"))
        assertNull(PaceReportParser.parse(null))
    }

    // ---- empty state ----------------------------------------------------------------------------------------------------

    @Test fun `under two samples the card is empty with a reason, not a flat line`() {
        val one = series(listOf(point(20, 10.0, 1.0, 12.0)))
        val spec = PaceChartModel.spec(one, PaceMetric.PACE, PaceRange.WINDOW, PaceScale.Fit, now)
        assertTrue(spec.empty)
        assertTrue(spec.emptyText!!.contains("1 of 2"))
        assertTrue(PaceChartModel.spec(series(emptyList()), PaceMetric.USED, PaceRange.H6, PaceScale.Fit, now).empty)
        // a range holding fewer than two of the points is empty too
        assertTrue(PaceChartModel.spec(series(pts), PaceMetric.USED, PaceRange.H6, PaceScale.Fit, now + 100 * h).empty)
    }

    // ---- series, ranges ----------------------------------------------------------------------------------------------------

    @Test fun `ranges pick the points of the last 6h, 24h, 7d or the active window`() {
        val s = series(pts)
        assertEquals(7, PaceChartModel.visible(s, PaceRange.H6, now).size)
        assertEquals(21, PaceChartModel.visible(s, PaceRange.H24, now).size)
        val two = series((0..9).map { point(it, 90.0, 0.0, 100.0, seg = 0) } + (10..20).map { point(it, 5.0, 0.0, 100.0, seg = 1) })
        assertEquals(11, PaceChartModel.visible(two, PaceRange.WINDOW, now).size, "the window range is the last segment")
    }

    @Test fun `a reset breaks the line in two (the drop is not drawn as a fall)`() {
        val two = series((0..9).map { point(it, 90.0, 0.0, 100.0, seg = 0) } + (10..20).map { point(it, 5.0, 0.0, 100.0, seg = 1) })
        val spec = PaceChartModel.spec(two, PaceMetric.USED, PaceRange.D7, PaceScale.Fit, now)
        assertEquals(2, spec.actual.size)
    }

    @Test fun `pace delta skips points without a pace delta`() {
        val s = series(pts.mapIndexed { i, p -> if (i % 2 == 0) p.copy(paceDelta = null) else p })
        val spec = PaceChartModel.spec(s, PaceMetric.PACE, PaceRange.WINDOW, PaceScale.Fit, now)
        assertEquals(10, spec.actual.single().pts.size)
    }

    // ---- scales, ticks, zero line, cap ---------------------------------------------------------------------------------------

    @Test fun `Fit never draws a flat block - the axis hugs the data and keeps the zero line`() {
        val flat = series((0..20).map { point(it, 40.0, 0.2, 40.0) })
        val spec = PaceChartModel.spec(flat, PaceMetric.PACE, PaceRange.WINDOW, PaceScale.Fit, now)
        assertTrue(spec.yMax - spec.yMin >= 2.0)
        assertTrue(spec.zeroLine, "even pace is on the chart and emphasised")
        assertTrue(spec.ticks.size in 3..8, "ticks ${spec.ticks} ${spec.yMin}..${spec.yMax}")
        val used = PaceChartModel.spec(series(flat.points, ready = false), PaceMetric.USED, PaceRange.WINDOW, PaceScale.Fit, now)
        assertTrue(used.yMin >= 0.0 && used.yMax - used.yMin >= 4.0)
        assertNull(used.capLine, "a 40% meter does not squash itself to show a far-away 100% cap")
    }

    @Test fun `Fit includes the 100 percent cap once the meter is near it`() {
        val high = series((0..20).map { point(it, 70.0 + it, 5.0, 110.0) })
        val spec = PaceChartModel.spec(high, PaceMetric.USED, PaceRange.WINDOW, PaceScale.Fit, now)
        assertEquals(100.0, spec.capLine)
    }

    @Test fun `a fixed scale clips and says so in words`() {
        val spec = PaceChartModel.spec(series(pts), PaceMetric.USED, PaceRange.WINDOW, PaceScale.Fixed(50.0), now)
        assertEquals(50.0, spec.yMax)
        assertTrue(spec.clipped)
        assertEquals("over scale: line clipped at 50%", spec.clipNote)
        val roomy = PaceChartModel.spec(series(pts), PaceMetric.USED, PaceRange.WINDOW, PaceScale.Fixed(150.0), now)
        assertFalse(roomy.clipped)
        assertNull(roomy.clipNote)
        val pace = PaceChartModel.spec(series(pts), PaceMetric.PACE, PaceRange.WINDOW, PaceScale.Fixed(10.0), now)
        assertEquals(-10.0, pace.yMin)
        assertEquals(10.0, pace.yMax)
        assertEquals(listOf(50.0, 100.0, 150.0), PaceScales.presets(PaceMetric.USED))
    }

    @Test fun `custom scale parses a typed maximum`() {
        assertEquals(120.0, PaceScales.parseCustom("120"))
        assertEquals(120.0, PaceScales.parseCustom(" 120% "))
        assertNull(PaceScales.parseCustom("abc"))
        assertNull(PaceScales.parseCustom("0"))
    }

    @Test fun `nice ticks are round`() {
        assertEquals(listOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), PaceChartModel.niceTicks(0.0, 100.0).map { it + 0.0 })
        val t = PaceChartModel.niceTicks(-3.2, 7.7)
        assertTrue(t.all { it == kotlin.math.round(it * 2) / 2 }, "$t")
        assertTrue(t.first() >= -3.2 - 1e-9 && t.last() <= 7.7 + 1e-9)
    }

    // ---- forecast on the chart -----------------------------------------------------------------------------------------------

    @Test fun `the forecast runs to the reset in 7d and Window, as two lines and a band`() {
        val spec = PaceChartModel.spec(series(pts), PaceMetric.USED, PaceRange.WINDOW, PaceScale.Fit, now)
        assertEquals(now + 84 * h, spec.t1)
        assertEquals(107.0, spec.currentForecast!!.pts.last().second)
        assertEquals(96.0, spec.recentForecast!!.pts.last().second)
        assertTrue(spec.hasBand)
        // in pace-delta terms the landing minus 100 is where the line ends
        val pace = PaceChartModel.spec(series(pts), PaceMetric.PACE, PaceRange.WINDOW, PaceScale.Fit, now)
        assertEquals(7.0, pace.currentForecast!!.pts.last().second, 1e-9)
        assertEquals(-4.0, pace.recentForecast!!.pts.last().second, 1e-9)
    }

    @Test fun `no forecast lines in 6h, or while there is not enough data`() {
        assertNull(PaceChartModel.spec(series(pts), PaceMetric.USED, PaceRange.H6, PaceScale.Fit, now).currentForecast)
        val not = PaceChartModel.spec(series(pts, ready = false), PaceMetric.USED, PaceRange.WINDOW, PaceScale.Fit, now)
        assertNull(not.currentForecast)
        assertFalse(not.hasBand)
        assertEquals(now, not.t1)
    }

    // ---- tooltip, labels, momentum -------------------------------------------------------------------------------------------

    @Test fun `the tooltip names the exact value and time`() {
        val t = PaceChartModel.tooltip(PaceMetric.USED, 0L, 53.5, TimeZone.UTC)
        assertEquals("53.5% used at Jan 1 00:00", t)
        assertEquals("+3.5 pts vs even pace at Jan 1 00:00", PaceChartModel.tooltip(PaceMetric.PACE, 0L, 3.5, TimeZone.UTC))
        assertEquals("-2 pts vs even pace at Jan 1 00:00", PaceChartModel.tooltip(PaceMetric.PACE, 0L, -2.0, TimeZone.UTC))
    }

    @Test fun `nearest picks the closest reading`() {
        val spec = PaceChartModel.spec(series(pts), PaceMetric.USED, PaceRange.WINDOW, PaceScale.Fit, now)
        val first = spec.actual.single().pts.first()
        assertEquals(first, PaceChartModel.nearest(spec, first.first - 5 * h))
    }

    @Test fun `momentum is an arrow plus a word, never colour alone`() {
        for (t in Trend.entries) assertTrue(t.label.contains(t.word) && t.label.length > t.word.length, t.label)
        assertEquals(setOf("\u25B2", "\u25BC", "\u25AC", "\u2022").size, Trend.entries.map { it.arrow }.toSet().size)
    }

    @Test fun `the caption states used, pace and landing`() {
        // the last point: 30 + 20 * 1.2 = 54 used, 2 + 2 = +4 pace, 100 - 6 = 94 projected
        assertEquals("54% of limit|+4 vs even pace|lands at 94%", PaceChartModel.caption(series(pts)).split(" \u00B7 ").joinToString("|"))
        assertEquals("no reading yet", PaceChartModel.caption(series(emptyList())))
    }

    @Test fun `the burn panel plots burn rate with a zero line, 0 when idle`() {
        val idle = series((0..20).map { point(it, 50.0, 4.0 - it * 0.4, 100.0 - it, 0).copy(burn = if (it == 0) null else 0.0) })
        val spec = PaceChartModel.spec(idle, PaceMetric.BURN, PaceRange.WINDOW, PaceScale.Fit, now)
        assertFalse(spec.empty)
        assertTrue(spec.zeroLine, "the heavy zero line is on the panel")
        assertTrue(spec.yMin < 0.0 && spec.yMax > 0.0)
        assertNull(spec.currentForecast, "no forecast on the burn panel")
        assertEquals(20, spec.actual.single().pts.size)
        assertTrue(spec.actual.single().pts.all { it.second == 0.0 })
        assertEquals("+0 pts per hour burn rate at Jan 1 00:00", PaceChartModel.tooltip(PaceMetric.BURN, 0L, 0.0, TimeZone.UTC))
        assertEquals(listOf(PaceMetric.PACE, PaceMetric.USED), PaceMetric.toggle)
        assertTrue(PaceChartModel.spec(series(pts), PaceMetric.BURN, PaceRange.WINDOW, PaceScale.Fit, now).empty, "no burn values yet = empty state")
    }

    // ---- token totals beside the trend ----------------------------------------------------------------------------------------

    @Test fun `token totals are summed per meter, cache included, over the last 7 days`() {
        val rows = listOf(
            UsageMeterRow("2026-10-29", "pm", "claude", "claude-sonnet-5-5", "pm", 2, 10, 100, 20, 1000, 50, 0, null),
            UsageMeterRow("2026-10-28", "pm", "pi", "anthropic/claude-haiku", "pm", 1, 5, 40, 10, 200, 0, 0, null),
            UsageMeterRow("2026-10-29", "pm", "pi", "opencode-go/x", "pm", 1, 7, 9, 9, 9, 9, 0, null),
            UsageMeterRow("2026-10-01", "pm", "claude", "claude-sonnet-5-5", "pm", 9, 99, 9999, 9999, 9999, 9999, 0, null),
        )
        val t = PaceTokens.totals(rows, "claude", "2026-10-29")
        assertEquals(140L + 1200 + 50, t.inputWithCache)
        assertEquals(30L, t.output)
        assertEquals(15L, t.turns)
        assertEquals(3L, t.runs)
        assertEquals(7L, PaceTokens.totals(rows, "opencode-go", "2026-10-29").turns)
        assertFalse(PaceTokens.totals(rows, "codex", "2026-10-29").any)
        assertEquals("claude", PaceTokens.meterOf("pi", "anthropic/claude-x"))
        assertEquals("codex", PaceTokens.meterOf("codex", null))
    }

    // ---- tab states --------------------------------------------------------------------------------------------------------------

    @Test fun `the tab shows offline, loading, unavailable, empty and ready deliberately`() {
        assertEquals(PaceTabState.Offline, PaceTabState.of(false, null, false, null))
        assertEquals(PaceTabState.Loading, PaceTabState.of(true, null, false, null))
        assertTrue(PaceTabState.of(true, null, true, null) is PaceTabState.Unavailable)
        assertEquals(PaceTabState.Unavailable("nope"), PaceTabState.of(true, null, false, "nope"))
        assertEquals(PaceTabState.Empty, PaceTabState.of(true, """{"series":[]}""", false, null))
        assertTrue(PaceTabState.of(true, """{"series":[{"provider":"claude","window":"5h limit"}]}""", false, null) is PaceTabState.Ready)
    }
}
