// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.shell.ServerChoice
import com.superiorsoftllc.infantry.ui.shell.ServerSwitcher
import kotlinx.coroutines.delay
import kotlin.time.Clock

const val SERVER_SWITCHER_TAG = "server-switcher"
const val SERVER_SWITCHER_ITEM_TAG_PREFIX = "server-switcher-item:"
const val SERVER_SWITCHER_MANAGE_TAG = "server-switcher-manage"
const val RECONNECT_NOW_TAG = "reconnect-now"

/** SWITCH-1: seconds until the next automatic retry, or null when none is scheduled. Pure, for the chip and its test. */
fun retryCountdownSeconds(info: ConnectionInfo, nowMillis: Long): Int? =
    if (info.state == ConnState.RECONNECTING) info.retryAtMillis?.let { (((it - nowMillis) + 999) / 1000).toInt().coerceAtLeast(0) } else null

/** SWITCH-1: what is wrong with a link that is not up and what happens next. Null when connected. */
fun connectionStatusLine(info: ConnectionInfo, nowMillis: Long): String? = when (info.state) {
    ConnState.CONNECTED -> null
    ConnState.CONNECTING -> "connecting: ${info.detail}"
    ConnState.RECONNECTING -> "reconnecting (try ${info.attempt})" +
        (retryCountdownSeconds(info, nowMillis)?.let { " in ${it}s" } ?: "") + ": ${info.detail}"
    ConnState.REFUSED -> "refused: ${info.detail}. Not retrying; fix the token, then Reconnect now."
    ConnState.FAILED -> "failed: ${info.detail}"
    ConnState.IDLE -> "not connected"
}

/** A once-a-second clock for countdowns; only ticks while [active]. */
@Composable
fun rememberTickingNow(active: Boolean): Long {
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(active) {
        while (active) {
            now = Clock.System.now().toEpochMilliseconds()
            delay(1000)
        }
    }
    return now
}

/** SWITCH-1: the title bar's server dropdown: the active server's name, a menu of the others, and "Manage servers...". */
@Composable
fun ServerSwitcherChip(switcher: ServerSwitcher, onSelect: (ServerChoice) -> Unit, onManage: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val active = switcher.active()
    Row(
        modifier.clickable { open = true }.testTag(SERVER_SWITCHER_TAG).padding(horizontal = 6.dp, vertical = 4.dp)
            .semantics { contentDescription = "Server: ${active?.name ?: "none"}. Switch server" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("▾ ${active?.name ?: "No server"}", color = Palette.Text, fontSize = 11.sp, maxLines = 1)
        InfantryDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            switcher.choices().forEach { c ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text((if (c.url == active?.url) "✓ " else "") + c.name, color = Palette.Text, fontSize = 12.sp)
                            Text(c.url ?: "this machine's own daemon", color = Palette.Muted, fontSize = 10.sp)
                        }
                    },
                    onClick = { open = false; onSelect(c) },
                    modifier = Modifier.testTag(SERVER_SWITCHER_ITEM_TAG_PREFIX + (c.url ?: "local")),
                )
            }
            DropdownMenuItem(
                text = { Text("Manage servers...", fontSize = 12.sp) },
                onClick = { open = false; onManage() },
                modifier = Modifier.testTag(SERVER_SWITCHER_MANAGE_TAG),
            )
        }
    }
}

/** SWITCH-1: add, edit and remove saved servers. A token is sealed by the platform store, shown masked, never logged. */
@Composable
fun ServersDialog(switcher: ServerSwitcher, onRemoved: (wasActive: Boolean) -> Unit, onEdited: (ServerChoice) -> Unit, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<ServerChoice?>(null) }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun open(c: ServerChoice?) {
        editing = c; adding = c == null; name = c?.name.orEmpty(); url = c?.url.orEmpty(); token = c?.token.orEmpty(); error = null
    }
    val form = editing != null || adding
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Servers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!form) {
                    val remote = switcher.choices().filter { !it.isLocal }
                    if (remote.isEmpty()) Text("No remote servers yet. Add one, or scan its pairing QR code.", color = Palette.Muted, fontSize = 11.sp)
                    remote.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, color = Palette.Text, fontSize = 12.sp)
                                Text(c.url.orEmpty() + if (c.token != null) "  (token saved)" else "", color = Palette.Muted, fontSize = 10.sp)
                            }
                            CopyValueButton(c.url.orEmpty())
                            TextButton({ open(c) }) { Text("Edit") }
                            TextButton({ onRemoved(switcher.remove(c.url!!)) }) { Text("Remove") }
                        }
                    }
                    TextButton({ open(null) }) { Text("Add server") }
                } else {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text("ws:// or wss:// address") }, singleLine = true)
                    OutlinedTextField(
                        token, { token = it }, label = { Text("Bearer token") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
                }
            }
        },
        confirmButton = {
            if (form) {
                Button({
                    val old = editing
                    error = if (old == null) switcher.add(name, url, token) else switcher.edit(old.url!!, name, url, token)
                    if (error == null) {
                        if (old != null) onEdited(ServerChoice(name, url.trim(), token.ifBlank { null }))
                        editing = null; adding = false
                    }
                }) { Text("Save") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (form) { editing = null; adding = false } else onDismiss() }) { Text(if (form) "Back" else "Close") }
        },
    )
}
