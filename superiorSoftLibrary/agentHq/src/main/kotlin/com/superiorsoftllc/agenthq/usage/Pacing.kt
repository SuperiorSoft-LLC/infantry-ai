package com.superiorsoftllc.agenthq.usage

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * USAGE-PACE (contract 1.119.0): how many ticket workers may run on a plan meter, read from how much of the plan window
 * is used. The owner can type a percent for a meter with no readable usage, override the allowance, and change the rule. A METER is the plan a run draws on, which is not always its harness: pi or opencode running an `anthropic/...`
 * model spends the Claude subscription, an `opencode-go/...` model spends OpenCode Go. Unknown usage FAILS OPEN (no
 * pacing, a warning in the report): a dead usage endpoint never stalls the board.
 */

/** Maps a run's (harness, model) to the meter it spends. */
object UsageMeters {
    const val CLAUDE = "claude"
    const val OPENCODE_GO = "opencode-go"

    /** [model] is the run's resolved model (the harness default when the run named none); null = unknown. */
    fun meterFor(provider: String, model: String?): String {
        val p = provider.trim().lowercase()
        val m = model?.trim()?.lowercase().orEmpty()
        return when {
            p == CLAUDE -> CLAUDE
            m.startsWith("anthropic/") || m.startsWith("claude-bridge/") -> CLAUDE
            m.startsWith("opencode-go/") -> OPENCODE_GO
            else -> p
        }
    }
}

/** Below [below] percent used (exclusive), [workers] ticket workers may run; [below] null = every percent left. */
@Serializable
data class PacingTier(val below: Double? = null, val workers: Int)

/** The pacing rule (setting `usage.pacing`). The metric is the HIGHEST percent among a meter's windows. */
@Serializable
data class PacingRule(val enabled: Boolean = true, val tiers: List<PacingTier> = DEFAULT_TIERS) {
    /** Workers allowed at [percent]; null = no limit (rule off, or usage unknown: fail open). */
    fun allowance(percent: Double?): Int? {
        if (!enabled || percent == null) return null
        return tiers.firstOrNull { it.below == null || percent < it.below }?.workers
    }

    companion object {
        val DEFAULT_TIERS = listOf(PacingTier(60.0, 3), PacingTier(80.0, 2), PacingTier(null, 1))
        val DEFAULT = PacingRule()
        const val MAX_WORKERS = 50

        /** Validates [element]; throws [IllegalArgumentException] naming the problem. */
        fun parse(element: JsonElement): PacingRule {
            val rule = try {
                json.decodeFromJsonElement(serializer(), element)
            } catch (e: Exception) {
                throw IllegalArgumentException("must be {\"enabled\": bool, \"tiers\": [{\"below\": percent, \"workers\": n}, ..., {\"workers\": n}]}")
            }
            require(rule.tiers.isNotEmpty()) { "needs at least one tier" }
            require(rule.tiers.last().below == null) { "last tier must have no 'below' (it covers every percent left)" }
            require(rule.tiers.dropLast(1).all { it.below != null }) { "only the last tier may omit 'below'" }
            val bounds = rule.tiers.mapNotNull { it.below }
            require(bounds.all { it > 0.0 && it <= 100.0 }) { "'below' must be in (0, 100]" }
            require(bounds.zipWithNext().all { (a, b) -> a < b }) { "'below' must increase from tier to tier" }
            require(rule.tiers.all { it.workers in 0..MAX_WORKERS }) { "'workers' must be in 0..$MAX_WORKERS" }
            return rule
        }
    }
}

/** A percent typed in by hand for a meter (setting `usage.manual`), valid until [expiresAt]. */
@Serializable
data class ManualUsage(
    val percent: Double,
    /** Epoch millis; null = unknown. */
    val resetsAt: Long? = null,
    val setAt: Long,
) {
    /** At the window's reset, else [MANUAL_TTL_MS] after it was typed. */
    val expiresAt: Long get() = resetsAt ?: (setAt + MANUAL_TTL_MS)

    companion object {
        const val MANUAL_TTL_MS = 5 * 60 * 60_000L
    }
}

