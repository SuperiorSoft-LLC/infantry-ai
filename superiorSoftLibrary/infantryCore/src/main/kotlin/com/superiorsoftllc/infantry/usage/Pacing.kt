package com.superiorsoftllc.infantry.usage

import com.superiorsoftllc.infantry.daemon.api.usage.*
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Builds the [UsageReport] and judges a start against it. Every input is a function read at call time, so a changed
 * setting or a fresh live read counts at once. [limits] must not block (the cached live read).
 */
class UsagePacing(
    private val limits: () -> List<ProviderLimits>,
    private val rule: () -> PacingRule,
    private val manual: () -> Map<String, ManualUsage>,
    private val overrides: () -> Map<String, UsageOverride>,
    /** The meter of every live ticket worker (one entry per worker). */
    private val workerMeters: () -> List<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun report(): UsageReport {
        val now = clock()
        val live = limits().associateBy { it.provider }
        val manualNow = manual().filterValues { it.expiresAt > now }
        val overridesNow = overrides().filterValues { it.until > now }
        val running = workerMeters().groupingBy { it }.eachCount()
        val currentRule = rule()
        val meters = (live.keys + manualNow.keys + overridesNow.keys + running.keys).distinct().sorted().map { meter ->
            meterUsage(meter, live[meter], manualNow[meter], overridesNow[meter], running[meter] ?: 0, currentRule, now)
        }
        return UsageReport(meters, currentRule, now)
    }

    /** Whether one more ticket worker may start on [meter] now. */
    fun check(meter: String): PacingVerdict {
        val m = report().meters.firstOrNull { it.meter == meter } ?: return PacingVerdict.Allow()
        if (m.canStart) return PacingVerdict.Allow(m.warning)
        return PacingVerdict.Over(refusal(m))
    }

    private fun meterUsage(
        meter: String,
        live: ProviderLimits?,
        manual: ManualUsage?,
        override: UsageOverride?,
        running: Int,
        rule: PacingRule,
        now: Long,
    ): MeterUsage {
        val windows = live?.windows.orEmpty()
        // The governing window is the one that allows the fewest workers (the tighter of the windows); ties: the higher percent.
        val judged = windows.filter { it.percent != null }.mapNotNull { w -> rule.judge(w, now)?.let { w to it } }
        val governing = judged.minWithOrNull(compareBy<Pair<LimitWindow, PacingRule.Judgement>>({ it.second.allowance }, { -(it.first.percent ?: 0.0) }))
        val top = governing?.first
        val judgement = governing?.second
        val source: String
        val percent: Double?
        val label: String?
        val resetsAt: Long?
        val pace: Double?
        when {
            top != null -> { source = "live"; percent = top.percent; label = top.label; resetsAt = top.resetsAt; pace = top.paceDelta }
            manual != null -> {
                source = "manual"; percent = manual.percent; label = "Manual entry"; resetsAt = manual.resetsAt; pace = null
            }
            else -> { source = "none"; percent = null; label = null; resetsAt = null; pace = null }
        }
        val ruleAllowance = if (!rule.enabled || percent == null) null else judgement?.allowance ?: rule.allowance(percent)
        val reasoning = if (ruleAllowance == null) null else judgement?.reason ?: "bands"
        val allowance = if (override != null) override.workers else ruleAllowance
        val warning = when {
            override != null -> null
            percent == null && rule.enabled -> "usage unknown${live?.reason?.let { " ($it)" } ?: ""}: pacing off for $meter (fail open)"
            else -> null
        }
        return MeterUsage(
            meter = meter, percent = percent, window = label, resetsAt = resetsAt, paceDelta = pace, source = source,
            windows = windows, reason = live?.reason, ruleAllowance = ruleAllowance, override = override,
            allowance = allowance, running = running, canStart = allowance == null || running < allowance, warning = warning,
            updatedAt = if (top != null) live?.observedAt else null,
            projectedPercent = judgement?.projected, reasoning = reasoning,
        )
    }

    private fun refusal(m: MeterUsage): String {
        val pct = m.percent?.let { "${fmt(it)}% ${m.window ?: ""}".trim() } ?: "unknown usage"
        val pace = m.paceDelta?.let { " (${if (it >= 0) "+" else ""}${fmt(it)} vs even pace)" } ?: ""
        val by = if (m.override != null) "override" else "rule"
        val projected = if (m.override == null) m.projectedPercent?.let { ", projected ${fmt(it)}% at reset" } ?: "" else ""
        val resets = m.resetsAt?.let { "; resets ${java.time.Instant.ofEpochMilli(it)}" } ?: ""
        return "usage pacing: ${m.meter} at $pct$pace$projected, allowance ${m.allowance} ($by), running ${m.running}$resets. " +
            "Wait for a worker to finish, or ask the owner for an override (infantry usage override ${m.meter} <n|none>)."
    }

    private fun fmt(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else "%.1f".format(d)

    companion object {
        const val RULE_SETTING = "usage.pacing"
        const val MANUAL_SETTING = "usage.manual"
        const val OVERRIDES_SETTING = "usage.overrides"

        fun parseManual(element: JsonElement): Map<String, ManualUsage> {
            val map = try {
                json.decodeFromJsonElement(MapSerializer(String.serializer(), ManualUsage.serializer()), element)
            } catch (e: Exception) {
                throw IllegalArgumentException("must be {meter: {\"percent\": n, \"resetsAt\": epochMs?, \"setAt\": epochMs}}")
            }
            map.forEach { (k, v) ->
                require(k.isNotBlank()) { "meter name is blank" }
                require(v.percent in 0.0..100.0) { "percent of '$k' must be in 0..100" }
            }
            return map
        }

        fun parseOverrides(element: JsonElement, ceiling: Int = PacingRule.DEFAULT_CEILING): Map<String, UsageOverride> {
            val map = try {
                json.decodeFromJsonElement(MapSerializer(String.serializer(), UsageOverride.serializer()), element)
            } catch (e: Exception) {
                throw IllegalArgumentException("must be {meter: {\"workers\": n|null, \"until\": epochMs}}")
            }
            map.forEach { (k, v) ->
                require(k.isNotBlank()) { "meter name is blank" }
                require(v.workers == null || v.workers in 0..ceiling) { "workers of '$k' must be in 0..$ceiling (setting limits.pacing.maxWorkersCeiling) or null" }
            }
            return map
        }

        fun manualJson(map: Map<String, ManualUsage>): JsonElement =
            json.encodeToJsonElement(MapSerializer(String.serializer(), ManualUsage.serializer()), map)

        fun overridesJson(map: Map<String, UsageOverride>): JsonElement =
            json.encodeToJsonElement(MapSerializer(String.serializer(), UsageOverride.serializer()), map)

        fun ruleJson(rule: PacingRule): JsonElement = json.encodeToJsonElement(PacingRule.serializer(), rule)

        val EMPTY_JSON = JsonObject(emptyMap())
    }
}

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
