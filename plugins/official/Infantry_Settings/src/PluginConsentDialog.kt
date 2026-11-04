// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.superiorsoftllc.infantry.plugin.api.InspectView
import com.superiorsoftllc.infantry.ui.Checkbox
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.TextButton

/** PLUGIN-UI-1: said in every install and update dialog, word for word. */
const val NOT_SANDBOXED_WARNING = "This plugin runs inside Infantry AI with the same access to your computer as the app itself. It is not sandboxed. Install only plugins you trust."

const val CONSENT_TAG = "plugin-consent"
const val CONSENT_WARNING_TAG = "plugin-consent-warning"
const val CONSENT_INSTALL_TAG = "plugin-consent-install"
const val CONSENT_CANCEL_TAG = "plugin-consent-cancel"
const val CONSENT_ERROR_TAG = "plugin-consent-error"
const val CONSENT_TICK_PREFIX = "plugin-consent-tick-"

/**
 * PLUGIN-UI-1: the one consent dialog for installing or updating a plugin (Browse, a grant-raising update, a `.ihq` file).
 * Shows class, signer, license and every requested grant with a tick; [onConfirm] gets exactly the ticked grants.
 * [error] is the refusal of the last attempt; [busy] disables Install while it runs.
 */
@Composable
fun PluginConsentDialog(
    title: String,
    view: InspectView,
    error: String?,
    busy: Boolean,
    onConfirm: (Set<String>) -> Unit,
    onCancel: () -> Unit,
) {
    var ticked by remember(view) { mutableStateOf(view.grants.toSet()) }
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(CONSENT_TAG),
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${view.id} ${view.version}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Class: ${view.cls}", fontSize = 13.sp)
                Text("Signed by: ${view.signer ?: "nobody (unsigned)"}", fontSize = 13.sp)
                Text("License: ${view.license ?: "Not stated"}", fontSize = 13.sp)
                Text(NOT_SANDBOXED_WARNING, color = Palette.Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag(CONSENT_WARNING_TAG))
                for (w in view.warnings) Text(w, color = Palette.Muted, fontSize = 12.sp)
                if (view.grants.isEmpty()) Text("It asks for no permissions.", color = Palette.Muted, fontSize = 12.sp)
                else Text("It asks for these (untick what you do not allow; those parts will not load):", color = Palette.Muted, fontSize = 12.sp)
                for (g in view.grants) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = g in ticked, onCheckedChange = { on -> ticked = if (on) ticked + g else ticked - g }, modifier = Modifier.testTag(CONSENT_TICK_PREFIX + g))
                    Text(g, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                }
                error?.let { Text(it, color = Palette.Danger, fontSize = 12.sp, modifier = Modifier.testTag(CONSENT_ERROR_TAG)) }
                if (busy) Text("Checking and installing ...", color = Palette.Muted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(ticked) }, enabled = !busy, modifier = Modifier.testTag(CONSENT_INSTALL_TAG)) { Text("Install") } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.testTag(CONSENT_CANCEL_TAG)) { Text("Cancel") } },
    )
}