/** A hand-set allowance for a meter (setting `usage.overrides`): [workers] null = no limit, until [until] (epoch millis). */
@Serializable
data class UsageOverride(val workers: Int? = null, val until: Long)

/** One meter in a [UsageReport]. */
@Serializable
data class MeterUsage(
    val meter: String,
    /** The highest percent among the windows (the pacing metric); null = unknown. */
    val percent: Double?,
    /** Label of the window [percent] comes from. */
    val window: String? = null,
    /** Epoch millis that window resets. */
    val resetsAt: Long? = null,
    /** Points ahead (+) / under (-) an even burn in that window. */
    val paceDelta: Double? = null,
    /** `live`, `manual` or `none`. */
    val source: String,
    /** Every live window (empty for manual / none). */
    val windows: List<LimitWindow> = emptyList(),
    /** Why a live read gave nothing (a [LimitReason] code). */
    val reason: String? = null,
    /** What the rule allows at [percent]; null = no limit. */
    val ruleAllowance: Int? = null,
    val override: UsageOverride? = null,
    /** The allowance in force (override first, then the rule); null = no limit. */
    val allowance: Int? = null,
    /** Live ticket workers spending this meter. */
    val running: Int,
    /** True when one more ticket worker may start now. */
    val canStart: Boolean,
    /** Set when pacing is off because usage is unknown (fail open), or other notes. */
    val warning: String? = null,
)

/** The `usage_report` frame (contract 1.119.0). */
@Serializable
data class UsageReport(val meters: List<MeterUsage>, val rule: PacingRule, val observedAt: Long)

/**
 * The `usage_set` frame (contract 1.120.0), answered by `usage_report`: a meter's manual percent and/or override set or
 * cleared, and/or the pacing rule replaced. An override with [overrideUntil] set and [overrideWorkers] null = no limit.
 */
@Serializable
data class UsageSet(
    val meter: String = "",
    val manualPercent: Double? = null,
    /** Epoch millis the typed window resets; null = unknown (the entry then lapses after [ManualUsage.MANUAL_TTL_MS]). */
    val manualResetsAt: Long? = null,
    val clearManual: Boolean = false,
    val overrideWorkers: Int? = null,
    val overrideUntil: Long? = null,
    val clearOverride: Boolean = false,
    /** A whole [PacingRule] as JSON; validated by the settings service. */
    val rule: JsonElement? = null,
) {
    companion object {
        fun decode(data: String): UsageSet = try {
            json.decodeFromString(serializer(), data)
        } catch (e: Exception) {
            throw IllegalArgumentException("usage_set expects a UsageSet JSON object")
        }

        fun encode(set: UsageSet): String = json.encodeToString(serializer(), set)
    }
}

/** Plain-text rendering of a [UsageReport], shared by `agenthq usage` and `agenthq_usage`. */
object UsageText {
    fun render(report: UsageReport): String = buildString {
        val rule = report.rule
        appendLine(
            "pacing rule: " + if (!rule.enabled) "off" else rule.tiers.joinToString(", ") { t ->
                (t.below?.let { "<${fmt(it)}% -> " } ?: "else -> ") + "${t.workers}"
            } + " ticket workers",
        )
        if (report.meters.isEmpty()) appendLine("no meters: no live usage source and no ticket worker running")
        for (m in report.meters) {
            append(m.meter).append(": ")
            append(m.percent?.let { "${fmt(it)}% used" } ?: "usage unknown")
            m.window?.let { append(" (").append(it).append(")") }
            m.paceDelta?.let { append(", ").append(if (it >= 0) "+" else "").append(fmt(it)).append(" vs even pace") }
            m.resetsAt?.let { append(", resets in ").append(until(it, report.observedAt)) }
            append(" [").append(m.source).append("]")
            append(" | running ").append(m.running).append(", allowance ").append(m.allowance?.toString() ?: "no limit")
            m.override?.let { o ->
                append(" (override ").append(o.workers?.toString() ?: "no limit").append(" for ").append(until(o.until, report.observedAt)).append(")")
            }
            append(" | ").append(if (m.canStart) "may start 1 more" else "FULL: at the allowance, start no more")
            appendLine()
            if (m.windows.size > 1) m.windows.forEach { w ->
                appendLine("    ${w.label}: ${w.percent?.let { "${fmt(it)}%" } ?: "?"}${w.resetsAt?.let { ", resets in " + until(it, report.observedAt) } ?: ""}")
            }
            m.warning?.let { appendLine("    warning: $it") }
        }
    }.trimEnd()

