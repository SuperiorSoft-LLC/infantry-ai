// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.usage.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** USAGE-MOCK-1: the mock snapshot becomes windows relative to now; nothing else can be expressed. */
class MockUsageTest {
    private val now = 1_000_000_000L

    @Test
    fun `percents and relative resets become windows`() {
        val w = MockUsage.windows("""{"claude":{"session":18,"week":42,"monthly":null,"weekResetsInMinutes":600}}""", now).getValue("claude")
        assertEquals(listOf("Current session", "Current week"), w.map { it.label }, "a null meter is no window")
        val week = w.last()
        assertEquals(42.0, week.percent)
        assertEquals(now + 600 * 60_000L, week.resetsAt)
        assertEquals(now + 150 * 60_000L, w.first().resetsAt, "default reset = half the window")
    }

    @Test
    fun `unreadable or empty data shows the default, out of range percents are dropped`() {
        assertTrue(MockUsage.windows("not json", now).containsKey("claude"))
        assertTrue(MockUsage.windows(null, now).containsKey("opencode-go"))
        assertNull(MockUsage.windows("""{"x":{"week":250}}""", now)["x"])
    }

    @Test
    fun `switch values`() {
        assertTrue(MockUsage.on("1") && MockUsage.on("true") && MockUsage.on(" ON "))
        assertTrue(!MockUsage.on(null) && !MockUsage.on("false") && !MockUsage.on(""))
    }
}
