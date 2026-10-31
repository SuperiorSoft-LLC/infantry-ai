package com.superiorsoftllc.agenthq.plugins.official.pace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/*
 * USAGE-FIT: the view model of the usage-fit section. Parses the daemon's `usagefit` answer (Infantry_Pace daemon half) and writes the
 * plain-language texts. Pure: no Compose, so the wording, the "not enough data" states and the numbers are unit-tested.
 */

data class FitSpread(val mean: Double, val sd: Double, val median: Double)

data class FitGroupView(val role: String, val model: String, val requests: Int, val input: Long, val cacheRead: Long, val cacheWrite: Long, val share: Double,
    val totalRequests: Int = requests, val totalCacheRead: Long = cacheRead, val output: Long = 0)

data class FitStepView(
    val t: Long, val points: Double, val requests: Int, val input: Long, val cacheRead: Long, val cacheWrite: Long,
    val cacheReadPerPoint: Double, val byRole: Map<String, Long>, val fitted: Double?,
    val output: Long = 0, val outputByRole: Map<String, Long> = emptyMap(),
)

data class FitCandidateView(val key: String, val label: String, val r2: Double)

data class FitInfoView(
    val n: Int, val r2: Double, val residualSd: Double, val bestKey: String, val bestLabel: String,
    val backgroundPerHour: Double?, val backgroundShare: Double?, val candidates: List<FitCandidateView>,
    val tokensPerPoint: List<Pair<String, Double?>>,
)

data class FitWindowView(
    val kind: String, val label: String, val steps: Int, val minSteps: Int, val enough: Boolean, val pointsTotal: Double,
    val cacheReadPerPoint: Double?, val requestsPerPoint: Double?, val cacheReadSpread: FitSpread?,
    val groups: List<FitGroupView>, val series: List<FitStepView>, val fit: FitInfoView?,
)

data class UsageFitView(val generatedAt: Long, val provider: String, val windows: List<FitWindowView>) {
    fun window(kind: String): FitWindowView? = windows.firstOrNull { it.kind == kind }
}

