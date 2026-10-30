package com.superiorsoftllc.agenthq.plugins.official.pace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.clickable

/**
 * The colours the pace chart draws with, all `Palette` tokens (docs/CHART-STYLE.md). The contrast test reads these lists, so a
 * colour added to the drawing must be added here and is then checked in both themes.
 */
object PaceColors {
    val actual: Color get() = Palette.Accent
    val currentForecast: Color get() = Palette.Muted
    val recentForecast: Color get() = Palette.Warn
    val clipWarning: Color get() = Palette.Text // Danger is 4.39:1 on the dark card; the words carry the warning
    /** The arrow and the word carry the meaning; the text stays in the readable tone (Danger / Ok miss 4.5:1 on the dark card). */
    fun trend(t: Trend): Color = if (t == Trend.UNKNOWN) Palette.Muted else Palette.Text
}

const val PACE_CARD_TAG = "pace-card"
const val PACE_CLIP_TAG = "pace-clip-warning"
const val PACE_TREND_TAG = "pace-trend"
const val PACE_EMPTY_TAG = "pace-card-empty"
const val PACE_TOOLTIP_TAG = "pace-tooltip"
const val PACE_FORECAST_TAG = "pace-forecast"
const val PACE_TOKENS_TAG = "pace-tokens"
const val PACE_BURN_TAG = "pace-burn"

/**
 * One provider window as a titled chart card (the "Over the session" look): title with a caption, the Pace delta / Used toggle at
 * the top right, range and scale chips with the active one highlighted, labelled y ticks with a faint gridline, a line with a
 * soft area fill, first / last time labels, the dashed forecast to reset, and an explicit line when a value is clipped.
 */
