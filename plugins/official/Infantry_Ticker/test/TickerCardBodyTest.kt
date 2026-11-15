// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.ticker

import kotlin.test.Test
import kotlin.test.assertEquals

/** CARD-UPDATE-1: the ticker card's body parse and number format. */
class TickerCardBodyTest {
    @Test fun parsesTitleUnitAndPoints() {
        val b = TickerCardBody.parse("{\"title\":\"ACME\",\"unit\":\"USD\",\"items\":[{\"label\":\"09:30\",\"value\":101.2},{\"value\":\"x\"},{\"value\":99},5]}")
        assertEquals("ACME", b.title)
        assertEquals("USD", b.unit)
        assertEquals(listOf(101.2, 99.0, 5.0), b.points.map { it.value })
        assertEquals("09:30", b.points[0].label)
        assertEquals(5.0 - 101.2, b.change)
    }

    @Test fun malformedBodyHasNoPoints() {
        assertEquals(0, TickerCardBody.parse("nope").points.size)
        assertEquals(0.0, TickerCardBody.parse("{\"items\":[{\"value\":1}]}").change)
    }

    @Test fun numbers() {
        assertEquals("101.2", tickerNumber(101.2))
        assertEquals("-3", tickerNumber(-3.0))
        assertEquals("0.05", tickerNumber(0.05))
        assertEquals("1.25", tickerNumber(1.25))
        assertEquals("0", tickerNumber(-0.001))
    }
}
