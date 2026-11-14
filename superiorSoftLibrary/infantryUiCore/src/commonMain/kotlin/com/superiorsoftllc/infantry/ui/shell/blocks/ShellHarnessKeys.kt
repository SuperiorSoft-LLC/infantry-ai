// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell.blocks

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.AhqAlertDialog
import com.superiorsoftllc.infantry.ui.Button
import com.superiorsoftllc.infantry.ui.CopyValueButton
import com.superiorsoftllc.infantry.ui.LocalShell
import com.superiorsoftllc.infantry.ui.OutlinedButton
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.TextButton
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.protocol.SecretRow
import kotlinx.coroutines.launch

const val HARNESS_KEY_PROMPT_TAG = "harness-key-prompt"
const val HARNESS_KEY_FIELD_TAG = "harness-key-field"
const val SUBSCRIPTION_MODE = "subscription-login"
const val API_KEY_MODE = "api-key"
const val HARNESS_SUBSCRIPTION_TAG = "harness-subscription-choice"
const val HARNESS_SUBSCRIPTION_STATE_TAG = "harness-subscription-state"
const val HARNESS_SUBSCRIPTION_CHECK_TAG = "harness-subscription-check"
const val HARNESS_KEY_SAVE_TAG = "harness-key-save"
const val HARNESS_KEY_SKIP_ALL_TAG = "harness-key-skip-all"
const val HARNESS_KEY_MORE_TAG = "harness-key-more"
const val HARNESS_KEY_SKIP_PREF = "harness.keys.skipped"
const val HARNESS_LOGIN_START_TAG = "harness-login-start"
const val HARNESS_LOGIN_OPEN_TAG = "harness-login-open"
const val HARNESS_LOGIN_CODE_TAG = "harness-login-code"
const val HARNESS_LOGIN_SUBMIT_TAG = "harness-login-submit"
const val HARNESS_LOGIN_CANCEL_TAG = "harness-login-cancel"
const val HARNESS_LOGIN_STATE_TAG = "harness-login-state"
/** ONBOARD-1: the daemon's in-app sign-in states (`SecretRow.loginState`); "" = none started. */
const val LOGIN_RUNNING = "running"
const val LOGIN_DONE = "done"
const val LOGIN_FAILED = "failed"

/**
 * CLAUDE-KEY-PROMPT-1: the harness's primary credential, `harness.<id>.key`. A further credential (`harness.<id>.<credId>.key`,
 * e.g. claude's optional subscription token for runners, opencode's Command Code key) is optional by declaration and lives in
 * Configuration > Secrets only: asking for it at start made every update that added one ask again.
 */
fun isPrimaryHarnessKey(name: String): Boolean =
    name.startsWith("harness.") && name.endsWith(".key") && name.removePrefix("harness.").removeSuffix(".key").let { it.isNotEmpty() && '.' !in it }

/** True when the harness offers a subscription sign-in the daemon can probe: the prompt probes it once per app start and waits for the answer. */
fun hasAuthProbe(row: SecretRow): Boolean = SUBSCRIPTION_MODE in row.authModes && row.loginCommand.isNotBlank()

/**
 * The harness credentials the start-up prompt asks for: a primary key that is not set, not read from a shared secret, not skipped
 * on this device, whose harness is not set to the subscription login and is not already signed in (CLAUDE-KEY-PROMPT-1: a
 * `claude auth login` on the machine is a working sign-in, so no key is asked for).
 */
fun missingHarnessKeys(rows: List<SecretRow>, skipped: Set<String>): List<SecretRow> =
    rows.filter {
        isPrimaryHarnessKey(it.name) && !it.set && it.usesSecret.isBlank() && it.name !in skipped &&
            it.authMode != SUBSCRIPTION_MODE && it.signedIn != "true"
    }

/**
 * FIRST-RUN-KEYS-1b: the optional credentials that are not a harness's primary key (`harness.<id>.<credId>.key`, e.g. opencode's
 * Command Code key): listed under "More providers" on the one first-run screen, never a trigger on their own. ONBOARD-1: a
 * credential that serves a sign-in mode (claude's subscription token for remote runners) is not a provider key; it stays in
 * Configuration > Secrets.
 */
