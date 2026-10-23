package com.superiorsoftllc.agenthq.usage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** U1: live plan-limit meters, with a fixed clock and a fake HTTP client. No test here touches the network. */
class LimitsTest {
    private val now = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()
    private val hour = 3_600_000L

    private class FakeHttp(var status: Int = 200, var body: String = "", var fail: Exception? = null) : LimitHttp {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        override fun get(url: String, headers: Map<String, String>): LimitHttpResponse {
            calls += url to headers
            fail?.let { throw it }
            return LimitHttpResponse(status, body)
        }
    }

    private val claudeBody = """
        {"five_hour":{"utilization":40.0,"resets_at":"2026-10-19T14:00:00Z"},
         "seven_day":{"utilization":20,"resets_at":"2026-10-23T12:00:00.000000+00:00"}}
    """.trimIndent()

    private val openCodeBody = """
        {"usage":{"rolling":{"percent":"55","resetsAt":"2026-10-19T13:00:00Z"},
                  "weekly":{"percent":12.5,"resetsAt":${(now + 24 * hour) / 1000}},
                  "monthly":{"percent":3,"resetsAt":${now + 15 * 24 * hour}}}}
    """.trimIndent()

    @Test
    fun claudeFiveHourAndSevenDayBecomeSessionAndWeekWindows() {
        val http = FakeHttp(body = claudeBody)
        val limits = ClaudeOAuthUsageSource(http) { "tok" }.read(now)
        assertEquals("ok", limits.status)
        assertNull(limits.reason)
        val (session, week) = limits.windows
        assertEquals(LimitWindow("claude", "Current session", 300, 40.0, now + 2 * hour, now, "live", -20.0), session)
        assertEquals(LimitWindow("claude", "Current week", 10_080, 20.0, now + 96 * hour, now, "live", -22.9), week)
        assertEquals(ClaudeOAuthUsageSource.URL, http.calls.single().first)
        assertEquals("oauth-2025-04-20", http.calls.single().second["anthropic-beta"])
    }

    @Test
    fun claudeLimitsArrayIsReadToo() {
        val body = """{"limits":[{"type":"seven_day","utilization":5,"resets_at":"2026-10-23T12:00:00Z"},
            {"type":"five_hour","utilization":10,"resets_at":"2026-10-19T14:00:00Z"}]}"""
        val windows = ClaudeOAuthUsageSource.parse(body, now)
        assertEquals(listOf("Current session" to 10.0, "Current week" to 5.0), windows.map { it.label to it.percent })
    }

    @Test
    fun openCodeRollingWeeklyMonthlyBecomeThreeWindows() {
        val http = FakeHttp(body = openCodeBody)
        val limits = OpenCodeGoUsageSource(http) { "key" }.read(now)
        assertEquals("ok", limits.status)
        assertEquals(
            listOf(
                Triple("5h limit", 300, 55.0),
                Triple("Weekly limit", 10_080, 12.5),
                Triple("Monthly limit", 43_200, 3.0),
            ),
            limits.windows.map { Triple(it.label, it.windowMinutes, it.percent) },
        )
        assertEquals(listOf(now + hour, now + 24 * hour, now + 360 * hour), limits.windows.map { it.resetsAt })
        assertEquals(-25.0, limits.windows[0].paceDelta)
        assertEquals(OpenCodeGoUsageSource.URL, http.calls.single().first)
        assertEquals("Bearer key", http.calls.single().second["Authorization"])
    }

    @Test
    fun errorsAreUnknownWithAReasonNeverZeroPercent() {
        val cases = listOf(
            FakeHttp(status = 401) to "expired",
            FakeHttp(status = 403) to "no_subscription",
            FakeHttp(status = 429) to "rate_limited",
            FakeHttp(status = 500) to "http_500",
            FakeHttp(fail = IOException("connect timed out")) to "network",
            FakeHttp(body = "<html>") to "bad_response",
            FakeHttp(body = "{}") to "bad_response",
        )
        for ((http, reason) in cases) {
            for (source in listOf(ClaudeOAuthUsageSource(http) { "t" }, OpenCodeGoUsageSource(http) { "t" })) {
                val limits = source.read(now)
                assertEquals(ProviderLimits.unknown(source.provider, now, reason), limits, "${source.provider} $reason")
            }
        }
    }