    fun until(at: Long, now: Long): String {
        val mins = ((at - now) / 60_000L).coerceAtLeast(0)
        return when {
            mins >= 24 * 60 -> "${mins / (24 * 60)}d ${(mins % (24 * 60)) / 60}h"
            mins >= 60 -> "${mins / 60}h ${mins % 60}m"
            else -> "${mins}m"
        }
    }

    fun fmt(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else "%.1f".format(d)

    /** `60:3,80:2,1` -> a [PacingRule] JSON; `off` / `on` toggle [current]; `default` resets. Throws [IllegalArgumentException]. */
    fun parseRule(text: String, current: PacingRule = PacingRule.DEFAULT): JsonElement {
        val rule = when (val t = text.trim().lowercase()) {
            "off" -> current.copy(enabled = false)
            "on" -> current.copy(enabled = true)
            "default" -> PacingRule.DEFAULT
            else -> PacingRule(
                enabled = true,
                tiers = t.split(',').map { part ->
                    val bits = part.trim().split(':')
                    when (bits.size) {
                        1 -> PacingTier(null, bits[0].trim().toIntOrNull() ?: throw IllegalArgumentException("bad tier '$part'"))
                        2 -> PacingTier(
                            bits[0].trim().removeSuffix("%").toDoubleOrNull() ?: throw IllegalArgumentException("bad percent in '$part'"),
                            bits[1].trim().toIntOrNull() ?: throw IllegalArgumentException("bad workers in '$part'"),
                        )
                        else -> throw IllegalArgumentException("bad tier '$part' (want below:workers, the last one workers alone)")
                    }
                },
            )
        }
        return UsagePacing.ruleJson(PacingRule.parse(UsagePacing.ruleJson(rule)))
    }
}

sealed interface PacingVerdict {
    data class Allow(val warning: String? = null) : PacingVerdict
    data class Over(val reason: String) : PacingVerdict
}

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
        val top = windows.filter { it.percent != null }.maxByOrNull { it.percent!! }
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
        val ruleAllowance = rule.allowance(percent)
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
        )
    }

    private fun refusal(m: MeterUsage): String {
        val pct = m.percent?.let { "${fmt(it)}% ${m.window ?: ""}".trim() } ?: "unknown usage"
        val pace = m.paceDelta?.let { " (${if (it >= 0) "+" else ""}${fmt(it)} vs even pace)" } ?: ""
        val by = if (m.override != null) "override" else "rule"
        val resets = m.resetsAt?.let { "; resets ${java.time.Instant.ofEpochMilli(it)}" } ?: ""
        return "usage pacing: ${m.meter} at $pct$pace, allowance ${m.allowance} ($by), running ${m.running}$resets. " +
            "Wait for a worker to finish."
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

        fun parseOverrides(element: JsonElement): Map<String, UsageOverride> {
            val map = try {
                json.decodeFromJsonElement(MapSerializer(String.serializer(), UsageOverride.serializer()), element)
            } catch (e: Exception) {
                throw IllegalArgumentException("must be {meter: {\"workers\": n|null, \"until\": epochMs}}")
            }
            map.forEach { (k, v) ->
                require(k.isNotBlank()) { "meter name is blank" }
                require(v.workers == null || v.workers in 0..PacingRule.MAX_WORKERS) { "workers of '$k' must be in 0..${PacingRule.MAX_WORKERS} or null" }
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
