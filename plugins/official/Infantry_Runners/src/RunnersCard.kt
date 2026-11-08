// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.runners

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.AhqAlertDialog
import com.superiorsoftllc.infantry.ui.CopyValueButton
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.TextButton
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.progress.ProgressBar
import com.superiorsoftllc.infantry.ui.shell.ActionButton
import com.superiorsoftllc.infantry.ui.shell.LinkContext
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull

/** The `runner_admin` frame (the CLI's `infantry runner ...` sends the same one) and the policy action that gates it. */
const val RUNNER_ADMIN_FRAME = "runner_admin"
const val RUNNER_ADMIN_ACTION = "runners.admin"

/** The slot of the Runners card (`infantry.shell.config.runners`) and the card's fill id. */
const val RUNNERS_CARD_NODE = "infantry.shell.config.runners"

/** RUNNER-ADD-UI-1: the command the empty card tells the owner to run on the other machine (the one it copies). */
const val RUNNER_UP_COMMAND = "infantry runner up --name <name>"

/** What the card says when a request never left or its answer could not be read. */
const val RUNNERS_OFFLINE = "not connected to the daemon: nothing was sent"
const val RUNNERS_UNREADABLE = "the daemon's answer could not be read"

/** One row of the card: what `runner_admin` op `list` answers for a runner. */
data class RunnerRow(
    val name: String,
    val health: String,
    val fingerprint: String,
    val workers: Int,
    val cap: Int,
    val paused: Boolean,
) {
    /** Enabled for new starts: a paused runner takes none. */
    val enabled: Boolean get() = !paused
    /** `local` is this machine: it has no pairing, so no detach, rotate or check. */
    val isLocal: Boolean get() = name == "local"
}

/** The `list` answer: whether runners are on, and the rows. Null when the text is not an ok answer. */
data class RunnersList(val enabled: Boolean, val rows: List<RunnerRow>)

/**
 * RUNNER-CHECK-1 / RUNNER-ADD-UI-1: one row of a `check` answer. [id] is what was checked (`link`, `contract`,
 * `harness:<id>`, `grant:project`, `grant:harness`, `git`, `disk`, `git:origin`), [detail] what was found, and [fix] the
 * exact step or command when it failed.
 */
data class RunnerCheckRow(val id: String, val ok: Boolean, val detail: String, val fix: String?) {
    /** The mark the row draws, with the word a screen reader reads in its place. */
    val mark: String get() = if (ok) "\u2713" else "\u2717"
    val markWord: String get() = if (ok) "ok" else "failed"
}

private val lenient = Json { ignoreUnknownKeys = true }

private fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()

/** Whether an answer carries `ok: true`. */
private fun JsonObject.okAnswer(): Boolean = (this["ok"] as? JsonPrimitive)?.contentOrNull == "true"

private fun parseObject(text: String): JsonObject? = runCatching { lenient.parseToJsonElement(text) as? JsonObject }.getOrNull()

fun parseRunnersList(text: String): RunnersList? {
    val obj = parseObject(text) ?: return null
    val rows = obj["runners"] as? JsonArray ?: return null
    if (!obj.okAnswer()) return null
    fun JsonObject.s(k: String) = text(k)
    return RunnersList(
        enabled = (obj["enabled"] as? JsonPrimitive)?.let { runCatching { it.boolean }.getOrNull() } ?: false,
        rows = rows.mapNotNull { it as? JsonObject }.map { r -> RunnerRow(r.s("name"), r.s("health").ifBlank { "down" }, r.s("fingerprint"), r.s("workers").toIntOrNull() ?: 0, r.s("cap").toIntOrNull() ?: 0, r.s("paused") == "true") },
    )
}

/** An op the card sends: `pause` / `resume` / `detach` / `rotate` on a runner. */
fun adminRequest(op: String, name: String): String =
    JsonObject(mapOf("op" to JsonPrimitive(op), "name" to JsonPrimitive(name))).toString()

