// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.usage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsageRefresherTest {
    private fun ok() = listOf(ProviderLimits("claude", "ok", emptyList(), null, 1))
    private fun bad() = listOf(ProviderLimits("claude", "unknown", emptyList(), LimitReason.NETWORK, 1))

    @Test
    fun readsPushesAndWaitsTheConfiguredMinutes() {
        var reads = 0; var pushes = 0
        val r = UsageRefresher({ reads++; ok() }, { 5 }, { pushes++ })
        assertEquals(5 * 60_000L, r.tick())
        assertEquals(1, reads); assertEquals(1, pushes)
    }

    @Test
    fun zeroMinutesIsOffAndNeverReads() {
        var reads = 0
        val r = UsageRefresher({ reads++; ok() }, { 0 }, {})
        assertEquals(UsageRefresher.IDLE_RECHECK_MS, r.tick())
        assertEquals(0, reads)
    }

    @Test
    fun failuresDoubleUpTo30MinutesAndASuccessResets() {
        var next = bad()
        val r = UsageRefresher({ next }, { 5 }, {})
        assertEquals(10 * 60_000L, r.tick())
        assertEquals(20 * 60_000L, r.tick())
        assertEquals(30 * 60_000L, r.tick())
        assertEquals(30 * 60_000L, r.tick())
        next = ok()
        assertEquals(5 * 60_000L, r.tick())
        assertEquals(0, r.failureCount())
    }

    @Test
    fun noCredentialsIsNotAFailure() {
        assertFalse(UsageRefresher.isFailure(ProviderLimits.unknown("x", 1, LimitReason.NO_CREDENTIALS)))
        assertTrue(UsageRefresher.isFailure(ProviderLimits.unknown("x", 1, "http_500")))
    }

    @Test
    fun anOverlappingTickDoesNotReadAgain() {
        var reads = 0
        lateinit var r: UsageRefresher
        r = UsageRefresher({ reads++; r.tick(); ok() }, { 1 }, {})
        r.tick()
        assertEquals(1, reads)
    }

    @Test
    fun aThrowingReadCountsAsAFailure() {
        val r = UsageRefresher({ error("boom") }, { 1 }, {}, log = {})
        assertEquals(2 * 60_000L, r.tick())
    }
}
