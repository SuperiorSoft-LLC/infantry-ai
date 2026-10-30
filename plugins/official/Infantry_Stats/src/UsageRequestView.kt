package com.superiorsoftllc.agenthq.plugins.official.stats

import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterPrice
import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterReport
import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterRequest
import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterRow
import kotlin.math.roundToLong

/** STATS-ACC-1: a harness-by-harness line of the pi-vs-claude comparison. Null = n/a (not reported / nothing recorded). */
data class HarnessLine(
    val harness: String, val turns: Long, val tokensPerTurn: Long?, val avgContext: Long?, val cacheHit: Double?, val estCost: Double?,
)

enum class CostBy(val label: String) { SESSION("Session"), TICKET("Ticket"), ROLE("Role"), MODEL("Model"), HARNESS("Harness") }

/** Human names for ids, filled by the host from what it knows (ticket titles, session roles); a missing id falls back to the short id. */
data class CostNames(val tickets: Map<String, String> = emptyMap(), val sessions: Map<String, String> = emptyMap())

/** One ranked row of the cost panel: [label] first, [detail] the muted id or harness, [share] 0..1 of the priced total, null cost = no price. */
data class CostRow(val key: String, val label: String, val detail: String?, val turns: Int, val cost: Double?, val share: Double?)

/** The panel's rows plus the priced total and how many rows have no price. */
data class CostTable(val rows: List<CostRow>, val total: Double?, val turns: Int, val unpriced: Int)

/** Pure derivations over recorded requests, rows and the daemon's price table. Nothing is invented: a missing field stays null. */
object UsageRequestView {
    const val NA = "n/a"

    fun price(model: String?, prices: List<UsageMeterPrice>): UsageMeterPrice? {
        if (model == null) return null
        val m = model.lowercase()
        return prices.firstOrNull { it.match.isNotBlank() && it.match.lowercase() in m }
    }

    /** Estimated USD for token counts at [p]'s per-million prices; null when there is no price. */
    fun estimate(p: UsageMeterPrice?, input: Long?, output: Long?, cacheRead: Long?, cacheWrite: Long?): Double? =
        p?.let { ((input ?: 0) * it.input + (output ?: 0) * it.output + (cacheRead ?: 0) * it.cacheRead + (cacheWrite ?: 0) * it.cacheWrite) / 1_000_000.0 }

    fun estimate(r: UsageMeterRow, prices: List<UsageMeterPrice>): Double? =
        estimate(price(r.model, prices), r.input, r.output, r.cacheRead, r.cacheWrite)

    fun estimate(r: UsageMeterRequest, prices: List<UsageMeterPrice>): Double? =
        if (r.input == null && r.output == null && r.cacheRead == null && r.cacheWrite == null) null
        else estimate(price(r.model, prices), r.input, r.output, r.cacheRead, r.cacheWrite)

    /** Cache share of the context over requests that reported a cache-read figure; null = n/a. */
    fun cacheHit(requests: List<UsageMeterRequest>): Double? {
        val rep = requests.filter { it.cacheRead != null && it.context != null }
        val ctx = rep.sumOf { it.context!! }
        return if (ctx <= 0) null else rep.sumOf { it.cacheRead!! } * 100.0 / ctx
    }

    fun pct(v: Double?): String = v?.let { "${(it * 10).roundToLong() / 10.0}%" } ?: NA
    fun usd(v: Double?): String = v?.let { "~$" + ((it * 100).roundToLong() / 100.0) } ?: NA

    /** pi vs claude vs opencode: one line per harness that has any recorded row. Empty list = "no data yet". */
    fun compare(report: UsageMeterReport): List<HarnessLine> {
        val byHarness = report.rows.groupBy { it.provider }
        return byHarness.map { (h, rows) ->
            val agg = UsageMeterView.total(rows)
            val reqs = report.requests.filter { it.harness == h }
            val cacheKnown = reqs.isEmpty() || reqs.any { it.cacheRead != null }
            val est = rows.map { estimate(it, report.prices) }
            HarnessLine(
                harness = h, turns = agg.turns,
                tokensPerTurn = if (agg.turns > 0) agg.tokens / agg.turns else null,
                avgContext = if (agg.turns > 0) agg.contextSum / agg.turns else null,
                cacheHit = if (!cacheKnown || agg.context <= 0) null else agg.cacheRead * 100.0 / agg.context,
                estCost = if (est.isNotEmpty() && est.all { it != null }) est.sumOf { it!! } else null,
            )
        }.sortedByDescending { it.turns }
    }

