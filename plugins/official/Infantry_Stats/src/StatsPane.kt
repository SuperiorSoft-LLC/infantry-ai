// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.stats

import com.superiorsoftllc.infantry.ui.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.drawscope.clipRect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ChartKind
import com.superiorsoftllc.infantry.ui.model.ChartScale
import com.superiorsoftllc.infantry.ui.model.ChartScaleSetting
import com.superiorsoftllc.infantry.ui.model.ChartScales
import com.superiorsoftllc.infantry.ui.model.StatsView
import com.superiorsoftllc.infantry.ui.model.contractAtLeast
import com.superiorsoftllc.infantry.ui.shell.UsageContext
import com.superiorsoftllc.infantry.ui.protocol.SessionStats
import com.superiorsoftllc.infantry.ui.protocol.StatsPoint
import kotlinx.coroutines.delay

const val STATS_PANE_TAG = "stats-pane"
const val STATS_HARNESS_TAG = "stats-harness"
/** Each tile's value carries `stats-tile-<key>`: duration, status, exchanges, tools, models, session. */
const val STATS_TILE_TAG_PREFIX = "stats-tile-"
const val STATS_COPY_ID_TAG = "stats-copy-session-id"
const val STATS_GAUGE_TAG = "stats-gauge"
const val STATS_GAUGE_PERCENT_TAG = "stats-gauge-percent"
const val STATS_AXIS_TIME_TAG = "stats-axis-time"
const val STATS_AXIS_REQUESTS_TAG = "stats-axis-requests"
const val STATS_CONTEXT_CHART_TAG = "stats-chart-context"
const val STATS_BILLED_CHART_TAG = "stats-chart-billed"
const val STATS_SOURCE_ROW_TAG_PREFIX = "stats-source-"
const val STATS_OVER_SCALE_TAG_PREFIX = "stats-over-scale-"
const val STATS_SCALE_CHIP_TAG_PREFIX = "stats-scale-"
const val STATS_SCALE_RESET_TAG_PREFIX = "stats-scale-reset-"
const val STATS_SCALE_CUSTOM_TAG_PREFIX = "stats-scale-custom-"
const val STATS_LIMIT_PERCENT_TAG = "stats-context-limit-percent"

/** How often the open tab asks again. Only while the pane is composed: leaving the tab stops the loop. */
const val STATS_REFRESH_MS = 3_000L

/**
 * STATS-2: the selected agent's session statistics, painted from the daemon's `session_stats` frame (contract 1.92.0).
 * No reading or computing happens here (HEADLESS-1): the daemon reads the harness's own record, the pane only shows it.
 * Every null field reads "-".
 */
