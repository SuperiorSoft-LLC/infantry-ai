// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.usage

import com.superiorsoftllc.infantry.daemon.api.usage.PacingRule
import com.superiorsoftllc.infantry.daemon.api.usage.PacingTier
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * USAGE-CARD-1: a failed plan meter read keeps the last good reading (stale, with its age) while it is younger than the
 * stale limit, the source backs off (Retry-After, else exponential from the refresh interval, capped), a forced read has
 * a floor, pacing keeps working on the stale reading and fails open past the limit, and one provider is read once.
 */
class UsageCardStaleTest {
    private val start = Instant.parse("2026-11-04T12:00:00Z").toEpochMilli()
    private val min = 60_000L

    private class FakeHttp(var status: Int = 200, var retryAfterMs: Long? = null) : LimitHttp {
        var calls = 0
        override fun get(url: String, headers: Map<String, String>): LimitHttpResponse {
            calls++
            return LimitHttpResponse(status, if (status == 200) BODY else "", if (status == 200) null else retryAfterMs)
        }
    }

    companion object {
        const val BODY = """{"five_hour":{"utilization":40.0,"resets_at":"2026-11-04T14:00:00Z"},"seven_day":{"utilization":70,"resets_at":"2026-11-08T12:00:00Z"}}"""
    }

    private val tuning = UsageReadTuning(staleMaxMs = 6 * 60 * min, backoffBaseMs = 8 * min, backoffMaxMs = 60 * min, manualFloorMs = min)

    private fun service(http: FakeHttp, clock: () -> Long, extra: () -> List<LimitSource> = { emptyList() }, log: (String) -> Unit = {}) =
        LimitService(listOf(ClaudeOAuthUsageSource(http) { "t" }), liveEnabled = { true }, clock = clock, log = log, extraSources = extra)
            .also { s -> s.tuning = { tuning } }

    @Test
    fun a429KeepsTheLastReadingAndSchedulesTheBackoff() {
        var now = start
        val http = FakeHttp()
        val s = service(http, { now })
        val good = s.snapshot().single()
        assertEquals("ok", good.status); assertFalse(good.stale)

        http.status = 429
        now += 8 * min
        val stale = s.snapshot(force = true).single()
        assertTrue(stale.stale, "a 429 keeps the last good reading")
        assertEquals(LimitReason.RATE_LIMITED, stale.reason)
        assertEquals(good.windows, stale.windows)
        assertEquals(good.observedAt, stale.observedAt, "the age is the good reading's")
        assertEquals(now + 8 * min, stale.retryAt, "first failure: wait the refresh interval")

        // Inside the backoff nobody reaches the endpoint, forced or not.
        now += 7 * min
        assertTrue(s.snapshot(force = true).single().stale)
        assertEquals(2, http.calls)

        // 8, 16, 32, then the 60 min cap.
        val waits = mutableListOf<Long>()
        now += min
        repeat(4) {
            val r = s.snapshot(force = true).single()
            waits += r.retryAt!! - now
            now = r.retryAt!!
        }
        assertEquals(listOf(16 * min, 32 * min, 60 * min, 60 * min), waits)
    }

    @Test
    fun retryAfterIsHonouredAndASuccessResetsTheBackoff() {
        var now = start
        val http = FakeHttp()
        val s = service(http, { now })
        s.snapshot()
        http.status = 429; http.retryAfterMs = 25 * min
        now += 8 * min
        assertEquals(now + 25 * min, s.snapshot(force = true).single().retryAt)

        http.status = 200
        now += 25 * min
        val back = s.snapshot(force = true).single()
        assertFalse(back.stale); assertNull(back.reason); assertNull(back.retryAt)

        http.status = 429; http.retryAfterMs = null
        now += 8 * min
        assertEquals(now + 8 * min, s.snapshot(force = true).single().retryAt, "the success reset the backoff to its first step")
    }

