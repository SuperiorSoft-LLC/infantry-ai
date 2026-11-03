package com.superiorsoftllc.infantry.daemon.api.usage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * USAGE-PACE (contract 1.119.0): how many ticket workers may run on a plan meter, read from how much of the plan window
 * is used. The owner can type a percent for a meter with no readable usage, override the allowance, and change the rule. A METER is the plan a run draws on, which is not always its harness: pi or opencode running an `anthropic/...`
 * model spends the Claude subscription, an `opencode-go/...` model spends OpenCode Go. Unknown usage FAILS OPEN (no
 * pacing, a warning in the report): a dead usage endpoint never stalls the board.
 */

/** One plan-limit window of one provider. [percent] null = unknown. [paceDelta] = percent minus the elapsed share. */
@Serializable
data class LimitWindow(
    val provider: String,
    /** "Current session", "Current week", "5h limit", "Weekly limit", "Monthly limit". */
    val label: String,
    val windowMinutes: Int,
    val percent: Double?,
    /** Epoch millis. */
    val resetsAt: Long?,
    val observedAt: Long,
    /** "live" (the provider's usage endpoint). "stream" and "estimate" are reserved for later sources. */
    val source: String,
    /** Points ahead (+) or under (-) an even pace through the window; null when percent or resetsAt is unknown. */
    val paceDelta: Double?,
)

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
data class PacingRule(
    val enabled: Boolean = true,
    val tiers: List<PacingTier> = DEFAULT_TIERS,
    /** `pace` (default): the allowance follows the projected burn at the window's reset; `bands`: the raw-percent [tiers]. */
    val mode: String = MODE_PACE,
    /** Pace mode: workers allowed while the projection stays within [okProjected]. */
    val maxWorkers: Int = DEFAULT_MAX_WORKERS,
    /** Pace mode: projected percent at reset up to which the max is allowed; at [okProjected]+[PACE_SPAN] and beyond only 1. */
    val okProjected: Double = 100.0,
    /** Pace mode: at or above this percent used a meter is held to 1 worker. */
    val hardStopPercent: Double = 95.0,
) {
    /** One window judged by pace: allowance, the projection (null when judged by bands) and why. */
    data class Judgement(val allowance: Int, val projected: Double?, val reason: String)

    /** Judges one live window at [now]; null = unreadable percent. Falls back to the bands without reset / length data. */
    fun judge(w: LimitWindow, now: Long): Judgement? {
        val percent = w.percent ?: return null
        val resets = w.resetsAt
        if (mode != MODE_PACE || resets == null || w.windowMinutes <= 0) {
            return Judgement(allowance(percent) ?: 0, null, "bands")
        }
        val windowMs = w.windowMinutes * 60_000.0
        val elapsed = (1.0 - (resets - now) / windowMs).coerceIn(MIN_ELAPSED, 1.0)
        val projected = percent / elapsed
        val max = maxWorkers.coerceAtLeast(1)
        val byProjection = when {
            projected <= okProjected + PACE_TOLERANCE -> max
            projected >= okProjected + PACE_SPAN -> 1
            else -> (max - Math.ceil((projected - okProjected) / PACE_SPAN * (max - 1))).toInt().coerceIn(1, max)
        }
        return when {
            percent >= hardStopPercent -> Judgement(1, projected, "at ${fmt(percent)}% (hard stop ${fmt(hardStopPercent)}%)")
            percent >= 90.0 && (w.paceDelta ?: 0.0) > 0.0 -> Judgement(1, projected, "at ${fmt(percent)}% and ahead of pace")
            else -> Judgement(byProjection, projected, "projected ${fmt(projected)}% at reset")
        }
    }

    private fun fmt(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else "%.1f".format(d)

    /** Bands: workers allowed at [percent]; used in `bands` mode and as the fallback without pace data. */
    fun allowance(percent: Double?): Int? {
        if (!enabled || percent == null) return null
        return tiers.firstOrNull { it.below == null || percent < it.below }?.workers
    }

    companion object {
        val DEFAULT_TIERS = listOf(PacingTier(60.0, 3), PacingTier(80.0, 2), PacingTier(null, 1))
        val DEFAULT = PacingRule()
        /** The default worker ceiling is the registry's `limits.pacing.maxWorkersCeiling`; callers with settings pass the live value. */
        val DEFAULT_CEILING: Int get() = PACING_CEILING_DEFAULT
        /** The registry default of `limits.pacing.maxWorkersCeiling` (a core test pins it to the registry). */
        const val PACING_CEILING_DEFAULT = 50
        const val MODE_PACE = "pace"
        const val MODE_BANDS = "bands"
        const val DEFAULT_MAX_WORKERS = 5
        const val PACE_SPAN = 20.0

        /** Slack (percentage points) so exactly on pace is not nudged down by rounding. */
        const val PACE_TOLERANCE = 0.5

        /** Floor of the elapsed fraction: the first minutes of a window neither divide by ~0 nor flip-flop. */
        const val MIN_ELAPSED = 0.10

        /** Validates [element]; throws [IllegalArgumentException] naming the problem. */
        fun parse(element: JsonElement, ceiling: Int = DEFAULT_CEILING): PacingRule {
            val rule = try {
                json.decodeFromJsonElement(serializer(), element)
            } catch (e: Exception) {
                throw IllegalArgumentException("must be {\"enabled\": bool, \"tiers\": [{\"below\": percent, \"workers\": n}, ..., {\"workers\": n}]}")
            }
            require(rule.mode == MODE_PACE || rule.mode == MODE_BANDS) { "mode must be 'pace' or 'bands'" }
            require(rule.maxWorkers in 1..ceiling) { "maxWorkers must be in 1..$ceiling (setting limits.pacing.maxWorkersCeiling)" }
            require(rule.okProjected > 0.0) { "okProjected must be positive" }
            require(rule.hardStopPercent > 0.0 && rule.hardStopPercent <= 100.0) { "hardStopPercent must be in (0, 100]" }
            require(rule.tiers.isNotEmpty()) { "needs at least one tier" }
            require(rule.tiers.last().below == null) { "last tier must have no 'below' (it covers every percent left)" }
            require(rule.tiers.dropLast(1).all { it.below != null }) { "only the last tier may omit 'below'" }
            val bounds = rule.tiers.mapNotNull { it.below }
            require(bounds.all { it > 0.0 && it <= 100.0 }) { "'below' must be in (0, 100]" }
            require(bounds.zipWithNext().all { (a, b) -> a < b }) { "'below' must increase from tier to tier" }
            require(rule.tiers.all { it.workers in 0..ceiling }) { "'workers' must be in 0..$ceiling (setting limits.pacing.maxWorkersCeiling)" }
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
    /** Contract 1.124.0: epoch millis of the live read these numbers come from; null for a manual or unread meter. */
    val updatedAt: Long? = null,
    /** Projected percent of the governing window at its reset (pace mode); null when judged by bands or unknown. */
    val projectedPercent: Double? = null,
    /** Why the rule allows [ruleAllowance] ("projected 33% at reset", "bands", ...). */
    val reasoning: String? = null,
)

/** The `usage_report` frame (contract 1.119.0). */
@Serializable
data class UsageReport(val meters: List<MeterUsage>, val rule: PacingRule, val observedAt: Long)

/**
 * The `usage_set` frame (contract 1.121.0), answered by `usage_report`: a meter's manual percent and/or override set or
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

/** Plain-text rendering of a [UsageReport], shared by `infantry usage` and `infantry_usage`. */
object UsageText {
    fun render(report: UsageReport): String = buildString {
        val rule = report.rule
        appendLine(
            "pacing rule: " + when {
                !rule.enabled -> "off"
                rule.mode == PacingRule.MODE_PACE ->
                    "pace (up to ${rule.maxWorkers} ticket workers while projected <= ${fmt(rule.okProjected)}% at reset, 1 from ${fmt(rule.hardStopPercent)}% used; bands fallback without reset data)"
                else -> rule.tiers.joinToString(", ") { t ->
                    (t.below?.let { "<${fmt(it)}% -> " } ?: "else -> ") + "${t.workers}"
                } + " ticket workers"
            },
        )
        if (report.meters.isEmpty()) appendLine("no meters: no live usage source and no ticket worker running")
        for (m in report.meters) {
            append(m.meter).append(": ")
            append(m.percent?.let { "${fmt(it)}% used" } ?: "usage unknown")
            m.window?.let { append(" (").append(it).append(")") }
            m.paceDelta?.let { append(", ").append(if (it >= 0) "+" else "").append(fmt(it)).append(" vs even pace") }
            m.resetsAt?.let { append(", resets in ").append(until(it, report.observedAt)) }
            m.projectedPercent?.let { append(", projected ").append(fmt(it)).append("% at reset") }
            append(" [").append(m.source).append("]")
            append(" | running ").append(m.running).append(", allowance ").append(m.allowance?.toString() ?: "no limit")
            m.override?.let { o ->
                append(" (override ").append(o.workers?.toString() ?: "no limit").append(" for ").append(until(o.until, report.observedAt)).append(")")
            }
            append(" | ").append(if (m.canStart) "may start 1 more" else "FULL: agent ticket starts refused")
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
            "pace" -> current.copy(enabled = true, mode = PacingRule.MODE_PACE)
            else -> if (t.startsWith("pace:")) {
                current.copy(
                    enabled = true, mode = PacingRule.MODE_PACE,
                    maxWorkers = t.removePrefix("pace:").trim().toIntOrNull() ?: throw IllegalArgumentException("bad max workers in '$t'"),
                )
            } else PacingRule(
                enabled = true,
                mode = PacingRule.MODE_BANDS,
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
        return json.encodeToJsonElement(PacingRule.serializer(), PacingRule.parse(json.encodeToJsonElement(PacingRule.serializer(), rule), Int.MAX_VALUE))
    }
}

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
