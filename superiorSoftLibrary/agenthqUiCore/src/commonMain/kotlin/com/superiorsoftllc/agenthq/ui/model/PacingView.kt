package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.MeterUsage
import com.superiorsoftllc.agenthq.ui.protocol.PacingRule
import com.superiorsoftllc.agenthq.ui.protocol.PacingTier
import kotlin.math.roundToInt

/**
 * USAGE-PACE (contract 1.119.0 / 1.120.0): the words the pacing panel shows and the rule text the owner types, kept pure
 * so they are tested without a window. The daemon validates every change again; this only catches typos early.
 */
object PacingView {
    /** "<60% → 3 · <80% → 2 · else → 1", or "off". */
    fun ruleText(rule: PacingRule): String =
        if (!rule.enabled) "off" else rule.tiers.joinToString(" · ") { t ->
            (t.below?.let { "<${num(it)}%" } ?: "else") + " → ${t.workers}"
        }

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
}
