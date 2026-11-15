// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.AhqAlertDialog
import com.superiorsoftllc.infantry.ui.HarnessChecklistView
import com.superiorsoftllc.infantry.ui.LocalShell
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.TextButton
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.model.HarnessChecklist
import com.superiorsoftllc.infantry.ui.model.HarnessInstallSetting
import kotlinx.coroutines.launch

const val HARNESS_CHECKLIST_DIALOG_TAG = "harness-checklist-dialog"
const val HARNESS_CHECKLIST_SUMMARY_TAG = "harness-checklist-summary"
const val HARNESS_CHECKLIST_DONE_TAG = "harness-checklist-done"
const val HARNESS_CHECKLIST_RECHECK_TAG = "harness-checklist-recheck"

/**
 * FIRST-RUN-HARNESS-CHECKLIST-1: the first-run agent tools checklist. Says what the daemon detected ("You have Claude Code
 * installed, ..." and what is missing) above the SAME list Configuration > Agents draws ([HarnessChecklistView], without the path
 * fields): install a missing tool, and set up the sign-in or key of an installed one right here.
 *
 * CHECKLIST-NO-AUTOOPEN-1 (owner 2026-11-15): never opens by itself ("as long as the user has a means to talk to an agent, it should
 * not show"; the guided setup handles the nothing-can-answer case). Only Configuration > Agents > Show first-run checklist opens
 * it. Done marks the primary keys still missing as skipped for the "Connect your providers" prompt.
 */
object ShellHarnessChecklist : Block("infantry.shell.harnessChecklist", "infantry.shell.harnessChecklist") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val state = s.state
        if (!state.connection.isLive || s.ui.showConfig || s.ui.showSetup) return
        val report = state.harnessInstall ?: return
        val secrets = state.secrets?.secrets ?: return
        if (!s.ui.showChecklist) return
        // An install finished anywhere (this window, another one, the CLI): re-read the daemon's report and CLI locations.
        val tasks by s.controller.progressTasks.collectAsState()
        val finished = tasks.filter { it.id.startsWith(HarnessInstallSetting.TASK_PREFIX) && it.finished }.map { it.id to it.updatedAt }.toSet()
        LaunchedEffect(finished) { if (finished.isNotEmpty() && !state.harnessInstallBusy) s.controller.checkHarnesses() }
        val items = HarnessChecklist.items(report, state.providers, state.cliLocations, secrets)
        fun done() {
            val skipped = runCatching { s.host.uiPref(HARNESS_KEY_SKIP_PREF).orEmpty().split(',').filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
            val seenMissing = items.flatMap { it.credentials }.filter { it.primary && !it.auth.ok }.map { it.row.name }
            runCatching { s.host.saveUiPref(HARNESS_KEY_SKIP_PREF, (skipped + seenMissing).joinToString(",")) }
            s.ui.showChecklist = false
        }
        AhqAlertDialog(
            onDismissRequest = { done() },
            modifier = Modifier.widthIn(max = 620.dp).testTag(HARNESS_CHECKLIST_DIALOG_TAG),
            title = { Text("Your agent tools") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(HarnessChecklist.summary(items), fontSize = 13.sp, modifier = Modifier.testTag(HARNESS_CHECKLIST_SUMMARY_TAG))
                    Text("Install a missing tool or connect an installed one here. Keys are stored encrypted by the daemon. All of this stays in Configuration > Agents.", fontSize = 11.sp, color = Palette.Muted)
                    HarnessChecklistView(
                        items, state.harnessInstallBusy, state.harnessInstallLog, HarnessInstallSetting.nodeNote(report),
                        onInstall = { ids, update -> s.actionScope.launch { s.controller.installHarnesses(ids, update) } },
                        onSaveLocation = null,
                        authEditor = { row -> HarnessAuthEditor(row) },
                    )
                }
            },
            confirmButton = { TextButton({ done() }, Modifier.testTag(HARNESS_CHECKLIST_DONE_TAG)) { Text("Done") } },
            dismissButton = {
                TextButton({ s.actionScope.launch { s.controller.checkHarnesses() } }, Modifier.testTag(HARNESS_CHECKLIST_RECHECK_TAG), enabled = !state.harnessInstallBusy) { Text("Check again") }
            },
        )
    }
}
