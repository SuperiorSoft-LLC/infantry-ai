// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.sites

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.plugin.api.Services
import com.superiorsoftllc.infantry.ui.AhqAlertDialog
import com.superiorsoftllc.infantry.ui.Button
import com.superiorsoftllc.infantry.ui.OutlinedButton
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.TextButton
import com.superiorsoftllc.infantry.ui.plugin.TabContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val SITES_EMPTY_TAG = "sites-empty"
const val SITES_SIGN_IN_TAG = "sites-sign-in"
const val SITES_SUBSCRIBE_TAG = "sites-subscribe"
const val SITES_CREATE_OPEN_TAG = "sites-create-open"
const val SITES_CREATE_SUBMIT_TAG = "sites-create-submit"
const val SITES_NAME_TAG = "sites-name"
const val SITES_KEY_TAG = "sites-ssh-key"
const val SITES_RECLAIM_TAG = "sites-reclaim-text"
const val SITES_MESSAGE_TAG = "sites-message"
const val SITES_ROW_TAG_PREFIX = "sites-server-"
const val SITES_START_TAG_PREFIX = "sites-start-"
const val SITES_STOP_TAG_PREFIX = "sites-stop-"
const val SITES_DESTROY_TAG_PREFIX = "sites-destroy-"

/** How often the section re-reads the daemon (display refresh only). */
const val SITES_POLL_MS = 10_000L

@OptIn(ExperimentalUuidApi::class)
private fun newIdempotencyKey(): String = Uuid.random().toString()

@Composable
private fun CardBox(tag: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().testTag(tag).background(Palette.Panel, RoundedCornerShape(8.dp)).border(BorderStroke(1.dp, Palette.Border), RoundedCornerShape(8.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(text, color = color, fontSize = 11.sp, modifier = Modifier.border(BorderStroke(1.dp, color), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
}

private fun statusColor(status: String): Color = when (status) {
    "active" -> Palette.Ok
    "new" -> Palette.Warn
    else -> Palette.Muted
}

/** The Sites section: the state first (sign in, subscribe, limit), then the servers with create, start, stop and destroy. */
@Composable
fun SitesServers(context: TabContext) {
    val client = remember { SitesClient(context.services.get(Services.DAEMON)) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var state by remember { mutableStateOf<SitesState?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var idem by remember { mutableStateOf(newIdempotencyKey()) }
    var confirmDestroy by remember { mutableStateOf<SiteServer?>(null) }

    suspend fun reload() {
        state = client.load() ?: state
        problem = client.lastProblem
    }
    fun act(run: suspend () -> String?) {
        scope.launch {
            message = run()
            reload()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            reload()
            delay(SITES_POLL_MS)
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        problem?.let { Text(it, color = Palette.Danger, fontSize = 12.sp) }
        message?.let { Text(it, color = Palette.Warn, fontSize = 12.sp, modifier = Modifier.testTag(SITES_MESSAGE_TAG)) }
        val s = state
        s?.reclaimableUntil?.let { until ->
            CardBox("sites-reclaim") {
                Text("Infantry AI Hosting", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Your subscription ended. Re-subscribe before ${SitesParser.date(until) ?: "the deadline"} to keep your server.", color = Palette.Warn, fontSize = 12.sp, modifier = Modifier.testTag(SITES_RECLAIM_TAG))
                s.signUpUrl?.let { url -> Button({ uri.openUri(url) }, Modifier.testTag(SITES_SUBSCRIBE_TAG)) { Text("Subscribe") } }
            }
        }
        when {
            s == null -> if (problem == null) Text("Loading...", color = Palette.Muted, fontSize = 12.sp)
            s.state == "not_configured" -> Text(s.message ?: "No hosting service is configured.", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag(SITES_EMPTY_TAG))
            s.state == "signed_out" -> CardBox("sites-signed-out") {
                Text("Infantry AI Hosting", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Sign in to see and manage your servers. The sign-in code shows in Account > Overview.", color = Palette.Muted, fontSize = 12.sp)
                Button({ act { client.startSignIn() } }, Modifier.testTag(SITES_SIGN_IN_TAG)) { Text("Sign in") }
            }
            s.state == "not_entitled" && s.reclaimableUntil != null -> Unit
            s.state == "not_entitled" -> CardBox("sites-not-entitled") {
                Text("Infantry AI Hosting", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Your plan does not include hosting yet.", color = Palette.Muted, fontSize = 12.sp)
                s.signUpUrl?.let { url -> Button({ uri.openUri(url) }, Modifier.testTag(SITES_SUBSCRIBE_TAG)) { Text("Subscribe") } }
            }
            s.state == "ready" || s.state == "limit" -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Servers", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Chip(if (s.limit != null) "${s.servers.size} of ${s.limit}" else "${s.servers.size}", Palette.Muted)
                    Button({ creating = !creating }, Modifier.testTag(SITES_CREATE_OPEN_TAG)) { Text(if (creating) "Close" else "Create server") }
                }
                if (s.state == "limit") Text(SitesParser.message("limit", ""), color = Palette.Warn, fontSize = 12.sp)
                if (creating) CardBox("sites-create-form") {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag(SITES_NAME_TAG), label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().testTag(SITES_KEY_TAG), label = { Text("SSH public key (optional)") }, singleLine = true)
                    Button({
                        val n = name.trim()
                        val k = key
                        val sent = idem
                        scope.launch {
                            message = client.create(n, k, sent)
                            if (message == null) { creating = false; name = ""; key = ""; idem = newIdempotencyKey() }
                            reload()
                        }
                    }, Modifier.testTag(SITES_CREATE_SUBMIT_TAG), enabled = name.isNotBlank()) { Text("Create") }
                }
                if (s.servers.isEmpty()) Text("No servers yet.", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag(SITES_EMPTY_TAG))
                for (srv in s.servers) CardBox(SITES_ROW_TAG_PREFIX + srv.id) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(srv.name, color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Chip(srv.status.ifBlank { "unknown" }, statusColor(srv.status))
                    }
                    Text(listOf(srv.ip, srv.region, srv.size, SitesParser.date(srv.createdAt).orEmpty()).filter { it.isNotBlank() }.joinToString("   "), color = Palette.Muted, fontSize = 12.sp)
                    SitesParser.date(srv.destroyAt)?.let { Text("Will be destroyed on $it", color = Palette.Warn, fontSize = 12.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (srv.status == "active") OutlinedButton({ act { client.power(srv.id, "stop") } }, Modifier.testTag(SITES_STOP_TAG_PREFIX + srv.id)) { Text("Stop") }
                        else OutlinedButton({ act { client.power(srv.id, "start") } }, Modifier.testTag(SITES_START_TAG_PREFIX + srv.id)) { Text("Start") }
                        OutlinedButton({ confirmDestroy = srv }, Modifier.testTag(SITES_DESTROY_TAG_PREFIX + srv.id)) { Text("Destroy") }
                    }
                }
            }
            else -> Text(s.message ?: "Hosting is not reachable right now.", color = Palette.Warn, fontSize = 12.sp)
        }
    }
    confirmDestroy?.let { srv ->
        AhqAlertDialog(
            onDismissRequest = { confirmDestroy = null },
            title = { Text("Destroy ${srv.name}?") },
            text = { Text("The server and everything on it are deleted. This cannot be undone.") },
            confirmButton = { TextButton({ confirmDestroy = null; act { client.destroy(srv.id) } }) { Text("Destroy") } },
            dismissButton = { TextButton({ confirmDestroy = null }) { Text("Cancel") } },
        )
    }
}
