package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.SessionStats
import kotlin.math.roundToLong
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * STATS-2: everything the Stats tab prints, derived from one `session_stats` frame (contract 1.92.0). Pure, so the
 * wording is unit-tested without Compose. The rule throughout: a null field is [NONE], never a made-up value.
 */
object StatsView {
    /** What a field the harness's record does not state reads as. */
    const val NONE = "-"

    /** "1d 12h", "3h 5m", "4m 12s", "42s"; [NONE] for null. */
    fun duration(ms: Long?): String {
        if (ms == null || ms < 0) return NONE
        val s = ms / 1000
        val d = s / 86_400; val h = (s % 86_400) / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${sec}s"
            else -> "${sec}s"
        }
    }

    /** A compact count: 950, 3.3K, 593.4K, 1.2M, 6.6B; [NONE] for null. */
    fun compact(n: Long?): String {
        if (n == null) return NONE
        val abs = kotlin.math.abs(n)
        fun one(v: Double, unit: String): String {
            val tenths = (v * 10).roundToLong()
            val text = if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
            return text + unit
        }
        return when {
            abs >= 1_000_000_000 -> one(n / 1e9, "B")
            abs >= 1_000_000 -> one(n / 1e6, "M")
            abs >= 1_000 -> one(n / 1e3, "K")
            else -> n.toString()
        }
    }

    /** 234,310 style grouping; [NONE] for null. */
    fun grouped(n: Long?): String {
        if (n == null) return NONE
        val digits = kotlin.math.abs(n).toString()
        val out = StringBuilder()
        digits.forEachIndexed { i, c -> if (i > 0 && (digits.length - i) % 3 == 0) out.append(','); out.append(c) }
        return (if (n < 0) "-" else "") + out
    }

    /** "507 / 2706" (prompts / turns); each side is [NONE] on its own when unknown. */
    fun exchanges(s: SessionStats): String = "${s.userPrompts?.toString() ?: NONE} / ${s.assistantTurns?.toString() ?: NONE}"

    fun toolUses(s: SessionStats): String = compact(s.toolUses?.toLong())

    fun models(s: SessionStats): String = s.models?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: NONE

    fun status(s: SessionStats): String = s.status?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: NONE

    /** The harness's own session id, shortened for the tile; the copy button copies the whole [SessionStats.providerSessionId]. */
    fun shortSessionId(s: SessionStats): String = s.providerSessionId?.takeIf { it.isNotBlank() }?.take(8) ?: NONE

    /** Context fill as a percentage of the model limit, or null when either side is unknown (no limit is not 0%). */
    fun gaugePercent(s: SessionStats): Int? {
        val last = s.lastContextTokens ?: return null
        val limit = s.modelContextLimit?.takeIf { it > 0 } ?: return null
        return (last * 100.0 / limit).roundToLong().toInt()
    }

    /** True once the last request's context is past the model's window: the gauge turns red. */
    fun gaugeOver(s: SessionStats): Boolean {
        val last = s.lastContextTokens ?: return false
        val limit = s.modelContextLimit?.takeIf { it > 0 } ?: return false
        return last > limit
    }

    /** "234,310 / 200,000"; a missing side is [NONE]. */
    fun gaugeLabel(s: SessionStats): String = "${grouped(s.lastContextTokens)} / ${s.modelContextLimit?.let { grouped(it) } ?: "limit unknown"}"

    fun gaugePercentLabel(s: SessionStats): String = gaugePercent(s)?.let { "$it%" } ?: NONE

    /** The same words for every harness when its record gave no per-request tokens. */
    fun noUsage(s: SessionStats): String = "no usage data for ${s.harness}"

    /** Cost the harness itself reported, running total; never computed. "cost not reported" when it reports none. */
    fun cost(s: SessionStats): String = s.costTotal?.let { "$" + formatCost(it) } ?: "cost not reported"

    /** "limit unknown" when no catalog states the model's window. */
    fun limit(s: SessionStats): String = s.modelContextLimit?.takeIf { it > 0 }?.let { grouped(it) } ?: "limit unknown"

    internal fun formatCost(v: Double): String {
        val cents = (v * 10_000).roundToLong()
        return "${cents / 10_000}.${(cents % 10_000).toString().padStart(4, '0')}"
    }

    /** True when every point carries a time, so the chart can use a time axis. */
    fun hasTimeAxis(s: SessionStats): Boolean = !s.series.isNullOrEmpty() && s.series.all { it.at != null }

    /** "10:21 AM" in [zone]; [NONE] for null. */
    fun clock(at: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (at == null) return NONE
        val t = Instant.fromEpochMilliseconds(at).toLocalDateTime(zone)
        return TimeFormat.clock12(t.hour, t.minute)
    }
}
