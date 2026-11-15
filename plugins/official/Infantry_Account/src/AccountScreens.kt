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
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
const val ACCOUNT_GUIDE_TAG = "account-guide"
const val ACCOUNT_CANCEL_TAG = "account-cancel"
const val ACCOUNT_ERROR_TAG = "account-error"
const val ACCOUNT_CHECK_NOW_TAG = "account-check-now"

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** An epoch-millisecond time as "Nov 21, 2026"; null when it is not a time. */
internal fun accountDate(epochMs: Long?): String? {
    val ms = epochMs ?: return null
    val d = runCatching { Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault()).date }.getOrNull() ?: return null
    return "${MONTHS[d.month.ordinal]} ${d.day}, ${d.year}"
}

/** Loads the providers and keeps them current; [reload] re-reads. An action error lives here so a refresh or a state change clears it. */
internal class AccountLive(val client: AccountClient) {
    var providers by mutableStateOf<List<AccountProvider>?>(null)
    var problem by mutableStateOf<String?>(null)
    var actionProblem by mutableStateOf<String?>(null)

    private fun signature(list: List<AccountProvider>?) = list.orEmpty().map { Triple(it.signedIn, it.planStatus, it.flow?.state) }

    suspend fun reload(refresh: Boolean = false) {
        val before = signature(providers)
        providers = client.load(refresh) ?: providers
        problem = client.lastProblem
        if (refresh || signature(providers) != before) actionProblem = null
    }

    /** Runs an action: its error shows only until the next refresh or state change. */
    suspend fun act(run: suspend () -> String?) {
        actionProblem = null
        val result = run()
        reload()
        actionProblem = result
    }
}

/**
 * What every section draws. The layout always renders (the guide, the cards, or a note); a failure is a small card inside it, never the
 * whole view. The section re-reads every [ACCOUNT_WAIT_POLL_MS] while a sign-in waits, else every [ACCOUNT_IDLE_POLL_MS].
 */
