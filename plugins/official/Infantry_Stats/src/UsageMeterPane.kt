package com.superiorsoftllc.agenthq.plugins.official.stats

import androidx.compose.foundation.background
import com.superiorsoftllc.agenthq.ui.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.NumericText
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.SegmentedControl
import com.superiorsoftllc.agenthq.ui.StatChip
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.contractAtLeast
import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterReport
import com.superiorsoftllc.agenthq.ui.shell.UsageMeterContext

const val USAGE_METER_TAG = "stats-usage-meter"

/** USAGE-1: the daemon usage meter in Stats: tokens by day or week, split by source / role / model. */
@Composable
fun UsageMeterPane(state: AppState, meter: UsageMeterContext) {
    val supported = contractAtLeast(state.connection.contract, 1, 141)
    val live = state.connection.isLive
    // STATS-ACC-1: push, not poll. One request when the pane opens (and again after a reconnect); from then on the daemon
    // sends a fresh `usage_meter` frame whenever a turn is recorded, so there is no timer here.
    LaunchedEffect(live, supported) {
        if (live && supported) meter.refresh()
    }
    Box(Modifier.fillMaxSize().testTag(USAGE_METER_TAG)) {
        val report = state.usageMeter
        when {
            !supported -> Note("This daemon has no usage meter. Update it to contract 1.141.0 or later.")
            report == null -> Note("Reading the usage meter...")
            else -> UsageMeterBody(report, remember(state.tickets, state.agents) { CostNames(state.tickets.associate { it.id to it.title }, state.agents.mapValues { (_, a) -> a.role ?: a.session.provider }) })
        }
    }
}

@Composable
private fun Note(text: String) = Box(Modifier.fillMaxSize(), Alignment.Center) { Text(text, color = Palette.Muted, fontSize = 12.sp) }

const val USAGE_TOKENS_GROUP_TAG = "stats-tokens-group"
const val USAGE_TOGGLE_TAG = "stats-tokens-toggle"

/**
 * USAGE-UI-1: the toggle bar is pinned above the scroll area (sticky); the first scrolled content is ONE group holding exactly
 * what the toggles change (turns trend + one block per period). Static panels follow in an overview, outside the group.
 */
@Composable
fun UsageMeterBody(report: UsageMeterReport, names: CostNames = CostNames()) {
    var weekly by remember { mutableStateOf(false) }
    var split by remember { mutableStateOf(MeterSplit.SOURCE) }
    val periods = remember(report, weekly) { UsageMeterView.periods(report.rows, weekly) }
    val scroll = rememberScrollState()
    LaunchedEffect(weekly, split) { scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = 14.dp, vertical = 8.dp).testTag(USAGE_TOGGLE_TAG),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SegmentedControl("Period", listOf(false, true), weekly, { if (it) "By week" else "By day" }) { weekly = it }
            SegmentedControl("Group by", MeterSplit.entries.toList(), split, { it.label }) { split = it }
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Raised).padding(12.dp).testTag(USAGE_TOKENS_GROUP_TAG)) {
                Text("TOKENS ${if (weekly) "BY WEEK" else "BY DAY"}", color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                if (periods.isEmpty()) Text("No runs recorded yet.", color = Palette.Muted, fontSize = 12.sp)
                if (periods.isNotEmpty()) TurnsTrend(periods.map { (k, r) -> k to UsageMeterView.total(r).turns }, weekly)
                periods.forEachIndexed { i, (key, rows) ->
                    val t = UsageMeterView.total(rows)
                    val prev = periods.getOrNull(i + 1)?.let { UsageMeterView.total(it.second).tokens }
                    val prevTurns = periods.getOrNull(i + 1)?.let { UsageMeterView.total(it.second).turns }
                    PeriodBlock((if (weekly) "week of " else "") + key, t, prev, prevTurns, UsageMeterView.split(rows, split))
                }
            }
            Text("OVERVIEW (does not change with the toggles)", color = Palette.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 6.dp))
            Text(
                "Provider-reported only. Per-run rows kept this and last week, then daily rollups for ${report.keepWeeks} weeks.",
                color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                "A turn is one model request and its response (one provider usage report). A run is one agent process from prompt to exit; it holds many turns.",
                color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 8.dp).testTag("stats-turn-definition"),
            )
            UsageMeterExtras(report, names)
            // STALL-2: the job watcher's counters, all daemon-side work with no model call behind it.
            report.watch?.let { w ->
                Text(
                    "Job watcher: ${w.events} events, ${w.heartbeats} heartbeats, ${w.hookRewrites} commands routed by hooks (${w.hookOverrides} overridden by another hook), ${w.optOuts} opt-outs, ${w.stallNudges} stall nudges" +
                        (if (w.note.isNotBlank()) " (${w.note})" else ""),
                    color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 8.dp).testTag("stats-watch-meter"),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** One period: heading with total and delta, labelled stat chips, then the split rows with aligned numeric columns. */
@Composable
private fun PeriodBlock(title: String, t: MeterAgg, prev: Long?, prevTurns: Long?, rows: List<Pair<String, MeterAgg>>) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Panel).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${UsageMeterView.compact(t.tokens)} tokens", color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(deltaText(UsageMeterView.delta(t.tokens, prev)), color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatChip("Runs", "${t.runs}")
            StatChip("Turns", "${t.turns} (${deltaText(UsageMeterView.delta(t.turns, prevTurns))})")
            StatChip("In / out", "${UsageMeterView.compact(t.input)} / ${UsageMeterView.compact(t.output)}")
            StatChip("Cache r / w", "${UsageMeterView.compact(t.cacheRead)} / ${UsageMeterView.compact(t.cacheWrite)}")
            StatChip("Cache hit", UsageMeterView.cacheHit(t))
            StatChip("Avg ctx/turn", UsageMeterView.avgContext(t))
            StatChip("Cost", UsageMeterView.cost(t))
        }
        for ((name, a) in rows) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = Palette.Text, fontSize = 11.sp, modifier = Modifier.weight(1f))
                NumericText("${a.turns} turns", 72.dp, color = Palette.Muted)
                NumericText(UsageMeterView.compact(a.tokens), 64.dp)
                NumericText("hit ${UsageMeterView.cacheHit(a)}", 80.dp, color = Palette.Muted)
                NumericText("ctx ${UsageMeterView.avgContext(a)}", 72.dp, color = Palette.Muted)
                NumericText(UsageMeterView.cost(a), 64.dp)
            }
        }
    }
}

