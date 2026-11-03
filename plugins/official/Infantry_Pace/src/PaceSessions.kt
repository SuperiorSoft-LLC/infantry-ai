// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.pace

import com.superiorsoftllc.infantry.ui.protocol.UsageMeterRequest
import kotlin.math.roundToInt

/*
 * PACE-TREND-2: per-session spend, from the daemon's own per-request token records (`usage_meter.requests`), NOT from the provider meter
 * (the meter is account-wide and cannot say which session caused a rise). A MODEL with no Compose, so the tests assert on it.
 * Provider-generic: the grouping key is whatever the records say. INFORMATIONAL ONLY, like every token number in this plugin.
 */
object PaceSessions {
    const val HOUR = 3_600_000L

    /** The lookbacks the tab offers; the default is [DEFAULT_LOOKBACK_HOURS]. */
    val LOOKBACKS = listOf(0.25, 1.0, 6.0, 24.0)
    const val DEFAULT_LOOKBACK_HOURS = 1.0

    /** A session whose last request is newer than this is shown as active. */
    const val ACTIVE_MS = 5 * 60_000L

    /** The estimate needs the meter to have moved at least this many points and the sessions to have spent at least this many tokens in the window. */
    const val MIN_METER_DELTA = 1.0
    const val MIN_TOKENS = 1_000L

    /** What one request spent: input including cache read and write, plus output. A null (not reported) counts 0. */
    fun tokensOf(r: UsageMeterRequest): Long = (r.input ?: 0) + (r.cacheRead ?: 0) + (r.cacheWrite ?: 0) + (r.output ?: 0)

    data class SessionSpend(
        val sessionId: String, val ticketId: String?, val role: String, val harness: String, val model: String?, val meter: String,
        val input: Long, val output: Long, val turns: Int, val tokens: Long, val perHour: Double,
        val share: Double, val active: Boolean, val firstTs: Long, val lastTs: Long,
        /** Estimated points of the meter this session used in the window; null = no estimate (see [MeterEstimate.reason]). */
        val estimatePoints: Double? = null,
    )

    /** One provider's block: its sessions ranked by tokens in the window, highest first. */
    data class ProviderGroup(val meter: String, val total: Long, val sessions: List<SessionSpend>, val estimate: MeterEstimate)

    /** The ratio of meter delta to token delta over the window; [pointsPerMillion] is null when there was not enough data, with [reason] saying why. */
    data class MeterEstimate(val pointsPerMillion: Double?, val meterDelta: Double?, val tokens: Long, val window: String?, val reason: String?)

    data class Marker(val t: Long, val start: Boolean, val meter: String, val label: String)

    private fun meterOfRequest(r: UsageMeterRequest) = PaceTokens.meterOf(r.harness, r.model)

    /** Sessions with at least one request inside the last [lookbackHours], grouped by meter and ranked. [series] feeds the estimate. */
    fun groups(requests: List<UsageMeterRequest>, series: List<PaceSeries>, now: Long, lookbackHours: Double = DEFAULT_LOOKBACK_HOURS): List<ProviderGroup> {
        val from = now - (lookbackHours * HOUR).toLong()
        val inWindow = requests.filter { it.ts in from..now }
        val groups = inWindow.groupBy { meterOfRequest(it) }.map { (meter, reqs) ->
            val total = reqs.sumOf { tokensOf(it) }
            val est = estimate(meter, series, total, now, lookbackHours)
            val sessions = reqs.groupBy { it.sessionId }.map { (sid, rs) ->
                val last = rs.maxBy { it.ts }
                val tokens = rs.sumOf { tokensOf(it) }
                SessionSpend(
                    sid, rs.firstNotNullOfOrNull { it.ticketId }, last.role, last.harness, last.model, meter,
                    rs.sumOf { (it.input ?: 0) + (it.cacheRead ?: 0) + (it.cacheWrite ?: 0) }, rs.sumOf { it.output ?: 0 }, rs.size, tokens,
                    tokens / lookbackHours, if (total > 0) tokens.toDouble() / total else 0.0, now - last.ts <= ACTIVE_MS,
                    rs.minOf { it.ts }, last.ts, est.pointsPerMillion?.let { tokens / 1_000_000.0 * it },
                )
            }.sortedWith(compareByDescending<SessionSpend> { it.tokens }.thenBy { it.sessionId })
            ProviderGroup(meter, total, sessions, est)
        }
        return groups.sortedWith(compareByDescending<ProviderGroup> { it.total }.thenBy { it.meter })
    }