fun moreHarnessKeys(rows: List<SecretRow>): List<SecretRow> =
    rows.filter { it.name.startsWith("harness.") && it.name.endsWith(".key") && !isPrimaryHarnessKey(it.name) && !it.set && it.usesSecret.isBlank() && it.forMode.isBlank() }

/** ONBOARD-1: the harness offers its subscription sign-in from inside the app (the daemon runs the login and relays the code). */
fun offersInAppLogin(row: SecretRow): Boolean = SUBSCRIPTION_MODE in row.authModes && row.canLogin

/** "Claude Code API key" -> "Claude Code": the harness name the sign-in choices are titled with. */
fun harnessDisplayName(row: SecretRow): String = row.label.removeSuffix(" API key").ifBlank { row.label }

/**
 * FIRST-RUN-KEYS-1b (UX-FIRST-RUN-KEYS-1): ONE screen for every provider key, never a chain of modals. Each missing primary key is
 * a row (masked field, what it unlocks, "optional"); the other credentials sit under "More providers". ONBOARD-1: a harness that
 * offers an in-app subscription sign-in (Claude Code) shows exactly two choices instead: "Log in with your subscription" (the
 * daemon runs the real login, the browser opens, a code the page shows is pasted back here) and "Use an API key".
 * Save stores each filled field through the same `setSecret` as Configuration > Secrets and remembers the rest as skipped; Skip
 * all remembers every row as skipped (on this device); both stay reachable later in Configuration > Secrets. A harness with a
 * sign-in probe is probed first, so a machine already signed in is never asked. A row stays on screen once shown, so a login that
 * just finished says so instead of vanishing. Draws nothing while the daemon is not live or nothing is missing.
 */
object ShellHarnessKeys : Block("infantry.shell.harnessKeys", "infantry.shell.harnessKeys") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        if (!s.state.connection.isLive || s.ui.showConfig) return
        // ONBOARD-2: while the guided setup is open, or nothing can answer yet (it opens by itself then), it asks instead.
        if (s.ui.showSetup) return
        val rows = s.state.secrets?.secrets ?: return
        if (com.superiorsoftllc.infantry.ui.model.SetupWizard.needed(s.state.harnessInstall, rows, s.state.providers) == true) return
        var skipped by remember { mutableStateOf(runCatching { s.host.uiPref(HARNESS_KEY_SKIP_PREF).orEmpty().split(',').filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())) }
        val missing = missingHarnessKeys(rows, skipped)
        // Probe each login once per app start before asking (a login made since the daemon's last probe counts): the answer is a
        // secret_state with a newer lastProbe and signedIn, which drops the row when it is signed in.
        val probeSent = remember { mutableStateMapOf<String, Long>() }
        val probing = missing.filter { hasAuthProbe(it) }
        LaunchedEffect(probing.map { it.name }) {
            probing.filter { it.name !in probeSent }.forEach { probeSent[it.name] = it.lastProbe; s.controller.checkHarnessAuth(it.name) }
        }
        val shown = remember { mutableStateListOf<String>() }
        if (probing.none { probeSent[it.name] == null || probeSent[it.name] == it.lastProbe }) missing.forEach { if (it.name !in shown) shown.add(it.name) }
        val asked = rows.filter { it.name in shown && it.name !in skipped }
        if (asked.isEmpty()) return
        val more = moreHarnessKeys(rows).filter { it.name !in skipped && it.name !in shown }
        val values = remember { mutableStateMapOf<String, String>() }
        var showMore by remember { mutableStateOf(false) }
        fun markSkipped(names: List<String>) {
            skipped = skipped + names
            runCatching { s.host.saveUiPref(HARNESS_KEY_SKIP_PREF, skipped.joinToString(",")) }
        }
        fun close() {
            // A login still waiting for the owner stops with the screen (nothing else would ever finish it).
            val waiting = asked.filter { it.loginState == LOGIN_RUNNING }
            markSkipped(asked.map { it.name } + more.map { it.name })
            if (waiting.isNotEmpty()) s.actionScope.launch { waiting.forEach { s.controller.harnessLogin(it.name, "cancel") } }
        }
        val allDone = asked.all { it.loginState == LOGIN_DONE } && values.values.none { it.isNotBlank() }
        AhqAlertDialog(
            onDismissRequest = { close() },
            modifier = Modifier.testTag(HARNESS_KEY_PROMPT_TAG),
            title = { Text("Connect your providers") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connect the providers you use; every one is optional. Keys are stored encrypted by the Infantry AI daemon and kept across updates. You can change them later in Configuration > Secrets.", fontSize = 12.sp, color = Palette.Muted)
                    asked.forEach { row ->
                        if (offersInAppLogin(row)) SignInChoices(row, values)
                        else HarnessKeyRow(row, SUBSCRIPTION_MODE in row.authModes, values) { markSkipped(listOf(row.name)) }
                    }
                    if (more.isNotEmpty()) {
                        TextButton({ showMore = !showMore }, Modifier.testTag(HARNESS_KEY_MORE_TAG)) { Text(if (showMore) "Hide more providers" else "More providers (${more.size})") }
                        if (showMore) more.forEach { row -> HarnessKeyRow(row, false, values) {} }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    val filled = (asked + more).filter { values[it.name].orEmpty().isNotBlank() && it.loginState != LOGIN_DONE }
                    close()
                    s.actionScope.launch {
                        filled.forEach { row ->
                            s.controller.setSecret(row.name, values[row.name].orEmpty().trim())
                            val id = row.name.removePrefix("harness.").removeSuffix(".key")
                            if (isPrimaryHarnessKey(row.name) && API_KEY_MODE in row.authModes) s.controller.setHarnessAuthMode(id, API_KEY_MODE)
                        }
                    }
                }, Modifier.testTag(HARNESS_KEY_SAVE_TAG)) { Text(if (allDone) "Done" else "Save") }
            },
            dismissButton = { if (!allDone) TextButton({ close() }, Modifier.testTag(HARNESS_KEY_SKIP_ALL_TAG)) { Text("Skip all") } },
        )
    }
}

