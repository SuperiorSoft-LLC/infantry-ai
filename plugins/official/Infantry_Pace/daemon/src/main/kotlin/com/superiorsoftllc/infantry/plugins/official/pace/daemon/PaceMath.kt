package com.superiorsoftllc.infantry.plugins.official.pace.daemon

/**
 * PACE-TREND-1: the whole pacing-trend arithmetic, pure functions over recorded [PaceSample]s (docs/PACING.md explains each
 * formula in plain words with one worked example). Nothing here reads a clock or a file; callers pass `now`.
 */
data class PaceSample(
    val provider: String,
    val window: String,
    /** Epoch millis of the provider read these numbers come from. */
    val observedAt: Long,
    val percent: Double,
    val paceDelta: Double?,
    val projectedPercent: Double?,
    val resetsAt: Long?,
    /** The window's length, for the forecast (0 = unknown). */
    val windowMinutes: Int = 0,
    /** TOKEN-9: percent of the previous sample of the series (null = first) and the raw step to this one. */
    val prevPercent: Double? = null,
    val deltaPercent: Double? = null,
    /** TOKEN-9: tokens each session spent since the previous recorded sample (empty = none seen). */
    val tokens: List<SessionTokens> = emptyList(),
)

/** TOKEN-9: one session's token counters over the requests since the previous sample (null = never reported). */
data class SessionTokens(val sessionId: String, val role: String, val input: Long?, val output: Long?, val cacheRead: Long?, val cacheWrite: Long?)

/** The tunables (module setting keys `pace.*`); every one has a default and the owner changes them in Settings. */
data class PaceSettings(
    val retentionDays: Int = 60,
    val lookbackShortHours: Double = 6.0,
    val lookbackLongHours: Double = 24.0,
    /** Points per hour: a slope with a smaller magnitude reads as FLAT. */
    val deadBand: Double = 0.25,
    val minSamples: Int = 3,
    val minSpanHours: Double = 2.0,
    /** The burn rate is the change in percent used per hour over this rolling window. */
    val burnWindowHours: Double = 1.0,
) {
    companion object {
        /** Reads each key through [get] (null/blank/unparsable = default). */
        fun from(get: (String) -> String?): PaceSettings {
            val d = PaceSettings()
            fun num(key: String): Double? = get(key)?.trim()?.toDoubleOrNull()
            return PaceSettings(
                retentionDays = num("pace.retentionDays")?.toInt()?.coerceAtLeast(0) ?: d.retentionDays,
                lookbackShortHours = num("pace.lookbackShortHours")?.takeIf { it > 0 } ?: d.lookbackShortHours,
                lookbackLongHours = num("pace.lookbackLongHours")?.takeIf { it > 0 } ?: d.lookbackLongHours,
                deadBand = num("pace.deadBand")?.takeIf { it >= 0 } ?: d.deadBand,
                minSamples = num("pace.minSamples")?.toInt()?.coerceAtLeast(2) ?: d.minSamples,
                minSpanHours = num("pace.minSpanHours")?.takeIf { it >= 0 } ?: d.minSpanHours,
                burnWindowHours = num("pace.burnWindowHours")?.takeIf { it > 0 } ?: d.burnWindowHours,
            )
        }
    }
}

enum class Momentum { RISING, FLAT, FALLING, UNKNOWN }

/** One look-back's slopes (points per hour) and the momentum they read as. [samples] = how many points the fit used. */
data class MomentumReading(
    val lookbackHours: Double,
    val samples: Int,
    val paceDeltaSlope: Double?,
    val projectedSlope: Double?,
    /** Of the projected landing: where you will end up is the question the owner asks. UNKNOWN = not enough data. */
    val momentum: Momentum,
)

/** A burn rate's landing at reset and when it crosses 100%. [hoursToCross] null = it never does before reset. */
data class RateLine(
    val label: String,
    val ratePerHour: Double,
    val landingPercent: Double,
    val hoursToCross: Double?,
    val staysUnder: Boolean,
)

/**
 * The forecast of the active window: [ready] false = "not enough data yet" ([reason] says what is missing).
 * [current] = used percent extrapolated at the average burn since the window start; [recent] = at the burn of the last
 * [PaceSettings.lookbackShortHours]. [bandLow]/[bandHigh] are the two landings, the shaded band of the chart.
 */
