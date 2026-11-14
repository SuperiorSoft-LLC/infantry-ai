// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.AhqAlertDialog
import com.superiorsoftllc.infantry.ui.CopyValueButton
import com.superiorsoftllc.infantry.ui.LocalShell
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
 * Command Code key): listed under "More providers" on the one first-run screen, never a trigger on their own.
 */
fun moreHarnessKeys(rows: List<SecretRow>): List<SecretRow> =
    rows.filter { it.name.startsWith("harness.") && it.name.endsWith(".key") && !isPrimaryHarnessKey(it.name) && !it.set && it.usesSecret.isBlank() }

/**
 * FIRST-RUN-KEYS-1b (UX-FIRST-RUN-KEYS-1): ONE screen for every provider key, never a chain of modals. Each missing primary key is
 * a row (masked field, what it unlocks, "optional"; a harness with a subscription sign-in also shows the login command, its
 * state, Check and Use subscription); the other credentials sit under "More providers". Save stores each filled field through
 * the same `setSecret` as Configuration > Secrets and remembers the rest as skipped; Skip all remembers every row as skipped
 * (on this device); both stay reachable later in Configuration > Secrets. A harness with a sign-in probe is probed first, so a
 * machine already signed in is never asked. Draws nothing while the daemon is not live or nothing is missing.
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
        if (missing.isEmpty() || probing.any { probeSent[it.name] == null || probeSent[it.name] == it.lastProbe }) return
        val more = moreHarnessKeys(rows).filter { it.name !in skipped }
        val values = remember { mutableStateMapOf<String, String>() }
        var showMore by remember { mutableStateOf(false) }
        fun markSkipped(names: List<String>) {
            skipped = skipped + names
            runCatching { s.host.saveUiPref(HARNESS_KEY_SKIP_PREF, skipped.joinToString(",")) }
        }
        fun skipAll() = markSkipped(missing.map { it.name } + more.map { it.name })
        AhqAlertDialog(
            onDismissRequest = { skipAll() },
            modifier = Modifier.testTag(HARNESS_KEY_PROMPT_TAG),
            title = { Text("Connect your providers") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Add the keys you have; every one is optional. Keys are stored encrypted by the Infantry AI daemon and kept across updates. You can add or change them later in Configuration > Secrets.", fontSize = 12.sp, color = Palette.Muted)
                    missing.forEach { row -> HarnessKeyRow(row, SUBSCRIPTION_MODE in row.authModes, values) { markSkipped(listOf(row.name)) } }
                    if (more.isNotEmpty()) {
                        TextButton({ showMore = !showMore }, Modifier.testTag(HARNESS_KEY_MORE_TAG)) { Text(if (showMore) "Hide more providers" else "More providers (${more.size})") }
                        if (showMore) more.forEach { row -> HarnessKeyRow(row, false, values) {} }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    val filled = (missing + more).filter { values[it.name].orEmpty().isNotBlank() }
                    skipAll()
                    s.actionScope.launch {
                        filled.forEach { row ->
                            s.controller.setSecret(row.name, values[row.name].orEmpty().trim())
                            val id = row.name.removePrefix("harness.").removeSuffix(".key")
                            if (isPrimaryHarnessKey(row.name) && API_KEY_MODE in row.authModes) s.controller.setHarnessAuthMode(id, API_KEY_MODE)
                        }
                    }
                }, Modifier.testTag(HARNESS_KEY_SAVE_TAG)) { Text("Save") }
            },
            dismissButton = { TextButton({ skipAll() }, Modifier.testTag(HARNESS_KEY_SKIP_ALL_TAG)) { Text("Skip all") } },
        )
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
