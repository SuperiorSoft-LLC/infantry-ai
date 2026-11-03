package com.superiorsoftllc.infantry.plugins.official.pace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.plugin.api.DaemonAccess
import com.superiorsoftllc.infantry.plugin.api.DaemonReply
import com.superiorsoftllc.infantry.ui.Palette
import kotlinx.coroutines.delay

/*
 * USAGE-FIT: the plugin child injected into the Stats pane (module.json `inject` into infantry.shell.stats). Collapsed by default:
 * one header row, so nothing around it moves. Expanded: headline, confidence, table, three charts. Read-only; one daemon call
 * on expand and each minute while open.
 */

const val USAGEFIT_ROUTE = "GET /plugins/Infantry_Pace/usagefit"
const val USAGEFIT_HEADER_TAG = "usagefit-header"
const val USAGEFIT_BODY_TAG = "usagefit-body"
const val USAGEFIT_REFRESH_MS = 60_000L

/** Colours of the charts, all checked against Palette.Raised (UsageFitContrastTest). */
object UsageFitColors {
    val roles: List<Color> get() = listOf(Palette.Accent, Palette.Info, Palette.Ok, Palette.Warn, Palette.Muted, Palette.Danger)
    fun role(i: Int): Color = roles[i % roles.size]
    val actual: Color get() = Palette.Info
    val fitted: Color get() = Palette.Warn
    val line: Color get() = Palette.Accent
}

sealed interface UsageFitState {
    object Loading : UsageFitState
    data class Failed(val why: String) : UsageFitState
    data class Ready(val view: UsageFitView) : UsageFitState

    companion object {
        fun of(reply: DaemonReply?): UsageFitState = when (reply) {
            null -> Loading
            is DaemonReply.Refused -> Failed("The host did not send the request: ${reply.reason}")
            is DaemonReply.Failed -> Failed("The daemon did not answer: ${reply.reason}")
            is DaemonReply.Ok -> UsageFitParser.parse(reply.body)?.let { Ready(it) }
                ?: Failed("The daemon answered, but not with a usage-fit report (the Infantry_Pace daemon half may be off or older).")
        }
    }
}

