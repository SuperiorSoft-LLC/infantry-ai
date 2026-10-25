package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.MeterUsage
import com.superiorsoftllc.agenthq.ui.protocol.PacingRule
import com.superiorsoftllc.agenthq.ui.protocol.PacingTier
import kotlin.math.roundToInt

/**
 * USAGE-PACE (contract 1.119.0 / 1.121.0): the words the pacing panel shows and the rule text the owner types, kept pure
 * so they are tested without a window. The daemon validates every change again; this only catches typos early.
 */
object PacingView {
    /**
     * Pace: "pace · up to 5 ticket workers while projected <= 100% at reset · 1 from 95% used"; bands: "<60% -> 3 · <80% -> 2
     * · else -> 1 ticket workers"; or "off".
     */
    fun ruleText(rule: PacingRule): String = when {
        !rule.enabled -> "off"
        isPace(rule) -> "pace · up to ${rule.maxWorkers ?: "?"} ticket workers while projected <= ${rule.okProjected?.let(::num) ?: "?"}% at reset" +
            " · 1 from ${rule.hardStopPercent?.let(::num) ?: "?"}% used"
        else -> tiersText(rule) + " ticket workers"
    }

    /** "<60% -> 3 · <80% -> 2 · else -> 1". */
    fun tiersText(rule: PacingRule): String = rule.tiers.joinToString(" · ") { t ->
        (t.below?.let { "<${num(it)}%" } ?: "else") + " -> ${t.workers}"
    }

    /** Whether the daemon judges by projected burn (contract: `mode` = `pace`). A daemon without the key is bands. */
    fun isPace(rule: PacingRule): Boolean = rule.mode == PacingRule.MODE_PACE

    /** Whether the daemon knows pace mode at all (it reports a `mode`); an older one is edited as tiers only. */
    fun hasModes(rule: PacingRule): Boolean = rule.mode != null

    /** "projected 87% at reset"; null when the meter is judged by bands or its projection is unknown. */
    fun projectedText(m: MeterUsage): String? = m.projectedPercent?.let { "projected ${num(roundOne(it))}% at reset" }

    /**
     * Why the rule allows what it does, when that is not already on the card: "at 96% (hard stop 95%)". Null for bands and
     * for the projection (shown beside the percent), and under an override (the rule is not what applies).
     */
    fun reasonText(m: MeterUsage): String? =
        m.reasoning?.takeIf { m.override == null && it != "bands" && !it.startsWith("projected") }?.let { "rule: $it" }

    /** Whether [m]'s projection runs past what the rule calls ok (else past 100%). */
    fun projectedOver(m: MeterUsage, rule: PacingRule): Boolean =
        m.projectedPercent?.let { it > (rule.okProjected ?: 100.0) } ?: false

    /**
     * The editor's whole rule: [base] with every field the editor shows replaced; a key the daemon never reported (an
     * older daemon) stays null so it is not sent. Pace fields are validated in either mode (they are kept for a later
     * switch); the error names the problem.
     */
    fun buildRule(
        base: PacingRule,
        enabled: Boolean,
        tiersText: String,
        mode: String?,
        maxWorkersText: String,
        okProjectedText: String,
        hardStopText: String,
    ): Result<PacingRule> = parseRule(tiersText, enabled).mapCatching { tiers ->
        if (!hasModes(base)) return@mapCatching base.copy(enabled = enabled, tiers = tiers.tiers)
        require(mode == PacingRule.MODE_PACE || mode == PacingRule.MODE_BANDS) { "mode must be pace or bands" }
        val max = maxWorkersText.trim().toIntOrNull()?.takeIf { it in 1..MAX_WORKERS }
            ?: error("max workers must be a whole number 1..$MAX_WORKERS")
        val ok = okProjectedText.trim().removeSuffix("%").trim().toDoubleOrNull()?.takeIf { it > 0.0 }
            ?: error("ok projection must be a percent above 0")
        val hard = hardStopText.trim().removeSuffix("%").trim().toDoubleOrNull()?.takeIf { it > 0.0 && it <= 100.0 }
            ?: error("hard stop must be a percent in 1..100")
        base.copy(enabled = enabled, tiers = tiers.tiers, mode = mode, maxWorkers = max, okProjected = ok, hardStopPercent = hard)
    }

