// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.processes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.AhqAlertDialog
import com.superiorsoftllc.infantry.ui.Button
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.Switch
import com.superiorsoftllc.infantry.ui.TextButton
import com.superiorsoftllc.infantry.ui.clickable
import com.superiorsoftllc.infantry.ui.model.ProcessWatchView
import com.superiorsoftllc.infantry.ui.protocol.TrackedProcess
import com.superiorsoftllc.infantry.ui.shell.LinkContext
import com.superiorsoftllc.infantry.ui.shell.ProcessesContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

const val PROCESSES_BADGE_TAG = "processes-badge"
const val PROCESSES_PANEL_TAG = "processes-panel"
const val PROCESSES_REAP_ALL_TAG = "processes-reap-all"
const val PROCESSES_REAP_TAG_PREFIX = "processes-reap-"
const val PROCESSES_AUTO_REAP_TAG = "processes-auto-reap"

/**
 * ROGUE-1 (contract 1.120.0): the status-bar badge: muted "RAM x GB free" at rest; amber with "⚠ RAM x GB free · N rogue
 * processes" under memory pressure or when the daemon lists rogue processes. A tap opens [ProcessesDialog]. While the
 * daemon speaks 1.120.0 the badge asks for a fresh report once a minute (one frame; the daemon does the work).
 * RUNTIME-18: out of Infantry_AppUi into the official Infantry_Processes; it reads the shell's [ProcessesContext].
 */
@Composable
fun ProcessesBadge(link: LinkContext, processes: ProcessesContext, modifier: Modifier = Modifier) {
    val live = link.live && link.contractAtLeast(ProcessWatchView.SINCE_MAJOR, ProcessWatchView.SINCE_MINOR)
    // DEMO-MERGE-1: a restricted link refuses `processes` (host process lines); no badge, no poll
    if (!live || link.restricted) return

    // The context is provided anew as the state changes: the poll keeps its clock and asks the newest one.
    val current by rememberUpdatedState(processes)
    LaunchedEffect(Unit) {
        while (true) {
            delay(ProcessWatchView.POLL_MS)
            runCatching { current.refresh() }
        }
    }
    var open by remember { mutableStateOf(false) }
    val text = ProcessWatchView.badge(processes.report)
    if (text != null) {
        val alarm = ProcessWatchView.alarming(processes.report)
        Text(
            if (alarm) "   ⚠ $text" else "   $text", color = if (alarm) Palette.Warn else Palette.Muted, fontSize = 11.sp, maxLines = 1,
            modifier = modifier.testTag(PROCESSES_BADGE_TAG).clickable(role = Role.Button) {
                open = true
            },
        )
    }
    if (open) ProcessesDialog(processes) { open = false }
}

/** The processes panel: rogue first (each with Reap), then healthy tracked ones and those that are not ours (no button). */
@Composable
fun ProcessesDialog(processes: ProcessesContext, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { processes.refresh() } }
    val report = processes.report
    val now = Clock.System.now().toEpochMilliseconds()
    // POLICY-1: reaping is the destructive `host.processes.reap` action; a read-only policy still lists.
    val reap = com.superiorsoftllc.infantry.ui.shell.rememberGate("host.processes.reap", com.superiorsoftllc.infantry.plugin.api.ActionKind.DESTRUCTIVE)
    // GATE-PANES-1: the auto-reap switch is a setting (`settings_set`, `settings.save`).
    val autoReap = com.superiorsoftllc.infantry.ui.shell.rememberGate("settings.save")
    AhqAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(PROCESSES_PANEL_TAG),
        containerColor = Palette.Panel,
        title = { Text("Background processes", color = Palette.Text) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (report == null) {
                    Text("Not read from the daemon yet.", color = Palette.Muted, fontSize = 12.sp)
                    return@Column
                }
                val free = report.freeMb?.let(ProcessWatchView::mb) ?: "?"
                val total = report.totalMb?.let { " of ${ProcessWatchView.mb(it)}" } ?: ""
                Text(
                    "Memory: $free free$total (warning below ${ProcessWatchView.mb(report.floorMb)})",
                    color = if (report.underPressure) Palette.Warn else Palette.Text, fontSize = 12.sp,
                )
                // Contract 1.132.0: the agents' share of all cores and the sustained-CPU alert rule (limits.cpu.*).
                report.agentCpuPercent?.let { cpu ->
                    val rule = if (report.cpuAlertMinutes > 0) "alert at ${report.cpuAlertPercent}% for ${report.cpuAlertMinutes} min" else "alert off"
                    Text(
                        "Agents CPU: ${ProcessWatchView.pct(cpu)} of all cores ($rule)",
                        color = if (ProcessWatchView.cpuHigh(report)) Palette.Accent else Palette.Text, fontSize = 12.sp,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Reap rogue processes automatically", color = Palette.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = processes.autoReap ?: report.autoReap,
                        onCheckedChange = { on -> scope.launch { processes.setAutoReap(on) } },
                        enabled = autoReap.enabled,
                        modifier = Modifier.testTag(PROCESSES_AUTO_REAP_TAG),
                    )
                }
                Section("Rogue (${report.rogue.size})", report.rogue, now, reapable = reap.enabled) { pid ->
                    scope.launch { processes.reap(pid) }
                }
                Section("Tracked, healthy", report.tracked.filter { !it.rogue }, now, reapable = false) {}
                Section("Not started by Infantry AI (never reaped)", report.unattributed, now, reapable = false) {}
            }
        },
        confirmButton = {
            Button(
                onClick = { scope.launch { processes.reap(null) } },
                enabled = reap.enabled && report?.rogue?.isNotEmpty() == true,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Danger, contentColor = Palette.Bg),
                modifier = Modifier.testTag(PROCESSES_REAP_ALL_TAG),
            ) { Text("Reap all rogue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Palette.Text) } },
    )
}

@Composable
private fun Section(title: String, rows: List<TrackedProcess>, now: Long, reapable: Boolean, onReap: (Long) -> Unit) {
    Spacer(Modifier.padding(top = 10.dp))
    Text(title, color = Palette.Muted, fontSize = 11.sp)
    if (rows.isEmpty()) Text("none", color = Palette.Muted, fontSize = 11.sp)
    rows.forEach { p ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                ProcessWatchView.row(p, now), color = Palette.Text, fontSize = 11.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (reapable) {
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = { onReap(p.pid) }, modifier = Modifier.testTag(PROCESSES_REAP_TAG_PREFIX + p.pid)) {
                    Text("Reap", color = Palette.Danger, fontSize = 11.sp)
                }
            }
        }
    }
}