@Composable
fun UsageFitSection(daemon: DaemonAccess?) {
    var open by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf<DaemonReply?>(null) }
    var kind by remember { mutableStateOf("weekly") }
    LaunchedEffect(open, daemon) {
        if (!open || daemon == null) return@LaunchedEffect
        while (true) {
            reply = runCatching { daemon.call(USAGEFIT_ROUTE, null) }.getOrElse { DaemonReply.Failed(it.message ?: "error") }
            delay(USAGEFIT_REFRESH_MS)
        }
    }
    Column(Modifier.fillMaxWidth().background(Palette.Raised)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp).testTag(USAGEFIT_HEADER_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text((if (open) "▾ " else "▸ ") + "Usage fit: tokens per meter point", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("  (what one percent of the plan costs in tokens)", color = Palette.Muted, fontSize = 11.sp)
        }
        if (open) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(12.dp).testTag(USAGEFIT_BODY_TAG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (daemon == null) Note("Not connected to the daemon, so there is nothing to analyse yet.")
                else when (val s = UsageFitState.of(reply)) {
                    UsageFitState.Loading -> Note("Loading…")
                    is UsageFitState.Failed -> Note(s.why)
                    is UsageFitState.Ready -> UsageFitBody(s.view, kind) { kind = it }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) = Text(text, color = Palette.Muted, fontSize = 11.sp)

@Composable
private fun UsageFitBody(view: UsageFitView, kind: String, onKind: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (k in listOf("weekly", "session")) {
            val sel = k == kind
            Text(
                if (k == "weekly") "Weekly" else "5-hour", color = if (sel) Palette.Bg else Palette.Text, fontSize = 12.sp,
                modifier = Modifier.background(if (sel) Palette.Accent else Palette.Panel).clickable { onKind(k) }
                    .padding(horizontal = 10.dp, vertical = 4.dp).testTag("usagefit-kind-$k"),
            )
        }
    }
    val w = view.window(kind)
    if (w == null) {
        Note("Not enough data yet: the daemon has no ${UsageFitText.kindName(kind)} meter samples.")
        return
    }
    Text(UsageFitText.headline(w), color = Palette.Text, fontSize = 13.sp, modifier = Modifier.testTag("usagefit-headline"))
    Text(UsageFitText.confidence(w), color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag("usagefit-confidence"))
    UsageFitText.spreadLine(w)?.let { Note(it) }
    UsageFitText.fitLine(w)?.let { Note(it) }
    UsageFitText.unexplained(w)?.let { Note(it) }
    if (w.groups.isNotEmpty()) GroupTable(w)
    if (w.series.size >= 2) {
        PerPointChart(w)
        RoleShareChart(w)
        if (w.series.any { it.fitted != null }) FitChart(w)
    }
    Note("${UsageFitText.POINT_HELP}; ${UsageFitText.CACHE_READ_HELP}; ${UsageFitText.CACHE_WRITE_HELP}; ${UsageFitText.INPUT_HELP}; ${UsageFitText.REQUESTS_HELP}.")
}

@Composable
private fun GroupTable(w: FitWindowView) {
    Column(Modifier.testTag("usagefit-table")) {
        Row(Modifier.fillMaxWidth()) {
            for ((t, wt) in listOf("Role" to 1f, "Model" to 1f, "All requests" to 0.9f, "All cache-read" to 1f, "Requests in steps" to 1f, "Cache-read in steps" to 1.1f, "Output in steps" to 1f, "Share" to 0.7f)) {
                Text(t, color = Palette.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(wt))
            }
        }
        for (g in w.groups) {
            Row(Modifier.fillMaxWidth()) {
                Text(g.role, color = Palette.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(UsageFitText.modelName(g.model), color = Palette.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(g.totalRequests.toString(), color = Palette.Text, fontSize = 12.sp, modifier = Modifier.weight(0.9f))
                Text(UsageFitText.tokens(g.totalCacheRead), color = Palette.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(g.requests.toString(), color = Palette.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(UsageFitText.tokens(g.cacheRead), color = Palette.Text, fontSize = 12.sp, modifier = Modifier.weight(1.1f))
                Text(UsageFitText.tokens(g.output), color = Palette.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(UsageFitText.percent(g.share), color = Palette.Text, fontSize = 12.sp, modifier = Modifier.weight(0.7f))
            }
        }
        Note(UsageFitText.SHARE_HELP + ". \"All\" columns count every recorded request over the analysed window; \"in steps\" columns count only requests inside a rising meter step (the share and the fit use those), so a low in-steps number does not mean requests were not recorded.")
    }
}

@Composable
private fun ChartFrame(title: String, xLabel: String, yLabel: String, explain: String, tag: String, content: @Composable () -> Unit) {
    Column(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text("↑ $yLabel", color = Palette.Muted, fontSize = 10.sp)
        content()
        Text("→ $xLabel", color = Palette.Muted, fontSize = 10.sp)
        Text(explain, color = Palette.Muted, fontSize = 11.sp)
    }
}

private fun Modifier.chartBox() = this.fillMaxWidth().height(110.dp).background(Palette.Panel)

@Composable
private fun PerPointChart(w: FitWindowView) {
    val ys = w.series.map { it.cacheReadPerPoint }
    val max = (ys.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    ChartFrame(
        "Tokens per point, step by step", "time (each dot is one meter step)", "cache-read tokens per point (top = ${UsageFitText.tokens(max)})",
        "A flat line means a point always costs about the same; spikes are steps that were unusually cheap or expensive.", "usagefit-chart-perpoint",
    ) {
        Canvas(Modifier.chartBox()) {
            val n = ys.size
            val pts = ys.mapIndexed { i, y -> Offset(size.width * i / (n - 1).coerceAtLeast(1), size.height * (1f - (y / max).toFloat())) }
            for (i in 1 until pts.size) drawLine(UsageFitColors.line, pts[i - 1], pts[i], strokeWidth = 2f)
            pts.forEach { drawCircle(UsageFitColors.line, 4f, it) }
        }
    }
}

@Composable
private fun RoleShareChart(w: FitWindowView) {
    val roles = w.series.flatMap { it.byRole.keys }.distinct().sorted()
    ChartFrame(
        "Who used the tokens, step by step", "time (one bar per meter step)", "share of that step's cache-read tokens (full height = 100%)",
        "Each colour is a role (see the key). Tall bands mean that role did most of the work in that step.", "usagefit-chart-roles",
    ) {
        Canvas(Modifier.chartBox()) {
            val bw = size.width / w.series.size
            w.series.forEachIndexed { i, s ->
                val total = s.byRole.values.sum().coerceAtLeast(1L).toFloat()
                var y = size.height
                roles.forEachIndexed { ri, r ->
                    val h = size.height * (s.byRole[r] ?: 0L) / total
                    y -= h
                    drawRect(UsageFitColors.role(ri), Offset(i * bw + 1f, y), Size((bw - 2f).coerceAtLeast(1f), h))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            roles.forEachIndexed { i, r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.width(10.dp).height(10.dp).background(UsageFitColors.role(i))) {}
                    Text(" $r", color = Palette.Text, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun FitChart(w: FitWindowView) {
    val max = w.series.maxOf { maxOf(it.points, it.fitted ?: 0.0) }.coerceAtLeast(1.0)
    ChartFrame(
        "Actual vs fitted points", "time (one pair of marks per meter step)", "meter points gained in the step (top = ${PaceChartModel.fmt(max)})",
        "Blue dots are what the meter really moved; amber lines are what the fit predicts from the recorded tokens. Close together means the tokens explain the meter.",
        "usagefit-chart-fit",
    ) {
        Canvas(Modifier.chartBox()) {
            val bw = size.width / w.series.size
            w.series.forEachIndexed { i, s ->
                val x = i * bw + bw / 2
                drawCircle(UsageFitColors.actual, 5f, Offset(x, size.height * (1f - (s.points / max).toFloat())))
                s.fitted?.let { f ->
                    val y = size.height * (1f - (f / max).toFloat())
                    drawLine(UsageFitColors.fitted, Offset(x - bw / 3, y), Offset(x + bw / 3, y), strokeWidth = 3f)
                }
            }
            drawRect(Palette.Border, Offset.Zero, size, style = Stroke(1f))
        }
    }
}