    /**
     * Points of meter per million tokens: (meter percent gained in the window) / (tokens our sessions spent in it). Uses the provider's
     * shortest window that has two readings of one window segment inside the lookback. Never invents precision: too little data = null + a reason.
     */
    fun estimate(meter: String, series: List<PaceSeries>, tokens: Long, now: Long, lookbackHours: Double): MeterEstimate {
        val from = now - (lookbackHours * HOUR).toLong()
        val mine = series.filter { it.provider.equals(meter, true) }.sortedBy { it.windowMinutes }
        if (mine.isEmpty()) return MeterEstimate(null, null, tokens, null, "No meter history for $meter, so no estimate.")
        if (tokens < MIN_TOKENS) return MeterEstimate(null, null, tokens, null, "Under $MIN_TOKENS tokens in this window: not enough data for an estimate.")
        for (s in mine) {
            val pts = s.points.filter { it.t in from..now }
            val seg = pts.groupBy { it.segment }.values.maxByOrNull { it.size } ?: continue
            if (seg.size < 2) continue
            val delta = seg.maxBy { it.t }.used - seg.minBy { it.t }.used
            if (delta < MIN_METER_DELTA) continue
            return MeterEstimate(delta / (tokens / 1_000_000.0), delta, tokens, s.window, null)
        }
        return MeterEstimate(null, null, tokens, null, "The $meter meter moved less than ${PaceChartModel.one(MIN_METER_DELTA)} point (or has under 2 readings) in this window: not enough data for an estimate.")
    }

    /** Session start / end marks for the chart. The oldest kept request may hide an older start, so a start at the very first record is not marked; an active session has no end. */
    fun markers(requests: List<UsageMeterRequest>, now: Long): List<Marker> {
        val oldest = requests.minOfOrNull { it.ts } ?: return emptyList()
        return requests.groupBy { it.sessionId }.flatMap { (_, rs) ->
            val first = rs.minBy { it.ts }; val last = rs.maxBy { it.ts }
            val meter = meterOfRequest(last)
            val label = listOfNotNull(last.ticketId?.take(8), last.role).joinToString(" ")
            buildList {
                if (first.ts > oldest) add(Marker(first.ts, true, meter, label))
                if (now - last.ts > ACTIVE_MS) add(Marker(last.ts, false, meter, label))
            }
        }.sortedBy { it.t }
    }

    data class CompareRow(val harness: String, val provider: String, val model: String, val sessions: Int, val avgTokens: Long, val avgTurns: Double)

    /** Average tokens and turns per run (a run = one session in the kept records), grouped by harness and model, highest average tokens first. */
    fun compare(requests: List<UsageMeterRequest>): List<CompareRow> =
        requests.groupBy { Triple(it.harness, meterOfRequest(it), it.model ?: "default") }.map { (k, rs) ->
            val perSession = rs.groupBy { it.sessionId }.values
            CompareRow(k.first, k.second, k.third, perSession.size, perSession.sumOf { s -> s.sumOf { tokensOf(it) } } / perSession.size, rs.size.toDouble() / perSession.size)
        }.sortedWith(compareByDescending<CompareRow> { it.avgTokens }.thenBy { it.harness }.thenBy { it.model })

    fun percent(share: Double): String = "${(share * 100).roundToInt()}%"
}
