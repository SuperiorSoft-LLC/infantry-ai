package com.superiorsoftllc.agenthq.plugins.official.usage

import com.superiorsoftllc.agenthq.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.LimitsView
import com.superiorsoftllc.agenthq.ui.model.PacingView
import com.superiorsoftllc.agenthq.ui.shell.UsageContext
import androidx.compose.runtime.rememberUpdatedState
import com.superiorsoftllc.agenthq.ui.protocol.MeterUsage
import com.superiorsoftllc.agenthq.ui.protocol.PacingRule
import com.superiorsoftllc.agenthq.ui.protocol.UsageSet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the pacing dialog edits. */
private sealed interface PacingEdit {
    data object Rule : PacingEdit
    data class Manual(val meter: MeterUsage) : PacingEdit
    data class Override(val meter: MeterUsage) : PacingEdit
}

/**
 * USAGE-PACE (contract 1.119.0 / 1.121.0): how many ticket workers each plan meter allows right now, from its percent
 * used and the pacing rule. An agent's ticket start past the allowance is refused by the daemon (1.121.0). The owner can
 * edit the rule, type a percent for a meter with no readable usage, and override a meter's allowance for a while.
 */
@Composable
internal fun PacingSection(state: AppState, now: Long, controller: UsageContext?) {
    val report = state.usageReport
    var edit by remember { mutableStateOf<PacingEdit?>(null) }
    val canEdit = controller != null && state.connection.canWrite && report != null
    // Running workers change with every start and exit: re-read while the pane is open (the daemon re-reads a plan's
    // live usage at most once per 5 minutes, so this costs no extra provider call).
    val live by rememberUpdatedState(state.connection.isLive)
    if (controller != null && report != null) {
        LaunchedEffect(controller) {
            while (true) {
                delay(REFRESH_MS)
                if (live) controller.refreshUsage()
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Pacing", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
        if (canEdit) {
            val scope = rememberCoroutineScope()
            TextButton(onClick = { scope.launch { controller?.refreshUsage(force = true) } }, modifier = Modifier.testTag("pacing-refresh")) {
                Text("Refresh", fontSize = 11.sp, color = Palette.Text)
            }
            TextButton(onClick = { edit = PacingEdit.Rule }, modifier = Modifier.testTag("pacing-edit-rule")) {
                Text("Edit rule", fontSize = 11.sp, color = Palette.Text)
            }
        }
    }
    if (report == null) {
        Text(
            if (state.connection.isLive) "This daemon does not report pacing (contract 1.119.0), or has not answered yet." else "Connect to a daemon to see pacing.",
            color = Palette.Muted, fontSize = 11.sp,
        )
        return
    }
    Text("Rule: " + PacingView.ruleText(report.rule), color = Palette.Text, fontSize = 11.sp, modifier = Modifier.testTag("pacing-rule"))
    if (report.meters.isEmpty()) Text("No meter: no live usage source and no ticket worker running.", color = Palette.Muted, fontSize = 11.sp)
    report.meters.forEach { m ->
        MeterCard(
            m, report.rule, now,
            onManual = if (canEdit) ({ edit = PacingEdit.Manual(m) }) else null,
            onOverride = if (canEdit) ({ edit = PacingEdit.Override(m) }) else null,
        )
    }
    val current = edit
    if (current != null && controller != null) {
        PacingDialog(current, report.rule, PacingView.ceilingOf(state.limitCatalog), now, controller, onDone = { edit = null })
    }
}

@Composable
private fun MeterCard(m: MeterUsage, rule: PacingRule, now: Long, onManual: (() -> Unit)?, onOverride: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp).testTag("pacing-meter-${m.meter}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(LimitsView.providerName(m.meter), color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(LimitsView.percentText(m.percent), color = if (m.percent == null) Palette.Muted else Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            // USAGE-PACE (pace mode): where this burn lands at the window's reset, next to the percent it comes from.
            PacingView.projectedText(m)?.let { p ->
                Text(
                    " \u00b7 $p", fontSize = 11.sp,
                    color = if (PacingView.projectedOver(m, rule)) Palette.Danger else Palette.Muted,
                    modifier = Modifier.testTag("pacing-projected-${m.meter}"),
                )
            }
        }
        Text(
            listOfNotNull(m.window, PacingView.sourceText(m), LimitsView.paceText(m.paceDelta), LimitsView.resetText(m.resetsAt), PacingView.updatedText(m.updatedAt, now)).joinToString(" · "),
            color = Palette.Muted, fontSize = 10.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(PacingView.allowanceText(m), color = Palette.Text, fontSize = 11.sp, modifier = Modifier.weight(1f).testTag("pacing-allowance-${m.meter}"))
            Chip(if (m.canStart) "can start" else "full", if (m.canStart) Palette.Ok else Palette.Danger)
        }
        PacingView.overrideText(m)?.let { o ->
            Text(o + " · ends in " + untilText(m.override!!.until, now) + " (rule says ${m.ruleAllowance ?: "no limit"})", color = Palette.Accent, fontSize = 10.sp)
        }
        PacingView.reasonText(m)?.let { Text(it, color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.testTag("pacing-reason-${m.meter}")) }
        m.warning?.let { Text(it, color = Palette.Muted, fontSize = 10.sp) }
        if (onManual != null || onOverride != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                onManual?.let { TextButton(onClick = it, modifier = Modifier.testTag("pacing-manual-${m.meter}")) { Text(if (m.source == "live") "Type %" else "Set %", fontSize = 11.sp, color = Palette.Text) } }
                onOverride?.let { TextButton(onClick = it, modifier = Modifier.testTag("pacing-override-${m.meter}")) { Text("Override", fontSize = 11.sp, color = Palette.Text) } }
            }
        }
    }
}

