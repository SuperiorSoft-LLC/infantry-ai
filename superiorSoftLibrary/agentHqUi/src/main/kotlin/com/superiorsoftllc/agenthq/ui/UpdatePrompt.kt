package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.update.InstallPrompt
import com.superiorsoftllc.agenthq.update.UpdateService
import com.superiorsoftllc.agenthq.update.footerLabel
import com.superiorsoftllc.agenthq.update.UpdateState

/**
 * UPDATE-1: the window's only update UI, a pure rendering of [UpdateService.state] (HEADLESS-1: no update logic here).
 *
 * A newer release opens a dialog with its version and notes, Update / Later. Everything else (checking, up to date,
 * a failure) is a small card in the bottom-right corner that never blocks the window.
 */
@Composable
fun UpdatePrompt(service: UpdateService) {
    val state by service.state.collectAsState()
    val toast by service.toastVisible.collectAsState()
    val dialogOpen by service.dialogOpen.collectAsState()
    var confirming by remember { mutableStateOf<InstallPrompt?>(null) }
    AgentHqTheme {
        val update = when (val s = state) {
            is UpdateState.Available -> s.update
            is UpdateState.Downloading -> s.update
            is UpdateState.Restarting -> s.update
            else -> null
        }
        val busy = state is UpdateState.Downloading || state is UpdateState.Restarting
        if (update != null && (dialogOpen || busy)) {
            AlertDialog(
                onDismissRequest = { if (!busy) service.dismiss() },
                modifier = Modifier.testTag(UPDATE_DIALOG_TAG),
                title = { Text("AgentHQ ${update.latest} is available", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("You are running ${update.current}.", color = Palette.Muted, fontSize = 12.sp)
                        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            Text(update.notes, fontSize = 13.sp, modifier = Modifier.testTag(UPDATE_NOTES_TAG))
                        }
                        when (state) {
                            is UpdateState.Downloading -> Text("Downloading and verifying ${update.installer.name}…", color = Palette.Info, fontSize = 12.sp)
                            is UpdateState.Restarting -> Text("Installing; AgentHQ restarts on its own. Your data stays where it is.", color = Palette.Ok, fontSize = 12.sp)
                            else -> Unit
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val prompt = service.installPrompt()
                            if (prompt.message != null) confirming = prompt else service.update()
                        },
                        enabled = !busy,
                        modifier = Modifier.testTag(UPDATE_NOW_TAG),
                    ) { Text("Update") }
                },
                dismissButton = {
                    TextButton(onClick = { service.dismiss() }, enabled = !busy, modifier = Modifier.testTag(UPDATE_LATER_TAG)) { Text("Later") }
                },
            )
        }
        confirming?.let { prompt ->
            // UPDATE-3: agents are working: the safe answer (Cancel) is the dialog's dismiss and the emphasised button.
            AlertDialog(
                onDismissRequest = { confirming = null },
                modifier = Modifier.testTag(UPDATE_RUNNING_TAG),
                title = { Text(prompt.message ?: "", fontWeight = FontWeight.Bold) },
                text = { Text("Installing closes AgentHQ and stops them.", fontSize = 12.sp, color = Palette.Muted) },
                confirmButton = { TextButton(onClick = { confirming = null }, modifier = Modifier.testTag(UPDATE_CANCEL_TAG)) { Text("Cancel", fontWeight = FontWeight.Bold) } },
                dismissButton = { TextButton(onClick = { confirming = null; service.update() }, modifier = Modifier.testTag(UPDATE_ANYWAY_TAG)) { Text("Update anyway") } },
            )
        }
        when (val s = state) {
            is UpdateState.Checking -> if (s.manual) UpdateCard("Checking for updates…", dismiss = null)
            is UpdateState.UpToDate -> UpdateCard("AgentHQ ${s.current} is up to date.", dismiss = { service.dismiss() })
            is UpdateState.Message -> UpdateCard(s.text, dismiss = { service.dismiss() })
            is UpdateState.Available -> if (toast && !dialogOpen) {
                UpdateCard("AgentHQ ${s.update.latest} is available.", dismiss = { service.dismissToast() }, action = "View" to { service.openDialog() })
            }
            else -> Unit
        }
        UpdateFooterButton(service, state)
    }
}

/** UPDATE-3: the always-visible footer button, bottom-left; click checks, or opens the prompt when an update is waiting. */
@Composable
private fun UpdateFooterButton(service: UpdateService, state: UpdateState) {
    if (!service.enabled) return
    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.BottomStart) {
        TextButton(
            onClick = { if (state is UpdateState.Available) service.openDialog() else if (state !is UpdateState.Checking) service.check(manual = true) },
            modifier = Modifier.testTag(UPDATE_FOOTER_TAG),
        ) { Text(state.footerLabel(InstalledUpdates.version()), fontSize = 11.sp, color = if (state is UpdateState.Available) Palette.Ok else Palette.Muted) }
    }
}

@Composable
private fun UpdateCard(text: String, dismiss: (() -> Unit)?, action: Pair<String, () -> Unit>? = null) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
        Row(
            Modifier.widthIn(max = 420.dp)
                .background(Palette.Raised, RoundedCornerShape(8.dp))
                .border(1.dp, Palette.Border, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag(UPDATE_CARD_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text, fontSize = 12.sp, modifier = Modifier.weight(1f, fill = false))
            if (action != null) TextButton(onClick = action.second) { Text(action.first) }
            if (dismiss != null) TextButton(onClick = dismiss) { Text("Dismiss") }
        }
    }
}

const val UPDATE_DIALOG_TAG = "update-dialog"
const val UPDATE_NOTES_TAG = "update-notes"
const val UPDATE_NOW_TAG = "update-now"
const val UPDATE_LATER_TAG = "update-later"
const val UPDATE_CARD_TAG = "update-card"
const val UPDATE_FOOTER_TAG = "update-footer"
const val UPDATE_RUNNING_TAG = "update-running-agents"
const val UPDATE_CANCEL_TAG = "update-cancel"
const val UPDATE_ANYWAY_TAG = "update-anyway"