data class Forecast(
    val ready: Boolean,
    val reason: String?,
    val current: RateLine?,
    val recent: RateLine?,
    val hoursLeft: Double?,
) {
    val bandLow: Double? get() = listOfNotNull(current?.landingPercent, recent?.landingPercent).minOrNull()
    val bandHigh: Double? get() = listOfNotNull(current?.landingPercent, recent?.landingPercent).maxOrNull()
}

object PaceMath {
    const val HOUR_MS = 3_600_000.0

    /** resetsAt moving by more than this is a new window (providers jitter their reset time by a few minutes). */
    const val RESET_TOLERANCE_MS = 30 * 60_000L

    /** A percent falling by more than this (points) inside one window is a reset the provider did not announce. */
    const val DROP_POINTS = 5.0

    /** Same floor as the pacing rule: the first minutes of a window do not divide by ~0. */
    const val MIN_ELAPSED = 0.10

    /**
     * Splits one series into windows (oldest first). A new one starts when resetsAt changes by more than [RESET_TOLERANCE_MS]
     * or the percent drops by more than [DROP_POINTS]; so the drop at a reset is a boundary, never a fall in momentum.
     */
    fun splitAtResets(series: List<PaceSample>): List<List<PaceSample>> {
        if (series.isEmpty()) return emptyList()
        val out = mutableListOf<List<PaceSample>>()
        var cur = mutableListOf<PaceSample>()
        var prev: PaceSample? = null
        for (s in series.sortedBy { it.observedAt }) {
            val p = prev
            if (p != null) {
                val pr = p.resetsAt
                val sr = s.resetsAt
                val resetMoved = pr != null && sr != null && kotlin.math.abs(sr - pr) > RESET_TOLERANCE_MS
                val dropped = p.percent - s.percent > DROP_POINTS
                if (resetMoved || dropped) { out += cur; cur = mutableListOf() }
            }
            cur += s
            prev = s
        }
        out += cur
        return out
    }

    /** The active window: the last segment of [series]. */
    fun active(series: List<PaceSample>): List<PaceSample> = splitAtResets(series).lastOrNull().orEmpty()

