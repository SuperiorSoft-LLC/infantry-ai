package com.superiorsoftllc.infantry.plugins.official.pace

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * PACE-TREND-1: the pacing chart as a MODEL (no Compose): the daemon's `pace_report` parsed into series, and each card's chart
 * (axis, ticks, lines, forecast, clipping) computed from it. The composables only draw a [ChartSpec]; the tests assert on it.
 * Provider-generic: nothing here names a harness.
 */

enum class Trend(val word: String, val arrow: String) {
    RISING("Rising", "\u25B2"), FLAT("Flat", "\u25AC"), FALLING("Falling", "\u25BC"), UNKNOWN("Not enough data", "\u2022");

    /** Arrow plus word, never colour alone. */
    val label: String get() = "$arrow $word"

    companion object { fun of(name: String?): Trend = entries.firstOrNull { it.name == name } ?: UNKNOWN }
}

data class PacePoint(val t: Long, val used: Double, val paceDelta: Double?, val projected: Double?, val segment: Int, val burn: Double? = null)

data class RateView(val label: String, val landing: Double, val hoursToCross: Double?)

data class ForecastView(
    val ready: Boolean,
    val reason: String?,
    val sentence: String,
    val hoursLeft: Double?,
    val current: RateView?,
    val recent: RateView?,
)

data class PaceSeries(
    val provider: String,
    val window: String,
    val windowMinutes: Int,
    val resetsAt: Long?,
    val samples: Int,
    val points: List<PacePoint>,
    val trend: Trend,
    val forecast: ForecastView,
) {
    val key: String get() = "$provider/$window"
    val latest: PacePoint? get() = points.lastOrNull()
}

data class PaceReportView(val generatedAt: Long, val retentionDays: Int, val series: List<PaceSeries>)

object PaceReportParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Null = not a pace report (an older daemon, a torn frame). */
    fun parse(text: String?): PaceReportView? {
        if (text.isNullOrBlank()) return null
        return try {
            val o = json.parseToJsonElement(text).jsonObject
            val series = (o["series"] as? JsonArray).orEmpty().map { series(it.jsonObject) }
            PaceReportView(o.long("generatedAt") ?: 0L, o.int("retentionDays") ?: 60, series)
        } catch (e: Exception) {
            null
        }
    }

    private fun series(o: JsonObject): PaceSeries = PaceSeries(
        provider = o.str("provider").orEmpty(), window = o.str("window").orEmpty(), windowMinutes = o.int("windowMinutes") ?: 0,
        resetsAt = o.long("resetsAt"), samples = o.int("samples") ?: 0,
        points = (o["points"] as? JsonArray).orEmpty().map { p ->
            val po = p.jsonObject
            PacePoint(po.long("t") ?: 0L, po.dbl("u") ?: 0.0, po.dbl("d"), po.dbl("j"), po.int("s") ?: 0, po.dbl("b"))
        },
        trend = Trend.of(o.str("momentum")),
        forecast = (o["forecast"] as? JsonObject)?.let(::forecast) ?: ForecastView(false, "no forecast", "not enough data yet", null, null, null),
    )

    private fun forecast(o: JsonObject) = ForecastView(
        ready = (o["ready"] as? JsonPrimitive)?.booleanOrNull ?: false, reason = o.str("reason"), sentence = o.str("sentence").orEmpty(),
        hoursLeft = o.dbl("hoursLeft"), current = (o["current"] as? JsonObject)?.let(::rate), recent = (o["recent"] as? JsonObject)?.let(::rate),
    )

    private fun rate(o: JsonObject) = RateView(o.str("label").orEmpty(), o.dbl("landingPercent") ?: 0.0, o.dbl("hoursToCross"))

    private fun JsonObject.prim(k: String): JsonPrimitive? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonObject.str(k: String) = prim(k)?.contentOrNull
    private fun JsonObject.dbl(k: String) = prim(k)?.doubleOrNull
    private fun JsonObject.long(k: String) = prim(k)?.longOrNull
    private fun JsonObject.int(k: String) = prim(k)?.intOrNull
    private fun JsonElement?.orEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
}

/** The range buttons: the last 6 hours, 24 hours, 7 days, or the whole current window. */
enum class PaceRange(val label: String, val hours: Double?) {
    H6("6h", 6.0), H24("24h", 24.0), D7("7d", 168.0), WINDOW("Window", null);

    /** The forecast runs to the reset time only where the range is wide enough to show it. */
    val showsForecast: Boolean get() = this == D7 || this == WINDOW
}

/** The two things a card can plot against time. */
enum class PaceMetric(val label: String) {
    PACE("Pace delta"), USED("Used"),
    /** The second, small panel: points of percent used per hour over the rolling burn window (0 = idle). Not a toggle choice. */
    BURN("Burn rate");

    companion object { val toggle = listOf(PACE, USED) }
}

