// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.superiorsoftllc.infantry.ui.Mono
import com.superiorsoftllc.infantry.ui.OutlinedButton
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.TextButton
import com.superiorsoftllc.infantry.ui.plugin.TabContext
import com.superiorsoftllc.infantry.ui.progress.ProgressBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val ACCOUNT_EMPTY_TAG = "account-empty"
const val ACCOUNT_CODE_TAG_PREFIX = "account-code-"
const val ACCOUNT_SIGN_IN_TAG_PREFIX = "account-sign-in-"
const val ACCOUNT_SIGN_OUT_TAG_PREFIX = "account-sign-out-"
const val ACCOUNT_CARD_TAG_PREFIX = "account-card-"

/** How often the section re-reads the daemon: fast while a sign-in waits for approval, slow otherwise. Display refresh only. */
const val ACCOUNT_WAIT_POLL_MS = 2_000L
const val ACCOUNT_IDLE_POLL_MS = 60_000L

/** Loads the providers when the section opens and keeps them current; [reload] forces a re-read after an action. */
private class AccountLive(val client: AccountClient) {
    var providers by mutableStateOf<List<AccountProvider>?>(null)
    var problem by mutableStateOf<String?>(null)

    suspend fun reload(refresh: Boolean = false) {
        providers = client.load(refresh) ?: providers
        problem = client.lastProblem
    }
}

/** What both sections draw: the provider cards from [content], kept live; an empty note when no provider registered. */
@Composable
private fun AccountSection(context: TabContext, content: @Composable (AccountLive, List<AccountProvider>) -> Unit) {
    val live = remember { AccountLive(AccountClient(context.services.get(Services.DAEMON))) }
    LaunchedEffect(Unit) {
        live.reload(refresh = true)
        while (true) {
            delay(if (live.providers.orEmpty().any { it.flow?.state == "pending" }) ACCOUNT_WAIT_POLL_MS else ACCOUNT_IDLE_POLL_MS)
            live.reload()
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        live.problem?.let { Text(it, color = Palette.Danger, fontSize = 12.sp) }
        val providers = live.providers
        when {
            providers == null -> if (live.problem == null) Text("Loading...", color = Palette.Muted, fontSize = 12.sp)
            providers.isEmpty() -> Text(
                "No service has registered an account provider yet. A plugin adds one by publishing an account.provider/<id> endpoint; it then appears here.",
                color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag(ACCOUNT_EMPTY_TAG),
            )
            else -> content(live, providers)
        }
    }
}

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

private fun planColor(status: String?): Color = when (status) {
    "active" -> Palette.Ok
    "past_due" -> Palette.Warn
    "canceled" -> Palette.Danger
    else -> Palette.Muted
}

/** Overview: one card per provider with sign in / sign out, the plan and its services, and the device-code card while a sign-in waits. */
@Composable
fun AccountOverview(context: TabContext) = AccountSection(context) { live, providers ->
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var confirmOut by remember { mutableStateOf<AccountProvider?>(null) }
    var actionProblem by remember { mutableStateOf<String?>(null) }
    actionProblem?.let { Text(it, color = Palette.Danger, fontSize = 12.sp) }
    for (p in providers) CardBox(ACCOUNT_CARD_TAG_PREFIX + p.id) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.name, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (p.signedIn) Chip("Signed in", Palette.Ok) else Chip("Signed out", Palette.Muted)
            p.planStatus?.let { Chip(it.replace('_', ' '), planColor(it)) }
        }
        if (p.signedIn) {
            val who = listOfNotNull(p.userName, p.userEmail).joinToString("  ")
            if (who.isNotBlank()) Text(who, color = Palette.Muted, fontSize = 12.sp)
            p.planName?.let { Text("Plan: $it", color = Palette.Text, fontSize = 12.sp) }
            p.error?.let { Text(it, color = Palette.Warn, fontSize = 11.sp) }
        }
        for (s in p.services) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.name, color = Palette.Text, fontSize = 12.sp)
            when {
                !p.signedIn -> Unit
                s.entitled -> Chip("Active", Palette.Ok)
                p.signUpUrl != null -> TextButton({ uri.openUri(p.signUpUrl) }) { Text("Subscribe", fontSize = 12.sp) }
                else -> Chip("Not included", Palette.Muted)
            }
        }
        val flow = p.flow
        if (flow != null && flow.state == "pending") {
            Text("Enter this code on the sign-in page:", color = Palette.Muted, fontSize = 12.sp)
            Text(flow.userCode, color = Palette.Text, fontFamily = Mono, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(ACCOUNT_CODE_TAG_PREFIX + p.id))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button({ uri.openUri(flow.verificationUri) }) { Text("Open sign-in page") }
                Text("Waiting for you to approve...", color = Palette.Muted, fontSize = 12.sp)
            }
            ProgressBar(null, Modifier.fillMaxWidth())
        } else if (flow != null) {
            Text(flow.error ?: "The sign-in ${flow.state}.", color = Palette.Warn, fontSize = 12.sp)
        }
        if (p.signedIn) {
            OutlinedButton({ confirmOut = p }, Modifier.testTag(ACCOUNT_SIGN_OUT_TAG_PREFIX + p.id)) { Text("Sign out") }
        } else if (flow?.state != "pending") {
            Button({
                scope.launch {
                    actionProblem = live.client.signIn(p.id)
                    live.reload()
                }
            }, Modifier.testTag(ACCOUNT_SIGN_IN_TAG_PREFIX + p.id)) { Text(if (flow == null) "Sign in" else "Try again") }
        }
    }
    confirmOut?.let { p ->
        AhqAlertDialog(
            onDismissRequest = { confirmOut = null },
            title = { Text("Sign out of ${p.name}?") },
            text = { Text("Plugins that use this account stop working until you sign in again.") },
            confirmButton = {
                TextButton({
                    confirmOut = null
                    scope.launch {
                        actionProblem = live.client.signOut(p.id)
                        live.reload()
                    }
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton({ confirmOut = null }) { Text("Cancel") } },
        )
    }
}

/** Billing: one card per provider with the plan and a button to manage it on the provider's own site. No card or price is ever held here. */
@Composable
fun AccountBilling(context: TabContext) = AccountSection(context) { _, providers ->
    val uri = LocalUriHandler.current
    for (p in providers) CardBox("account-billing-" + p.id) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.name, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            p.planStatus?.let { Chip(it.replace('_', ' '), planColor(it)) }
        }
        when {
            !p.signedIn -> Text("Sign in from the Overview section to see your plan.", color = Palette.Muted, fontSize = 12.sp)
            p.planName != null -> Text("Plan: ${p.planName}", color = Palette.Text, fontSize = 12.sp)
            else -> Text("No plan yet.", color = Palette.Muted, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.billingUrl != null) OutlinedButton({ uri.openUri(p.billingUrl) }) { Text("Manage") }
            if (p.signedIn && p.planName == null && p.signUpUrl != null) Button({ uri.openUri(p.signUpUrl) }) { Text("Subscribe") }
        }
    }
}