    @Test
    fun parsesRetryAfterSecondsAndHttpDate() {
        assertEquals(120_000L, parseRetryAfter("120", start))
        assertEquals(90_000L, parseRetryAfter("Wed, 04 Nov 2026 12:01:30 GMT", start))
        assertNull(parseRetryAfter("soon", start))
        assertNull(parseRetryAfter(null, start))
    }

    @Test
    fun aManualRefreshInsideTheFloorAnswersTheLastRead() {
        var now = start
        val http = FakeHttp()
        val s = service(http, { now })
        s.snapshot()
        now += 20_000L
        s.snapshot(force = true)
        assertEquals(1, http.calls, "a click 20 s after a read does not reach the endpoint")
        now += 50_000L
        s.snapshot(force = true)
        assertEquals(2, http.calls)
    }

    @Test
    fun theStaleReadingExpiresAfterTheLimit() {
        var now = start
        val http = FakeHttp()
        val s = service(http, { now })
        s.snapshot()
        http.status = 429
        now += 5 * 60 * min
        assertTrue(s.snapshot(force = true).single().stale, "5 h old: still kept")
        now += 61 * min
        val gone = s.snapshot(force = true).single()
        assertEquals("unknown", gone.status)
        assertTrue(gone.windows.isEmpty())
        assertFalse(gone.stale)
        assertEquals(LimitReason.RATE_LIMITED, gone.reason)
    }

    @Test
    fun noCredentialsDropsTheOldReading() {
        var now = start
        var token: String? = "t"
        val http = FakeHttp()
        val s = LimitService(listOf(ClaudeOAuthUsageSource(http) { token }), liveEnabled = { true }, clock = { now }, log = {})
            .also { it.tuning = { tuning } }
        s.snapshot()
        token = null
        now += 8 * min
        val r = s.snapshot(force = true).single()
        assertEquals(LimitReason.NO_CREDENTIALS, r.reason)
        assertTrue(r.windows.isEmpty(), "signed out: an old reading would mislead")
    }

    @Test
    fun aSecondSourceForTheSameProviderIsNotRead() {
        val http = FakeHttp()
        var pluginReads = 0
        val plugin = object : LimitSource {
            override val provider = "claude"
            override fun read(now: Long): ProviderLimits { pluginReads++; return ProviderLimits.unknown("claude", now, LimitReason.SOURCE_FAILED) }
        }
        val logs = mutableListOf<String>()
        val s = service(http, { start }, extra = { listOf(plugin) }, log = { logs += it })
        val all = s.snapshot()
        assertEquals(1, all.size, "one Claude card, not two")
        assertEquals("ok", all.single().status)
        assertEquals(0, pluginReads)
        assertEquals(1, http.calls)
        assertTrue(logs.any { "second usage source" in it })
    }

    @Test
    fun pacingKeepsWorkingOnAStaleButYoungReadingAndFailsOpenAfterTheLimit() {
        var now = start
        val http = FakeHttp()
        val s = service(http, { now })
        // 70% of the week: the tier rule allows one worker; one is running, so a second start is refused.
        val rule = PacingRule(enabled = true, mode = PacingRule.MODE_BANDS, tiers = listOf(PacingTier(60.0, 3), PacingTier(null, 1)), maxWorkers = 3)
        val pacing = UsagePacing(
            limits = { s.cachedOrRefresh() }, rule = { rule }, manual = { emptyMap() }, overrides = { emptyMap() },
            workerMeters = { listOf("claude") }, clock = { now },
        )
        s.snapshot()
        assertIs<PacingVerdict.Over>(pacing.check("claude"))

        http.status = 429
        now += 8 * min
        s.snapshot(force = true)
        val m = pacing.report().meters.single()
        assertEquals(70.0, m.percent, "pacing reads the stale reading")
        assertNotNull(m.warning); assertTrue("pacing on the last good reading" in m.warning!!)
        assertIs<PacingVerdict.Over>(pacing.check("claude"), "still paced, not fail open")

        now += 6 * 60 * min
        val after = pacing.report().meters.single()
        assertNull(after.percent)
        assertTrue(after.warning!!.contains("fail open"))
        assertIs<PacingVerdict.Allow>(pacing.check("claude"))
    }
}