/** One dialog for the three edits, on the shared modal wrapper ([AhqAlertDialog]). */
@Composable
private fun PacingDialog(edit: PacingEdit, rule: PacingRule, ceiling: Int?, now: Long, controller: UsageContext, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var first by remember(edit) {
        mutableStateOf(
            when (edit) {
                PacingEdit.Rule -> PacingView.ruleInput(rule)
                is PacingEdit.Manual -> edit.meter.percent?.let { LimitsView.percentText(it).removeSuffix("%") } ?: ""
                is PacingEdit.Override -> edit.meter.override?.workers?.toString() ?: (edit.meter.allowance?.plus(1)?.toString() ?: "")
            },
        )
    }
    var second by remember(edit) { mutableStateOf("") }
    var enabled by remember(edit) { mutableStateOf(rule.enabled) }
    // Pace keys of the rule editor, seeded from the rule the daemon reported (never from a local default).
    var mode by remember(edit) { mutableStateOf(rule.mode) }
    var maxWorkers by remember(edit) { mutableStateOf(rule.maxWorkers?.toString() ?: "") }
    var okProjected by remember(edit) { mutableStateOf(PacingView.fieldText(rule.okProjected)) }
    var hardStop by remember(edit) { mutableStateOf(PacingView.fieldText(rule.hardStopPercent)) }
    var error by remember(edit) { mutableStateOf<String?>(null) }
    val title = when (edit) {
        PacingEdit.Rule -> "Pacing rule"
        is PacingEdit.Manual -> "Usage of ${LimitsView.providerName(edit.meter.meter)}"
        is PacingEdit.Override -> "Override ${LimitsView.providerName(edit.meter.meter)}"
    }

    fun submit(set: UsageSet) {
        scope.launch { controller.usageSet(set) }
        onDone()
    }

    fun save() {
        when (edit) {
            PacingEdit.Rule -> PacingView.buildRule(rule, enabled, first, mode, maxWorkers, okProjected, hardStop, ceiling).fold(
                onSuccess = { submit(UsageSet(rule = it)) },
                onFailure = { error = it.message },
            )
            is PacingEdit.Manual -> {
                val pct = PacingView.parsePercent(first) ?: run { error = "percent must be 0..100"; return }
                val mins = second.trim().takeIf { it.isNotEmpty() }?.let { it.toLongOrNull()?.takeIf { m -> m > 0 } ?: run { error = "minutes must be a whole number above 0"; return } }
                submit(UsageSet(meter = edit.meter.meter, manualPercent = pct, manualResetsAt = mins?.let { now + it * 60_000L }))
            }
            is PacingEdit.Override -> {
                val workers = PacingView.parseWorkers(first, ceiling) ?: run { error = "workers must be ${PacingView.rangeText(0, ceiling)}, or none"; return }
                val hours = second.trim().takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull()?.takeIf { h -> h > 0 } ?: run { error = "hours must be a number above 0"; return } }
                val until = hours?.let { now + (it * 3_600_000L).toLong() } ?: edit.meter.resetsAt?.takeIf { it > now } ?: (now + 2 * 3_600_000L)
                submit(UsageSet(meter = edit.meter.meter, overrideWorkers = workers.workers, overrideUntil = until))
            }
        }
    }

    AhqAlertDialog(
        onDismissRequest = onDone,
        containerColor = Palette.Panel,
        modifier = Modifier.testTag("pacing-dialog"),
        title = { Text(title, color = Palette.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (edit) {
                    PacingEdit.Rule -> {
                        if (PacingView.hasModes(rule)) {
                            ModePicker(mode) { mode = it; error = null }
                            if (mode == PacingRule.MODE_PACE) {
                                Text("Workers follow where the burn lands at the window's reset: the max while projected stays at or under the ok %, down to one at 20 points over, and one from the hard stop % used.", color = Palette.Muted, fontSize = 11.sp)
                                OutlinedTextField(maxWorkers, { maxWorkers = it; error = null }, label = { Text("Max ticket workers") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("pacing-rule-max"))
                                OutlinedTextField(okProjected, { okProjected = it; error = null }, label = { Text("OK projected at reset (%)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("pacing-rule-ok"))
                                OutlinedTextField(hardStop, { hardStop = it; error = null }, label = { Text("Hard stop at used (%)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("pacing-rule-hardstop"))
                            }
                        }
                        Text(
                            (if (mode == PacingRule.MODE_PACE) "Fallback tiers, used when a window has no reset time. " else "") +
                                "Tiers of percent:workers, lowest first; the last one is workers alone. Example: 60:3, 80:2, 1 = under 60% three ticket workers, under 80% two, else one.",
                            color = Palette.Muted, fontSize = 11.sp,
                        )
                        OutlinedTextField(first, { first = it; error = null }, label = { Text("Tiers") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("pacing-rule-input"))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Pace agent ticket starts", color = Palette.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Switch(enabled, { enabled = it }, modifier = Modifier.testTag("pacing-rule-enabled"))
                        }
                    }
                    is PacingEdit.Manual -> {
                        Text("For a plan whose usage cannot be read. A live read wins over it; it lapses at the reset you give, else after 5 hours.", color = Palette.Muted, fontSize = 11.sp)
                        OutlinedTextField(first, { first = it; error = null }, label = { Text("Percent used") }, singleLine = true, modifier = Modifier.testTag("pacing-manual-percent"))
                        OutlinedTextField(second, { second = it; error = null }, label = { Text("Resets in (minutes, optional)") }, singleLine = true, modifier = Modifier.testTag("pacing-manual-resets"))
                        if (edit.meter.source == "manual") {
                            TextButton(onClick = { submit(UsageSet(meter = edit.meter.meter, clearManual = true)) }) { Text("Clear the typed percent", color = Palette.Text) }
                        }
                    }
                    is PacingEdit.Override -> {
                        Text("Replaces the rule's allowance (now ${edit.meter.ruleAllowance ?: "no limit"}) for a while. Blank or none = no limit. No hours = until the window resets, else 2 hours.", color = Palette.Muted, fontSize = 11.sp)
                        OutlinedTextField(first, { first = it; error = null }, label = { Text("Ticket workers") }, singleLine = true, modifier = Modifier.testTag("pacing-override-workers"))
                        OutlinedTextField(second, { second = it; error = null }, label = { Text("For (hours, optional)") }, singleLine = true, modifier = Modifier.testTag("pacing-override-hours"))
                        if (edit.meter.override != null) {
                            TextButton(onClick = { submit(UsageSet(meter = edit.meter.meter, clearOverride = true)) }) { Text("Clear the override", color = Palette.Text) }
                        }
                    }
                }
                error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.testTag("pacing-error")) }
            }
        },
        confirmButton = { Button(onClick = ::save, modifier = Modifier.testTag("pacing-save")) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel", color = Palette.Text) } },
    )
}

/** Pace or bands, as a radio group (one selectable row per mode, so a screen reader reads the pair as one choice). */
@Composable
private fun ModePicker(mode: String?, onPick: (String) -> Unit) {
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(PacingRule.MODE_PACE to "Pace (projected burn)", PacingRule.MODE_BANDS to "Bands (percent used)").forEach { (value, label) ->
            Row(
                Modifier.selectable(selected = mode == value, role = Role.RadioButton, onClick = { onPick(value) })
                    .handCursor().testTag("pacing-rule-mode-$value").padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = mode == value, onClick = null)
                Text(label, color = Palette.Text, fontSize = 12.sp)
            }
        }
    }
}

private const val REFRESH_MS = 60_000L

/** "1h 20m" / "35m" until [at]. */
private fun untilText(at: Long, now: Long): String {
    val mins = ((at - now) / 60_000L).coerceAtLeast(0)
    return if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
}