/** The scale buttons: Fit (default), a preset or Custom (a typed maximum). */
sealed interface PaceScale {
    object Fit : PaceScale
    /** Axis from the metric's floor to [max] (pace delta: -max to +max). */
    data class Fixed(val max: Double) : PaceScale
}

object PaceScales {
    fun presets(metric: PaceMetric): List<Double> = if (metric == PaceMetric.USED) listOf(50.0, 100.0, 150.0) else listOf(10.0, 25.0, 50.0)

    /** Parses a typed maximum ("120", "120%"); null = refused. */
    fun parseCustom(text: String): Double? = text.trim().removeSuffix("%").trim().toDoubleOrNull()?.takeIf { it in 1.0..1000.0 }
}

/** One drawn line: [pts] are (epoch millis, value). */
data class ChartLine(val pts: List<Pair<Long, Double>>)

data class ChartSpec(
    /** Under two samples (or no value to plot): the card says so instead of drawing. */
    val empty: Boolean,
    val emptyText: String?,
    val yMin: Double,
    val yMax: Double,
    val ticks: List<Double>,
    val t0: Long,
    val t1: Long,
    /** Actual readings, one line per window segment (a reset breaks the line). */
    val actual: List<ChartLine>,
    /** Dashed: the current-rate forecast to reset; dotted: the last-lookback-rate forecast. Null = not drawn. */
    val currentForecast: ChartLine?,
    val recentForecast: ChartLine?,
    /** The shaded band between the two forecast landings (its polygon corners are in [currentForecast]/[recentForecast]). */
    val hasBand: Boolean,
    /** The emphasised even-pace line (pace delta: 0). */
    val zeroLine: Boolean,
    /** The plan cap (used: 100) when it is inside the axis. */
    val capLine: Double?,
    /** A value is past the scale: [clipNote] says so in words. */
    val clipped: Boolean,
    val clipNote: String?,
    val yUnit: String,
) {
    fun y(v: Double): Double = ((v - yMin) / (yMax - yMin)).coerceIn(0.0, 1.0)
}

object PaceChartModel {
    /** Minimum samples inside the range to draw a line. */
    const val MIN_SAMPLES = 2

    private fun value(p: PacePoint, m: PaceMetric): Double? = when (m) { PaceMetric.USED -> p.used; PaceMetric.PACE -> p.paceDelta; PaceMetric.BURN -> p.burn }

    /** The points of [s] a [range] shows, as of [now]. WINDOW = the last segment. */
    fun visible(s: PaceSeries, range: PaceRange, now: Long): List<PacePoint> {
        val pts = s.points
        val hours = range.hours
        if (hours == null) {
            val last = pts.lastOrNull()?.segment ?: return emptyList()
            return pts.filter { it.segment == last }
        }
        val from = now - (hours * 3_600_000).toLong()
        return pts.filter { it.t >= from }
    }

    fun spec(s: PaceSeries, metric: PaceMetric, range: PaceRange, scale: PaceScale, now: Long): ChartSpec {
        val shown = visible(s, range, now).filter { value(it, metric) != null }
        val unit = when (metric) { PaceMetric.USED -> "%"; PaceMetric.PACE -> " pts"; PaceMetric.BURN -> " pts/h" }
        if (shown.size < MIN_SAMPLES) {
            return ChartSpec(
                empty = true, emptyText = "Not enough samples in this range yet (${shown.size} of $MIN_SAMPLES). The daemon records a reading about every 5 minutes while this plugin is on.",
                yMin = 0.0, yMax = 1.0, ticks = emptyList(), t0 = 0, t1 = 1, actual = emptyList(), currentForecast = null, recentForecast = null,
                hasBand = false, zeroLine = false, capLine = null, clipped = false, clipNote = null, yUnit = unit,
            )
        }
        val lines = shown.groupBy { it.segment }.values.map { seg -> ChartLine(seg.map { it.t to value(it, metric)!! }) }
        val last = shown.last()
        val lastV = value(last, metric)!!
        val resets = s.resetsAt
        val f = s.forecast
        var cur: ChartLine? = null
        var rec: ChartLine? = null
        var t1 = last.t
        if (metric != PaceMetric.BURN && range.showsForecast && f.ready && resets != null && resets > last.t) {
            // Pace delta at the reset = landing - 100: at the reset an even pace is 100% used.
            fun end(r: RateView?): Double? = r?.landing?.let { if (metric == PaceMetric.USED) it else it - 100.0 }
            cur = end(f.current)?.let { ChartLine(listOf(last.t to lastV, resets to it)) }
            rec = end(f.recent)?.let { ChartLine(listOf(last.t to lastV, resets to it)) }
            if (cur != null || rec != null) t1 = resets
        }
        val t0 = shown.first().t
        val all = shown.mapNotNull { value(it, metric) } + listOfNotNull(cur?.pts?.last()?.second, rec?.pts?.last()?.second)
        val dataMin = all.min()
        val dataMax = all.max()

        val yMin: Double
        val yMax: Double
        when (scale) {
            is PaceScale.Fixed -> if (metric == PaceMetric.USED) { yMin = 0.0; yMax = scale.max } else { yMin = -scale.max; yMax = scale.max }
            PaceScale.Fit -> {
                var lo = dataMin
                var hi = dataMax
                if (metric != PaceMetric.USED) { lo = min(lo, 0.0); hi = max(hi, 0.0) }
                else { if (hi >= 70.0) hi = max(hi, 100.0) }
                val span = max(hi - lo, when (metric) { PaceMetric.USED -> 4.0; PaceMetric.PACE -> 2.0; PaceMetric.BURN -> 0.5 })
                val pad = span * 0.08
                lo -= pad; hi = lo + span + 2 * pad
                if (metric == PaceMetric.USED) lo = max(lo, 0.0)
                yMin = lo; yMax = hi
            }
        }
        val ticks = niceTicks(yMin, yMax)
        // Under Fit the axis is the ticks' hull so labelled gridlines reach the plot edges.
        val fy0 = if (scale == PaceScale.Fit) min(yMin, ticks.first()) else yMin
        val fy1 = if (scale == PaceScale.Fit) max(yMax, ticks.last()) else yMax
        val clipped = all.any { it > fy1 + 1e-9 || it < fy0 - 1e-9 }
        val note = if (!clipped) null else {
            val over = all.max() > fy1 + 1e-9
            "over scale: line clipped at ${fmt(if (over) fy1 else fy0)}$unit"
        }
        val cap = if (metric == PaceMetric.USED && 100.0 > fy0 && 100.0 < fy1 + 1e-9) 100.0 else null
        return ChartSpec(
            false, null, fy0, fy1, ticks.filter { it >= fy0 - 1e-9 && it <= fy1 + 1e-9 }, t0, t1, lines, cur, rec,
            hasBand = cur != null && rec != null, zeroLine = metric != PaceMetric.USED && fy0 < 0 && fy1 > 0, capLine = cap,
            clipped = clipped, clipNote = note, yUnit = unit,
        )
    }