private fun deltaText(d: String) = if (d == "-") "no previous" else "$d vs previous"

@Composable
private fun Card(title: String, tag: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp).testTag(tag)) {
        Text(title, color = Palette.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

/** STATS-ACC-1: harness comparison, cost estimates, context per session and compactions; only recorded data. */
@Composable
fun UsageMeterExtras(report: UsageMeterReport, names: CostNames = CostNames()) {
    var costBy by remember { mutableStateOf(CostBy.SESSION) }
    val lines = remember(report) { UsageRequestView.compare(report) }
    Card("HARNESS COMPARISON (turns, tokens per turn, cache hit, estimated cost)", "stats-compare") {
        if (lines.isEmpty()) Text("No data yet.", color = Palette.Muted, fontSize = 12.sp)
        for (l in lines) Row(Modifier.fillMaxWidth()) {
            Text(l.harness, color = Palette.Text, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(
                "${l.turns} turns  ${l.tokensPerTurn?.let { UsageMeterView.compact(it) } ?: UsageRequestView.NA}/turn  ctx ${l.avgContext?.let { UsageMeterView.compact(it) } ?: UsageRequestView.NA}  " +
                    "hit ${UsageRequestView.pct(l.cacheHit)}  ${UsageRequestView.usd(l.estCost)}",
                color = Palette.Muted, fontSize = 11.sp,
            )
        }
        if (lines.isNotEmpty()) Text("Cost is an estimate from the price table in the daemon's usage-prices.json, not a bill.", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
    }
    CostPanel(report, names, costBy) { costBy = it }
    Card("CONTEXT PER SESSION (tokens re-read each turn, oldest first)", "stats-context") {
        val sessions = remember(report) { UsageRequestView.sessions(report.requests).take(6) }
        if (sessions.isEmpty()) Text("No data yet.", color = Palette.Muted, fontSize = 12.sp)
        for (id in sessions) {
            val series = UsageRequestView.contextSeries(report.requests, id)
            val max = (series.filterNotNull().maxOrNull() ?: 0L).coerceAtLeast(1L)
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(id.take(8), color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.width(60.dp))
                Row(Modifier.weight(1f).height(14.dp), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.Bottom) {
                    for (v in series.takeLast(60)) Box(Modifier.weight(1f).fillMaxHeight(if (v == null) 0.05f else (v.toFloat() / max).coerceIn(0.05f, 1f)).background(if (v == null) Palette.Border else Palette.Accent))
                }
                Text(series.lastOrNull()?.let { UsageMeterView.compact(it) } ?: UsageRequestView.NA, color = Palette.Text, fontSize = 10.sp, modifier = Modifier.width(48.dp).padding(start = 6.dp))
            }
        }
        for (e in report.events.takeLast(5).reversed()) Text(
            "${e.kind} in ${e.sessionId.take(8)} (${e.harness}${e.trigger?.let { ", $it" } ?: ""}), context before ${e.tokensBefore?.let { UsageMeterView.compact(it) } ?: UsageRequestView.NA}",
            color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Turns per period as bars, oldest at the top; [periods] is newest first. */
@Composable
private fun TurnsTrend(periods: List<Pair<String, Long>>, weekly: Boolean) {
    val shown = periods.take(14).reversed()
    val max = (shown.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Panel).padding(10.dp)) {
        Text("TURNS (MODEL REQUESTS) PER ${if (weekly) "WEEK" else "DAY"}", color = Palette.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        for ((key, turns) in shown) {
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(key, color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.width(76.dp))
                Box(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(turns.toFloat() / max).height(8.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Accent))
                }
                NumericText("$turns", 54.dp, modifier = Modifier.padding(start = 6.dp), fontSize = 10)
            }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (on) Palette.Accent else Palette.Muted, fontSize = 10.sp,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (on) Palette.Border else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** CHART-STYLE-1: the "Estimated cost by" card as a ranked list (docs/CHART-STYLE.md): label, bar, value, share, total. */
@Composable
private fun CostPanel(report: UsageMeterReport, names: CostNames, by: CostBy, onBy: (CostBy) -> Unit) {
    val table = remember(report, by, names) { UsageRequestView.costTable(report, by, names) }
    var picked by remember(by) { mutableStateOf<String?>(null) }
    Card("ESTIMATED COST BY", "stats-cost") {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (c in CostBy.entries) Toggle(c.label, by == c) { onBy(c) } }
        Text(
            "Estimate, not a bill: tokens recorded per model request x the price table in the daemon's usage-prices.json. Biggest first; share is of the priced total.",
            color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp).testTag("stats-cost-note"),
        )
        if (table.rows.isEmpty()) Text("No data yet.", color = Palette.Muted, fontSize = 12.sp)
        val max = (table.rows.firstOrNull()?.cost ?: 0.0).coerceAtLeast(1e-9)
        for (r in table.rows.take(12)) {
            val open = picked == r.key
            val desc = "${r.label}${r.detail?.let { ", $it" } ?: ""}: ${UsageRequestView.turns(r.turns)}, ${UsageRequestView.usd(r.cost)}, ${UsageRequestView.share(r.share)} of total"
            Column(
                Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(4.dp))
                    .clickable(onClickLabel = "Show detail") { picked = if (open) null else r.key }
                    .semantics(mergeDescendants = true) { contentDescription = desc }.testTag("stats-cost-row"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.label, color = Palette.Text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        r.detail?.let { Text(it, color = Palette.Muted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp)) }
                    }
                    Text(UsageRequestView.usd(r.cost), color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 56.dp))
                    Text(UsageRequestView.share(r.share), color = Palette.Muted, fontSize = 10.sp, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
                }
                Box(Modifier.fillMaxWidth().padding(top = 2.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Border)) {
                    if (r.cost != null) Box(Modifier.fillMaxWidth((r.cost / max).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().background(Palette.Accent))
                }
                if (open) Text(
                    UsageRequestView.turns(r.turns) + (r.cost?.let { " | ${UsageRequestView.usd(it / r.turns.coerceAtLeast(1))} per turn" } ?: " | no price for this model"),
                    color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (table.rows.size > 12) Text("+${table.rows.size - 12} more not shown", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        if (table.rows.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(Palette.Border))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp).testTag("stats-cost-total")) {
                Text("Total", color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(UsageRequestView.turns(table.turns), color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp))
                Spacer(Modifier.weight(1f))
                Text(UsageRequestView.usd(table.total), color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            if (table.unpriced > 0) Text("${table.unpriced} without a price are not in the total", color = Palette.Muted, fontSize = 10.sp)
        }
    }
}
