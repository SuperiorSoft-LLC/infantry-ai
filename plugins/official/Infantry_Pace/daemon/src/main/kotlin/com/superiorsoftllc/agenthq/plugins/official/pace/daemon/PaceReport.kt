package com.superiorsoftllc.agenthq.plugins.official.pace.daemon

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a `pace_get` frame / `agenthq_pace` call asks for; every field optional (blank = all). */
data class PaceQuery(
    val provider: String? = null,
    val window: String? = null,
    /** Epoch millis; 0 = from the start of the history. */
    val sinceMs: Long = 0,
    /** Most points per series in the answer (the newest kept, evenly thinned). */
    val maxPoints: Int = 400,
    /** TOKEN-9: include the raw rows (newest RAW_CAP); default off. */
    val includeSamples: Boolean = false,
)

const val RAW_CAP = 200

/**
 * PACE-TREND-1: turns the stored samples into the `pace_report` answer (the JSON the chart draws and `agenthq_pace` prints):
 * per provider window the points (with the window segment each belongs to, so a reset breaks the line), the latest reading,
 * the momentum over both look-backs and the forecast. Provider-generic: nothing here names a provider.
 */
object PaceReport {
    fun build(store: PaceStore, settings: PaceSettings, query: PaceQuery, now: Long): JsonObject {
        val series = store.keys()
            .filter { (p, w) -> (query.provider.isNullOrBlank() || p.equals(query.provider, true)) && (query.window.isNullOrBlank() || w.equals(query.window, true)) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { (p, w) -> one(store, settings, p, w, query, now) }
        return buildJsonObject {
            put("generatedAt", now)
            put("retentionDays", settings.retentionDays)
            put("lookbackShortHours", settings.lookbackShortHours)
            put("lookbackLongHours", settings.lookbackLongHours)
            put("burnWindowHours", settings.burnWindowHours)
            put("deadBand", settings.deadBand)
            put("series", JsonArray(series.map { it.first }))
            put("text", series.joinToString("\n") { it.second }.ifBlank { "no pacing history recorded yet (the recorder samples the plan meters while this plugin is enabled)" })
        }
    }

    private fun one(store: PaceStore, s: PaceSettings, provider: String, window: String, q: PaceQuery, now: Long): Pair<JsonObject, String> {
        val all = store.samples(provider, window)
        val shown = all.filter { it.observedAt >= q.sinceMs }
        val latest = all.lastOrNull()
        val minutes = all.lastOrNull { it.windowMinutes > 0 }?.windowMinutes ?: 0
        val short = PaceMath.momentum(all, now, s.lookbackShortHours, s)
        val long = PaceMath.momentum(all, now, s.lookbackLongHours, s)
        val headline = if (short.momentum != Momentum.UNKNOWN) short.momentum else long.momentum
        val forecast = PaceMath.forecast(all, minutes, now, s)
        val sentence = PaceMath.sentence(forecast, headline)
        // Segment ids over the WHOLE history so they stay stable whatever the range asked for.
        val segmentOf = HashMap<PaceSample, Int>()
        PaceMath.splitAtResets(all).forEachIndexed { i, seg -> seg.forEach { segmentOf[it] = i } }
        val burnOf = HashMap<PaceSample, Double>()
        PaceMath.splitAtResets(all).forEach { seg -> PaceMath.burnRates(seg, s.burnWindowHours).forEach { (p, b) -> burnOf[p] = b } }
        val thinned = thin(shown, q.maxPoints)
        val obj = buildJsonObject {
            put("provider", provider); put("window", window); put("windowMinutes", minutes)
            put("samples", all.size)
            put("resetsAt", latest?.resetsAt?.let { JsonPrimitive(it) } ?: JsonNull)
            put("latest", latest?.let { l ->
                buildJsonObject {
                    put("t", l.observedAt); put("percent", l.percent)
                    put("paceDelta", l.paceDelta?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("projectedPercent", l.projectedPercent?.let { JsonPrimitive(it) } ?: JsonNull)
                }
            } ?: JsonNull)
            put("points", buildJsonArray {
                for (p in thinned) add(buildJsonObject {
                    put("t", p.observedAt); put("u", p.percent)
                    put("d", p.paceDelta?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("j", p.projectedPercent?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("s", segmentOf[p] ?: 0)
                    put("b", burnOf[p]?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            })
            if (q.includeSamples) put("rawSamples", buildJsonArray {
                for (p in shown.takeLast(RAW_CAP)) add(buildJsonObject {
                    put("t", p.observedAt); put("percent", p.percent)
                    put("prev", p.prevPercent?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("delta", p.deltaPercent?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("tokens", buildJsonArray {
                        p.tokens.forEach { t ->
                            add(buildJsonObject {
                                put("session", t.sessionId); put("role", t.role)
                                put("input", t.input?.let { JsonPrimitive(it) } ?: JsonNull); put("output", t.output?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("cacheRead", t.cacheRead?.let { JsonPrimitive(it) } ?: JsonNull); put("cacheWrite", t.cacheWrite?.let { JsonPrimitive(it) } ?: JsonNull)
                            })
                        }
                    })
                })
            })
            put("momentum", headline.name)
            put("momentumShort", momentumJson(short))
            put("momentumLong", momentumJson(long))
            put("forecast", forecastJson(forecast, sentence))
        }
        val head = "${provider} / ${window}: ${latest?.let { "${PaceMath.fmt(it.percent)}% used" } ?: "no reading"}" +
            (latest?.paceDelta?.let { ", ${if (it >= 0) "+" else ""}${PaceMath.fmt(it)} vs even pace" } ?: "") +
            ", momentum ${headline.name} (${all.size} samples)\n    $sentence"
        return obj to head
    }

    private fun momentumJson(m: MomentumReading) = buildJsonObject {
        put("lookbackHours", m.lookbackHours); put("samples", m.samples)
        put("paceDeltaSlope", m.paceDeltaSlope?.let { JsonPrimitive(it) } ?: JsonNull)
        put("projectedSlope", m.projectedSlope?.let { JsonPrimitive(it) } ?: JsonNull)
        put("momentum", m.momentum.name)
    }

    private fun lineJson(l: RateLine?): kotlinx.serialization.json.JsonElement = if (l == null) JsonNull else buildJsonObject {
        put("label", l.label); put("ratePerHour", l.ratePerHour); put("landingPercent", l.landingPercent)
        put("hoursToCross", l.hoursToCross?.let { JsonPrimitive(it) } ?: JsonNull)
        put("staysUnder", l.staysUnder)
    }

    private fun forecastJson(f: Forecast, sentence: String) = buildJsonObject {
        put("ready", f.ready)
        put("reason", f.reason?.let { JsonPrimitive(it) } ?: JsonNull)
        put("sentence", sentence)
        put("hoursLeft", f.hoursLeft?.let { JsonPrimitive(it) } ?: JsonNull)
        put("current", lineJson(f.current)); put("recent", lineJson(f.recent))
        put("bandLow", f.bandLow?.let { JsonPrimitive(it) } ?: JsonNull)
        put("bandHigh", f.bandHigh?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    /** At most [max] points, evenly spaced, always keeping the first and the newest. */
    fun thin(points: List<PaceSample>, max: Int): List<PaceSample> {
        if (max < 2 || points.size <= max) return points
        val step = (points.size - 1).toDouble() / (max - 1)
        return (0 until max).map { points[Math.round(it * step).toInt().coerceAtMost(points.size - 1)] }.distinct()
    }
}
