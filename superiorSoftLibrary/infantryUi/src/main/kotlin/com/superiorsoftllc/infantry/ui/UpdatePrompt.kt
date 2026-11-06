// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.compositeOver
import com.superiorsoftllc.infantry.ui.model.ToastKind
import com.superiorsoftllc.infantry.plugins.official.updater.indicatorLabel
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import com.superiorsoftllc.infantry.plugins.official.updater.InstallPrompt
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateService
import com.superiorsoftllc.infantry.plugins.official.updater.footerLabel
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateState

/**
 * UPDATE-1: the window's only update UI, a pure rendering of [UpdateService.state] (HEADLESS-1: no update logic here).
 *
 * A newer release can open a dialog with its version and notes, Update / Later. UPDATE-UX-1: Update CLOSES the dialog and
 * the download runs in the background; nothing here ever blocks the window. Everything else is a card in the
 * bottom-right corner, styled like the app's other notices: "found an update" (View / Dismiss), the download with its
 * progress bar ("Downloading 1.0.7 - 45%"), "Ready to install" (Restart now / Later), a failure (Retry / Dismiss), and
 * the manual check's answers.
 */
@Composable
fun UpdatePrompt(service: UpdateService) {
    val state by service.state.collectAsState()
    val toast by service.toastVisible.collectAsState()
    val dialogOpen by service.dialogOpen.collectAsState()
    val indicator by service.indicatorVisible.collectAsState()
    var confirming by remember { mutableStateOf<InstallPrompt?>(null) }
    InfantryTheme {
        val available = state as? UpdateState.Available
        if (available != null && dialogOpen) {
            val update = available.update
            AlertDialog(
                onDismissRequest = { service.dismiss() },
                modifier = Modifier.testTag(UPDATE_DIALOG_TAG),
                title = { Text("Infantry AI ${update.latest} is available", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("You are running ${update.current}.", color = Palette.Muted, fontSize = 12.sp)
                        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            Text(update.notes, fontSize = 13.sp, modifier = Modifier.testTag(UPDATE_NOTES_TAG))
                        }
                        Text("Update downloads in the background; you keep working and choose when to restart.", color = Palette.Muted, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { service.update() }, modifier = Modifier.testTag(UPDATE_NOW_TAG)) { Text("Update") }
                },
                dismissButton = {
                    TextButton(onClick = { service.dismiss() }, modifier = Modifier.testTag(UPDATE_LATER_TAG)) { Text("Later") }
                },
            )
        }
        confirming?.let { prompt ->
            // UPDATE-3: agents are working: the safe answer (Cancel) is the dialog's dismiss and the emphasised button.
            AlertDialog(
                onDismissRequest = { confirming = null },
                modifier = Modifier.testTag(UPDATE_RUNNING_TAG),
                title = { Text(prompt.message ?: "", fontWeight = FontWeight.Bold) },
                text = { Text("Installing closes Infantry AI and stops them.", fontSize = 12.sp, color = Palette.Muted) },
                confirmButton = { TextButton(onClick = { confirming = null }, modifier = Modifier.testTag(UPDATE_CANCEL_TAG)) { Text("Cancel", fontWeight = FontWeight.Bold) } },
                dismissButton = { TextButton(onClick = { confirming = null; service.install() }, modifier = Modifier.testTag(UPDATE_ANYWAY_TAG)) { Text("Update anyway") } },
            )
        }
        val restartNow: () -> Unit = {
            val prompt = service.installPrompt()
            if (prompt.message != null) confirming = prompt else service.install()
        }
        when (val s = state) {
            is UpdateState.Checking -> if (s.manual) UpdateCard("Checking for updates…", ToastKind.INFO, progress = UpdateProgress.Indeterminate)
            is UpdateState.UpToDate -> UpdateCard("Infantry AI ${s.current} is up to date.", ToastKind.SUCCESS, actions = listOf("Dismiss" to { service.dismiss() }))
            is UpdateState.Message -> UpdateCard(s.text, ToastKind.WARN, actions = listOf("Dismiss" to { service.dismiss() }))
            is UpdateState.Available -> if (toast && !dialogOpen) {
                UpdateCard("Infantry AI ${s.update.latest} is available.", ToastKind.INFO,
                    actions = listOf("View" to { service.openDialog() }, "Dismiss" to { service.dismissToast() }))
            }
            is UpdateState.Downloading -> UpdateCard(s.indicatorLabel() ?: "", ToastKind.INFO,
                progress = s.percent?.let { UpdateProgress.Fraction(it / 100f) } ?: UpdateProgress.Indeterminate, tag = UPDATE_PROGRESS_TAG)
            is UpdateState.Ready -> if (indicator) {
                UpdateCard(s.indicatorLabel() ?: "", ToastKind.SUCCESS, tag = UPDATE_PROGRESS_TAG,
                    actions = listOf("Restart now" to restartNow, "Later" to { service.dismiss() }), actionTags = listOf(UPDATE_RESTART_TAG, UPDATE_LATER_CARD_TAG))
            }
            is UpdateState.DownloadFailed -> UpdateCard(s.indicatorLabel() ?: "", ToastKind.ERROR, tag = UPDATE_PROGRESS_TAG,
                actions = listOf<Pair<String, () -> Unit>>("Retry" to { service.retry() }, "Dismiss" to { service.dismiss() }), actionTags = listOf(UPDATE_RETRY_TAG, ""))
            is UpdateState.Restarting -> UpdateCard(s.indicatorLabel() ?: "", ToastKind.SUCCESS, progress = UpdateProgress.Indeterminate, tag = UPDATE_PROGRESS_TAG)
            UpdateState.Idle -> Unit
        }
        UpdateFooterButton(service, state, indicator)
    }
}

