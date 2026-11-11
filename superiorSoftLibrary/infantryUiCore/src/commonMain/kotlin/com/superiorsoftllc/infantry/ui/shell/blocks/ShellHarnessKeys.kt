// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell.blocks

import androidx.compose.foundation.layout.Column
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
 * FIRST-RUN-KEYS-1, CLAUDE-KEY-PROMPT-1: asks once per harness how it signs in when it has no key and no login: a harness that
 * offers a subscription shows both ways (the subscription, with the terminal login command, its state and Check; or an API key).
 * Save stores the key in the keystore (the same `secret_set` as Configuration > Secrets); Use subscription stores the choice
 * (`harness.<id>.authMode`); Skip remembers the answer on this device. A harness with a sign-in probe is probed first, so a
 * machine already signed in is never asked. Draws nothing while the daemon is not live or nothing is missing.
 */
object ShellHarnessKeys : Block("infantry.shell.harnessKeys", "infantry.shell.harnessKeys") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        if (!s.state.connection.isLive || s.ui.showConfig) return
        val rows = s.state.secrets?.secrets ?: return
        var skipped by remember { mutableStateOf(runCatching { s.host.uiPref(HARNESS_KEY_SKIP_PREF).orEmpty().split(',').filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())) }
        val row = missingHarnessKeys(rows, skipped).firstOrNull() ?: return
        // Probe the login before asking, once per app start (a login made since the daemon's last probe counts): the answer is a
        // secret_state with a newer lastProbe and signedIn, which drops the row when it is signed in.
        var probeSentAt by remember(row.name) { mutableStateOf<Long?>(null) }
        if (hasAuthProbe(row) && (probeSentAt == null || row.lastProbe == probeSentAt)) {
            LaunchedEffect(row.name) { probeSentAt = row.lastProbe; s.controller.checkHarnessAuth(row.name) }
            return
        }
        val harnessId = row.name.removePrefix("harness.").removeSuffix(".key")
        val offersSubscription = SUBSCRIPTION_MODE in row.authModes
        val title = if (offersSubscription) "Sign in to ${row.label.removeSuffix(" API key")}" else row.label
        var value by remember(row.name) { mutableStateOf("") }
        fun skip() {
            skipped = skipped + row.name
            runCatching { s.host.saveUiPref(HARNESS_KEY_SKIP_PREF, skipped.joinToString(",")) }
        }
        AhqAlertDialog(
            onDismissRequest = { skip() },
            modifier = Modifier.testTag(HARNESS_KEY_PROMPT_TAG),
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (offersSubscription) {
                        Text("Your Claude subscription", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("No key is stored. Sign in once in a terminal, then press Check:", fontSize = 12.sp)
                        if (row.loginCommand.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(row.loginCommand, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            CopyValueButton(row.loginCommand)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val state = when (row.signedIn) { "true" -> "signed in"; "false" -> "not signed in yet"; else -> "sign-in not checked" }
                            Text(state, fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.weight(1f).testTag(HARNESS_SUBSCRIPTION_STATE_TAG))
                            TextButton({ s.actionScope.launch { s.controller.checkHarnessAuth(row.name) } }, Modifier.testTag(HARNESS_SUBSCRIPTION_CHECK_TAG)) { Text("Check") }
                            TextButton({
                                skip()
                                s.actionScope.launch { s.controller.setHarnessAuthMode(harnessId, SUBSCRIPTION_MODE) }
                            }, Modifier.testTag(HARNESS_SUBSCRIPTION_TAG)) { Text("Use subscription") }
                        }
                        Text("Or an API key", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("This agent CLI needs an API key to run.", fontSize = 12.sp)
                    }
                    Text("A key is stored encrypted by the Infantry AI daemon, kept across updates, and never shown again.", fontSize = 12.sp, color = Palette.Muted)
                    if (row.help.isNotBlank()) Text(row.help, fontSize = 12.sp, color = Palette.Muted)
                    OutlinedTextField(
                        value = value, onValueChange = { value = it }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        placeholder = { Text("API key") },
                        modifier = Modifier.fillMaxWidth().testTag(HARNESS_KEY_FIELD_TAG),
                    )
                    Text("You can skip this and choose later in Configuration > Secrets.", fontSize = 11.sp, color = Palette.Muted)
                }
            },
            confirmButton = {
                TextButton({
                    val v = value.trim()
                    if (v.isNotEmpty()) s.actionScope.launch {
                        s.controller.setSecret(row.name, v)
                        if (API_KEY_MODE in row.authModes) s.controller.setHarnessAuthMode(harnessId, API_KEY_MODE)
                    }
                }) { Text("Save key") }
            },
            dismissButton = { TextButton({ skip() }) { Text("Skip") } },
        )
    }
}