object UsageFitParser {
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonElement?.d(): Double? = (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.doubleOrNull
    private fun JsonElement?.l(): Long? = (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.longOrNull
    private fun JsonElement?.s(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.contentOrNull
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

    /** Null when the text is not a usage-fit answer. */
    fun parse(raw: String?): UsageFitView? = try {
        val o = json.parseToJsonElement(raw?.takeIf { it.isNotBlank() } ?: return null).jsonObject
        UsageFitView(
            o["generatedAt"].l() ?: 0L, o["provider"].s() ?: "claude",
            o["windows"].arr().mapNotNull { it.obj()?.let(::window) },
        )
    } catch (e: Exception) {
        null
    }

    private fun spread(e: JsonElement?): FitSpread? = e.obj()?.let { FitSpread(it["mean"].d() ?: return null, it["sd"].d() ?: 0.0, it["median"].d() ?: return null) }

    private fun window(o: JsonObject): FitWindowView = FitWindowView(
        kind = o["kind"].s() ?: "weekly", label = o["label"].s() ?: "", steps = o["steps"].l()?.toInt() ?: 0, minSteps = o["minSteps"].l()?.toInt() ?: 8,
        enough = (o["enough"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false, pointsTotal = o["pointsTotal"].d() ?: 0.0,
        cacheReadPerPoint = o["cacheReadPerPoint"].d(), requestsPerPoint = o["requestsPerPoint"].d(),
        cacheReadSpread = spread(o["perPoint"].obj()?.get("cacheRead")),
        groups = o["groups"].arr().mapNotNull { it.obj() }.map {
            FitGroupView(it["role"].s() ?: "?", it["model"].s() ?: "?", it["requests"].l()?.toInt() ?: 0, it["input"].l() ?: 0, it["cacheRead"].l() ?: 0, it["cacheWrite"].l() ?: 0, it["share"].d() ?: 0.0,
                it["totalRequests"].l()?.toInt() ?: (it["requests"].l()?.toInt() ?: 0), it["totalCacheRead"].l() ?: (it["cacheRead"].l() ?: 0), it["output"].l() ?: 0)
        },
        series = o["series"].arr().mapNotNull { it.obj() }.map { s ->
            FitStepView(
                s["t"].l() ?: 0, s["points"].d() ?: 0.0, s["requests"].l()?.toInt() ?: 0, s["input"].l() ?: 0, s["cacheRead"].l() ?: 0, s["cacheWrite"].l() ?: 0,
                s["cacheReadPerPoint"].d() ?: 0.0, s["byRole"].obj()?.mapValues { it.value.l() ?: 0L }.orEmpty(), s["fitted"].d(),
                s["output"].l() ?: 0, s["outputByRole"].obj()?.mapValues { it.value.l() ?: 0L }.orEmpty(),
            )
        },
        fit = o["fit"].obj()?.let { f ->
            FitInfoView(
                f["n"].l()?.toInt() ?: 0, f["r2"].d() ?: 0.0, f["residualSd"].d() ?: 0.0, f["best"].obj()?.get("key").s() ?: "", f["best"].obj()?.get("label").s() ?: "",
                f["backgroundPerHour"].d(), f["backgroundShare"].d(),
                f["candidates"].arr().mapNotNull { it.obj() }.map { FitCandidateView(it["key"].s() ?: "", it["label"].s() ?: "", it["r2"].d() ?: 0.0) },
                f["tokensPerPoint"].arr().mapNotNull { it.obj() }.map { (it["name"].s() ?: "?") to it["value"].d() },
            )
        },
    )
}

/** The words of the section. Every number gets a one-line plain explanation here, so the UI never shows a bare jargon term. */
object UsageFitText {
    private fun r0(v: Double): String = kotlin.math.round(v).toLong().toString()
    private fun one(v: Double): String = PaceChartModel.one(v)
    private fun two(v: Double): String { val r = kotlin.math.round(v * 100).toLong(); return (if (r < 0) "-" else "") + (kotlin.math.abs(r) / 100) + "." + (kotlin.math.abs(r) % 100).toString().padStart(2, '0') }

    /** 1_234_567 -> "1.2M", 45_600 -> "46k", 812 -> "812". */
    fun tokens(v: Double): String = when {
        v >= 1_000_000_000 -> one(v / 1_000_000_000) + "B"
        v >= 1_000_000 -> one(v / 1_000_000) + "M"
        v >= 10_000 -> r0(v / 1_000) + "k"
        v >= 1_000 -> one(v / 1_000) + "k"
        else -> r0(v)
    }
    fun tokens(v: Long): String = tokens(v.toDouble())

    fun percent(share: Double): String = r0(share * 100) + "%"

    fun kindName(kind: String) = if (kind == "weekly") "weekly" else "5-hour"

    /** "claude-sonnet-5-5" -> "Sonnet"; anything else is shown as it is. */
    fun modelName(model: String): String {
        val l = model.lowercase()
        return listOf("sonnet", "opus", "haiku").firstOrNull { it in l }?.replaceFirstChar { it.uppercase() } ?: model
    }

    /** What the section says before any number: not enough data, or the 1-2 sentence headline. */
    fun headline(w: FitWindowView): String {
        val cr = w.cacheReadPerPoint
        if (w.steps == 0 || cr == null) return "Not enough data yet: no ${kindName(w.kind)} meter step has been recorded together with token usage. Check back after the meter has moved a few points."
        val req = w.requestsPerPoint?.let { " (about ${r0(it)} requests)" }.orEmpty()
        val top = w.groups.firstOrNull()
        val who = top?.let { " ${it.role.replaceFirstChar { c -> c.uppercase() }} work on ${modelName(it.model)} is ${percent(it.share)} of it." }.orEmpty()
        return "One ${kindName(w.kind)} meter point costs about ${tokens(cr)} cache-read tokens$req.$who"
    }

    /** The confidence line, always shown. */
    fun confidence(w: FitWindowView): String {
        val base = "Based on ${w.steps} ${if (w.steps == 1) "step" else "steps"} (${r0(w.pointsTotal)} points)"
        val fit = w.fit?.let { " Fit quality R² = ${two(it.r2)} (1 is perfect, 0 is no better than a guess)." }.orEmpty()
        return if (w.enough) "$base.$fit" else "$base: not enough data yet, treat the numbers as rough (at least ${w.minSteps} steps make them reliable).$fit"
    }

    fun spreadLine(w: FitWindowView): String? = w.cacheReadSpread?.let {
        "Per step: average ${tokens(it.mean)}, typical (median) ${tokens(it.median)}, spread ±${tokens(it.sd)} tokens per point."
    }

    /** What the fit says drives a point, in a sentence; null when there is no fit. */
    fun fitLine(w: FitWindowView): String? {
        val f = w.fit ?: return if (w.steps > 0) "A fit needs at least ${MIN_FIT_STEPS} steps; there are ${w.steps}." else null
        val parts = f.tokensPerPoint.filter { it.second != null && !it.first.contains("background") }.joinToString(", ") { "${it.first}: ${tokens(it.second!!)} per point" }
        return "Best explanation: ${f.bestLabel}${if (parts.isNotEmpty()) " ($parts)" else ""}."
    }

    const val MIN_FIT_STEPS = 5

    /** The part no recorded request explains: usage the daemon cannot see (other devices, the web app, other tools). */
    fun unexplained(w: FitWindowView): String? {
        val f = w.fit ?: return null
        val bg = f.backgroundShare
        return if (bg != null) "About ${percent(bg)} of the points are not explained by any request the daemon recorded: account-wide use it cannot see."
        else "Unexplained by the fit: ${percent((1.0 - f.r2).coerceIn(0.0, 1.0))} of the step-to-step variation (the rest is usage the daemon cannot see, or noise)."
    }

    const val CACHE_READ_HELP = "cache-read = context the model re-reads on every request (the biggest part of the meter)"
    const val CACHE_WRITE_HELP = "cache-write = context stored so later requests can re-read it"
    const val INPUT_HELP = "input = fresh text sent to the model, not from the cache"
    const val REQUESTS_HELP = "request = one call to the model"
    const val POINT_HELP = "point = one percent step of the plan meter"
    const val SHARE_HELP = "share = this row's part of all cache-read tokens in the analysed steps"
}
