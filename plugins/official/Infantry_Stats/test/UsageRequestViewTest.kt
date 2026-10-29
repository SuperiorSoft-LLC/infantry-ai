package com.superiorsoftllc.agenthq.plugins.official.stats

import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterPrice
import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterReport
import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterRequest
import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsageRequestViewTest {
    private val prices = listOf(UsageMeterPrice("sonnet", 3.0, 15.0, 0.3, 3.75))
    private fun row(h: String, model: String, turns: Long, input: Long, out: Long, cr: Long, cw: Long) =
        UsageMeterRow("2026-10-29", "pm", h, model, "pm", 1, turns, input, out, cr, cw, input + cr + cw, null)
    private fun req(h: String, cr: Long?, ctx: Long?) = UsageMeterRequest(1, "s", null, "pm", h, "m", "pm", 10, null, cr, null, ctx, null)

    @Test fun `no data gives an empty comparison`() {
        assertEquals(emptyList(), UsageRequestView.compare(UsageMeterReport()))
    }

    @Test fun `comparison shows n-a for a harness that never reports cache, and prices only matching models`() {
        val rep = UsageMeterReport(
            rows = listOf(row("claude", "claude-sonnet-5-5", 2, 100, 1_000_000, 900, 0), row("pi", "x/unknown", 4, 400, 10, 0, 0)),
            requests = listOf(req("pi", null, 400), req("claude", 900, 1000)),
            prices = prices,
        )
        val by = UsageRequestView.compare(rep).associateBy { it.harness }
        val pi = by.getValue("pi"); val claude = by.getValue("claude")
        assertNull(pi.cacheHit, "pi reported no cache-read field"); assertNull(pi.estCost, "no price for the model")
        assertEquals(90.0, claude.cacheHit)
        assertEquals(15.0 + 0.0003 + 0.00027, claude.estCost!!, 1e-6)
        assertEquals(4L, pi.turns); assertEquals(102L, pi.tokensPerTurn)
        assertEquals("n/a", UsageRequestView.pct(pi.cacheHit)); assertEquals("n/a", UsageRequestView.usd(pi.estCost))
    }

    @Test fun `cost by ticket groups requests and flags the unpriced as null`() {
        val q = UsageMeterRequest(1, "s1", "t-123456789", "pm", "claude", "claude-sonnet-5-5", "pm", 1_000_000, 0, 0, 0, 1_000_000, null)
        val rep = UsageMeterReport(requests = listOf(q, q.copy(ticketId = null, model = "mystery")), prices = prices)
        val rows = UsageRequestView.costBy(rep, CostBy.TICKET).associateBy { it.first }
        assertEquals(3.0, rows.getValue("t-123456").third)
        assertNull(rows.getValue("(no ticket)").third)
    }
}
