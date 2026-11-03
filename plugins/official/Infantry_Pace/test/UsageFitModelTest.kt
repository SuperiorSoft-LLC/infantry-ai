// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.pace

import com.superiorsoftllc.infantry.plugin.api.DaemonReply
import com.superiorsoftllc.infantry.plugins.official.pace.ThemeContrastAssert.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageFitModelTest {
    private val raw = """{"generatedAt":5,"provider":"claude","windows":[
      {"kind":"weekly","label":"Weekly","steps":9,"minSteps":8,"enough":true,"pointsTotal":12,"cacheReadPerPoint":1500000,"requestsPerPoint":40,
       "perPoint":{"cacheRead":{"mean":1500000,"sd":200000,"median":1400000}},
       "groups":[{"role":"worker","model":"claude-sonnet-5-5","requests":300,"input":10,"cacheRead":9000000,"cacheWrite":5,"share":0.75}],
       "series":[{"t":1,"points":1,"requests":4,"input":1,"cacheRead":10,"cacheWrite":1,"cacheReadPerPoint":10,"byRole":{"worker":10},"fitted":1.1},
                 {"t":2,"points":2,"requests":4,"input":1,"cacheRead":20,"cacheWrite":1,"cacheReadPerPoint":10,"byRole":{"worker":20},"fitted":null}],
       "fit":{"n":9,"r2":0.91,"residualSd":0.3,"best":{"key":"cacheRead","label":"cache-read tokens"},"backgroundPerHour":null,"backgroundShare":null,"candidates":[],"tokensPerPoint":[{"name":"cache-read","value":1500000}]}},
      {"kind":"session","label":"5h","steps":2,"minSteps":8,"enough":false,"pointsTotal":3,"cacheReadPerPoint":null,"requestsPerPoint":null,"groups":[],"series":[],"fit":null}]}"""

    @Test fun `parses the daemon answer`() {
        val v = assertNotNull(UsageFitParser.parse(raw))
        assertEquals(2, v.windows.size)
        val w = assertNotNull(v.window("weekly"))
        assertEquals(9, w.steps); assertEquals(0.91, w.fit!!.r2); assertEquals(2, w.series.size); assertNull(w.series[1].fitted)
        assertEquals(1_400_000.0, w.cacheReadSpread!!.median)
    }

    @Test fun `garbage is not a report`() {
        assertNull(UsageFitParser.parse(null)); assertNull(UsageFitParser.parse("")); assertNull(UsageFitParser.parse("nope"))
    }

    @Test fun `headline and confidence say the step count`() {
        val w = UsageFitParser.parse(raw)!!.window("weekly")!!
        assertTrue("1.5M" in UsageFitText.headline(w), UsageFitText.headline(w))
        assertTrue("Sonnet" in UsageFitText.headline(w) && "75%" in UsageFitText.headline(w))
        assertTrue("Based on 9 steps" in UsageFitText.confidence(w) && "0.91" in UsageFitText.confidence(w))
    }

    @Test fun `few steps say not enough data yet`() {
        val w = UsageFitParser.parse(raw)!!.window("session")!!
        assertTrue(UsageFitText.headline(w).startsWith("Not enough data yet"))
        assertTrue("not enough data yet" in UsageFitText.confidence(w))
    }

    @Test fun `states follow the reply`() {
        assertEquals(UsageFitState.Loading, UsageFitState.of(null))
        assertTrue(UsageFitState.of(DaemonReply.Refused("x")) is UsageFitState.Failed)
        assertTrue(UsageFitState.of(DaemonReply.Failed("x")) is UsageFitState.Failed)
        assertTrue(UsageFitState.of(DaemonReply.Ok(200, "junk")) is UsageFitState.Failed)
        assertTrue(UsageFitState.of(DaemonReply.Ok(200, raw)) is UsageFitState.Ready)
    }

    @Test fun `token text is compact`() {
        assertEquals("812", UsageFitText.tokens(812.0)); assertEquals("1.5k", UsageFitText.tokens(1500.0))
        assertEquals("46k", UsageFitText.tokens(45_600.0)); assertEquals("1.2M", UsageFitText.tokens(1_234_567.0))
    }

    @Test fun `chart colours are readable in dark and light`() {
        ThemeContrastAssert.assertReadable(
            text = emptyList(),
            graphics = (0 until 6).map { i -> Token("role colour $i") { UsageFitColors.role(i) } } +
                listOf(Token("actual") { UsageFitColors.actual }, Token("fitted") { UsageFitColors.fitted }, Token("line") { UsageFitColors.line }),
        )
    }
}
