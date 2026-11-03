// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.pace

import com.superiorsoftllc.infantry.ui.protocol.UsageMeterRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PACE-TREND-2: ranking, rates, share, estimate (and its refusal on thin data), markers, compare, empty state; fixed data. */
class PaceSessionsTest {
    private val h = 3_600_000L
    private val now = 1_000 * h

    private fun req(session: String, minsAgo: Long, input: Long, output: Long, harness: String = "claude", model: String? = "opus", ticket: String? = "t-$session", cacheRead: Long? = null) =
        UsageMeterRequest(ts = now - minsAgo * 60_000, sessionId = session, ticketId = ticket, role = "dev", harness = harness, model = model, input = input, output = output, cacheRead = cacheRead)

    private fun series(provider: String, vararg usedAtMinsAgo: Pair<Long, Double>) = PaceSeries(
        provider, "5h limit", 300, null, usedAtMinsAgo.size,
        usedAtMinsAgo.map { (m, u) -> PacePoint(now - m * 60_000, u, null, null, 0) }, Trend.FLAT,
        ForecastView(false, null, "", null, null, null),
    )

    @Test fun `ranks highest first, rate and share from fixed data`() {
        val reqs = listOf(req("a", 10, 1_000, 500), req("b", 20, 6_000, 2_000, cacheRead = 1_000), req("b", 5, 0, 0), req("c", 30, 750, 750))
        val g = PaceSessions.groups(reqs, emptyList(), now).single()
        assertEquals("claude", g.meter)
        assertEquals(listOf("b", "a", "c"), g.sessions.map { it.sessionId })
        assertEquals(12_000L, g.total)
        val b = g.sessions.first()
        assertEquals(9_000L, b.tokens); assertEquals(2, b.turns); assertEquals(7_000L, b.input); assertEquals(2_000L, b.output)
        assertEquals(9_000.0, b.perHour, 0.001)
        assertEquals(0.75, b.share, 0.0001); assertEquals("75%", PaceSessions.percent(b.share))
        assertTrue(b.active)
    }

    @Test fun `lookback excludes old requests and groups by provider`() {
        val reqs = listOf(req("a", 10, 1_000, 0), req("old", 90, 9_999, 0), req("p", 5, 3_000, 0, harness = "pi", model = "anthropic/sonnet"), req("x", 5, 4_500, 0, harness = "codex", model = "gpt"))
        val gs = PaceSessions.groups(reqs, emptyList(), now, 1.0)
        assertEquals(listOf("codex", "claude"), gs.map { it.meter }) // pi on an anthropic model spends the claude plan; ranked by group total
        assertEquals(2, gs.first { it.meter == "claude" }.sessions.size)
        assertEquals(setOf("a", "p"), gs.first { it.meter == "claude" }.sessions.map { it.sessionId }.toSet())
        assertEquals(1_000L + 9_999L + 3_000L, PaceSessions.groups(reqs, emptyList(), now, 24.0).first { it.meter == "claude" }.total)
    }

    @Test fun `estimate from meter delta over token delta`() {
        val reqs = listOf(req("a", 10, 1_500_000, 0), req("b", 20, 500_000, 0))
        val s = series("claude", 50L to 10.0, 5L to 14.0)
        val g = PaceSessions.groups(reqs, listOf(s), now).single()
        assertEquals(2.0, g.estimate.pointsPerMillion!!, 0.0001) // 4 points / 2M tokens
        assertEquals(3.0, g.sessions.first { it.sessionId == "a" }.estimatePoints!!, 0.0001)
        assertEquals(1.0, g.sessions.first { it.sessionId == "b" }.estimatePoints!!, 0.0001)
    }

    @Test fun `no estimate when data is insufficient, with a reason`() {
        val few = PaceSessions.groups(listOf(req("a", 10, 100, 0)), listOf(series("claude", 50L to 1.0, 5L to 9.0)), now).single()
        assertNull(few.estimate.pointsPerMillion); assertNotNull(few.estimate.reason); assertNull(few.sessions.single().estimatePoints)
        val reqs = listOf(req("a", 10, 2_000_000, 0))
        assertNull(PaceSessions.groups(reqs, listOf(series("claude", 5L to 9.0)), now).single().estimate.pointsPerMillion) // one reading
        assertNull(PaceSessions.groups(reqs, listOf(series("claude", 50L to 9.0, 5L to 9.4)), now).single().estimate.pointsPerMillion) // meter barely moved
        assertNull(PaceSessions.groups(reqs, emptyList(), now).single().estimate.pointsPerMillion) // no history
        assertTrue(PaceSessions.groups(reqs, emptyList(), now).single().estimate.reason!!.contains("No meter history"))
    }

    @Test fun `markers for start and end, none for active end or truncated start`() {
        val reqs = listOf(req("old", 200, 1, 1), req("old", 150, 1, 1), req("a", 100, 1, 1), req("a", 60, 1, 1), req("b", 90, 1, 1), req("b", 2, 1, 1))
        val ms = PaceSessions.markers(reqs, now)
        assertEquals(listOf(false, true, true, false), ms.map { it.start })
        assertTrue(ms.none { it.t == now - 200 * 60_000 }) // oldest record: its start may be truncated
        assertTrue(ms.any { it.t == now - 150 * 60_000 && !it.start }) // old ended
        assertTrue(ms.none { it.t == now - 2 * 60_000 }) // b still active: no end
        assertTrue(ms.any { it.t == now - 90 * 60_000 && it.start })
    }

    @Test fun `compare averages per run by harness and model`() {
        val reqs = listOf(req("a", 10, 1_000, 0), req("a", 9, 1_000, 0), req("b", 8, 4_000, 0), req("p", 5, 500, 0, harness = "pi", model = "anthropic/sonnet"))
        val rows = PaceSessions.compare(reqs)
        assertEquals(listOf("claude", "pi"), rows.map { it.harness })
        assertEquals(3_000L, rows[0].avgTokens) // (2000 + 4000) / 2 runs
        assertEquals(1.5, rows[0].avgTurns, 0.0001)
        assertEquals(2, rows[0].sessions)
    }

    @Test fun `empty state`() {
        assertTrue(PaceSessions.groups(emptyList(), emptyList(), now).isEmpty())
        assertTrue(PaceSessions.markers(emptyList(), now).isEmpty())
        assertTrue(PaceSessions.compare(emptyList()).isEmpty())
    }
}