/**
 * RUNNER-ADD-UI-1 (docs/wiki/RunnerPairing-design.md 4, step 5): the `add` op of the ONE link a runner prints
 * (`infantry runner up --name n`). [name] overrides the name the link carries; null sends none, so the link's name wins.
 * The link is sent verbatim (it is a 10-minute secret and never shown again).
 */
fun addLinkRequest(link: String, name: String? = null): String = JsonObject(
    buildMap {
        put("op", JsonPrimitive("add"))
        put("link", JsonPrimitive(link.trim()))
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { put("name", JsonPrimitive(it)) }
    },
).toString()

/** RUNNER-CHECK-1: the `check` op for [name] over this window's project ([projectId]; null = the daemon checks without it). */
fun checkRequest(name: String, projectId: String? = null): String = JsonObject(
    buildMap {
        put("op", JsonPrimitive("check"))
        put("name", JsonPrimitive(name))
        projectId?.takeIf { it.isNotBlank() }?.let { put("projectId", JsonPrimitive(it)) }
    },
).toString()

/**
 * The daemon's own refusal text of a `runner_admin` answer (`error`, verbatim: it never carries the pairing code), or null
 * when the answer is an ok one. A text that is not even an answer is a failure too, never a silent success.
 */
fun runnerFailure(text: String): String? {
    val obj = parseObject(text) ?: return RUNNERS_UNREADABLE
    if (obj.okAnswer()) return null
    return obj.text("error").ifBlank { "the daemon refused the request" }
}

/** The name of the runner an `add` answer paired, or null when the answer carries none. */
fun addedRunnerName(text: String): String? = parseObject(text)?.takeIf { it.okAnswer() }?.text("name")?.takeIf { it.isNotBlank() }

/** The rows of a `check` answer, or null when the text is not an ok answer (its `error` says why: [runnerFailure]). */
fun parseRunnerCheck(text: String): List<RunnerCheckRow>? {
    val obj = parseObject(text) ?: return null
    if (!obj.okAnswer()) return null
    val rows = obj["rows"] as? JsonArray ?: return emptyList()
    return rows.mapNotNull { it as? JsonObject }.map { r ->
        RunnerCheckRow(
            id = r.text("id"),
            ok = (r["ok"] as? JsonPrimitive)?.let { runCatching { it.boolean }.getOrNull() } ?: false,
            detail = r.text("detail"),
            fix = r.text("fix").takeIf { it.isNotBlank() },
        )
    }
}

/**
 * RUNNER-POOL-11 / RUNNER-ADD-UI-1: the rows of the Runners card, with their actions. Nothing is drawn while runners are
 * off. The buttons call [onOp] (`pause`, `resume`, `detach`, `rotate`) and [onCheck] (a paired runner's health check); each
 * is the `runner_admin` op the CLI uses, gated by [RUNNER_ADMIN_ACTION].
 */
