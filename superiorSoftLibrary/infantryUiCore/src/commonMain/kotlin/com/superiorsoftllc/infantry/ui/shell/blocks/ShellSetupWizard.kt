// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.AhqAlertDialog
import com.superiorsoftllc.infantry.ui.LocalShell
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.RadioButton
import com.superiorsoftllc.infantry.ui.Shell
import com.superiorsoftllc.infantry.ui.TextButton
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.clickable
import com.superiorsoftllc.infantry.ui.model.HarnessInstallSetting
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.PickedModel
import com.superiorsoftllc.infantry.ui.model.SetupWizard
import com.superiorsoftllc.infantry.ui.progress.ProgressIndicator
import com.superiorsoftllc.infantry.ui.protocol.HarnessInstallReport
import com.superiorsoftllc.infantry.ui.protocol.SecretRow
import kotlinx.coroutines.launch

const val SETUP_WIZARD_TAG = "setup-wizard"
const val SETUP_STEP_TAG_PREFIX = "setup-step-"
const val SETUP_NODE_RETRY_TAG = "setup-node-retry"
const val SETUP_NODE_RECHECK_TAG = "setup-node-recheck"
const val SETUP_HARNESS_TAG_PREFIX = "setup-harness-"
const val SETUP_INSTALL_TAG = "setup-install"
const val SETUP_NEXT_TAG = "setup-next"
const val SETUP_LATER_TAG = "setup-later"
const val SETUP_REPLY_TAG = "setup-reply"
const val SETUP_ERROR_TAG = "setup-error"

/**
 * ONBOARD-2: the sign-in screen of the guided setup, filled by whoever owns the harness's login (ONBOARD-1 for Claude's
 * subscription login / API-key choice). [content] draws the screen for one harness and calls `onSignedIn` once a login or
 * key works; null = the built-in fallback (the same key / subscription row as the first-run keys screen).
 */
object SetupSignIn {
    var content: (@Composable (harnessId: String, row: SecretRow, onSignedIn: () -> Unit) -> Unit)? = null
}

/**
 * ONBOARD-2: the guided setup. Opens by itself on EVERY app start while nothing can answer ([SetupWizard.needed]), and from
 * Configuration > Agents > Open guided setup at any time. One choice per screen, no docs:
 *  1. Node.js (only while npm is missing): installed automatically into the app's own folder, no admin prompt; where the daemon
 *     cannot (another OS) it says how and re-checks without a restart.
 *  2. Pick a harness the daemon's installer offers and install it, with the daemon's progress task and a plain failure + Retry.
 *  3. Sign in ([SetupSignIn]); skipped when the harness already can answer (a key, a login, or free models).
 *  4. Start a first agent and show its reply.
 * "Not now" closes it for this run; it comes back next start until something can answer.
 */