    /** Least-squares slope of y against time in hours (points per hour); null with fewer than 2 points or no time spread. */
    fun slope(points: List<Pair<Long, Double>>): Double? {
        if (points.size < 2) return null
        val t0 = points.first().first
        val xs = points.map { (it.first - t0) / HOUR_MS }
        val mx = xs.average()
        val my = points.map { it.second }.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx < 1e-12) return null
        val sxy = xs.indices.sumOf { (xs[it] - mx) * (points[it].second - my) }
        return sxy / sxx
    }

    /**
     * Burn rate over time for ONE window segment (oldest first): at each sample, (percent now - percent one rolling window ago)
     * / hours between them, in points per hour. The reference is the newest sample at or before `t - windowHours`; a sample
     * with no such reference yet has no burn rate. Idle usage reads 0.
     */
    fun burnRates(segment: List<PaceSample>, windowHours: Double): List<Pair<PaceSample, Double>> {
        val out = mutableListOf<Pair<PaceSample, Double>>()
        val cutoffMs = (windowHours * HOUR_MS).toLong()
        for (p in segment) {
            val ref = segment.lastOrNull { it.observedAt <= p.observedAt - cutoffMs } ?: continue
            val hours = (p.observedAt - ref.observedAt) / HOUR_MS
            if (hours > 0) out += p to (p.percent - ref.percent) / hours
        }
        return out
    }

    /** Points of [segment] observed in the last [hours] before [now]. */
    private fun within(segment: List<PaceSample>, now: Long, hours: Double) =
        segment.filter { it.observedAt >= now - (hours * HOUR_MS).toLong() && it.observedAt <= now }

    private fun classify(slope: Double?, deadBand: Double) = when {
        slope == null -> Momentum.UNKNOWN
        slope > deadBand -> Momentum.RISING
        slope < -deadBand -> Momentum.FALLING
        else -> Momentum.FLAT
    }

    /** Whether [pts] are enough (count and time span) for a trend or forecast. */
    fun enough(pts: List<PaceSample>, s: PaceSettings): Boolean =
        pts.size >= s.minSamples && (pts.last().observedAt - pts.first().observedAt) >= s.minSpanHours * HOUR_MS - 1

    /** Momentum over one look-back of the ACTIVE window only (a reset boundary is never inside the fit). */
    fun momentum(series: List<PaceSample>, now: Long, lookbackHours: Double, s: PaceSettings = PaceSettings()): MomentumReading {
        val pts = within(active(series), now, lookbackHours)
        if (!enough(pts, s)) return MomentumReading(lookbackHours, pts.size, null, null, Momentum.UNKNOWN)
        val pace = slope(pts.mapNotNull { p -> p.paceDelta?.let { p.observedAt to it } })
        val proj = slope(pts.mapNotNull { p -> p.projectedPercent?.let { p.observedAt to it } })
        // The headline momentum follows the projected landing; without projections it follows the pace delta.
        return MomentumReading(lookbackHours, pts.size, pace, proj, classify(proj ?: pace, s.deadBand))
    }

    /**
     * The two forecast lines of the active window (see docs/PACING.md).
     *  - current rate: percent / elapsedFraction (floor [MIN_ELAPSED]), i.e. the average burn since the window start.
     *  - recent rate: percent + max(slope of percent over the short look-back, 0) * hoursLeft.
     * `hoursToCross` = (100 - percent) / rate when the landing is above 100 (0 when already at or over 100).
     */
    fun forecast(series: List<PaceSample>, windowMinutes: Int, now: Long, s: PaceSettings = PaceSettings()): Forecast {
        val seg = active(series)
        val last = seg.lastOrNull() ?: return Forecast(false, "no samples yet", null, null, null)
        val resets = last.resetsAt ?: return Forecast(false, "the provider gave no reset time", null, null, null)
        if (windowMinutes <= 0) return Forecast(false, "unknown window length", null, null, null)
        val pts = within(seg, now, s.lookbackShortHours)
        if (!enough(pts, s)) {
            return Forecast(false, "not enough data yet (needs ${s.minSamples} samples over ${fmt(s.minSpanHours)}h, has ${pts.size})", null, null, null)
        }
        val windowMs = windowMinutes * 60_000.0
        val hoursLeft = ((resets - now) / HOUR_MS).coerceAtLeast(0.0)
        val elapsed = (1.0 - (resets - now) / windowMs).coerceIn(MIN_ELAPSED, 1.0)
        val percent = last.percent
        val avgRate = percent / (elapsed * windowMs / HOUR_MS)
        val current = line("current rate", percent, avgRate, percent / elapsed, hoursLeft)
        val recentRate = (slope(pts.map { it.observedAt to it.percent }) ?: 0.0).coerceAtLeast(0.0)
        val recent = line("last ${fmt(s.lookbackShortHours)}h rate", percent, recentRate, percent + recentRate * hoursLeft, hoursLeft)
        return Forecast(true, null, current, recent, hoursLeft)
    }

    private fun line(label: String, percent: Double, rate: Double, landing: Double, @Suppress("UNUSED_PARAMETER") hoursLeft: Double): RateLine {
        val cross = when {
            percent >= 100.0 -> 0.0
            landing > 100.0 && rate > 0 -> (100.0 - percent) / rate
            else -> null
        }
        return RateLine(label, rate, landing, cross, cross == null)
    }

    /** The one-line label: 'at the current rate you land at 107%; at the last 6h rate, 96% (falling)'. */
    fun sentence(f: Forecast, momentum: Momentum): String {
        val c = f.current
        val r = f.recent
        if (!f.ready || c == null || r == null) return f.reason ?: "not enough data yet"
        val m = momentum.takeIf { it != Momentum.UNKNOWN }?.name?.lowercase()?.let { " ($it)" } ?: ""
        val crossC = c.hoursToCross
        val crossR = r.hoursToCross
        val cross = when {
            crossR != null && crossC != null -> "; you cross 100% in ${fmt(minOf(crossR, crossC))}h"
            crossR != null -> "; at the recent rate you cross 100% in ${fmt(crossR)}h"
            crossC != null -> "; at the current rate you cross 100% in ${fmt(crossC)}h"
            else -> "; stays under 100%"
        }
        return "at the current rate you land at ${fmt0(c.landingPercent)}%; at the ${r.label.removeSuffix(" rate")} rate, ${fmt0(r.landingPercent)}%$m$cross"
    }

    fun fmt(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else "%.1f".format(d)
    fun fmt0(d: Double): String = Math.round(d).toString()
}