    @Test
    fun noCredentialMakesNoCall() {
        val http = FakeHttp(body = claudeBody)
        assertEquals("no_credentials", ClaudeOAuthUsageSource(http) { null }.read(now).reason)
        assertEquals("no_credentials", OpenCodeGoUsageSource(http) { null }.read(now).reason)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun paceIsPercentMinusTheElapsedShare() {
        // Half of a 5h window gone, half used: on pace.
        assertEquals(0.0, LimitPace.delta(50.0, now + 150 * 60_000L, 300, now))
        // One hour into 5h (20% expected) with 50% used: 30 points ahead.
        assertEquals(30.0, LimitPace.delta(50.0, now + 4 * hour, 300, now))
        // A reset already past counts as the whole window elapsed.
        assertEquals(-10.0, LimitPace.delta(90.0, now - hour, 300, now))
        assertNull(LimitPace.delta(null, now + hour, 300, now))
        assertNull(LimitPace.delta(10.0, null, 300, now))
    }

    @Test
    fun theTokenNeverReachesTheFrameOrTheLog() {
        val secret = "sk-ant-oat01-SECRET-TOKEN-0123456789"
        val log = mutableListOf<String>()
        val http = FakeHttp()
        val service = LimitService(
            listOf(ClaudeOAuthUsageSource(http) { secret }, OpenCodeGoUsageSource(http) { secret }),
            liveEnabled = { true }, clock = { now }, minIntervalMs = 0, log = { log += it },
        )
        val outcomes = listOf(
            { http.status = 200; http.body = claudeBody; http.fail = null },
            { http.status = 401; http.body = """{"error":"bad token $secret"}"""; http.fail = null },
            { http.fail = IOException("refused Authorization: Bearer $secret") },
            { http.fail = IllegalStateException(secret) },
        )
        for (setUp in outcomes) {
            setUp()
            val frame = Json.encodeToString(ListSerializer(ProviderLimits.serializer()), service.snapshot())
            assertFalse(frame.contains(secret), "token in frame: $frame")
        }
        assertTrue(log.isNotEmpty(), "the failures were logged")
        assertTrue(log.none { it.contains(secret) }, "token in log: $log")
        // The token went out, and only to each source's own endpoint.
        assertTrue(http.calls.all { (url, headers) -> url in setOf(ClaudeOAuthUsageSource.URL, OpenCodeGoUsageSource.URL) && headers["Authorization"] == "Bearer $secret" })
    }

    @Test
    fun liveSourcesOffMakesNoHttpCallAndReadsNoCredential() {
        val http = FakeHttp(body = claudeBody)
        var credentialReads = 0
        val service = LimitService(
            listOf(ClaudeOAuthUsageSource(http) { credentialReads++; "t" }),
            liveEnabled = { false }, clock = { now },
        )
        assertEquals(listOf(ProviderLimits.unknown("claude", now, "live_sources_off")), service.snapshot())
        assertTrue(http.calls.isEmpty())
        assertEquals(0, credentialReads)
        assertFalse(LimitService.liveFromEnv { if (it == LimitService.LIVE_ENV) "false" else null })
        assertTrue(LimitService.liveFromEnv { null })
    }

    @Test
    fun aRequestRefreshesAtMostEveryFiveMinutes() {
        var clock = now
        val http = FakeHttp(body = claudeBody)
        val service = LimitService(listOf(ClaudeOAuthUsageSource(http) { "t" }), liveEnabled = { true }, clock = { clock })
        service.snapshot()
        clock += 4 * 60_000L
        service.snapshot()
        assertEquals(1, http.calls.size, "a request inside 5 min is served from the last read")
        clock += 60_000L
        service.snapshot()
        assertEquals(2, http.calls.size)
    }
}
