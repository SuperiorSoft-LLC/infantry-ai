package com.superiorsoftllc.agenthq.plugins.official.pace.daemon

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * USAGE-FIT: what drives the plan meter. Pure computation over two read-only inputs: the pace samples (every percent step of a
 * window, [PaceSample]) and the recorded model requests ([FitRequest]). No environment-specific text, no model calls, no writes.
 *
 * A STEP is a sample whose percent is above the previous step's (inside one window; a reset starts a new one). The INTERVAL of a
 * step runs from the previous step's time to this step's time: the meter lags, so only whole between-step intervals are used,
 * never the minutes before a step. The tokens of the requests inside the interval, divided by the points the step gained, are
 * the tokens per point of that step.
 */

/** One recorded model request already narrowed to the meter being analysed. Null token fields are counted as 0 (output is not used). */
data class FitRequest(val ts: Long, val role: String, val model: String, val input: Long, val cacheRead: Long, val cacheWrite: Long)

/** One between-step interval: [points] gained, and what was spent in (fromMs, toMs]. */
class FitStep(
    val fromMs: Long, val toMs: Long, val points: Double,
    val requests: Int, val input: Long, val cacheRead: Long, val cacheWrite: Long,
    /** Cache-read tokens by role in this interval. */
    val cacheReadByRole: Map<String, Long>,
    /** Cache-read tokens by model in this interval. */
    val cacheReadByModel: Map<String, Long>,
) {
    val hours: Double get() = (toMs - fromMs) / 3_600_000.0
}

/** Mean, spread (sample standard deviation), median. */
data class Spread(val mean: Double, val sd: Double, val median: Double)

/** [requests]/[input]/[cacheRead]/[cacheWrite] count only requests inside a rising step; the total* fields count every recorded request over the whole analysed window. */
data class GroupRow(
    val role: String, val model: String, val requests: Int, val input: Long, val cacheRead: Long, val cacheWrite: Long, val share: Double,
    val totalRequests: Int = requests, val totalInput: Long = input, val totalCacheRead: Long = cacheRead, val totalCacheWrite: Long = cacheWrite,
)

data class Candidate(val key: String, val label: String, val columns: Int, val r2: Double, val adjR2: Double)

data class FitResult(
    val n: Int,
    val best: Candidate,
    val candidates: List<Candidate>,
    /** The tokens per point of each column of the best fit (1 / coefficient), by column label; null when the coefficient is not positive. */
    val tokensPerPoint: List<Pair<String, Double?>>,
    val r2: Double,
    /** Points per hour of usage the fit could not attribute to any request (account-wide, unseen); null when the fit has no such part. */
    val backgroundPerHour: Double?,
    val backgroundShare: Double?,
    val residualSd: Double,
    val fitted: List<Double>,
)

data class WindowFit(
    val kind: String, val label: String, val steps: List<FitStep>, val groups: List<GroupRow>, val fit: FitResult?,
    val perPointCacheRead: Spread?, val perPointRequests: Spread?, val perPointInput: Spread?, val perPointCacheWrite: Spread?,
    val pointsTotal: Double, val cacheReadPerPoint: Double?, val requestsPerPoint: Double?,
)

object UsageFit {
    const val MIN_FIT_STEPS = 5
    const val MAX_SERIES = 400
    private const val RESET_JUMP_MS = 5 * 60_000L

    /** Weekly and session windows of [provider] among [labels]: the label with the most samples of each kind. */
    fun kindOf(label: String): String? {
        val l = label.lowercase()
        return when {
            "week" in l -> "weekly"
            "5h" in l || "session" in l -> "session"
            else -> null
        }
    }

    /** The between-step intervals (from, to, points) of one window's samples, oldest first. */
    fun intervals(samples: List<PaceSample>): List<Triple<Long, Long, Double>> {
        val out = ArrayList<Triple<Long, Long, Double>>()
        var prev: PaceSample? = null
        for (s in samples.sortedBy { it.observedAt }) {
            val p = prev
            if (p == null) { prev = s; continue }
            val reset = p.resetsAt != null && s.resetsAt != null && abs(p.resetsAt - s.resetsAt) > RESET_JUMP_MS
            when {
                reset || s.percent < p.percent -> prev = s
                s.percent > p.percent -> { out += Triple(p.observedAt, s.observedAt, s.percent - p.percent); prev = s }
                // same percent: the step time stays the last step's, so the interval is whole.
            }
        }
        return out
    }

    /** Sums the requests of each interval. [requests] must be sorted by time. */
    fun steps(intervals: List<Triple<Long, Long, Double>>, requests: List<FitRequest>): List<FitStep> = intervals.map { (from, to, points) ->
        val inside = requests.filter { it.ts > from && it.ts <= to }
        FitStep(
            from, to, points, inside.size, inside.sumOf { it.input }, inside.sumOf { it.cacheRead }, inside.sumOf { it.cacheWrite },
            inside.groupBy { it.role }.mapValues { e -> e.value.sumOf { it.cacheRead } },
            inside.groupBy { it.model }.mapValues { e -> e.value.sumOf { it.cacheRead } },
        )
    }

