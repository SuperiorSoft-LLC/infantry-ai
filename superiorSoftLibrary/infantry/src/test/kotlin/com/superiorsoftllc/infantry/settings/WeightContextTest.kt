// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.settings

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WeightContextTest {
    @Test fun defaultsCoverEveryWeight() {
        assertEquals(WeightContextEntry(250_000, "low"), WeightContext.DEFAULT.forWeight(1))
        assertEquals("low", WeightContext.DEFAULT.forWeight(2)?.effort)
        assertEquals(WeightContextEntry(250_000, null), WeightContext.DEFAULT.forWeight(3))
        assertEquals(WeightContextEntry(250_000, null), WeightContext.DEFAULT.forWeight(4))
        assertEquals(WeightContextEntry(250_000, null), WeightContext.DEFAULT.forWeight(5))
        assertNull(WeightContext.DEFAULT.forWeight(null))
    }

    @Test fun parseReplacesDefaultsAndEmptyTurnsItOff() {
        val ctx = WeightContext.parse(Json.parseToJsonElement("""{"3":{"contextLimit":120000,"effort":"HIGH"}}"""))
        assertEquals(WeightContextEntry(120_000, "high"), ctx.forWeight(3))
        assertNull(ctx.forWeight(1))
        assertNull(WeightContext.parse(Json.parseToJsonElement("{}")).forWeight(3))
    }

    @Test fun parseRefusesBadInput() {
        for (bad in listOf("""{"9":{"effort":"low"}}""", """{"1":{"effort":"nope"}}""", """{"1":{"contextLimit":5}}""", """{"1":{}}""")) {
            assertFailsWith<IllegalArgumentException>(bad) { WeightContext.parse(Json.parseToJsonElement(bad)) }
        }
    }
}
