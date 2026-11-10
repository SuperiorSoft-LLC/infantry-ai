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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.AhqAlertDialog
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
const val HARNESS_SUBSCRIPTION_TAG = "harness-subscription-choice"
const val HARNESS_KEY_SKIP_PREF = "harness.keys.skipped"

/** The harness API keys the daemon says are missing (`harness.<id>.key` rows not set) and the owner has not skipped. */
fun missingHarnessKeys(rows: List<SecretRow>, skipped: Set<String>): List<SecretRow> =
    rows.filter { it.name.startsWith("harness.") && !it.set && it.name !in skipped && it.authMode != SUBSCRIPTION_MODE }

/**
 * FIRST-RUN-KEYS-1: asks for the API key of a harness that declares one and has none, once per harness: Save stores it in the
 * keystore (the same `secret_set` as Configuration > Secrets), Skip remembers the answer on this device. Skipped keys stay
 * settable in Configuration > Secrets. Draws nothing while the daemon is not live or nothing is missing.
 */
object ShellHarnessKeys : Block("infantry.shell.harnessKeys", "infantry.shell.harnessKeys") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        if (!s.state.connection.isLive || s.ui.showConfig) return
        val rows = s.state.secrets?.secrets ?: return
        var skipped by remember { mutableStateOf(runCatching { s.host.uiPref(HARNESS_KEY_SKIP_PREF).orEmpty().split(',').filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())) }
        val row = missingHarnessKeys(rows, skipped).firstOrNull() ?: return
        var value by remember(row.name) { mutableStateOf("") }
        fun skip() {
            skipped = skipped + row.name
            runCatching { s.host.saveUiPref(HARNESS_KEY_SKIP_PREF, skipped.joinToString(",")) }
        }
        AhqAlertDialog(
            onDismissRequest = { skip() },
            modifier = Modifier.testTag(HARNESS_KEY_PROMPT_TAG),
            title = { Text(row.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This agent CLI needs an API key to run. It is stored encrypted by the Infantry AI daemon and is never shown again.", fontSize = 12.sp)
                    if (SUBSCRIPTION_MODE in row.authModes) Text("Or use your Claude subscription instead: no key is stored. Pick it, then sign in with /login in a Claude chat session (the login URL shows in the chat; paste the code if asked).", fontSize = 12.sp, color = Palette.Muted)
                    if (row.help.isNotBlank()) Text(row.help, fontSize = 12.sp, color = Palette.Muted)
                    Text("You can skip this and set it later in Configuration > Secrets.", fontSize = 11.sp, color = Palette.Muted)
                    OutlinedTextField(
                        value = value, onValueChange = { value = it }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        placeholder = { Text("API key") },
                        modifier = Modifier.fillMaxWidth().testTag(HARNESS_KEY_FIELD_TAG),
                    )
                }
            },
            confirmButton = {
                TextButton({
                    val v = value.trim()
                    if (v.isNotEmpty()) s.actionScope.launch { s.controller.setSecret(row.name, v) }
                }) { Text("Save") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (SUBSCRIPTION_MODE in row.authModes) TextButton({
                        val id = row.name.removePrefix("harness.").removeSuffix(".key")
                        skip()
                        s.actionScope.launch { s.controller.setHarnessAuthMode(id, SUBSCRIPTION_MODE); s.controller.startHarnessLogin(id) }
                    }, Modifier.testTag(HARNESS_SUBSCRIPTION_TAG)) { Text("Use subscription") }
                    TextButton({ skip() }) { Text("Skip") }
                }
            },
        )
    }
}
