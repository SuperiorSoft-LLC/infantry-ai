package com.superiorsoftllc.agenthq.plugins.official.usage

import com.superiorsoftllc.agenthq.ui.*

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AgentView
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.LimitsView
import com.superiorsoftllc.agenthq.ui.shell.UsageContext
import com.superiorsoftllc.agenthq.ui.protocol.LimitWindow
import com.superiorsoftllc.agenthq.ui.protocol.ProviderLimits
import com.superiorsoftllc.agenthq.ui.protocol.ProviderState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Clock

/**
 * Usage and limit state (story 9.8c, contract 1.13.0).
 *
 * Honesty rules, the same as the daemon's: a provider with no signal is shown as `unknown` with NO bar --
 * an empty bar would read as 0%, which is a claim nobody made. Usage is what runs reported; a run that
 * reported nothing is not shown as zero tokens. The operator budget is the daemon's, and never called quota.
 */
@Composable
fun UsagePane(state: AppState, selected: AgentView?, controller: UsageContext? = null) {
    val now = Clock.System.now().toEpochMilliseconds()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val refresh: (() -> Unit)? = controller?.let { c -> { scope.launch { c.refreshLimits() }; Unit } }
        PlanLimits(state, now, onRefresh = refresh)

        HorizontalDivider(color = Palette.Border)
        PacingSection(state, now, controller)

        HorizontalDivider(color = Palette.Border)
        Text("Observed in runs", color = Palette.Muted, fontSize = 10.sp)
        if (state.providerStates.isEmpty()) {
            Text(if (state.connection.isLive) "The daemon reported no provider." else "Connect to a daemon to see provider limits.", color = Palette.Muted, fontSize = 11.sp)
        }
        state.providerStates.values.forEach { p -> ProviderLimit(p, now) }

        HorizontalDivider(color = Palette.Border)
        val a = selected
        Text(if (a == null) "Usage" else "Usage of ${a.session.worktreeBranch ?: a.id.take(8)}", color = Palette.Muted, fontSize = 10.sp)
        val totals = a?.usageTotals
        when {
            a == null -> Text("Select an agent to see what its runs reported.", color = Palette.Muted, fontSize = 11.sp)
            totals == null -> Text("No run of this agent reported usage. That is no signal, not zero.", color = Palette.Muted, fontSize = 11.sp)
            else -> {
                Text(
                    "${totals.runs} run(s): ${compact(totals.input)} in, ${compact(totals.output)} out" +
                        (if (totals.reasoning > 0) ", ${compact(totals.reasoning)} reasoning" else "") +
                        (totals.costUsd?.let { ", $" + money(it) } ?: ""),
                    color = Palette.Text, fontSize = 12.sp,
                )
                a.usage.forEach { u ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("run ${u.runId.substringAfterLast(':')}", color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.width(48.dp))
                        Text(
                            "${compact(u.input)} in · ${compact(u.output)} out · cache ${compact(u.cacheRead)}/${compact(u.cacheWrite)}" +
                                (u.costUsd?.let { " · $" + money(it) } ?: ""),
                            color = Palette.Text, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.weight(1f),
                        )
                        Chip(u.model)
                    }
                }
            }
        }
    }
}

/**
 * U2 (contract 1.59.0): the plan-limit meters, one card per provider of the last `limits` frame. Each window is a
 * segmented bar (green under an even pace, orange ahead, red ahead and nearly spent), its % used, its reset and
 * its pace. A provider or window with no percent shows "—" and why: no signal is never drawn as 0%.
 */