@Composable
fun RunnersRows(
    list: RunnersList?,
    error: String?,
    onOp: (op: String, name: String) -> Unit,
    onCheck: (name: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (list == null || !list.enabled) return
    Column(modifier.fillMaxWidth().testTag("runners-rows"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (r in list.rows) {
            Row(Modifier.fillMaxWidth().testTag("runner-row:${r.name}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("runner-name:${r.name}"))
                    Text(
                        "${r.health} · ${r.workers}/${r.cap} workers · ${if (r.enabled) "enabled" else "paused"}" +
                            if (r.fingerprint.isNotBlank()) " · ${r.fingerprint.take(FINGERPRINT_SHOWN)}" else "",
                        fontSize = 12.sp, modifier = Modifier.testTag("runner-status:${r.name}"),
                    )
                }
                if (!r.isLocal) {
                    ActionButton("runners.admin", "Check", { onCheck(r.name) }, Modifier.testTag("runner-check:${r.name}"))
                }
                ActionButton("runners.admin", if (r.paused) "Resume" else "Pause", { onOp(if (r.paused) "resume" else "pause", r.name) }, Modifier.testTag("runner-pause:${r.name}"))
                if (!r.isLocal) {
                    ActionButton("runners.admin", "Rotate", { onOp("rotate", r.name) }, Modifier.testTag("runner-rotate:${r.name}"))
                    ActionButton("runners.admin", "Detach", { onOp("detach", r.name) }, Modifier.testTag("runner-detach:${r.name}"))
                }
            }
        }
        if (error != null) Text(error, fontSize = 12.sp, color = Palette.Danger, modifier = Modifier.padding(top = 2.dp).testTag("runners-error"))
    }
}

private const val FINGERPRINT_SHOWN = 16

/**
 * RUNNER-ADD-UI-1: the Runners card. Its heading and the "Add runner" button are always drawn, also while runners are off,
 * so pairing never needs a terminal; [RunnersEmptyHint] says what to run on the other machine until one is paired.
 */
@Composable
fun RunnersCard(
    list: RunnersList?,
    error: String?,
    onAdd: () -> Unit,
    onOp: (op: String, name: String) -> Unit,
    onCheck: (name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag("runners-card"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Runners", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            ActionButton(RUNNER_ADMIN_ACTION, "Add runner", onAdd, Modifier.testTag("runners-add"))
        }
        val paired = list?.rows.orEmpty().count { !it.isLocal }
        if (paired == 0 && list?.enabled != true) RunnersEmptyHint()
        // The rows draw their own error while they are on screen; with runners off (or no answer yet) the card says it.
        val rowsShown = list?.enabled == true
        RunnersRows(list, if (rowsShown) error else null, onOp, onCheck)
        if (!rowsShown && error != null) Text(error, fontSize = 12.sp, color = Palette.Danger, modifier = Modifier.testTag("runners-error"))
    }
}

/** RUNNER-ADD-UI-1: how to get a runner to pair with, with the command to copy onto that machine. */
@Composable
fun RunnersEmptyHint(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("runners-hint"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Run agents on another machine. Start a runner there with `$RUNNER_UP_COMMAND` and paste the link it prints here.",
            fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.testTag("runners-hint-text"),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(RUNNER_UP_COMMAND, fontSize = 11.sp, modifier = Modifier.testTag("runners-hint-command"))
            CopyValueButton(RUNNER_UP_COMMAND, Modifier.testTag("runners-hint-copy"))
        }
    }
}

/**
 * RUNNER-ADD-UI-1: the Add runner dialog. One field for the pairing link a runner prints, masked like a password (it
 * carries the one-time code), an optional name override, and Connect, which [RUNNER_ADMIN_ACTION] gates. [sending] shows
 * the wait while the daemon tries every host in the link; a refusal lands in [error] verbatim.
 * [gate] is the link the buttons ask their gate on (null = the window's, which is what the app passes).
 */
@Composable
fun AddRunnerDialog(
    sending: Boolean,
    error: String?,
    onConnect: (link: String, name: String?) -> Unit,
    onDismiss: () -> Unit,
    gate: LinkContext? = null,
) {
    AhqAlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Add a runner") },
        text = { AddRunnerForm(sending, error, onConnect, onDismiss, gate) },
        confirmButton = {},
    )
}

/**
 * The body of [AddRunnerDialog], buttons and all: one composable to render (and to test) without the dialog's own window.
 * The link is never shown again once it is sent; the daemon's refusal is printed as it arrives.
 */
