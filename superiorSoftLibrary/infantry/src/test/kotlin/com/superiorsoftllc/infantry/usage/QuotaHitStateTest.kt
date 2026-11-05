// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.usage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** G15 (HARNESS-GAP): a quota hit marks a provider exhausted until its reset time, then it reads unknown again. */
class QuotaHitStateTest {
    @Test
    fun `quota hit reads exhausted until its reset time then unknown`() {
        var now = 1_000L
        val tracker = ProviderStateTracker(clock = { now })
        tracker.markQuotaHit("opencode", resetsAt = 5_000L)

        val during = tracker.state("opencode")
        assertEquals(ProviderStatus.EXHAUSTED, during.status)
        assertEquals(5_000L, during.resetsAt)
        assertEquals(ProviderStateTracker.QUOTA_HIT_SOURCE, during.source)

        now = 5_000L
        assertEquals(ProviderStatus.UNKNOWN, tracker.state("opencode").status)
    }

    @Test
    fun `quota hit without a reset time stays exhausted until another observation replaces it`() {
        val tracker = ProviderStateTracker(clock = { 1L })
        tracker.markQuotaHit("opencode")
        assertEquals(ProviderStatus.EXHAUSTED, tracker.state("opencode").status)
        tracker.markStalled("opencode")
        assertEquals(ProviderStatus.STALLED, tracker.state("opencode").status)
    }

    @Test
    fun `a quota hit is published so the daemon can store it`() {
        var pushed: ProviderState? = null
        val tracker = ProviderStateTracker(clock = { 1L }, sink = { pushed = it })
        tracker.markQuotaHit("opencode", resetsAt = 9L)
        assertEquals(ProviderStatus.EXHAUSTED, pushed?.status)
    }

    @Test
    fun `a claude stream observation is never expired by the quota-hit rule`() {
        var now = 1L
        val tracker = ProviderStateTracker(clock = { now }, exhaustion = { true })
        tracker.observeStreamLine("claude", """{"type":"rate_limit_event","rate_limit_info":{"resetsAt":2}}""")
        now = 10_000L
        assertTrue(tracker.state("claude").status == ProviderStatus.EXHAUSTED)
        assertFalse(tracker.state("claude").source == ProviderStateTracker.QUOTA_HIT_SOURCE)
    }
}

class QuotaHitStreamTest {
    @Test
    fun `an opencode free usage exceeded line marks the provider exhausted for the hold time`() {
        val tracker = ProviderStateTracker(clock = { 100L }, quotaHitHoldMs = 50L)
        val s = tracker.observeStreamLine("opencode", """{"type":"error","message":"Free usage exceeded, subscribe to Go"}""")
        assertEquals(ProviderStatus.EXHAUSTED, s?.status)
        assertEquals(150L, s?.resetsAt)
        assertEquals(null, ProviderStateTracker(clock = { 1L }).observeStreamLine("opencode", """{"type":"text","text":"hi"}"""))
    }
}