@Composable
fun PaceCard(s: PaceSeries, now: Long, tokens: PaceTokens.Totals?, markers: List<PaceSessions.Marker> = emptyList()) {
    var metric by remember(s.key) { mutableStateOf(PaceMetric.PACE) }
    var range by remember(s.key) { mutableStateOf(PaceRange.WINDOW) }
    var scale by remember(s.key, metric) { mutableStateOf<PaceScale>(PaceScale.Fit) }
    var customOpen by remember(s.key) { mutableStateOf(false) }
    var customText by remember(s.key) { mutableStateOf("") }
    val spec = remember(s, metric, range, scale, now) { PaceChartModel.spec(s, metric, range, scale, now) }
    var hoverT by remember(s.key, metric, range) { mutableStateOf<Long?>(null) }
    val shape = RoundedCornerShape(6.dp)

    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape).background(Palette.Raised).border(1.dp, Palette.Border, shape).padding(12.dp).testTag(PACE_CARD_TAG)) {
        // Title, caption, momentum; the metric toggle sits top right.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Text("${s.provider} \u00B7 ${s.window}", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Text(PaceChartModel.caption(s), color = Palette.Muted, fontSize = 11.sp, maxLines = 1)
                }
                Text(
                    s.trend.label, color = PaceColors.trend(s.trend), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp).testTag(PACE_TREND_TAG).semantics { contentDescription = "Momentum: ${s.trend.word}" },
                )
            }
            Row { for (m in PaceMetric.toggle) Chip(m.label, metric == m, "pace-metric-${m.name.lowercase()}") { metric = m } }
        }
        // Range and scale buttons.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Range", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(end = 6.dp))
            for (r in PaceRange.entries) Chip(r.label, range == r, "pace-range-${r.name.lowercase()}") { range = r }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Scale", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(end = 6.dp))
            for (p in PaceScales.presets(metric)) {
                val label = if (metric == PaceMetric.USED) "${p.toInt()}%" else "\u00B1${p.toInt()}"
                Chip(label, scale == PaceScale.Fixed(p), "pace-scale-${p.toInt()}") { scale = PaceScale.Fixed(p) }
            }
            Chip("Fit", scale == PaceScale.Fit, "pace-scale-fit") { scale = PaceScale.Fit }
            Chip("Custom", customOpen, "pace-scale-custom") { customOpen = !customOpen }
            Chip("Reset to default", false, "pace-scale-reset", enabled = scale != PaceScale.Fit) { scale = PaceScale.Fit; customOpen = false }
        }
        if (customOpen) {
            val parsed = PaceScales.parseCustom(customText)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                Box(Modifier.width(110.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, Palette.Border, RoundedCornerShape(4.dp)).padding(6.dp)) {
                    BasicTextField(
                        value = customText, onValueChange = { customText = it }, singleLine = true,
                        textStyle = TextStyle(color = Palette.Text, fontSize = 11.sp), cursorBrush = SolidColor(Palette.Text),
                        modifier = Modifier.testTag("pace-scale-custom-input"),
                    )
                    if (customText.isEmpty()) Text("e.g. 120", color = Palette.Muted, fontSize = 11.sp)
                }
                Spacer(Modifier.width(6.dp))
                Chip("Set", false, "pace-scale-custom-set", enabled = parsed != null) { parsed?.let { scale = PaceScale.Fixed(it); customOpen = false } }
            }
        }
        if (spec.clipped) Text(
            spec.clipNote.orEmpty(), color = PaceColors.clipWarning, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 2.dp).testTag(PACE_CLIP_TAG),
        )
        if (spec.empty) {
            Box(Modifier.fillMaxWidth().height(110.dp).testTag(PACE_EMPTY_TAG), Alignment.Center) {
                Text(spec.emptyText.orEmpty(), color = Palette.Muted, fontSize = 12.sp)
            }
        } else {
            val mine = markers.filter { it.meter.equals(s.provider, true) }
            ChartBody(s, metric, spec, hoverT, markers = mine) { hoverT = it }
            val withDate = spec.t1 - spec.t0 > 24 * 3_600_000L
            Row(Modifier.fillMaxWidth().padding(start = 52.dp, top = 2.dp)) {
                Text(PaceChartModel.time(spec.t0, withDate), color = Palette.Muted, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(PaceChartModel.time(spec.t1, withDate), color = Palette.Muted, fontSize = 10.sp)
            }
            Legend(spec)
            if (mine.isNotEmpty()) Text(
                "Marks: ● solid tick = a session started, ○ dashed tick = a session ended (see Sessions below).",
                color = Palette.Muted, fontSize = 10.sp,
            )
        }
        BurnPanel(s, range, now)
        // The tooltip line: exact value and time of the hovered / tapped point (touch needs no hover).
        val hit = hoverT?.let { PaceChartModel.nearest(spec, it) }
        Text(
            hit?.let { PaceChartModel.tooltip(metric, it.first, it.second) } ?: "Hover or tap the chart for an exact value and time.",
            color = if (hit != null) Palette.Text else Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp).testTag(PACE_TOOLTIP_TAG),
        )
        Text(
            if (s.forecast.ready) "Forecast: ${s.forecast.sentence}" else "Forecast: ${s.forecast.sentence.ifBlank { "not enough data yet" }}",
            color = Palette.Text, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp).testTag(PACE_FORECAST_TAG),
        )
        if (s.forecast.ready && !range.showsForecast) Text(
            "The dashed forecast lines are drawn in the 7d and Window ranges, where the reset fits on the chart.",
            color = Palette.Muted, fontSize = 10.sp,
        )
        tokens?.takeIf { it.any }?.let { t ->
            Text(
                "Tokens (informational): ${PaceTokens.line(t)}. The meter percent above drives pacing, not these.",
                color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp).testTag(PACE_TOKENS_TAG),
            )
        }
    }
}