    /** Context per request for one session, oldest first (null entries = harness did not report). */
    fun contextSeries(requests: List<UsageMeterRequest>, sessionId: String): List<Long?> =
        requests.filter { it.sessionId == sessionId }.sortedBy { it.ts }.map { it.context }

    /** Sessions that have requests, most recently active first. */
    fun sessions(requests: List<UsageMeterRequest>): List<String> =
        requests.groupBy { it.sessionId }.entries.sortedByDescending { e -> e.value.maxOf { it.ts } }.map { it.key }

    fun costKey(r: UsageMeterRequest, by: CostBy): String = when (by) {
        CostBy.SESSION -> r.sessionId.take(8)
        CostBy.TICKET -> r.ticketId?.take(8) ?: "(no ticket)"
        CostBy.ROLE -> r.role
        CostBy.MODEL -> "${r.harness}/${r.model ?: NA}"
        CostBy.HARNESS -> r.harness
    }

    /** "1 turn", "2 turns". */
    fun turns(n: Int): String = if (n == 1) "1 turn" else "$n turns"

    /** A share 0..1 as "12%", "<1%" for a sliver, "n/a" when unknown. */
    fun share(v: Double?): String = when {
        v == null -> NA
        v > 0 && v < 0.005 -> "<1%"
        else -> "${(v * 100).roundToLong()}%"
    }

    private fun short(id: String) = id.take(8)

    private fun label(r: UsageMeterRequest, by: CostBy, names: CostNames): Pair<String, String?> = when (by) {
        CostBy.SESSION -> (names.sessions[r.sessionId] ?: r.role) to short(r.sessionId)
        CostBy.TICKET -> r.ticketId?.let { (names.tickets[it] ?: short(it)) to short(it) } ?: ("(no ticket)" to null)
        CostBy.ROLE -> r.role to null
        CostBy.MODEL -> (r.model ?: NA) to r.harness
        CostBy.HARNESS -> r.harness to null
    }

    /** Ranked rows, biggest cost first, unpriced rows last; share is of the priced total. Nothing is invented. */
    fun costTable(report: UsageMeterReport, by: CostBy, names: CostNames = CostNames()): CostTable {
        val groups = report.requests.groupBy { costKey(it, by) }
        val raw = groups.map { (k, rs) ->
            val e = rs.map { estimate(it, report.prices) }
            val (label, detail) = label(rs.first(), by, names)
            CostRow(k, label, detail, rs.size, if (e.any { it != null }) e.sumOf { it ?: 0.0 } else null, null)
        }
        val total = raw.mapNotNull { it.cost }.takeIf { it.isNotEmpty() }?.sum()
        val rows = raw.map { it.copy(share = if (it.cost != null && total != null && total > 0) it.cost / total else null) }
            .sortedWith(compareByDescending<CostRow> { it.cost ?: -1.0 }.thenByDescending { it.turns })
        return CostTable(rows, total, raw.sumOf { it.turns }, raw.count { it.cost == null })
    }

    /** Estimated cost per key, biggest first; a key whose requests have no price shows null. */
    fun costBy(report: UsageMeterReport, by: CostBy): List<Triple<String, Int, Double?>> =
        report.requests.groupBy { costKey(it, by) }.map { (k, rs) ->
            val e = rs.map { estimate(it, report.prices) }
            Triple(k, rs.size, if (e.any { it != null }) e.sumOf { it ?: 0.0 } else null)
        }.sortedByDescending { it.third ?: -1.0 }
}