@Composable
fun AddRunnerForm(
    sending: Boolean,
    error: String?,
    onConnect: (link: String, name: String?) -> Unit,
    onDismiss: () -> Unit,
    gate: LinkContext? = null,
    modifier: Modifier = Modifier,
) {
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val canConnect = link.isNotBlank() && !sending
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "On the runner machine, run `$RUNNER_UP_COMMAND` and paste the link it prints. The link is a secret for " +
                "10 minutes and works once.",
            fontSize = 12.sp, color = Palette.Muted,
        )
        OutlinedTextField(
            value = link, onValueChange = { link = it }, singleLine = true,
            label = { Text("Pairing link") },
            placeholder = { Text("infantry://runner?v=1&…") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            shape = Palette.PopupShape,
            modifier = Modifier.fillMaxWidth().testTag("runner-add-link"),
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            label = { Text("Name (optional)") },
            placeholder = { Text("the name the link carries") },
            shape = Palette.PopupShape,
            modifier = Modifier.fillMaxWidth().testTag("runner-add-name"),
        )
        if (sending) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressBar(null, Modifier.width(120.dp))
                Text("Trying every address in the link…", fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.testTag("runner-add-wait"))
            }
        }
        if (error != null) Text(error, color = Palette.Danger, fontSize = 12.sp, modifier = Modifier.testTag("runner-add-error"))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connecting adds the runner and turns runners on.", fontSize = 11.sp, color = Palette.Muted, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (!sending) onDismiss() }, enabled = !sending, modifier = Modifier.testTag("runner-add-cancel")) { Text("Cancel") }
            ActionButton(
                "runners.admin", "Connect", { onConnect(link.trim(), name.trim().ifBlank { null }) },
                Modifier.testTag("runner-add-connect"), link = gate, enabled = canConnect,
            )
        }
    }
}

/**
 * RUNNER-ADD-UI-1 / RUNNER-CHECK-1: the check rows, as the CLI's `infantry runner check` prints them: a tick or a cross,
 * what was checked, what was found, and under a failure its fix with a copy button. [onAgain] runs the check again.
 */
@Composable
fun RunnerCheckRows(
    rows: List<RunnerCheckRow>?,
    error: String?,
    checking: Boolean,
    onAgain: () -> Unit,
    gate: LinkContext? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag("runner-check-rows"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (checking) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressBar(null, Modifier.width(120.dp))
                Text("Checking over the link…", fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.testTag("runner-check-wait"))
            }
        }
        for (r in rows.orEmpty()) {
            Column(Modifier.fillMaxWidth().testTag("runner-check-row:${r.id}"), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        r.mark, fontSize = 12.sp, color = if (r.ok) Palette.Ok else Palette.Danger,
                        modifier = Modifier.semantics { contentDescription = r.markWord },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(r.id, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(r.detail, fontSize = 12.sp, color = if (r.ok) Palette.Muted else Palette.Text, modifier = Modifier.testTag("runner-check-detail:${r.id}"))
                    }
                }
                val fix = r.fix
                if (!r.ok && fix != null) {
                    Row(Modifier.padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("fix: $fix", fontSize = 11.sp, color = Palette.Muted, modifier = Modifier.weight(1f).testTag("runner-check-fix:${r.id}"))
                        CopyValueButton(fix, Modifier.testTag("runner-check-fix-copy:${r.id}"))
                    }
                }
            }
        }
        if (error != null) Text(error, color = Palette.Danger, fontSize = 12.sp, modifier = Modifier.testTag("runner-check-error"))
        ActionButton("runners.admin", "Check again", onAgain, Modifier.testTag("runner-check-again"), link = gate, enabled = !checking)
    }
}

/** One runner's check rows in a dialog (after an add, and from the row's Check action). Close leaves it paired. */
@Composable
fun RunnerCheckDialog(
    runner: String,
    rows: List<RunnerCheckRow>?,
    error: String?,
    checking: Boolean,
    onAgain: () -> Unit,
    onDismiss: () -> Unit,
    gate: LinkContext? = null,
) {
    AhqAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Runner $runner") },
        text = { RunnerCheckRows(rows, error, checking, onAgain, gate) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("runner-check-close")) { Text("Close") }
        },
    )
}

