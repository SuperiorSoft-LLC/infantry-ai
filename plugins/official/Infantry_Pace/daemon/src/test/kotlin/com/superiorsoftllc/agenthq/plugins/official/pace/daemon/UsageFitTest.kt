package com.superiorsoftllc.agenthq.plugins.official.pace.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageFitTest {
    private val reset = 1_000_000_000L
    private fun sample(t: Long, pct: Double, resetsAt: Long = reset) = PaceSample("claude", "Weekly limit", t, pct, null, null, resetsAt, 10080)
    private fun req(ts: Long, cr: Long, role: String = "backend", model: String = "sonnet", input: Long = 10, cw: Long = 5) = FitRequest(ts, role, model, input, cr, cw)

    @Test
    fun `between-step intervals sum only the requests inside them`() {
        val samples = listOf(sample(0, 10.0), sample(100, 10.0), sample(200, 11.0), sample(300, 11.0), sample(400, 13.0))
        val iv = UsageFit.intervals(samples)
        assertEquals(listOf(Triple(0L, 200L, 1.0), Triple(200L, 400L, 2.0)), iv)
        val steps = UsageFit.steps(iv, listOf(req(0, 99), req(50, 100), req(200, 7), req(250, 300), req(400, 1), req(401, 999)))
        assertEquals(107L, steps[0].cacheRead) // (0,200]: ts 50 and 200, not ts 0
        assertEquals(301L, steps[1].cacheRead) // (200,400]: 250 and 400
        assertEquals(2, steps[0].requests)
    }

    @Test
    fun `a reset starts a new window and a drop does not make a step`() {
        val samples = listOf(sample(0, 50.0), sample(10, 51.0), sample(20, 1.0, reset + 10_000_000), sample(30, 2.0, reset + 10_000_000))
        assertEquals(listOf(Triple(0L, 10L, 1.0), Triple(20L, 30L, 1.0)), UsageFit.intervals(samples))
    }

    @Test
    fun `per point mean spread and median`() {
        val s = UsageFit.spread(listOf(2.0, 4.0, 6.0))!!
        assertEquals(4.0, s.mean, 1e-9); assertEquals(2.0, s.sd, 1e-9); assertEquals(4.0, s.median, 1e-9)
        assertNull(UsageFit.spread(emptyList()))
    }

    @Test
    fun `least squares recovers a known slope and the fit names the driver`() {
        // points = cacheRead / 1000 exactly; input is noise-free but unrelated.
        val n = 12
        val steps = (0 until n).map { i ->
            val cr = 1000L * (i % 4 + 1) * 10
            FitStep(i * 100L, i * 100L + 90, cr / 1000.0, 3, (i * 37L) % 11, cr, 5L + i % 3, mapOf("backend" to cr), mapOf("sonnet" to cr))
        }
        val fit = assertNotNull(UsageFit.fit(steps))
        assertEquals("cacheRead", fit.best.key)
        assertTrue(fit.r2 > 0.99, "r2 ${fit.r2}")
        assertEquals(n, fit.n)
        val perPoint = fit.tokensPerPoint.first().second!!
        assertEquals(1000.0, perPoint, 1.0)
        assertEquals(n, fit.fitted.size)
    }

    @Test
    fun `fit by model separates a heavy and a light model`() {
        val steps = (0 until 14).map { i ->
            val a = 1000L * (i % 5 + 1); val b = 1000L * ((i * 3) % 7 + 1)
            // sonnet costs 1 point per 1000, haiku 1 point per 4000
            FitStep(i * 100L, i * 100L + 90, a / 1000.0 + b / 4000.0, 2, 1, a + b, 1, mapOf("r" to a + b), mapOf("sonnet" to a, "haiku" to b))
        }
        val fit = assertNotNull(UsageFit.fit(steps))
        assertEquals("cacheReadByModel", fit.best.key)
        val byName = fit.tokensPerPoint.toMap()
        assertEquals(1000.0, byName.getValue("cache-read sonnet")!!, 5.0)
        assertEquals(4000.0, byName.getValue("cache-read haiku")!!, 20.0)
    }

    @Test
    fun `few steps give no fit and an honest not-enough flag`() {
        val w = UsageFit.analyse("weekly", "Weekly limit", listOf(sample(0, 1.0), sample(100, 2.0), sample(200, 3.0)), listOf(req(50, 1000), req(150, 2000)))
        assertEquals(2, w.steps.size)
        assertNull(w.fit)
        val json = UsageFit.toJson(w, minSteps = 8)
        assertEquals("false", json["enough"].toString())
        assertEquals("null", json["fit"].toString())
    }

    @Test
    fun `no data at all is empty and does not crash`() {
        val w = UsageFit.analyse("weekly", "Weekly limit", emptyList(), emptyList())
        assertEquals(0, w.steps.size); assertNull(w.cacheReadPerPoint); assertNull(w.fit)
        UsageFit.toJson(w, 8)
    }

    @Test
    fun `groups share cache-read by role and model`() {
        val w = UsageFit.analyse(
            "weekly", "Weekly limit", listOf(sample(0, 1.0), sample(100, 2.0)),
            listOf(req(10, 600, "backend", "sonnet"), req(20, 300, "pm", "opus"), req(30, 100, "backend", "haiku")),
        )
        assertEquals(3, w.groups.size)
        assertEquals("backend", w.groups[0].role); assertEquals(0.6, w.groups[0].share, 1e-9)
        assertEquals(1000.0, w.cacheReadPerPoint!!, 1e-9)
    }

    @Test
    fun `one model family is one group and the totals include requests outside the steps`() {
        val w = UsageFit.analyse(
            "weekly", "Weekly limit", listOf(sample(0, 1.0), sample(100, 2.0), sample(200, 2.0), sample(300, 2.0)),
            listOf(req(10, 600, "pm", "opus"), req(20, 300, "pm", "claude-opus-5-5"), req(250, 50, "pm", "anthropic/claude-opus-5-5"), req(30, 100, "x", "weird")),
        )
        val pm = w.groups.single { it.role == "pm" }
        assertEquals("opus", pm.model)
        assertEquals(3, pm.totalRequests); assertEquals(2, pm.requests)
        assertEquals("weird", w.groups.single { it.role == "x" }.model)
    }
}
