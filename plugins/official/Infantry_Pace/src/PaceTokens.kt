package com.superiorsoftllc.agenthq.plugins.official.pace

import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterRow
import kotlin.time.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * PACE-TREND-1: the per-run token totals the daemon already keeps (`usage_meter` rows, from run_usage), shown BESIDE the trend.
 * INFORMATIONAL ONLY: the meter percent is the provider's own number and the only one the pacing rule reads (docs/PACING.md).
 */
object PaceTokens {
    /** Input counts cache reads and writes: what the model was sent. */
    data class Totals(val input: Long, val output: Long, val cacheRead: Long, val cacheWrite: Long, val turns: Long, val runs: Long) {
        val inputWithCache: Long get() = input + cacheRead + cacheWrite
        val any: Boolean get() = turns > 0 || inputWithCache > 0 || output > 0
    }

    const val DAYS = 7

    /** Which plan a run spends: the same mapping as the daemon's `UsageMeters.meterFor` (anthropic models spend the Claude plan). */
    fun meterOf(provider: String, model: String?): String {
        val p = provider.trim().lowercase()
        val m = model?.trim()?.lowercase().orEmpty()
        return when {
            p == "claude" -> "claude"
            m.startsWith("anthropic/") || m.startsWith("claude-bridge/") -> "claude"
            m.startsWith("opencode-go/") -> "opencode-go"
            else -> p
        }
    }

    /** Rows of [meter] on or after the date [DAYS] before [today] (ISO `yyyy-MM-dd` compares as text). */
    fun totals(rows: List<UsageMeterRow>, meter: String, todayIso: String = todayIso()): Totals {
        val from = fromIso(todayIso)
        val mine = rows.filter { it.day >= from && meterOf(it.provider, it.model).equals(meter, true) }
        return Totals(
            mine.sumOf { it.input }, mine.sumOf { it.output }, mine.sumOf { it.cacheRead }, mine.sumOf { it.cacheWrite },
            mine.sumOf { it.turns }, mine.sumOf { it.runs },
        )
    }

    fun todayIso(): String = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

    private fun fromIso(today: String): String =
        kotlinx.datetime.LocalDate.parse(today).minus(DatePeriod(days = DAYS - 1)).toString()

    fun compact(n: Long): String = when {
        n >= 1_000_000 -> PaceChartModel.one(n / 1_000_000.0) + "M"
        n >= 1_000 -> PaceChartModel.one(n / 1_000.0) + "k"
        else -> n.toString()
    }

    fun line(t: Totals): String =
        "last $DAYS days: in ${compact(t.inputWithCache)} (cache read ${compact(t.cacheRead)}, write ${compact(t.cacheWrite)}), out ${compact(t.output)}, " +
            "${t.turns} ${if (t.turns == 1L) "turn" else "turns"}"
}