@Composable
fun StatsPane(state: AppState, controller: UsageContext) {
    val agent = state.selected
    val supported = contractAtLeast(state.connection.contract, 1, 92)
    val live = state.connection.isLive
    LaunchedEffect(agent?.id, live, supported) {
        val id = agent?.id ?: return@LaunchedEffect
        if (!live || !supported) return@LaunchedEffect
        while (true) {
            controller.sessionStats(id)
            delay(STATS_REFRESH_MS)
        }
    }
    Column(Modifier.fillMaxSize().testTag(STATS_PANE_TAG)) {
        when {
            agent == null -> Empty("Select an agent to see its session's statistics.")
            !supported -> Empty("This daemon does not report session statistics. Update it to contract 1.92.0 or later.")
            else -> {
                val stats = state.sessionStats[agent.id]
                if (stats == null) Empty("Reading this session's record...")
                else {
                    val host = com.superiorsoftllc.infantry.ui.shell.LocalPlatformHost.current
                    val scales = remember(host) { ChartScales(host::uiPref, host::saveUiPref) }
                    StatsBody(stats, scales)
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) { Text(text, color = Palette.Muted, fontSize = 12.sp) }
}

/** The pane's content for one frame. Public so a test can paint a fake frame without a controller. */
@Composable
fun StatsBody(s: SessionStats, scales: ChartScales = remember { ChartScales() }) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SESSION", color = Palette.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Spacer(Modifier.width(8.dp))
            Chip(s.harness, Palette.Accent, textModifier = Modifier.testTag(STATS_HARNESS_TAG))
            Spacer(Modifier.weight(1f))
            Text("refreshes every ${STATS_REFRESH_MS / 1000}s", color = Palette.Muted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(8.dp))
        TileRow(
            { Tile("Duration", StatsView.duration(s.durationMs), s.startedAt?.let { "since ${StatsView.clock(it)}" }, "duration", Modifier.weight(1f)) },
            { Tile("Status", StatsView.status(s), null, "status", Modifier.weight(1f)) },
        )
        TileRow(
            { Tile("Exchanges", StatsView.exchanges(s), "prompts / turns", "exchanges", Modifier.weight(1f)) },
            { Tile("Tool uses", StatsView.toolUses(s), null, "tools", Modifier.weight(1f)) },
        )
        TileRow(
            { Tile("Models", StatsView.models(s), null, "models", Modifier.weight(1f)) },
            { SessionIdTile(s, Modifier.weight(1f)) },
        )
        TileRow(
            { Tile("Billed tokens", if (s.series.isNullOrEmpty()) StatsView.noUsage(s) else StatsView.compact(s.billedTokens), "all token classes, running total", "billed", Modifier.weight(1f)) },
            { Tile("Cost", StatsView.cost(s), "as reported by the harness", "cost", Modifier.weight(1f)) },
        )

        Section("CONTEXT WINDOW")
        Gauge(s)

        Section("OVER THE SESSION")
        Charts(s, scales)

        Section("WHAT FILLED THE CONTEXT")
        Sources(s)
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, color = Palette.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun TileRow(left: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit, right: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { left(); right() }
}

private val CardShape = RoundedCornerShape(6.dp)
private fun Modifier.card(): Modifier = this.clip(CardShape).background(Palette.Raised).border(1.dp, Palette.Border, CardShape)

@Composable
private fun Tile(label: String, value: String, hint: String?, key: String, modifier: Modifier, trailing: (@Composable () -> Unit)? = null) {
    Column(modifier.card().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label.uppercase(), color = Palette.Muted, fontSize = 10.sp, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(STATS_TILE_TAG_PREFIX + key),
            )
            trailing?.let { Spacer(Modifier.width(6.dp)); it() }
        }
        if (hint != null) Text(hint, color = Palette.Muted, fontSize = 10.sp)
    }
}

@Composable
private fun SessionIdTile(s: SessionStats, modifier: Modifier) {
    val clip = LocalClipboardManager.current
    val full = s.providerSessionId?.takeIf { it.isNotBlank() }
    Tile("Session id", StatsView.shortSessionId(s), "the harness's own id", "session", modifier, trailing = full?.let {
        {
            Text(
                "copy", color = Palette.Accent, fontSize = 10.sp,
                modifier = Modifier.testTag(STATS_COPY_ID_TAG).semantics { contentDescription = "Copy session id" }
                    .clickable { clip.setText(AnnotatedString(it)) }.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    })
}

@Composable
private fun Gauge(s: SessionStats) {
    val percent = StatsView.gaugePercent(s)
    val over = StatsView.gaugeOver(s)
    val barColor = if (over) Palette.Danger else Palette.Accent
    val described = when {
        percent == null -> "Context window: limit unknown"
        over -> "Context window: $percent% of the limit, over"
        else -> "Context window: $percent% of the limit"
    }
    Column(Modifier.fillMaxWidth().card().padding(12.dp).testTag(STATS_GAUGE_TAG).semantics { contentDescription = described }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(StatsView.gaugeLabel(s), color = Palette.Text, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                StatsView.gaugePercentLabel(s), color = if (over) Palette.Danger else Palette.Muted, fontFamily = Mono, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag(STATS_GAUGE_PERCENT_TAG),
            )
        }
        Spacer(Modifier.height(8.dp))
        // No bar without a limit: an unknown window is not an empty one.
        if (percent != null) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Border)) {
                Box(Modifier.fillMaxWidth(percent.coerceIn(0, 100) / 100f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(barColor))
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(
            if (percent == null) "Size of the last request. Limit unknown: no local catalog states this model's window."
            else "Size of the last request against the model's window.",
            color = Palette.Muted, fontSize = 10.sp,
        )
    }
}

@Composable
private fun Charts(s: SessionStats, scales: ChartScales) {
    val series = s.series.orEmpty()
    val timeAvailable = StatsView.hasTimeAxis(s)
    var byTime by remember { mutableStateOf(true) }
    val useTime = byTime && timeAvailable
    Column(Modifier.fillMaxWidth().card().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${series.size} requests", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            AxisToggle("Time", useTime, STATS_AXIS_TIME_TAG, enabled = timeAvailable) { byTime = true }
            Spacer(Modifier.width(4.dp))
            AxisToggle("Requests", !useTime, STATS_AXIS_REQUESTS_TAG, enabled = true) { byTime = false }
        }
        if (series.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(StatsView.noUsage(s), color = Palette.Muted, fontSize = 11.sp)
            return@Column
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Context tokens", color = Palette.Text, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                StatsView.gaugePercent(s)?.let { "$it% of limit" } ?: "limit unknown", color = Palette.Muted, fontSize = 10.sp,
                modifier = Modifier.testTag(STATS_LIMIT_PERCENT_TAG),
            )
        }
        ScaleChart(s, series, useTime, ChartKind.CONTEXT, scales, { it.contextTokens }, s.modelContextLimit, STATS_CONTEXT_CHART_TAG, "Context tokens per request")
        Spacer(Modifier.height(10.dp))
        Text("Billed tokens, running total", color = Palette.Text, fontSize = 11.sp)
        ScaleChart(s, series, useTime, ChartKind.BILLED, scales, { it.cumulativeBilledTokens }, null, STATS_BILLED_CHART_TAG, "Billed tokens running total")
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val first = if (useTime) StatsView.clock(series.first().at) else "#1"
            val last = if (useTime) StatsView.clock(series.last().at) else "#${series.size}"
            Text(first, color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text(last, color = Palette.Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun AxisToggle(label: String, on: Boolean, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val color = when { !enabled -> Palette.Border; on -> Palette.Accent; else -> Palette.Muted }
    Text(
        label, color = color, fontSize = 10.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.testTag(tag).clip(RoundedCornerShape(4.dp))
            .background(if (on) Palette.Border else Color.Transparent)
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** One chart with its scale control. The same code for every harness: only the session id picks the setting. */
@Composable
private fun ScaleChart(
    s: SessionStats, series: List<StatsPoint>, byTime: Boolean, kind: ChartKind, scales: ChartScales,
    value: (StatsPoint) -> Long, limit: Long?, tag: String, description: String,
) {
    val setting = scales.get(s.sessionId, kind)
    val axis = ChartScale.resolve(kind, setting, series.maxOf(value), limit)
    ScaleControl(s.sessionId, kind, setting, scales, hasLimit = limit != null)
    if (axis.overScale) {
        Text(
            "over scale: line clipped at ${StatsView.compact(axis.max)}", color = Palette.Danger, fontSize = 10.sp,
            modifier = Modifier.testTag(STATS_OVER_SCALE_TAG_PREFIX + kind.key),
        )
    }
    LineChart(series, byTime, value, if (ChartScale.limitInside(axis, limit)) limit else null, axis.max, tag, description)
}

@Composable
private fun ScaleControl(sessionId: String, kind: ChartKind, setting: ChartScaleSetting, scales: ChartScales, hasLimit: Boolean) {
    val effective = if (setting == ChartScaleSetting.Default) ChartScaleSetting.Fixed(kind.defaultMax) else setting
    var customOpen by remember(sessionId, kind) { mutableStateOf(false) }
    var customText by remember(sessionId, kind) { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Scale", color = Palette.Muted, fontSize = 10.sp)
        Spacer(Modifier.width(6.dp))
        kind.presets.forEach { p ->
            ScaleChip(StatsView.compact(p), effective == ChartScaleSetting.Fixed(p), STATS_SCALE_CHIP_TAG_PREFIX + kind.key + "-" + p, true) {
                scales.set(sessionId, kind, ChartScaleSetting.Fixed(p))
            }
        }
        if (kind == ChartKind.CONTEXT) {
            ScaleChip("Model limit", effective == ChartScaleSetting.ModelLimit, STATS_SCALE_CHIP_TAG_PREFIX + kind.key + "-limit", hasLimit) {
                scales.set(sessionId, kind, ChartScaleSetting.ModelLimit)
            }
        }
        ScaleChip("Fit", effective == ChartScaleSetting.Fit, STATS_SCALE_CHIP_TAG_PREFIX + kind.key + "-fit", true) {
            scales.set(sessionId, kind, ChartScaleSetting.Fit)
        }
        ScaleChip("Custom", customOpen, STATS_SCALE_CHIP_TAG_PREFIX + kind.key + "-custom", true) { customOpen = !customOpen }
        ScaleChip("Reset to default", false, STATS_SCALE_RESET_TAG_PREFIX + kind.key, setting != ChartScaleSetting.Default) {
            scales.reset(sessionId, kind); customOpen = false
        }
    }
    if (customOpen) {
        val parsed = ChartScale.parseTokens(customText)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Box(Modifier.width(110.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, Palette.Border, RoundedCornerShape(4.dp)).padding(6.dp)) {
                androidx.compose.foundation.text.BasicTextField(
                    value = customText, onValueChange = { customText = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Palette.Text, fontSize = 11.sp, fontFamily = Mono),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Text),
                    modifier = Modifier.testTag(STATS_SCALE_CUSTOM_TAG_PREFIX + kind.key),
                )
                if (customText.isEmpty()) Text("e.g. 300k", color = Palette.Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.width(6.dp))
            ScaleChip("Set", false, STATS_SCALE_CUSTOM_TAG_PREFIX + kind.key + "-set", parsed != null) {
                parsed?.let { scales.set(sessionId, kind, ChartScaleSetting.Fixed(it)); customOpen = false }
            }
        }
    }
}

@Composable
private fun ScaleChip(label: String, on: Boolean, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val color = when { !enabled -> Palette.Border; on -> Palette.Accent; else -> Palette.Muted }
    Text(
        label, color = color, fontSize = 10.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
        modifier = Modifier.testTag(tag).padding(end = 4.dp).clip(RoundedCornerShape(4.dp))
            .background(if (on) Palette.Border else Color.Transparent)
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun LineChart(series: List<StatsPoint>, byTime: Boolean, value: (StatsPoint) -> Long, limit: Long?, maxValue: Long, tag: String, description: String) {
    Row(Modifier.fillMaxWidth().height(110.dp)) {
        Column(Modifier.height(110.dp).width(44.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(StatsView.compact(maxValue), color = Palette.Muted, fontSize = 9.sp)
            Text("0", color = Palette.Muted, fontSize = 9.sp)
        }
        Canvas(Modifier.weight(1f).height(110.dp).testTag(tag).semantics { contentDescription = description }) {
            val w = size.width; val h = size.height
            val t0 = series.first().at ?: 0L
            val span = ((series.last().at ?: 0L) - t0).coerceAtLeast(1L)
            fun x(i: Int, p: StatsPoint): Float =
                if (byTime) ((p.at!! - t0).toFloat() / span) * w
                else if (series.size == 1) w / 2 else i.toFloat() / (series.size - 1) * w
            fun y(v: Long): Float = h - (v.toFloat() / maxValue) * h
            listOf(0.5f).forEach { f -> drawLine(Palette.Border, Offset(0f, h * f), Offset(w, h * f), 1f) }
            drawLine(Palette.Border, Offset(0f, h), Offset(w, h), 1f)
            val line = Path(); val fill = Path()
            series.forEachIndexed { i, p ->
                val px = x(i, p); val py = y(value(p))
                if (i == 0) { line.moveTo(px, py); fill.moveTo(px, h); fill.lineTo(px, py) } else { line.lineTo(px, py); fill.lineTo(px, py) }
            }
            fill.lineTo(x(series.lastIndex, series.last()), h); fill.close()
            // Past the axis the line is clipped by the canvas, never rescaled; the "over scale" marker says so.
            clipRect(0f, 0f, w, h) {
                drawPath(fill, Palette.Accent.copy(alpha = 0.15f))
                drawPath(line, Palette.Accent, style = Stroke(width = 1.5f))
            }
            if (limit != null && limit > 0) {
                val ly = y(limit)
                drawLine(Palette.Muted, Offset(0f, ly), Offset(w, ly), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
            }
        }
    }
}


@Composable
private fun Sources(s: SessionStats) {
    val rows = s.topSources.orEmpty()
    Column(Modifier.fillMaxWidth().card().padding(12.dp)) {
        Row {
            Text("SOURCE", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("CALLS", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.width(56.dp))
            Text("TOKENS ADDED", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.width(150.dp))
        }
        if (rows.isEmpty()) {
            Text(StatsView.NONE, color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        val max = rows.maxOfOrNull { it.tokensAdded ?: 0L }?.coerceAtLeast(1L) ?: 1L
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).testTag(STATS_SOURCE_ROW_TAG_PREFIX + r.tool), verticalAlignment = Alignment.CenterVertically) {
                Text(r.tool, color = Palette.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(r.calls.toString(), color = Palette.Text, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.width(56.dp))
                Column(Modifier.width(150.dp)) {
                    Text(StatsView.compact(r.tokensAdded), color = Palette.Text, fontFamily = Mono, fontSize = 12.sp)
                    r.tokensAdded?.let { added ->
                        Box(Modifier.padding(top = 3.dp).width(120.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Border)) {
                            Box(Modifier.fillMaxWidth((added.toFloat() / max).coerceIn(0f, 1f)).height(4.dp).background(Palette.Accent))
                        }
                    }
                }
            }
        }
        s.unattributedToolTokens?.let {
            Text(
                "${StatsView.compact(it)} tokens followed steps that ran several tools at once and are not split between them.",
                color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