@Composable
private fun AccountSection(context: TabContext, content: @Composable (AccountLive, List<AccountProvider>) -> Unit) {
    val live = remember { AccountLive(AccountClient(context.services.get(Services.DAEMON))) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        live.reload(refresh = true)
        var sinceMs = 0L
        while (true) {
            delay(ACCOUNT_WAIT_POLL_MS)
            sinceMs += ACCOUNT_WAIT_POLL_MS
            // The tick is short so a sign-in started AFTER the last wait still flips within seconds; the slow period only gates the idle re-read.
            if (live.providers.orEmpty().any { it.flow?.state == "pending" } || sinceMs >= ACCOUNT_IDLE_POLL_MS) {
                sinceMs = 0
                live.reload()
            }
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        live.problem?.let { ErrorCard(it) { scope.launch { live.reload(refresh = true) } } }
        live.actionProblem?.let { Text(it, color = Palette.Danger, fontSize = 12.sp) }
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

/** A failure in plain words with Retry; the technical text sits behind Details. */
@Composable
private fun ErrorCard(detail: String, onRetry: () -> Unit) {
    var details by remember { mutableStateOf(false) }
    CardBox(ACCOUNT_ERROR_TAG) {
        Text("Couldn't reach your account.", color = Palette.Warn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("Check your connection, then try again. Nothing you set up was lost.", color = Palette.Muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onRetry) { Text("Retry") }
            TextButton({ details = !details }) { Text(if (details) "Hide details" else "Details", fontSize = 12.sp) }
        }
        if (details) Text(detail, color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp)
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

/** One plain sentence about the plan state, with what to do; null when the plan is fine. */
internal fun planNotice(p: AccountProvider): String? = when (p.planStatus) {
    "past_due" -> "Payment failed. Update your payment method to keep hosting."
    "canceled" -> "Your subscription ended." + (accountDate(p.reclaimableUntil)?.let { " Servers are kept until $it." } ?: "")
    else -> null
}

/** The page to open for a pending sign-in: the verification URL with the code attached when it is not already in it. */
internal fun signInUrl(flow: AccountFlow): String =
    if (flow.userCode.isBlank() || "user_code=" in flow.verificationUri) flow.verificationUri
    else flow.verificationUri + (if ('?' in flow.verificationUri) "&" else "?") + "user_code=" + flow.userCode

/**
 * The one signed-out card (Overview and Billing share it): "Sign in" and a single button. Pressing it opens the browser and the card
 * then shows the code and a self-updating wait; the section flips to signed in by itself when the browser approves.
 */
@Composable
internal fun SignInCard(live: AccountLive, p: AccountProvider) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val flow = p.flow
    CardBox(ACCOUNT_GUIDE_TAG) {
        Text("Sign in", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (flow != null && flow.state == "pending") {
            Text("Finish signing in in your browser.", color = Palette.Muted, fontSize = 12.sp)
            Text(flow.userCode, color = Palette.Text, fontFamily = Mono, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(ACCOUNT_CODE_TAG_PREFIX + p.id))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ uri.openUri(signInUrl(flow)) }, Modifier.testTag(ACCOUNT_CHECK_NOW_TAG)) { Text("Open the page again", fontSize = 12.sp) }
                TextButton({ scope.launch { live.act { live.client.signOut(p.id) } } }, Modifier.testTag(ACCOUNT_CANCEL_TAG)) { Text("Cancel", fontSize = 12.sp) }
                Text("Waiting for approval...", color = Palette.Muted, fontSize = 12.sp)
            }
            ProgressBar(null, Modifier.fillMaxWidth())
        } else {
            if (flow != null) Text(flow.error ?: "The sign-in ${flow.state}.", color = Palette.Warn, fontSize = 12.sp)
            else Text("Sign in to see and manage your services.", color = Palette.Muted, fontSize = 12.sp)
            Button({
                scope.launch {
                    live.act { live.client.signIn(p.id) }
                    live.providers?.firstOrNull { it.id == p.id }?.flow?.takeIf { it.state == "pending" }?.let { uri.openUri(signInUrl(it)) }
                }
            }, Modifier.testTag(ACCOUNT_SIGN_IN_TAG_PREFIX + p.id)) { Text(if (flow == null) "Sign in" else "Try again") }
        }
    }
}

/** Overview: the guide until setup is done, then one card per signed-in provider with the plan, what it includes and its services. */
@Composable
fun AccountOverview(context: TabContext) = AccountSection(context) { live, providers ->
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var confirmOut by remember { mutableStateOf<AccountProvider?>(null) }
    if (providers.none { it.signedIn }) SignInCard(live, providers.first())
    for (p in providers.filter { it.signedIn }) CardBox(ACCOUNT_CARD_TAG_PREFIX + p.id) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.name, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Chip("Signed in", Palette.Ok)
        }
        val who = listOfNotNull(p.userName, p.userEmail).joinToString("  ")
        if (who.isNotBlank()) Text(who, color = Palette.Muted, fontSize = 12.sp)
        p.error?.let { Text(it, color = Palette.Warn, fontSize = 11.sp) }
        OutlinedButton({ confirmOut = p }, Modifier.testTag(ACCOUNT_SIGN_OUT_TAG_PREFIX + p.id)) { Text("Sign out") }
    }
    for (p in providers.filter { it.signedIn }) {
        Text("Your services", color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (p.services.isEmpty()) Text("No service is offered yet.", color = Palette.Muted, fontSize = 12.sp)
        for (s in p.services) CardBox("account-service-${s.id}") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.name, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (s.entitled) Chip("Active", Palette.Ok)
            }
            if (!s.entitled) {
                Text("${s.name} needs a plan.", color = Palette.Muted, fontSize = 12.sp)
                planNotice(p)?.let { Text(it, color = Palette.Warn, fontSize = 12.sp) }
                p.signUpUrl?.let { url -> Button({ uri.openUri(url) }) { Text(if (p.planStatus == "canceled") "Resubscribe" else "Subscribe") } }
            }
        }
    }
    confirmOut?.let { p ->
        AhqAlertDialog(
            onDismissRequest = { confirmOut = null },
            title = { Text("Sign out of ${p.name}?") },
            text = { Text("Plugins that use this account stop working until you sign in again.") },
            confirmButton = { TextButton({ confirmOut = null; scope.launch { live.act { live.client.signOut(p.id) } } }) { Text("Sign out") } },
            dismissButton = { TextButton({ confirmOut = null }) { Text("Cancel") } },
        )
    }
}

/** Billing: the guide pointing at Step 1 until signed in, then the plan, its status and Manage. No card or price is held here. */
@Composable
fun AccountBilling(context: TabContext) = AccountSection(context) { live, providers ->
    val uri = LocalUriHandler.current
    if (providers.none { it.signedIn }) {
        SignInCard(live, providers.first())
        return@AccountSection
    }
    for (p in providers.filter { it.signedIn }) CardBox("account-billing-" + p.id) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.name, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            p.planStatus?.let { Chip(it.replace('_', ' '), planColor(it)) }
        }
        if (p.hasPlan) Text("Plan: ${AccountWords.planLabel(p) ?: "your plan"}", color = Palette.Text, fontSize = 12.sp)
        else Text("No plan yet.", color = Palette.Muted, fontSize = 12.sp)
        planNotice(p)?.let { Text(it, color = Palette.Warn, fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.billingUrl != null && p.hasPlan) OutlinedButton({ uri.openUri(p.billingUrl) }) { Text("Manage") }
            if (p.signUpUrl != null && (!p.hasPlan || p.planStatus == "canceled")) Button({ uri.openUri(p.signUpUrl) }) { Text(if (p.hasPlan) "Resubscribe" else "Subscribe") }
        }
    }
}