    /** 4-6 round tick values covering [lo]..[hi]. */
    fun niceTicks(lo: Double, hi: Double, target: Int = 6): List<Double> {
        val span = (hi - lo).coerceAtLeast(1e-6)
        val raw = span / target
        val mag = 10.0.pow(floor(kotlin.math.log10(raw)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw - 1e-12 }
        val first = ceil(lo / step - 1e-9) * step
        val out = mutableListOf<Double>()
        var v = first
        while (v <= hi + 1e-9 && out.size < 12) { out += (kotlin.math.round(v / step) * step + 0.0); v += step }
        return out.ifEmpty { listOf(lo, hi) }
    }

    /** The point of [spec]'s first actual line closest in time to [t] (hover / tap). */
    fun nearest(spec: ChartSpec, t: Long): Pair<Long, Double>? =
        spec.actual.flatMap { it.pts }.minByOrNull { abs(it.first - t) }

    fun fmt(v: Double): String = if (abs(v - kotlin.math.round(v)) < 0.05) kotlin.math.round(v).toLong().toString() else one(v)

    /** One decimal without String.format (not in common code on wasm). */
    fun one(v: Double): String {
        val r = kotlin.math.round(v * 10).toLong()
        return (if (r < 0) "-" else "") + (abs(r) / 10) + "." + (abs(r) % 10)
    }

    fun signed(v: Double): String = (if (v >= 0) "+" else "") + fmt(v)

    /** Axis / tooltip clock text: `14:05`, or `May 4 14:05` when [withDate]. */
    fun time(t: Long, withDate: Boolean, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val d = Instant.fromEpochMilliseconds(t).toLocalDateTime(zone)
        val hm = d.hour.toString().padStart(2, '0') + ":" + d.minute.toString().padStart(2, '0')
        if (!withDate) return hm
        return "${MONTHS[d.monthNumber - 1]} ${d.dayOfMonth} $hm"
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** The exact-value tooltip of one point. */
    fun tooltip(metric: PaceMetric, t: Long, v: Double, zone: TimeZone = TimeZone.currentSystemDefault()): String =
        (when (metric) { PaceMetric.USED -> "${fmt(v)}% used"; PaceMetric.PACE -> "${signed(v)} pts vs even pace"; PaceMetric.BURN -> "${signed(v)} pts per hour burn rate" }) + " at " + time(t, true, zone)

    /** Plain text of a card's header numbers: used, pace delta and the projection. */
    fun caption(s: PaceSeries): String {
        val l = s.latest ?: return "no reading yet"
        return buildString {
            append(fmt(l.used)).append("% of limit")
            l.paceDelta?.let { append(" \u00B7 ").append(signed(it)).append(" vs even pace") }
            l.projected?.let { append(" \u00B7 lands at ").append(fmt(it)).append("%") }
        }
    }
}