    fun spread(values: List<Double>): Spread? {
        if (values.isEmpty()) return null
        val mean = values.average()
        val sd = if (values.size < 2) 0.0 else sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
        val sorted = values.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        return Spread(mean, sd, median)
    }

    /** Least squares y ~ sum(b_j * col_j), no intercept. Columns are scaled for stability. Null when singular. */
    fun leastSquares(cols: List<DoubleArray>, y: DoubleArray): DoubleArray? {
        val k = cols.size
        if (k == 0 || y.size < k) return null
        val scale = DoubleArray(k) { j -> (cols[j].maxOfOrNull { abs(it) } ?: 0.0).takeIf { it > 0 } ?: return null }
        val a = Array(k) { DoubleArray(k + 1) }
        for (i in 0 until k) {
            for (j in 0 until k) a[i][j] = y.indices.sumOf { r -> (cols[i][r] / scale[i]) * (cols[j][r] / scale[j]) }
            a[i][k] = y.indices.sumOf { r -> (cols[i][r] / scale[i]) * y[r] }
        }
        for (c in 0 until k) {
            val piv = (c until k).maxByOrNull { abs(a[it][c]) }!!
            if (abs(a[piv][c]) < 1e-12) return null
            val t = a[c]; a[c] = a[piv]; a[piv] = t
            for (r in 0 until k) if (r != c) {
                val f = a[r][c] / a[c][c]
                for (j in c..k) a[r][j] -= f * a[c][j]
            }
        }
        return DoubleArray(k) { a[it][k] / a[it][it] / scale[it] }
    }

    private fun r2(y: DoubleArray, fitted: DoubleArray): Double? {
        val mean = y.average()
        val tot = y.sumOf { (it - mean) * (it - mean) }
        if (tot <= 0.0) return null
        val res = y.indices.sumOf { (y[it] - fitted[it]) * (y[it] - fitted[it]) }
        return 1.0 - res / tot
    }

    private class Fit(val key: String, val label: String, val names: List<String>, val cols: List<DoubleArray>)

    private fun evaluate(f: Fit, y: DoubleArray): Pair<Candidate, DoubleArray>? {
        val n = y.size
        if (n < f.cols.size + 2) return null
        val b = leastSquares(f.cols, y) ?: return null
        val fitted = DoubleArray(n) { r -> f.cols.indices.sumOf { j -> b[j] * f.cols[j][r] } }
        val r2 = r2(y, fitted) ?: return null
        val k = f.cols.size
        val adj = 1.0 - (1.0 - r2) * (n - 1) / (n - k)
        return Candidate(f.key, f.label, k, r2, adj) to b
    }

