package com.superiorsoftllc.agenthq.plugins.official.stats

import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterRow
import kotlin.math.roundToLong

/** USAGE-1: sums of meter rows. Provider-reported usage only. */
data class MeterAgg(
    val runs: Long = 0, val turns: Long = 0, val input: Long = 0, val output: Long = 0,
    val cacheRead: Long = 0, val cacheWrite: Long = 0, val contextSum: Long = 0, val cost: Double? = null,
) {
    /** Billed tokens: input + output + cache read + cache write (reasoning is not added; harnesses disagree). */
    val tokens: Long get() = input + output + cacheRead + cacheWrite
    /** What the model saw per request: input + cache read + cache write. */
    val context: Long get() = input + cacheRead + cacheWrite

    operator fun plus(r: UsageMeterRow) = MeterAgg(
        runs + r.runs, turns + r.turns, input + r.input, output + r.output, cacheRead + r.cacheRead,
        cacheWrite + r.cacheWrite, contextSum + r.contextSum, r.cost?.let { (cost ?: 0.0) + it } ?: cost,
    )
}

enum class MeterSplit(val label: String) { SOURCE("Source"), ROLE("Role"), MODEL("Model") }

/** Pure derivations for the by-day / by-week view; no I/O, so a test can drive it. */
object UsageMeterView {
    /** Days since 1970-01-01 of a `yyyy-MM-dd` string (civil-from-days, no platform date API: this also compiles for web). */
    fun epochDay(day: String): Long {
        val p = day.split('-')
        var y = p[0].toLong(); val m = p[1].toInt(); val d = p[2].toInt()
        if (m <= 2) y -= 1
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    fun dayString(epochDay: Long): String {
        val z = epochDay + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
        return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    /** The day itself, or for a week the Monday that starts it (weeks run Monday..Sunday, UTC like the daemon). */
    fun periodKey(day: String, weekly: Boolean): String {
        if (!weekly) return day
        val e = epochDay(day)
        return dayString(e - ((e + 3) % 7 + 7) % 7)
    }

    fun total(rows: List<UsageMeterRow>): MeterAgg = rows.fold(MeterAgg()) { a, r -> a + r }

    /** Rows grouped per period, newest first. */
    fun periods(rows: List<UsageMeterRow>, weekly: Boolean): List<Pair<String, List<UsageMeterRow>>> =
        rows.groupBy { periodKey(it.day, weekly) }.toList().sortedByDescending { it.first }

    fun splitKey(r: UsageMeterRow, split: MeterSplit): String = when (split) {
        MeterSplit.SOURCE -> r.source
        MeterSplit.ROLE -> r.role
        MeterSplit.MODEL -> "${r.provider}/${r.model}"
    }

    /** One period's rows split by [split], biggest first. */
    fun split(rows: List<UsageMeterRow>, split: MeterSplit): List<Pair<String, MeterAgg>> =
        rows.groupBy { splitKey(it, split) }.map { (k, v) -> k to total(v) }.sortedByDescending { it.second.tokens }

    /** Share of the context that came from the cache; "-" when no context was reported. */
    fun cacheHit(a: MeterAgg): String = if (a.context <= 0) "-" else "${(a.cacheRead * 1000.0 / a.context).roundToLong() / 10.0}%"

    /** Average context (tokens the model saw) per turn; "-" when there were no turns. */
    fun avgContext(a: MeterAgg): String = if (a.turns <= 0) "-" else compact(a.contextSum / a.turns)

    fun cost(a: MeterAgg): String = a.cost?.let { "$" + ((it * 100).roundToLong() / 100.0) } ?: "-"

    /** Change of [cur] tokens against [prev]: "+12%", "-5%", or "-" with nothing to compare. */
    fun delta(cur: Long, prev: Long?): String {
        if (prev == null || prev <= 0) return "-"
        val pct = ((cur - prev) * 100.0 / prev).roundToLong()
        return (if (pct > 0) "+" else "") + pct + "%"
    }

    fun compact(n: Long): String = when {
        n >= 1_000_000_000 -> one(n / 1e9, "B")
        n >= 1_000_000 -> one(n / 1e6, "M")
        n >= 1_000 -> one(n / 1e3, "K")
        else -> n.toString()
    }

    private fun one(v: Double, unit: String): String {
        val t = (v * 10).roundToLong()
        return (if (t % 10 == 0L) "${t / 10}" else "${t / 10}.${t % 10}") + unit
    }
}