object ShellSetupWizard : Block("infantry.shell.setupWizard", "infantry.shell.setupWizard") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val state = s.state
        if (!state.connection.isLive) return
        // The daemon's install report is what every decision reads; ask for it once per connection.
        var asked by remember(state.connection.contract) { mutableStateOf(false) }
        LaunchedEffect(asked) { if (!asked) { asked = true; s.controller.checkHarnesses() } }
        val report = state.harnessInstall
        val secrets = state.secrets?.secrets
        val needed = SetupWizard.needed(report, secrets, state.providers, state.modelCatalog)
        // Once per app start: opened by itself while nothing can answer.
        var decided by remember { mutableStateOf(false) }
        LaunchedEffect(needed) {
            if (!decided && needed != null) { decided = true; if (needed) s.ui.showSetup = true }
        }
        if (!s.ui.showSetup || report == null || s.ui.showConfig) return
        Wizard(s, report, secrets.orEmpty())
    }

    @Composable
    private fun Wizard(s: Shell, report: HarnessInstallReport, secrets: List<SecretRow>) {
        val state = s.state
        var step by remember { mutableStateOf(SetupWizard.firstStep(report)) }
        var pick by remember { mutableStateOf(SetupWizard.defaultPick(report)) }
        val busy = state.harnessInstallBusy
        fun close() { s.ui.showSetup = false }
        // Step changes that follow the daemon's answers, never a guess: Node found -> the pick; installed -> sign-in.
        LaunchedEffect(step, report.npmPath) { if (step == SetupWizard.Step.NODE && report.npmPath != null && !busy) step = SetupWizard.Step.HARNESS }
        // An install finished anywhere (this window, another one, the CLI): re-read the daemon's report.
        val tasks by s.controller.progressTasks.collectAsState()
        val finishedInstalls = tasks.filter { it.id.startsWith(HarnessInstallSetting.TASK_PREFIX) && it.finished }.map { it.id to it.updatedAt }.toSet()
        LaunchedEffect(finishedInstalls) { if (finishedInstalls.isNotEmpty() && !busy) s.controller.checkHarnesses() }
        // The pick follows what is really installed when the picked one is not (e.g. installed from elsewhere).
        val installedIds = report.harnesses.filter(SetupWizard::installed).map { it.id }
        LaunchedEffect(installedIds) { if (pick !in installedIds && installedIds.isNotEmpty()) pick = installedIds.first() }
        val picked = report.harnesses.firstOrNull { it.id == pick }
        val pickedInstalled = picked != null && SetupWizard.installed(picked)
        // Installed: re-read the model list (free models count as "can answer"), and move on right after a successful install.
        var sawMissing by remember { mutableStateOf(false) }
        LaunchedEffect(pickedInstalled) {
            if (!pickedInstalled) { sawMissing = true; return@LaunchedEffect }
            s.controller.refreshModelCatalog()
            if (step == SetupWizard.Step.HARNESS && sawMissing) step = SetupWizard.Step.SIGN_IN
        }
        LaunchedEffect(step, pick, pickedInstalled, secrets, state.providers, state.modelCatalog) {
            if (step == SetupWizard.Step.SIGN_IN && pick != null && SetupWizard.credentialOk(pick!!, secrets, state.providers, state.modelCatalog)) step = SetupWizard.Step.FIRST_AGENT
        }
        AhqAlertDialog(
            onDismissRequest = {},
            modifier = Modifier.widthIn(max = 560.dp).testTag(SETUP_WIZARD_TAG),
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Step ${step.ordinal + 1} of ${SetupWizard.Step.entries.size}", fontSize = 11.sp, color = Palette.Muted)
                    Text(step.title, modifier = Modifier.testTag(SETUP_STEP_TAG_PREFIX + step.name.lowercase()))
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    when (step) {
                        SetupWizard.Step.NODE -> NodeStep(s, report, busy)
                        SetupWizard.Step.HARNESS -> HarnessStep(s, report, pick, busy) { pick = it }
                        SetupWizard.Step.SIGN_IN -> SignInStep(pick, secrets) { step = SetupWizard.Step.FIRST_AGENT }
                        SetupWizard.Step.FIRST_AGENT -> FirstAgentStep(s, pick, secrets)
                    }
                }
            },
            confirmButton = {
                when (step) {
                    SetupWizard.Step.HARNESS -> if (pickedInstalled) {
                        TextButton({ step = SetupWizard.Step.SIGN_IN }, Modifier.testTag(SETUP_NEXT_TAG)) { Text("Next") }
                    } else {
                        TextButton({ pick?.let { id -> s.actionScope.launch { s.controller.installHarnesses(listOf(id), false) } } }, Modifier.testTag(SETUP_INSTALL_TAG), enabled = !busy && pick != null) {
                            Text(if (busy) "Installing..." else "Install ${picked?.label ?: ""}".trim())
                        }
                    }
                    // An optional key never blocks: Continue tries the first agent, which says plainly if it cannot answer.
                    SetupWizard.Step.SIGN_IN -> TextButton({ step = SetupWizard.Step.FIRST_AGENT }, Modifier.testTag(SETUP_NEXT_TAG)) { Text("Continue") }
                    SetupWizard.Step.FIRST_AGENT -> TextButton({ close() }, Modifier.testTag(SETUP_NEXT_TAG)) { Text("Done") }
                    else -> {}
                }
            },
            dismissButton = { TextButton({ close() }, Modifier.testTag(SETUP_LATER_TAG)) { Text(if (step == SetupWizard.Step.FIRST_AGENT) "Close" else "Not now") } },
        )
    }

    @Composable
    private fun NodeStep(s: Shell, report: HarnessInstallReport, busy: Boolean) {
        Text("The agent tools run on Node.js, and this computer does not have it yet.", fontSize = 13.sp)
        if (report.nodeInstallable) {
            // The owner's call: install it automatically when it is missing. Started once on this screen; Retry after a failure.
            var started by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { if (!started && !busy) { started = true; s.controller.installNodeRuntime() } }
            Text("Installing it for you now: about 40 MB, into Infantry AI's own folder. No admin prompt, nothing else on your PC changes.", fontSize = 12.sp, color = Palette.Muted)
            ProgressIndicator(HarnessInstallSetting.taskId(HarnessInstallSetting.NODE_ID), Modifier.fillMaxWidth())
            val outcome = HarnessInstallSetting.outcomeFor(HarnessInstallSetting.NODE_ID, s.state.harnessInstallLog)
            if (!busy && outcome != null && !outcome.ok) {
                Text(outcome.text, fontSize = 12.sp, color = Palette.Danger, modifier = Modifier.testTag(SETUP_ERROR_TAG))
                TextButton({ s.actionScope.launch { s.controller.installNodeRuntime() } }, Modifier.testTag(SETUP_NODE_RETRY_TAG)) { Text("Try again") }
            }
        } else {
            Text(report.guidance ?: "Install Node.js 20 or newer from nodejs.org, then press Check again.", fontSize = 12.sp, color = Palette.Muted)
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ runCatching { uri.openUri("https://nodejs.org/en/download") } }) { Text("Open nodejs.org") }
                TextButton({ s.actionScope.launch { s.controller.checkHarnesses() } }, Modifier.testTag(SETUP_NODE_RECHECK_TAG)) { Text("Check again") }
            }
        }
    }

    @Composable
    private fun HarnessStep(s: Shell, report: HarnessInstallReport, pick: String?, busy: Boolean, onPick: (String) -> Unit) {
        Text("Pick the agent tool you want to use. You can add the others later.", fontSize = 13.sp)
        report.harnesses.forEach { h ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { onPick(h.id) }.testTag(SETUP_HARNESS_TAG_PREFIX + h.id),
            ) {
                RadioButton(selected = h.id == pick, onClick = { onPick(h.id) }, enabled = !busy)
                Column(Modifier.weight(1f)) {
                    Text(h.label.ifBlank { h.id }, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(HarnessInstallSetting.statusText(h), fontSize = 11.sp, color = Palette.Muted)
                }
            }
        }
        val id = pick ?: return
        // The daemon's tasks draw whoever started the install; nothing shows while none runs.
        ProgressIndicator(HarnessInstallSetting.taskId(HarnessInstallSetting.NODE_ID), Modifier.fillMaxWidth(), hideWhenDone = true)
        ProgressIndicator(HarnessInstallSetting.taskId(id), Modifier.fillMaxWidth(), hideWhenDone = true)
        if (busy) {
            Text("Installing. This can take a few minutes.", fontSize = 11.sp, color = Palette.Muted)
        } else {
            HarnessInstallSetting.outcomeFor(id, s.state.harnessInstallLog)?.takeIf { !it.ok }?.let { o ->
                Text(o.text, fontSize = 12.sp, color = Palette.Danger, modifier = Modifier.testTag(SETUP_ERROR_TAG))
                Text("Press Install to try again.", fontSize = 11.sp, color = Palette.Muted)
            }
        }
    }

    @Composable
    private fun SignInStep(pick: String?, secrets: List<SecretRow>, onSignedIn: () -> Unit) {
        val id = pick ?: return
        val row = SetupWizard.keyRow(id, secrets)
        if (row == null) { LaunchedEffect(id) { onSignedIn() }; return }
        val owner = SetupSignIn.content
        if (owner != null) { owner(id, row, onSignedIn); return }
        // Default: the first-run keys screen's own pieces. A harness with an in-app subscription login (ONBOARD-1) gets its two
        // choices (log in with the subscription, pasting the code back if the page shows one; or an API key); others the key row.
        Text("Connect ${harnessDisplayName(row)} so your agent can answer.", fontSize = 13.sp)
        val values = remember { mutableStateMapOf<String, String>() }
        if (offersInAppLogin(row)) SignInChoices(row, values) else HarnessKeyRow(row, SUBSCRIPTION_MODE in row.authModes, values) {}
        val s = LocalShell.current
        TextButton({
            val v = values[row.name].orEmpty().trim()
            if (v.isNotEmpty()) s.actionScope.launch {
                s.controller.setSecret(row.name, v)
                if (API_KEY_MODE in row.authModes) s.controller.setHarnessAuthMode(id, API_KEY_MODE)
            }
        }, Modifier.testTag(HARNESS_KEY_SAVE_TAG)) { Text("Save key") }
    }

    @Composable
    private fun FirstAgentStep(s: Shell, pick: String?, secrets: List<SecretRow>) {
        val id = pick ?: return
        val state = s.state
        // The session this screen started: the first one that was not there before the spawn.
        val before = remember { state.agents.keys.toSet() }
        // A refusal comes back as a daemon notice after the send (e.g. "new refused: ..."): any error notice since this screen opened.
        val since = remember { kotlin.time.Clock.System.now().toEpochMilliseconds() }
        var sent by remember { mutableStateOf(false) }
        var refused by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            if (sent) return@LaunchedEffect
            sent = true
            s.controller.refreshModelCatalog()
            s.controller.pickModel(PickedModel(id, SetupWizard.firstModel(id, secrets, s.state.providers, s.state.modelCatalog)))
            val r = s.controller.spawn(SetupWizard.HELLO_PROMPT)
            if (r is com.superiorsoftllc.infantry.ui.net.CommandResult.Refused) refused = r.reason
        }
        val agent = state.agents.values.firstOrNull { it.id !in before && it.session.provider == id }
        val reply = agent?.entries?.filter { it.kind == LineKind.TEXT }?.joinToString("") { it.text }?.trim().orEmpty()
        val notice = state.notices.lastOrNull { it.at >= since && it.kind == com.superiorsoftllc.infantry.ui.model.NoticeKind.ERROR }?.text
        val error = refused ?: agent?.lastError ?: agent?.entries?.lastOrNull { it.kind == LineKind.ERROR }?.text ?: notice.takeIf { agent == null }
        Text("Your first agent is starting. It was asked: \"${SetupWizard.HELLO_PROMPT}\"", fontSize = 12.sp, color = Palette.Muted)
        when {
            reply.isNotEmpty() -> {
                Text(reply, fontSize = 14.sp, modifier = Modifier.testTag(SETUP_REPLY_TAG))
                Text("It works. Press Done and start working; the guided setup stays in Configuration > Agents.", fontSize = 12.sp, color = Palette.Muted)
            }
            error != null -> Text("The agent could not answer: $error", fontSize = 12.sp, color = Palette.Danger, modifier = Modifier.testTag(SETUP_ERROR_TAG))
            else -> Text("Waiting for the reply...", fontSize = 12.sp, color = Palette.Muted)
        }
    }
}