/** A bordered card for one way to connect a provider. */
@Composable
private fun ChoiceCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, Palette.Border, Palette.FieldShape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) Text(subtitle, fontSize = 11.sp, color = Palette.Muted)
        content()
    }
}

/** ONBOARD-1: the two ways a subscription harness connects, as two cards: log in with the subscription, or an API key. */
@Composable
internal fun SignInChoices(row: SecretRow, values: MutableMap<String, String>) {
    val name = harnessDisplayName(row)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("optional", fontSize = 11.sp, color = Palette.Muted)
        }
        Text("Choose one way to connect $name.", fontSize = 11.sp, color = Palette.Muted)
        ChoiceCard("Log in with your Claude subscription", "For a Pro or Max plan. Your browser opens so you can log in to your Claude account.") { SubscriptionLogin(row) }
        ChoiceCard("Use an Anthropic API key", row.help) {
            if (row.loginState == LOGIN_DONE) Text("Not needed: you are logged in with your subscription.", fontSize = 12.sp, color = Palette.Muted)
            else OutlinedTextField(
                value = values[row.name].orEmpty(), onValueChange = { values[row.name] = it }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), shape = Palette.FieldShape,
                placeholder = { Text("Anthropic API key") },
                modifier = Modifier.fillMaxWidth().testTag(HARNESS_KEY_FIELD_TAG),
            )
        }
    }
}

/**
 * ONBOARD-1: the in-app subscription login. Idle: Log in. Running: the browser opened (a link opens the page again) and a field takes
 * the code the page shows when it cannot hand the login back by itself. Done: logged in, and the harness is set to its subscription.
 * Failed: why, and Try again. The state is the daemon's (`loginState` on the row), so every window agrees.
 */
