package com.superiorsoftllc.agenthq.plugins.official.stats

import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterPrice
import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterReport
import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** CHART-STYLE-1: the ranked cost table and the panel's colours. */
class CostPanelTest {
    private val prices = listOf(UsageMeterPrice("sonnet", 3.0, 15.0, 0.3, 3.75))
    private fun req(s: String, t: String?, role: String, model: String, out: Long) =
        UsageMeterRequest(1, s, t, role, "claude", model, "x", 0, out, 0, 0, 0, null)
    private val report = UsageMeterReport(
        requests = listOf(
            req("sess-aaaaaaaa", "tick-11111111", "frontend", "claude-sonnet-5-5", 1_000_000),
            req("sess-aaaaaaaa", "tick-11111111", "frontend", "claude-sonnet-5-5", 1_000_000),
            req("sess-bbbbbbbb", "tick-22222222", "pm", "claude-sonnet-5-5", 1_000_000),
            req("sess-cccccccc", null, "pm", "mystery", 5),
        ),
        prices = prices,
    )

    @Test fun `ticket rows use the title, rank by cost, and shares add up over the priced total`() {
        val t = UsageRequestView.costTable(report, CostBy.TICKET, CostNames(tickets = mapOf("tick-11111111" to "Fix the chart")))
        assertEquals(listOf("Fix the chart", "tick-222", "(no ticket)"), t.rows.map { it.label })
        assertEquals("tick-111", t.rows[0].detail)
        assertEquals(45.0, t.total!!, 1e-9)
        assertEquals(2.0 / 3, t.rows[0].share!!, 1e-9)
        assertNull(t.rows.last().cost); assertNull(t.rows.last().share); assertEquals(1, t.unpriced); assertEquals(4, t.turns)
    }

    @Test fun `model and harness splits label the human part first`() {
        val m = UsageRequestView.costTable(report, CostBy.MODEL).rows.first()
        assertEquals("claude-sonnet-5-5", m.label); assertEquals("claude", m.detail)
        assertEquals(listOf("claude"), UsageRequestView.costTable(report, CostBy.HARNESS).rows.map { it.label })
    }

    @Test fun `turns pluralise and shares format`() {
        assertEquals("1 turn", UsageRequestView.turns(1)); assertEquals("2 turns", UsageRequestView.turns(2)); assertEquals("0 turns", UsageRequestView.turns(0))
        assertEquals("<1%", UsageRequestView.share(0.001)); assertEquals("67%", UsageRequestView.share(2.0 / 3)); assertEquals("n/a", UsageRequestView.share(null))
    }

    @Test fun `no requests is an empty table`() {
        val t = UsageRequestView.costTable(UsageMeterReport(), CostBy.ROLE)
        assertEquals(emptyList(), t.rows); assertNull(t.total)
    }

    @Test fun `the panel text and bar colours are readable on the card in both themes`() {
        ThemeContrastAssert.assertReadable(
            text = listOf(ThemeContrastAssert.TEXT, ThemeContrastAssert.MUTED), graphics = listOf(ThemeContrastAssert.ACCENT),
        )
    }
}