/**
 * UPDATE-3: the always-visible footer button, bottom-left; click checks, or opens the prompt when an update is waiting.
 * UPDATE-UX-1: hidden while the bottom-right card shows a download; after "Later" on a ready one it brings the card back.
 */
@Composable
private fun UpdateFooterButton(service: UpdateService, state: UpdateState, indicator: Boolean) {
    if (!service.enabled) return
    val label = state.footerLabel(InstalledUpdates.version(), indicator) ?: return
    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.BottomStart) {
        TextButton(
            onClick = {
                when (state) {
                    is UpdateState.Available -> service.openDialog()
                    is UpdateState.Ready -> service.showIndicator()
                    is UpdateState.Checking -> Unit
                    else -> service.check(manual = true)
                }
            },
            modifier = Modifier.testTag(UPDATE_FOOTER_TAG),
        ) { Text(label, fontSize = 11.sp, color = if (state is UpdateState.Available || state is UpdateState.Ready) Palette.Ok else Palette.Muted) }
    }
}

/** UPDATE-UX-1: the card's progress bar: none, a known fraction, or a running bar while the size is not known. */
private sealed interface UpdateProgress {
    data object Indeterminate : UpdateProgress
    data class Fraction(val value: Float) : UpdateProgress
}

/**
 * UPDATE-UX-1: the bottom-right update card in the app's notice style ([ToastHost]): the kind's colour as a stripe and a
 * light tint over the panel, the text in the normal text colour, and the actions in the kind's colour, so it reads like
 * every other notice (no dim grey on grey, no accent-red buttons).
 */
@Composable
private fun UpdateCard(
    text: String,
    kind: ToastKind,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    progress: UpdateProgress? = null,
    tag: String = UPDATE_CARD_TAG,
    actionTags: List<String> = emptyList(),
) {
    val colour = toastColour(kind)
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
        Row(
            Modifier.width(UPDATE_CARD_WIDTH).height(IntrinsicSize.Min)
                .background(colour.copy(alpha = 0.12f).compositeOver(Palette.Panel), Palette.PopupShape)
                .border(1.dp, Palette.Border, Palette.PopupShape)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(colour))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text, color = Palette.Text, fontSize = 12.sp, maxLines = 4)
                when (progress) {
                    is UpdateProgress.Fraction -> LinearProgressIndicator(progress = { progress.value }, modifier = Modifier.fillMaxWidth(), color = colour, trackColor = Palette.Border)
                    UpdateProgress.Indeterminate -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = colour, trackColor = Palette.Border)
                    null -> Unit
                }
                if (actions.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        actions.forEachIndexed { i, (label, onClick) ->
                            val t = actionTags.getOrNull(i)?.takeIf { it.isNotEmpty() }
                            TextButton(
                                onClick = onClick,
                                modifier = if (t != null) Modifier.testTag(t) else Modifier,
                                colors = ButtonDefaults.textButtonColors(contentColor = if (i == 0) colour else Palette.Muted),
                            ) { Text(label, fontSize = 12.sp, fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal) }
                        }
                    }
                }
            }
        }
    }
}

private val UPDATE_CARD_WIDTH = 340.dp

const val UPDATE_DIALOG_TAG = "update-dialog"
const val UPDATE_NOTES_TAG = "update-notes"
const val UPDATE_NOW_TAG = "update-now"
const val UPDATE_LATER_TAG = "update-later"
const val UPDATE_CARD_TAG = "update-card"
const val UPDATE_FOOTER_TAG = "update-footer"
const val UPDATE_RUNNING_TAG = "update-running-agents"
const val UPDATE_CANCEL_TAG = "update-cancel"
const val UPDATE_ANYWAY_TAG = "update-anyway"
const val UPDATE_PROGRESS_TAG = "update-progress"
const val UPDATE_RESTART_TAG = "update-restart"
const val UPDATE_LATER_CARD_TAG = "update-later-card"
const val UPDATE_RETRY_TAG = "update-retry"
