package com.superiorsoftllc.agenthq.plugins.official.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.contractAtLeast
import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterReport
import com.superiorsoftllc.agenthq.ui.shell.UsageMeterContext
import kotlinx.coroutines.delay

const val USAGE_METER_TAG = "stats-usage-meter"

/** USAGE-1: the daemon usage meter in Stats: tokens by day or week, split by source / role / model. */
@Composable
fun UsageMeterPane(state: AppState, meter: UsageMeterContext) {
    val supported = contractAtLeast(state.connection.contract, 1, 141)
    val live = state.connection.isLive
    LaunchedEffect(live, supported) {
        if (!live || !supported) return@LaunchedEffect
        while (true) { meter.refresh(); delay(15_000) }
    }
    Box(Modifier.fillMaxSize().testTag(USAGE_METER_TAG)) {
        val report = state.usageMeter
        when {
            !supported -> Note("This daemon has no usage meter. Update it to contract 1.141.0 or later.")
            report == null -> Note("Reading the usage meter...")
            else -> UsageMeterBody(report)
        }
    }
}

@Composable
private fun Note(text: String) = Box(Modifier.fillMaxSize(), Alignment.Center) { Text(text, color = Palette.Muted, fontSize = 12.sp) }

@Composable
fun UsageMeterBody(report: UsageMeterReport) {
    var weekly by remember { mutableStateOf(false) }
    var split by remember { mutableStateOf(MeterSplit.SOURCE) }
    val periods = remember(report, weekly) { UsageMeterView.periods(report.rows, weekly) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TOKENS", color = Palette.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Spacer(Modifier.width(8.dp))
            Toggle("By day", !weekly) { weekly = false }
            Toggle("By week", weekly) { weekly = true }
            Spacer(Modifier.width(12.dp))
            for (s in MeterSplit.entries) Toggle(s.label, split == s) { split = s }
        }
        Text(
            "Provider-reported only. Per-run rows kept this and last week, then daily rollups for ${report.keepWeeks} weeks.",
            color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        if (periods.isEmpty()) Text("No runs recorded yet.", color = Palette.Muted, fontSize = 12.sp)
        periods.forEachIndexed { i, (key, rows) ->
            val t = UsageMeterView.total(rows)
            val prev = periods.getOrNull(i + 1)?.let { UsageMeterView.total(it.second).tokens }
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (weekly) "week of " else "") + key, color = Palette.Accent, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${UsageMeterView.compact(t.tokens)} tokens  (${UsageMeterView.delta(t.tokens, prev)} vs previous)", fontSize = 12.sp)
                }
                Text(
                    "${t.runs} runs, ${t.turns} turns | in ${UsageMeterView.compact(t.input)} out ${UsageMeterView.compact(t.output)} " +
                        "cache read ${UsageMeterView.compact(t.cacheRead)} write ${UsageMeterView.compact(t.cacheWrite)} | " +
                        "cache hit ${UsageMeterView.cacheHit(t)} | avg context/turn ${UsageMeterView.avgContext(t)} | cost ${UsageMeterView.cost(t)}",
                    color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                )
                for ((name, a) in UsageMeterView.split(rows, split)) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                        Text(name, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text(
                            "${UsageMeterView.compact(a.tokens)}  hit ${UsageMeterView.cacheHit(a)}  ctx ${UsageMeterView.avgContext(a)}  ${UsageMeterView.cost(a)}",
                            color = Palette.Muted, fontSize = 11.sp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
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
