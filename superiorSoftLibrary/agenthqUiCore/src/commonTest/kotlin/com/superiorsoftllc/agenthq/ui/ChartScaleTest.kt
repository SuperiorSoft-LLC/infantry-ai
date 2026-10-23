package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.ChartKind
import com.superiorsoftllc.agenthq.ui.model.ChartScale
import com.superiorsoftllc.agenthq.ui.model.ChartScaleSetting
import com.superiorsoftllc.agenthq.ui.model.ChartScales
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChartScaleTest {
    @Test fun `the shared defaults are 200K for context and 2M for billed`() {
        assertEquals(200_000L, ChartScale.resolve(ChartKind.CONTEXT, ChartScaleSetting.Default, 50_000, 1_000_000).max)
        assertEquals(2_000_000L, ChartScale.resolve(ChartKind.BILLED, ChartScaleSetting.Default, 50_000, null).max)
    }

    @Test fun `an unchanged session gets the same axis whatever harness, limit or data`() {
        val scales = ChartScales()
        val axes = listOf("claude" to 200_000L, "pi" to 1_000_000L, "opencode" to null, "opencode-go" to 128_000L).map { (h, limit) ->
            ChartScale.resolve(ChartKind.CONTEXT, scales.get("sess-$h", ChartKind.CONTEXT), 90_000, limit)
        }
        assertEquals(1, axes.map { it.max }.toSet().size)
        assertEquals(200_000L, axes.first().max)
    }

    @Test fun `one session's choice does not reach another`() {
        val scales = ChartScales()
        scales.set("a", ChartKind.CONTEXT, ChartScaleSetting.Fixed(500_000))
        assertEquals(ChartScaleSetting.Fixed(500_000), scales.get("a", ChartKind.CONTEXT))
        assertEquals(ChartScaleSetting.Default, scales.get("b", ChartKind.CONTEXT))
        assertEquals(ChartScaleSetting.Default, scales.get("a", ChartKind.BILLED))
    }

    @Test fun `a choice is remembered through the host store and read back by a fresh instance`() {
        val disk = HashMap<String, String>()
        ChartScales(disk::get, disk::put).set("a", ChartKind.CONTEXT, ChartScaleSetting.Fit)
        val again = ChartScales(disk::get, disk::put)
        assertEquals(ChartScaleSetting.Fit, again.get("a", ChartKind.CONTEXT))
        assertEquals(ChartScaleSetting.Default, again.get("b", ChartKind.CONTEXT))
    }

    @Test fun `reset returns to the default and survives a reload`() {
        val disk = HashMap<String, String>()
        val s = ChartScales(disk::get, disk::put)
        s.set("a", ChartKind.CONTEXT, ChartScaleSetting.Fixed(1_000_000))
        s.reset("a", ChartKind.CONTEXT)
        assertEquals(ChartScaleSetting.Default, s.get("a", ChartKind.CONTEXT))
        assertEquals(ChartScaleSetting.Default, ChartScales(disk::get, disk::put).get("a", ChartKind.CONTEXT))
    }

    @Test fun `a series past the axis is flagged over scale, never silently cut`() {
        assertTrue(ChartScale.resolve(ChartKind.CONTEXT, ChartScaleSetting.Default, 234_310, 200_000).overScale)
        assertFalse(ChartScale.resolve(ChartKind.CONTEXT, ChartScaleSetting.Default, 200_000, 200_000).overScale)
        assertFalse(ChartScale.resolve(ChartKind.CONTEXT, ChartScaleSetting.Fit, 234_310, null).overScale)
    }

    @Test fun `fit and model limit follow the session, and model limit with no limit falls back to the default`() {
        assertEquals(234_310L, ChartScale.resolve(ChartKind.CONTEXT, ChartScaleSetting.Fit, 234_310, 1_000_000).max)
        assertEquals(1_000_000L, ChartScale.resolve(ChartKind.CONTEXT, ChartScaleSetting.ModelLimit, 10, 1_000_000).max)
        assertEquals(200_000L, ChartScale.resolve(ChartKind.CONTEXT, ChartScaleSetting.ModelLimit, 10, null).max)
    }

    @Test fun `the dashed limit line is only inside the axis`() {
        val axis = ChartScale.resolve(ChartKind.CONTEXT, ChartScaleSetting.Default, 1, 1_000_000)
        assertFalse(ChartScale.limitInside(axis, 1_000_000))
        assertTrue(ChartScale.limitInside(axis, 200_000))
        assertFalse(ChartScale.limitInside(axis, null))
    }

    @Test fun `custom values parse with k and m suffixes`() {
        assertEquals(300_000L, ChartScale.parseTokens("300k"))
        assertEquals(1_500_000L, ChartScale.parseTokens("1.5M"))
        assertEquals(250_000L, ChartScale.parseTokens("250,000"))
        assertNull(ChartScale.parseTokens("abc"))
        assertNull(ChartScale.parseTokens("0"))
    }
}
