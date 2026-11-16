// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import com.superiorsoftllc.infantry.ui.clickable
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
 *
 * [scrolls] false: the section fills the height and does not scroll, so a child that scrolls by itself (another plugin's dashboard)
 * gets a bounded height. Nesting it in this scroll measures it with an infinite height, which Compose refuses (SIGNIN-APP-1).
 */
@Composable
private fun AccountSection(context: TabContext, scrolls: Boolean = true, content: @Composable ColumnScope.(AccountLive, List<AccountProvider>) -> Unit) {
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
    val layout = if (scrolls) Modifier.fillMaxWidth().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize()
    Column(layout.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
private fun CardBox(tag: String, extra: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().testTag(tag).then(extra).background(Palette.Panel, RoundedCornerShape(8.dp)).border(BorderStroke(1.dp, Palette.Border), RoundedCornerShape(8.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Chip(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = 11.sp, modifier = modifier.border(BorderStroke(1.dp, color), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
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

const val SERVICES_BACK_TAG = "services-back"
const val SERVICES_OPEN_TAG_PREFIX = "services-open-"
const val SERVICES_CONNECTED_TAG_PREFIX = "services-connected-"
const val SERVICES_BILLING_TAG = "services-billing"
const val SERVICES_DASHBOARD_TAG_PREFIX = "services-dashboard-"

/** Whether a card for [p] opens a dashboard: connected, and the provider names a dashboard tab that is loaded. */
internal fun canOpenDashboard(p: AccountProvider, loaded: (String) -> Boolean): Boolean = p.signedIn && p.dashboardTabId?.let(loaded) == true

/**
 * The Services tab: one bordered card per registered provider (the list comes from the daemon, nothing is named here). Not signed in:
 * a Sign in button that opens the provider's own site; the card then shows the code and flips to Connected by itself. Connected: the
 * card opens that service's own dashboard (a tab its plugin contributes), with a back to the list.
 */
@Composable
fun ServicesHub(context: TabContext) {
    // Hoisted: an open dashboard scrolls by itself, so the section around it must not (see AccountSection's scrolls).
    var openId by remember { mutableStateOf<String?>(null) }
    AccountSection(context, scrolls = openId == null) { live, providers -> ServicesHubBody(live, providers, context, openId) { openId = it } }
}

@Composable
private fun ColumnScope.ServicesHubBody(live: AccountLive, providers: List<AccountProvider>, context: TabContext, openId: String?, setOpen: (String?) -> Unit) {
    val factory = com.superiorsoftllc.infantry.ui.LocalTabFactory.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var confirmOut by remember { mutableStateOf<AccountProvider?>(null) }
    val open = providers.firstOrNull { it.id == openId && canOpenDashboard(it) { t -> factory.find(t) != null } }
    // The opened service signed out or its dashboard went away: back to the (scrolling) list.
    if (openId != null && open == null) SideEffect { setOpen(null) }
    if (open != null) {
        val tabId = open.dashboardTabId!!
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ setOpen(null) }, Modifier.testTag(SERVICES_BACK_TAG)) { Text("< Services") }
            Text(open.name, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Chip("Connected", Palette.Ok)
            Spacer(Modifier.weight(1f))
            open.billingUrl?.let { url -> OutlinedButton({ uri.openUri(url) }, Modifier.testTag(SERVICES_BILLING_TAG)) { Text("Billing") } }
            OutlinedButton({ confirmOut = open }, Modifier.testTag(ACCOUNT_SIGN_OUT_TAG_PREFIX + open.id)) { Text("Sign out") }
        }
        planNotice(open)?.let { Text(it, color = Palette.Warn, fontSize = 12.sp) }
        // weight: the dashboard gets the height that is left, a bounded one, so its own scroll works.
        Box(Modifier.fillMaxWidth().weight(1f).testTag(SERVICES_DASHBOARD_TAG_PREFIX + open.id)) { factory.Content(tabId, context) }
    } else {
        for (p in providers) ServiceCard(live, p, canOpenDashboard(p) { factory.find(it) != null }) { setOpen(p.id) }
    }
    confirmOut?.let { p ->
        AhqAlertDialog(
            onDismissRequest = { confirmOut = null },
            title = { Text("Sign out of ${p.name}?") },
            text = { Text("Plugins that use this account stop working until you sign in again.") },
            confirmButton = { TextButton({ confirmOut = null; setOpen(null); scope.launch { live.act { live.client.signOut(p.id) } } }) { Text("Sign out") } },
            dismissButton = { TextButton({ confirmOut = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ServiceCard(live: AccountLive, p: AccountProvider, hasDashboard: Boolean, onOpen: () -> Unit) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val flow = p.flow
    val pending = flow != null && flow.state == "pending"
    CardBox(ACCOUNT_CARD_TAG_PREFIX + p.id, if (p.signedIn && hasDashboard) Modifier.clickable(onClick = onOpen).testTag(SERVICES_OPEN_TAG_PREFIX + p.id) else Modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.name, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (p.signedIn) Chip("Connected", Palette.Ok, Modifier.testTag(SERVICES_CONNECTED_TAG_PREFIX + p.id))
        }
        p.description?.let { Text(it, color = Palette.Muted, fontSize = 12.sp) }
        p.error?.let { Text(it, color = Palette.Warn, fontSize = 11.sp) }
        when {
            p.signedIn -> if (hasDashboard) Text("Open its dashboard", color = Palette.Muted, fontSize = 12.sp)
            pending -> {
                Text("Finish signing in in your browser.", color = Palette.Muted, fontSize = 12.sp)
                Text(flow!!.userCode, color = Palette.Text, fontFamily = Mono, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(ACCOUNT_CODE_TAG_PREFIX + p.id))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ uri.openUri(signInUrl(flow)) }, Modifier.testTag(ACCOUNT_CHECK_NOW_TAG)) { Text("Open the page again", fontSize = 12.sp) }
                    TextButton({ scope.launch { live.act { live.client.signOut(p.id) } } }, Modifier.testTag(ACCOUNT_CANCEL_TAG)) { Text("Cancel", fontSize = 12.sp) }
                    Text("Waiting for approval...", color = Palette.Muted, fontSize = 12.sp)
                }
                ProgressBar(null, Modifier.fillMaxWidth())
            }
            else -> {
                if (flow != null) Text(flow.error ?: "The sign-in ${flow.state}.", color = Palette.Warn, fontSize = 12.sp)
                Button({
                    scope.launch {
                        live.act { live.client.signIn(p.id) }
                        live.providers?.firstOrNull { it.id == p.id }?.flow?.takeIf { it.state == "pending" }?.let { uri.openUri(signInUrl(it)) }
                    }
                }, Modifier.testTag(ACCOUNT_SIGN_IN_TAG_PREFIX + p.id)) { Text(if (flow == null) "Sign in" else "Try again") }
            }
        }
    }
}