    /** Which token type and model best explains the points of each step; null with fewer than [MIN_FIT_STEPS] steps. */
    fun fit(steps: List<FitStep>): FitResult? {
        val n = steps.size
        if (n < MIN_FIT_STEPS) return null
        val y = DoubleArray(n) { steps[it].points }
        fun col(f: (FitStep) -> Double) = DoubleArray(n) { f(steps[it]) }
        val fits = ArrayList<Fit>()
        fits += Fit("cacheRead", "cache-read tokens", listOf("cache-read"), listOf(col { it.cacheRead.toDouble() }))
        fits += Fit("input", "fresh input tokens", listOf("input"), listOf(col { it.input.toDouble() }))
        fits += Fit("cacheWrite", "cache-write tokens", listOf("cache-write"), listOf(col { it.cacheWrite.toDouble() }))
        fits += Fit("requests", "number of requests", listOf("requests"), listOf(col { it.requests.toDouble() }))
        fits += Fit(
            "allTokens", "all three token types", listOf("input", "cache-read", "cache-write"),
            listOf(col { it.input.toDouble() }, col { it.cacheRead.toDouble() }, col { it.cacheWrite.toDouble() }),
        )
        val models = steps.flatMap { it.cacheReadByModel.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
            .entries.sortedByDescending { it.value }.filter { it.value > 0 }.take(4).map { it.key }
        if (models.size >= 2) {
            fits += Fit("cacheReadByModel", "cache-read tokens, split by model", models.map { "cache-read $it" }, models.map { m -> col { (it.cacheReadByModel[m] ?: 0L).toDouble() } })
        }
        val scored = fits.mapNotNull { f -> evaluate(f, y)?.let { f to it } }
        if (scored.isEmpty()) return null
        val (bestFit, bestEval) = scored.maxByOrNull { it.second.first.adjR2 }!!
        // Add "hours in the interval" as a column: the part of a point that no request explains (account-wide, unseen usage).
        val hoursCol = col { it.hours }
        val withBg = Fit(bestFit.key, bestFit.label, bestFit.names + "background", bestFit.cols + listOf(hoursCol))
        val bgEval = evaluate(withBg, y)
        val bgB = bgEval?.second
        val useBg = bgEval != null && bgB != null && bgB.last() > 0 && bgB.dropLast(1).all { it > 0 } && bgEval.first.adjR2 > bestEval.first.adjR2
        val chosenFit = if (useBg) withBg else bestFit
        val chosenEval = if (useBg) bgEval!! else bestEval
        val b = chosenEval.second
        val fitted = DoubleArray(n) { r -> chosenFit.cols.indices.sumOf { j -> b[j] * chosenFit.cols[j][r] } }
        val residuals = DoubleArray(n) { y[it] - fitted[it] }
        val residualSd = if (n < 2) 0.0 else sqrt(residuals.sumOf { it * it } / (n - 1))
        val bg = if (useBg) b.last() else null
        val total = y.sum()
        val bgShare = if (bg != null && total > 0) (bg * steps.sumOf { it.hours } / total).coerceIn(0.0, 1.0) else null
        val perPoint = chosenFit.names.mapIndexed { j, name -> name to (if (name == "background") null else b[j].takeIf { it > 0 }?.let { 1.0 / it }) }
        return FitResult(
            n, chosenEval.first, scored.map { it.second.first }.sortedByDescending { it.adjR2 }, perPoint, chosenEval.first.r2, bg, bgShare, residualSd, fitted.toList(),
        )
    }

    /** The model family (opus / sonnet / haiku) found as a token in a model id or alias; an unknown model is kept as-is. */
    fun modelFamily(model: String): String {
        val tokens = model.lowercase().split(Regex("[^a-z0-9]+"))
        return listOf("opus", "sonnet", "haiku").firstOrNull { it in tokens } ?: model
    }

    /** The whole analysis of one window kind. */
    fun analyse(kind: String, label: String, samples: List<PaceSample>, requests: List<FitRequest>): WindowFit {
        val steps = steps(intervals(samples), requests.sortedBy { it.ts })
        val inWindow = requests.filter { r -> steps.any { r.ts > it.fromMs && r.ts <= it.toMs } }
        val totalCr = inWindow.sumOf { it.cacheRead }.toDouble()
        // The whole analysed span (first step start .. last step end): every recorded request in it, in or out of a rising step.
        val spanFrom = steps.minOfOrNull { it.fromMs }; val spanTo = steps.maxOfOrNull { it.toMs }?.let { maxOf(it, samples.maxOfOrNull { s -> s.observedAt } ?: it) }
        val inSpan = if (spanFrom == null || spanTo == null) emptyList() else requests.filter { it.ts > spanFrom && it.ts <= spanTo }
        val steppedBy = inWindow.groupBy { it.role to modelFamily(it.model) }
        val groups = inSpan.groupBy { it.role to modelFamily(it.model) }.map { (k, all) ->
            val v = steppedBy[k].orEmpty()
            GroupRow(
                k.first, k.second, v.size, v.sumOf { it.input }, v.sumOf { it.cacheRead }, v.sumOf { it.cacheWrite }, if (totalCr > 0) v.sumOf { it.cacheRead } / totalCr else 0.0,
                all.size, all.sumOf { it.input }, all.sumOf { it.cacheRead }, all.sumOf { it.cacheWrite },
            )
        }.sortedWith(compareByDescending<GroupRow> { it.cacheRead }.thenByDescending { it.totalCacheRead })
        val points = steps.sumOf { it.points }
        return WindowFit(
            kind, label, steps, groups, fit(steps),
            spread(steps.map { it.cacheRead / it.points }), spread(steps.map { it.requests / it.points }),
            spread(steps.map { it.input / it.points }), spread(steps.map { it.cacheWrite / it.points }),
            points, if (points > 0) steps.sumOf { it.cacheRead } / points else null, if (points > 0) steps.sumOf { it.requests } / points else null,
        )
    }

    // ---- JSON ----------------------------------------------------------------------------------------------------

    private fun num(v: Double?): JsonElement = if (v == null || v.isNaN() || v.isInfinite()) JsonNull else JsonPrimitive(v)

    private fun spreadJson(s: Spread?): JsonElement = if (s == null) JsonNull else buildJsonObject { put("mean", s.mean); put("sd", s.sd); put("median", s.median) }

    fun toJson(w: WindowFit, minSteps: Int): JsonObject = buildJsonObject {
        put("kind", w.kind); put("label", w.label); put("steps", w.steps.size); put("minSteps", minSteps)
        put("enough", w.steps.size >= minSteps)
        put("pointsTotal", w.pointsTotal)
        put("cacheReadPerPoint", num(w.cacheReadPerPoint)); put("requestsPerPoint", num(w.requestsPerPoint))
        put("perPoint", buildJsonObject {
            put("cacheRead", spreadJson(w.perPointCacheRead)); put("requests", spreadJson(w.perPointRequests))
            put("input", spreadJson(w.perPointInput)); put("cacheWrite", spreadJson(w.perPointCacheWrite))
        })
        put("groups", buildJsonArray {
            w.groups.forEach { g ->
                add(buildJsonObject {
                    put("role", g.role); put("model", g.model); put("requests", g.requests); put("input", g.input)
                    put("cacheRead", g.cacheRead); put("cacheWrite", g.cacheWrite); put("share", g.share)
                    put("totalRequests", g.totalRequests); put("totalInput", g.totalInput); put("totalCacheRead", g.totalCacheRead); put("totalCacheWrite", g.totalCacheWrite)
                })
            }
        })
        val shown = w.steps.takeLast(MAX_SERIES)
        val offset = w.steps.size - shown.size
        put("series", buildJsonArray {
            shown.forEachIndexed { i, s ->
                add(buildJsonObject {
                    put("t", s.toMs); put("from", s.fromMs); put("points", s.points); put("requests", s.requests)
                    put("input", s.input); put("cacheRead", s.cacheRead); put("cacheWrite", s.cacheWrite)
                    put("cacheReadPerPoint", s.cacheRead / s.points)
                    put("byRole", buildJsonObject { s.cacheReadByRole.forEach { (r, v) -> put(r, v) } })
                    put("fitted", w.fit?.fitted?.getOrNull(offset + i)?.let { num(it) } ?: JsonNull)
                })
            }
        })
        put("fit", w.fit?.let { f ->
            buildJsonObject {
                put("n", f.n); put("r2", f.r2); put("residualSd", f.residualSd)
                put("best", buildJsonObject { put("key", f.best.key); put("label", f.best.label) })
                put("backgroundPerHour", num(f.backgroundPerHour)); put("backgroundShare", num(f.backgroundShare))
                put("candidates", buildJsonArray {
                    f.candidates.forEach { c -> add(buildJsonObject { put("key", c.key); put("label", c.label); put("columns", c.columns); put("r2", c.r2); put("adjR2", c.adjR2) }) }
                })
                put("tokensPerPoint", buildJsonArray {
                    f.tokensPerPoint.forEach { (name, v) -> add(buildJsonObject { put("name", name); put("value", num(v)) }) }
                })
            }
        } ?: JsonNull)
    }

    /** The `usagefit` answer: one entry per window kind found for [provider]. */
    fun report(provider: String, store: PaceStore, requests: List<FitRequest>, minSteps: Int, now: Long): JsonObject {
        val keys = store.keys().filter { it.first == provider }
        val windows = ArrayList<JsonElement>()
        for (kind in listOf("weekly", "session")) {
            val key = keys.filter { kindOf(it.second) == kind }.maxByOrNull { store.samples(it.first, it.second).size } ?: continue
            windows += toJson(analyse(kind, key.second, store.samples(key.first, key.second), requests), minSteps)
        }
        return buildJsonObject {
            put("generatedAt", now); put("provider", provider); put("windows", JsonArray(windows))
        }
    }
}

/**
 * Reads the requests (read-only, through `Meters.requests`) and answers the analysis; the answer is kept 30 s and dropped when a new
 * pace sample arrives, so opening the section again is cheap and a new step shows at once.
 */
class UsageFitService(
    private val store: PaceStore,
    private val meters: com.superiorsoftllc.agenthq.daemon.api.usage.Meters,
    private val minSteps: () -> Int,
) {
    private var cached: Triple<String, Pair<Int, Long>, JsonObject>? = null

    @Synchronized
    fun answer(provider: String, now: Long): JsonObject {
        val stamp = store.count() to now / TTL_MS
        cached?.let { if (it.first == provider && it.second == stamp) return it.third }
        val since = now - LOOKBACK_DAYS * 86_400_000L
        val requests = meters.requests(since).filter { com.superiorsoftllc.agenthq.daemon.api.usage.UsageMeters.meterFor(it.harness, it.model) == provider }.map {
            FitRequest(it.ts, it.role, it.model ?: "unknown", it.input ?: 0, it.cacheRead ?: 0, it.cacheWrite ?: 0)
        }
        val out = UsageFit.report(provider, store, requests, minSteps(), now)
        cached = Triple(provider, stamp, out)
        return out
    }

    companion object {
        const val DEFAULT_MIN_STEPS = 8
        const val LOOKBACK_DAYS = 90L
        const val TTL_MS = 30_000L
    }
}
