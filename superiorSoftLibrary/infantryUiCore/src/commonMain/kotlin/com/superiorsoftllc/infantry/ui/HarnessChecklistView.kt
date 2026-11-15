// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.superiorsoftllc.infantry.ui.model.CliLocationsSetting
import com.superiorsoftllc.infantry.ui.model.HarnessChecklist
import com.superiorsoftllc.infantry.ui.model.HarnessInstallSetting
import com.superiorsoftllc.infantry.ui.protocol.CliLocation
import com.superiorsoftllc.infantry.ui.protocol.SecretRow

const val HARNESS_CHECKLIST_TAG = "harness-checklist"
const val HARNESS_ROW_TAG_PREFIX = "harness-row-"
const val HARNESS_HOWTO_TAG_PREFIX = "harness-howto-"
/** One credential's state: the tag is the prefix + the secret's name, e.g. `harness-auth-harness.claude.key`. */
const val HARNESS_AUTH_TAG_PREFIX = "harness-auth-"
const val HARNESS_AUTH_SETUP_TAG_PREFIX = "harness-auth-setup-"

/**
 * FIRST-RUN-HARNESS-CHECKLIST-1: every agent tool the daemon knows, one bordered card each: its name, GREEN "Installed <version>"
 * or RED "Not installed", the built-in installer's Install / Update (or how to install it by hand), and, for an installed tool,
 * each declared credential with its state (green Signed in / API key set, red Needs setup) and a Set up button that opens
 * [authEditor] (the first-run keys screen's own sign-in / key pieces). With [onSaveLocation] the card also carries the CLI path
 * field (Browse, Save, Clear to default). Configuration > Agents and the first-run checklist both draw exactly this.
 */
@Composable
fun HarnessChecklistView(
    items: List<HarnessChecklist.Item>,
    busy: Boolean,
    log: List<String>,
    nodeNote: String?,
    onInstall: ((ids: List<String>, update: Boolean) -> Unit)?,
    onSaveLocation: ((id: String, path: String?) -> Unit)?,
    authEditor: (@Composable (SecretRow) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag(HARNESS_CHECKLIST_TAG), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { item -> HarnessCard(item, busy, log, nodeNote, onInstall, onSaveLocation, authEditor) }
    }
}