@Composable
private fun SubscriptionLogin(row: SecretRow) {
    val s = LocalShell.current
    val uri = LocalUriHandler.current
    val harnessId = row.name.removePrefix("harness.").removeSuffix(".key")
    var code by remember(row.name) { mutableStateOf("") }
    LaunchedEffect(row.loginState) {
        if (row.loginState == LOGIN_DONE && row.authMode != SUBSCRIPTION_MODE) s.controller.setHarnessAuthMode(harnessId, SUBSCRIPTION_MODE)
    }
    val start: () -> Unit = { code = ""; s.actionScope.launch { s.controller.harnessLogin(row.name, "start") } }
    when (row.loginState) {
        LOGIN_RUNNING -> {
            Text("Finish logging in in your browser. This window updates by itself when you are done.", fontSize = 12.sp, modifier = Modifier.testTag(HARNESS_LOGIN_STATE_TAG))
            if (row.loginUrl.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Browser didn't open?", fontSize = 12.sp, color = Palette.Muted)
                TextButton({ runCatching { uri.openUri(row.loginUrl) } }, Modifier.testTag(HARNESS_LOGIN_OPEN_TAG)) { Text("Open the login page") }
                CopyValueButton(row.loginUrl)
            }
            Text("If the page shows a code, paste it here:", fontSize = 12.sp, color = Palette.Muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = code, onValueChange = { code = it }, singleLine = true, shape = Palette.FieldShape,
                    placeholder = { Text("Code from the login page") },
                    modifier = Modifier.weight(1f).testTag(HARNESS_LOGIN_CODE_TAG),
                )
                Button({ val c = code; s.actionScope.launch { s.controller.harnessLogin(row.name, "input", c) } }, Modifier.testTag(HARNESS_LOGIN_SUBMIT_TAG), enabled = code.isNotBlank()) { Text("Submit") }
            }
            if (row.loginMessage.isNotBlank()) Text(row.loginMessage, fontSize = 12.sp, color = if (row.loginMessage.startsWith("Invalid", true)) Palette.Danger else Palette.Muted)
            TextButton({ s.actionScope.launch { s.controller.harnessLogin(row.name, "cancel") } }, Modifier.testTag(HARNESS_LOGIN_CANCEL_TAG)) { Text("Cancel") }
        }
        LOGIN_DONE -> Text("✓ Logged in with your Claude subscription.", fontSize = 12.sp, color = Palette.Ok, modifier = Modifier.testTag(HARNESS_LOGIN_STATE_TAG))
        LOGIN_FAILED -> {
            Text(row.loginMessage.ifBlank { "The login did not finish." }, fontSize = 12.sp, color = Palette.Danger, modifier = Modifier.testTag(HARNESS_LOGIN_STATE_TAG))
            OutlinedButton(start, Modifier.testTag(HARNESS_LOGIN_START_TAG)) { Text("Try again") }
        }
        else -> Button(start, Modifier.testTag(HARNESS_LOGIN_START_TAG)) { Text("Log in") }
    }
}

/** One provider row of the first-run keys screen: label, "optional", help, optional subscription sign-in, masked key field. */
@Composable
internal fun HarnessKeyRow(row: SecretRow, subscription: Boolean, values: MutableMap<String, String>, onUseSubscription: () -> Unit) {
    val s = LocalShell.current
    val harnessId = row.name.removePrefix("harness.").removeSuffix(".key")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("optional", fontSize = 11.sp, color = Palette.Muted)
        }
        if (row.help.isNotBlank()) Text(row.help, fontSize = 11.sp, color = Palette.Muted)
        if (subscription) {
            if (row.loginCommand.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.loginCommand, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                CopyValueButton(row.loginCommand)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val state = when (row.signedIn) { "true" -> "signed in"; "false" -> "subscription: not signed in yet"; else -> "subscription: sign-in not checked" }
                Text(state, fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.weight(1f).testTag(HARNESS_SUBSCRIPTION_STATE_TAG))
                TextButton({ s.actionScope.launch { s.controller.checkHarnessAuth(row.name) } }, Modifier.testTag(HARNESS_SUBSCRIPTION_CHECK_TAG)) { Text("Check") }
                TextButton({
                    onUseSubscription()
                    s.actionScope.launch { s.controller.setHarnessAuthMode(harnessId, SUBSCRIPTION_MODE) }
                }, Modifier.testTag(HARNESS_SUBSCRIPTION_TAG)) { Text("Use subscription") }
            }
        }
        OutlinedTextField(
            value = values[row.name].orEmpty(), onValueChange = { values[row.name] = it }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            placeholder = { Text(if (subscription) "Or an API key" else "API key") },
            modifier = Modifier.fillMaxWidth().testTag(HARNESS_KEY_FIELD_TAG),
        )
    }
}