/** The dialog the card has open, or null. One owner: a successful add hands the same dialog over to its check rows. */
private sealed interface CardDialog {
    /** The paste form; [sending] while the daemon tries every host in the link. */
    data class Add(val sending: Boolean, val error: String? = null) : CardDialog
    /** One runner's check rows, [running] while the answer is on its way. */
    data class Check(val runner: String, val rows: List<RunnerCheckRow>? = null, val error: String? = null, val running: Boolean = false) : CardDialog
}

/** The fill of [RUNNERS_CARD_NODE]: lists the runners over the window's link, adds one from its link, and checks it. */
class RunnersCardBlock : Block("runnersCard", RUNNERS_CARD_NODE) {
    @Composable
    override fun Render(scope: BlockScope) {
        val shell = LocalShellContext.current ?: return
        val link = shell.link
        /** The window's project, so a check reports whether the runner may take this project (its grant row). */
        val projectId = shell.session.activeProjectId
        var list by remember { mutableStateOf<RunnersList?>(null) }
        var listError by remember { mutableStateOf<String?>(null) }
        var dialog by remember { mutableStateOf<CardDialog?>(null) }
        val coroutines = rememberCoroutineScope()
        /** Sends one request and returns the daemon's next `runner_admin` answer (subscribed before the send, so it cannot be missed). */
        suspend fun call(request: String): String? = kotlinx.coroutines.coroutineScope {
            val answer = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { link.frames(RUNNER_ADMIN_FRAME).first() }
            if (!link.sendFrame(RUNNER_ADMIN_FRAME, request)) { answer.cancel(); null } else answer.await()
        }
        suspend fun refresh() {
            val text = call(adminRequest("list", "")) ?: return
            parseRunnersList(text)?.let { list = it; listError = null } ?: run { listError = runnerFailure(text) }
        }
        /** RUNNER-CHECK-1: runs the check for [runner] and shows its rows in the open dialog. */
        suspend fun check(runner: String) {
            dialog = CardDialog.Check(runner, running = true)
            val text = call(checkRequest(runner, projectId))
            dialog = when {
                text == null -> CardDialog.Check(runner, error = RUNNERS_OFFLINE)
                else -> runnerFailure(text)?.let { CardDialog.Check(runner, error = it) }
                    ?: CardDialog.Check(runner, rows = parseRunnerCheck(text).orEmpty())
            }
        }
        LaunchedEffect(link) { refresh() }
        RunnersCard(
            list, listError,
            onAdd = { dialog = CardDialog.Add(sending = false) },
            onOp = { op, name ->
                coroutines.launch {
                    val text = call(adminRequest(op, name))
                    if (text == null) listError = RUNNERS_OFFLINE
                    else runnerFailure(text)?.let { listError = it } ?: refresh()
                }
            },
            onCheck = { name -> coroutines.launch { check(name) } },
            modifier = scope.modifier,
        )
        when (val open = dialog) {
            null -> Unit
            is CardDialog.Add -> AddRunnerDialog(
                sending = open.sending, error = open.error,
                onConnect = { pasted, name ->
                    coroutines.launch {
                        dialog = CardDialog.Add(sending = true)
                        val text = call(addLinkRequest(pasted, name))
                        val failure = text?.let { runnerFailure(it) }
                        when {
                            text == null -> dialog = CardDialog.Add(sending = false, error = RUNNERS_OFFLINE)
                            failure != null -> dialog = CardDialog.Add(sending = false, error = failure)
                            else -> {
                                refresh()
                                val runner = addedRunnerName(text)
                                if (runner == null) dialog = null else check(runner)
                            }
                        }
                    }
                },
                onDismiss = { dialog = null },
                gate = link,
            )
            is CardDialog.Check -> RunnerCheckDialog(
                open.runner, open.rows, open.error, open.running,
                onAgain = { coroutines.launch { check(open.runner) } },
                onDismiss = { dialog = null },
                gate = link,
            )
        }
    }
}