@Composable
private fun HarnessCard(
    item: HarnessChecklist.Item,
    busy: Boolean,
    log: List<String>,
    nodeNote: String?,
    onInstall: ((ids: List<String>, update: Boolean) -> Unit)?,
    onSaveLocation: ((id: String, path: String?) -> Unit)?,
    authEditor: (@Composable (SecretRow) -> Unit)?,
) {
    var pressed by remember(item.id) { mutableStateOf(false) }
    val check = item.check
    Column(
        Modifier.fillMaxWidth().border(1.dp, Palette.Border, Palette.FieldShape).padding(12.dp).testTag(HARNESS_ROW_TAG_PREFIX + item.id),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.label, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                item.status, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (item.installed) Palette.Ok else Palette.Danger,
                modifier = Modifier.testTag(HARNESS_STATE_TAG_PREFIX + item.id),
            )
            val label = check?.let(HarnessInstallSetting::action)
            if (check != null && label != null && onInstall != null) {
                TextButton(
                    onClick = { pressed = true; onInstall(listOf(check.id), HarnessInstallSetting.isUpdate(check)) },
                    enabled = !busy,
                    modifier = Modifier.testTag(HARNESS_INSTALL_TAG_PREFIX + check.id),
                ) { Text(if (busy && pressed) "Installing..." else label) }
            }
        }
        item.location?.let { loc ->
            Text(
                CliLocationsSetting.status(loc), color = Palette.Muted, fontSize = 11.sp,
                modifier = Modifier.testTag(CLI_STATUS_TAG_PREFIX + loc.id),
            )
        }
        if (check != null) {
            // ONBOARD-2: an Install press always answers on its own card: the running task (Node first), then the outcome.
            if (busy && pressed) {
                com.superiorsoftllc.infantry.ui.progress.ProgressIndicator(HarnessInstallSetting.taskId(HarnessInstallSetting.NODE_ID), Modifier.fillMaxWidth(), hideWhenDone = true)
                com.superiorsoftllc.infantry.ui.progress.ProgressIndicator(HarnessInstallSetting.taskId(check.id), Modifier.fillMaxWidth(), hideWhenDone = true)
                Text("Installing ${item.label}. This can take a few minutes.", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(HARNESS_BUSY_TAG_PREFIX + check.id))
            } else if (pressed) {
                HarnessInstallSetting.outcomeFor(check.id, log)?.let { o ->
                    Text(o.text, color = if (o.ok) Palette.Muted else Palette.Danger, fontSize = 11.sp, modifier = Modifier.testTag(HARNESS_OUTCOME_TAG_PREFIX + check.id))
                }
            }
            if (!busy && check.state == "missing" && nodeNote != null) {
                Text(nodeNote, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(HARNESS_NODE_NOTE_TAG_PREFIX + check.id))
            }
        }
        item.howTo?.let { Text(it, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(HARNESS_HOWTO_TAG_PREFIX + item.id)) }
        // A tool that is not installed is never asked for a key (the model lists no credentials for it).
        item.credentials.forEach { cred -> CredentialLine(cred, authEditor) }
        if (onSaveLocation != null && item.location != null) {
            item.location.let { loc ->
                CliLocationsSetting.triedLines(loc).forEach { Text("tried: $it", color = Palette.Muted, fontSize = 10.sp) }
                CliPathControls(loc, onSaveLocation)
            }
        }
    }
}

@Composable
private fun CredentialLine(cred: HarnessChecklist.Credential, authEditor: (@Composable (SecretRow) -> Unit)?) {
    var open by remember(cred.row.name) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(cred.label + if (cred.primary) "" else " (optional)", fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                cred.auth.text, fontSize = 12.sp,
                color = if (cred.auth.ok) Palette.Ok else Palette.Danger,
                modifier = Modifier.testTag(HARNESS_AUTH_TAG_PREFIX + cred.row.name),
            )
            if (authEditor != null) TextButton(
                onClick = { open = !open },
                modifier = Modifier.testTag(HARNESS_AUTH_SETUP_TAG_PREFIX + cred.row.name),
            ) { Text(if (open) "Close" else if (cred.auth.ok) "Change" else "Set up") }
        }
        if (open && authEditor != null) authEditor(cred.row)
    }
}

/** CLIPATH-1 / PATH-PICKER-1: one CLI's path field (Browse fills it), Save and Clear to default. The row shows the DAEMON's answer. */
@Composable
internal fun CliPathControls(location: CliLocation, onSave: (id: String, path: String?) -> Unit) {
    // Re-seeded whenever the daemon reports a different override, so a save or clear elsewhere shows here.
    var text by remember(location.id, location.override) { mutableStateOf(CliLocationsSetting.initialText(location)) }
    PathField(
        text, { text = it }, PathKind.FILE, "${location.label} executable", Modifier.fillMaxWidth(),
        tag = CLI_FIELD_TAG_PREFIX + location.id, title = "Locate ${location.label}",
        placeholder = "Absolute path to the ${location.label} executable",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val toSave = CliLocationsSetting.pathToSave(text)
        TextButton(
            onClick = { onSave(location.id, toSave) },
            enabled = toSave != null && toSave != location.override,
            modifier = Modifier.testTag(CLI_SAVE_TAG_PREFIX + location.id),
        ) { Text("Save") }
        TextButton(
            onClick = { text = ""; onSave(location.id, null) },
            enabled = location.override != null,
            modifier = Modifier.testTag(CLI_CLEAR_TAG_PREFIX + location.id),
        ) { Text("Clear to default") }
    }
}