@Composable
private fun PlanLimits(state: AppState, now: Long, onRefresh: (() -> Unit)?) {
    val limits = state.planLimits
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Plan limits", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
        limits?.let { LimitsView.observedAt(it) }?.let { Text("updated ${ago(it, now)} ago", color = Palette.Muted, fontSize = 10.sp) }
        if (onRefresh != null) {
            Spacer(Modifier.width(6.dp))
            IconAction(
                AhqIcons.Refresh, "Refresh",
                onClick = onRefresh,
                enabled = state.connection.isLive,
                modifier = Modifier.testTag("limits-refresh"),
                compact = true,
            )
        }
    }
    when {
        limits == null -> Text(
            if (state.connection.isLive) "Waiting for the daemon's plan limits." else "Connect to a daemon to see plan limits.",
            color = Palette.Muted, fontSize = 11.sp,
        )
        limits.isEmpty() -> Text("The daemon reported no plan-limit source.", color = Palette.Muted, fontSize = 11.sp)
        else -> limits.forEach { PlanLimitCard(it) }
    }
}

@Composable
private fun PlanLimitCard(p: ProviderLimits) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp).testTag("limits-card-${p.provider}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(LimitsView.providerName(p.provider), color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        if (p.windows.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(LimitsView.reasonText(p.reason), color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("—", color = Palette.Muted, fontSize = 12.sp)
            }
        }
        p.windows.forEach { LimitWindowRow(it, p.reason) }
    }
}

@Composable
private fun LimitWindowRow(w: LimitWindow, reason: String?) {
    val percent = w.percent
    val ahead = LimitsView.ahead(w)
    val color: Color = when {
        !ahead -> Palette.Ok
        (percent ?: 0.0) >= 90.0 -> Palette.Danger
        else -> Palette.Accent
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(w.label, color = Palette.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(LimitsView.percentText(percent), color = if (percent == null) Palette.Muted else Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (percent == null) {
            Text(LimitsView.reasonText(reason), color = Palette.Muted, fontSize = 11.sp)
        } else {
            val filled = LimitsView.filledSegments(percent)
            val description = LimitsView.barDescription(w)
            Row(
                Modifier.fillMaxWidth().height(8.dp).semantics { contentDescription = description },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(LimitsView.SEGMENTS) { i ->
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(if (i < filled) color else Palette.Border))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(LimitsView.resetText(w.resetsAt) ?: "reset unknown", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            LimitsView.paceText(w.paceDelta)?.let { Text(it, color = color, fontSize = 10.sp) }
        }
    }
}

@Composable
private fun ProviderLimit(p: ProviderState, now: Long) {
    val color = when (p.status) {
        "exhausted" -> Palette.Danger
        "stalled" -> Palette.Accent
        else -> Palette.Muted
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.provider, color = Palette.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip(p.status, color)
        }
        val utilization = p.utilization
        if (utilization == null) {
            Text(
                if (p.status == "stalled") "a run stalled: free-quota exhaustion looks like this, but the stream cannot say" else "no signal: unknown, not 0%",
                color = Palette.Muted, fontSize = 11.sp,
            )
        } else {
            val fraction = (if (utilization > 1.0) utilization / 100.0 else utilization).coerceIn(0.0, 1.0)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Border)) {
                Box(Modifier.fillMaxWidth(fraction.toFloat()).fillMaxHeight().background(if (fraction >= 0.9) Palette.Danger else Palette.Info))
            }
            Text(
                "${(fraction * 100).roundToInt()}% used" + (p.window?.let { " of $it" } ?: "") +
                    (p.resetsAt?.let { " · resets in " + until(it, now) } ?: ""),
                color = Palette.Text, fontSize = 11.sp,
            )
        }
        Row {
            Text("source ${p.source}", color = Palette.Muted, fontSize = 10.sp)
            p.observedAt?.let { Spacer(Modifier.width(8.dp)); Text("observed ${ago(it, now)} ago", color = Palette.Muted, fontSize = 10.sp) }
        }
    }
}

private fun money(v: Double): String {
    val cents = (v * 10_000).roundToInt()
    return "${cents / 10_000}.${(cents % 10_000).toString().padStart(4, '0')}"
}

/** A reset time in seconds or millis since the epoch, as a coarse "3h" from [now]. */
private fun until(resetsAt: Long, now: Long): String {
    val millis = if (resetsAt < 100_000_000_000L) resetsAt * 1000 else resetsAt
    val s = ((millis - now) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        else -> "${s / 86_400}d"
    }
}