/** The second small panel: burn rate over time with a zero line (idle = 0). Same card style and colours; follows the card's range. */
@Composable
private fun BurnPanel(s: PaceSeries, range: PaceRange, now: Long) {
    val spec = remember(s, range, now) { PaceChartModel.spec(s, PaceMetric.BURN, range, PaceScale.Fit, now) }
    var hoverT by remember(s.key, range) { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag(PACE_BURN_TAG)) {
        Text("Burn rate", color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Percent used gained per hour over a rolling window. The heavy line is 0: nothing being used. Idle, this sits on 0 while the pace delta above drifts down.",
            color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp),
        )
        if (spec.empty) Text("Not enough samples yet for a burn rate.", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
        else {
            ChartBody(s, PaceMetric.BURN, spec, hoverT, height = 70.dp) { hoverT = it }
            val hit = hoverT?.let { PaceChartModel.nearest(spec, it) }
            if (hit != null) Text(PaceChartModel.tooltip(PaceMetric.BURN, hit.first, hit.second), color = Palette.Text, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Legend(spec: ChartSpec) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 52.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Swatch(PaceColors.actual, null); Text(" actual   ", color = Palette.Muted, fontSize = 10.sp)
        if (spec.currentForecast != null) { Swatch(PaceColors.currentForecast, floatArrayOf(8f, 6f)); Text(" dashed: at the current rate   ", color = Palette.Muted, fontSize = 10.sp) }
        if (spec.recentForecast != null) { Swatch(PaceColors.recentForecast, floatArrayOf(2f, 5f)); Text(" dotted: at the recent rate   ", color = Palette.Muted, fontSize = 10.sp) }
        if (spec.zeroLine) Text("heavy line: even pace", color = Palette.Muted, fontSize = 10.sp)
        if (spec.capLine != null) Text("dashed top line: 100% cap", color = Palette.Muted, fontSize = 10.sp)
    }
}

@Composable
private fun Swatch(color: Color, dash: FloatArray?) {
    Canvas(Modifier.width(22.dp).height(8.dp)) {
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.5f, pathEffect = dash?.let { PathEffect.dashPathEffect(it) })
    }
}

@Composable
private fun ChartBody(s: PaceSeries, metric: PaceMetric, spec: ChartSpec, hoverT: Long?, height: androidx.compose.ui.unit.Dp = 130.dp, markers: List<PaceSessions.Marker> = emptyList(), onHover: (Long?) -> Unit) {
    val desc = "${s.provider} ${s.window} ${metric.label} chart, ${spec.actual.sumOf { it.pts.size }} readings, momentum ${s.trend.word}"
    Row(Modifier.fillMaxWidth().height(height)) {
        BoxWithConstraints(Modifier.width(52.dp).height(height)) {
            for (v in spec.ticks) {
                val label = when {
                    metric == PaceMetric.PACE && v == 0.0 -> "0 even"
                    metric == PaceMetric.BURN && v == 0.0 -> "0 idle"
                    metric == PaceMetric.USED && v == 100.0 -> "100 cap"
                    else -> PaceChartModel.fmt(v) + if (metric == PaceMetric.USED) "%" else ""
                }
                Text(
                    label, color = Palette.Muted, fontSize = 9.sp, maxLines = 1,
                    modifier = Modifier.offset(y = (maxHeight * (1f - spec.y(v).toFloat())) - 6.dp).padding(end = 6.dp),
                )
            }
        }
        Canvas(
            Modifier.weight(1f).height(height).testTag(if (metric == PaceMetric.BURN) "pace-burn-chart" else "pace-chart").semantics { contentDescription = desc }
                .pointerInput(spec) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            val pos = e.changes.firstOrNull()?.position
                            when (e.type) {
                                PointerEventType.Move, PointerEventType.Press -> if (pos != null) {
                                    onHover(spec.t0 + ((pos.x / size.width).coerceIn(0f, 1f) * (spec.t1 - spec.t0)).toLong())
                                }
                                PointerEventType.Exit -> onHover(null)
                                else -> {}
                            }
                        }
                    }
                },
        ) {
            val w = size.width; val h = size.height
            val span = (spec.t1 - spec.t0).coerceAtLeast(1L).toFloat()
            fun x(t: Long) = (t - spec.t0) / span * w
            fun y(v: Double) = h - spec.y(v).toFloat() * h
            // Faint gridlines at the labelled ticks.
            for (v in spec.ticks) drawLine(Palette.Border, Offset(0f, y(v)), Offset(w, y(v)), 1f)
            drawLine(Palette.Border, Offset(0f, h), Offset(w, h), 1f)
            clipRect(0f, 0f, w, h) {
                if (spec.zeroLine) drawLine(Palette.Muted, Offset(0f, y(0.0)), Offset(w, y(0.0)), 2.5f)
                spec.capLine?.let { drawLine(Palette.Warn, Offset(0f, y(it)), Offset(w, y(it)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))) }
                // The shaded band between the two forecast landings.
                val c = spec.currentForecast; val r = spec.recentForecast
                if (spec.hasBand && c != null && r != null) {
                    val band = Path().apply {
                        moveTo(x(c.pts.first().first), y(c.pts.first().second))
                        lineTo(x(c.pts.last().first), y(c.pts.last().second))
                        lineTo(x(r.pts.last().first), y(r.pts.last().second))
                        close()
                    }
                    drawPath(band, PaceColors.recentForecast.copy(alpha = 0.18f))
                }
                for (line in spec.actual) {
                    val path = Path(); val fill = Path()
                    line.pts.forEachIndexed { i, (t, v) ->
                        if (i == 0) { path.moveTo(x(t), y(v)); fill.moveTo(x(t), h); fill.lineTo(x(t), y(v)) } else { path.lineTo(x(t), y(v)); fill.lineTo(x(t), y(v)) }
                    }
                    fill.lineTo(x(line.pts.last().first), h); fill.close()
                    drawPath(fill, PaceColors.actual.copy(alpha = 0.15f))
                    drawPath(path, PaceColors.actual, style = Stroke(width = 2.5f))
                }
                c?.let { drawLine(PaceColors.currentForecast, Offset(x(it.pts[0].first), y(it.pts[0].second)), Offset(x(it.pts[1].first), y(it.pts[1].second)), 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))) }
                r?.let { drawLine(PaceColors.recentForecast, Offset(x(it.pts[0].first), y(it.pts[0].second)), Offset(x(it.pts[1].first), y(it.pts[1].second)), 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 8f))) }
                // PACE-TREND-2: a session started (solid tick, filled dot) or ended (dashed tick, hollow dot); the shape differs, never colour alone.
                for (m in markers) if (m.t in spec.t0..spec.t1) {
                    if (m.start) {
                        drawLine(Palette.Text, Offset(x(m.t), 0f), Offset(x(m.t), 18f), 2f); drawCircle(Palette.Text, 4f, Offset(x(m.t), 18f))
                    } else {
                        drawLine(Palette.Muted, Offset(x(m.t), 0f), Offset(x(m.t), 18f), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f)))
                        drawCircle(Palette.Muted, 4f, Offset(x(m.t), 18f), style = Stroke(1.5f))
                    }
                }
                // The hovered / tapped point.
                hoverT?.let { PaceChartModel.nearest(spec, it) }?.let { (t, v) ->
                    drawLine(Palette.Muted, Offset(x(t), 0f), Offset(x(t), h), 1f)
                    drawCircle(PaceColors.actual, 5f, Offset(x(t), y(v)))
                }
            }
        }
    }
}

/** A range / scale / metric button: the active one is highlighted and bold; a disabled one is dimmed and not clickable. */
@Composable
fun Chip(label: String, on: Boolean, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    // A disabled chip stays readable (muted text); it is inert, not hidden.
    val color = if (on) Palette.Text else Palette.Muted
    Text(
        label, color = color, fontSize = 10.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
        modifier = Modifier.testTag(tag).padding(end = 4.dp).clip(RoundedCornerShape(4.dp))
            .background(if (on) Palette.Border else Color.Transparent)
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