    /** A percent or count as the editor shows it ("95", "97.5"); "" when unknown. */
    fun fieldText(d: Double?): String = d?.let(::num) ?: ""

    /** The editable form, "60:3, 80:2, 1". */
    fun ruleInput(rule: PacingRule): String =
        rule.tiers.joinToString(", ") { t -> t.below?.let { "${num(it)}:${t.workers}" } ?: "${t.workers}" }

    /** Parses "60:3, 80:2, 1" into tiers; the error names the problem. */
    fun parseRule(text: String, enabled: Boolean = true): Result<PacingRule> = runCatching {
        val tiers = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
            val bits = part.split(':').map { it.trim() }
            when (bits.size) {
                1 -> PacingTier(null, bits[0].toIntOrNull() ?: error("'$part': workers must be a whole number"))
                2 -> PacingTier(
                    bits[0].removeSuffix("%").toDoubleOrNull() ?: error("'$part': percent must be a number"),
                    bits[1].toIntOrNull() ?: error("'$part': workers must be a whole number"),
                )
                else -> error("'$part': write percent:workers, the last tier workers alone")
            }
        }
        require(tiers.isNotEmpty()) { "at least one tier" }
        require(tiers.last().below == null) { "the last tier is workers alone (it covers every percent left)" }
        require(tiers.dropLast(1).all { it.below != null }) { "only the last tier may omit the percent" }
        val bounds = tiers.mapNotNull { it.below }
        require(bounds.all { it > 0.0 && it <= 100.0 }) { "percents must be in 1..100" }
        require(bounds.zipWithNext().all { (a, b) -> a < b }) { "percents must increase" }
        require(tiers.all { it.workers in 0..MAX_WORKERS }) { "workers must be 0..$MAX_WORKERS" }
        PacingRule(enabled, tiers)
    }

    /** "2 of 3 running", "2 running · no limit". */
    fun allowanceText(m: MeterUsage): String =
        m.allowance?.let { "${m.running} of $it running" } ?: "${m.running} running · no limit"

    /** "updated just now" / "updated 4m ago" / "updated 2h ago"; null when the meter has no live read. */
    fun updatedText(updatedAt: Long?, now: Long): String? {
        updatedAt ?: return null
        val min = ((now - updatedAt) / 60_000L).coerceAtLeast(0)
        return "updated " + when {
            min < 1 -> "just now"
            min < 60 -> "${min}m ago"
            else -> "${min / 60}h ago"
        }
    }

    /** Where the percent comes from, as a person reads it. */
    fun sourceText(m: MeterUsage): String = when (m.source) {
        "live" -> "live"
        "manual" -> "typed by hand"
        else -> "usage unknown" + (m.reason?.let { " (${LimitsView.reasonText(it)})" } ?: "")
    }

    /** "override 5 until 3:00 PM"-style text without a clock; the pane adds the time. */
    fun overrideText(m: MeterUsage): String? = m.override?.let { o -> "override: " + (o.workers?.toString() ?: "no limit") }

    /** A percent typed in the manual field, or null when it is not 0..100. */
    fun parsePercent(text: String): Double? = text.trim().removeSuffix("%").trim().toDoubleOrNull()?.takeIf { it in 0.0..100.0 }

    /** Workers typed in the override field: a number 0..[MAX_WORKERS], or "none" / "" for no limit; null = invalid. */
    fun parseWorkers(text: String): OverrideWorkers? {
        val t = text.trim().lowercase()
        if (t.isEmpty() || t == "none") return OverrideWorkers(null)
        return t.toIntOrNull()?.takeIf { it in 0..MAX_WORKERS }?.let { OverrideWorkers(it) }
    }

    data class OverrideWorkers(val workers: Int?)

    const val MAX_WORKERS = 50

    private fun num(d: Double): String = if (d == d.roundToInt().toDouble()) d.roundToInt().toString() else d.toString()

    private fun roundOne(d: Double): Double = (d * 10.0).roundToInt() / 10.0
}
