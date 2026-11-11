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
import com.superiorsoftllc.infantry.ui.Checkbox
import com.superiorsoftllc.infantry.ui.CopyValueButton
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.TextButton
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.progress.ProgressBar
import com.superiorsoftllc.infantry.ui.shell.ActionButton
import com.superiorsoftllc.infantry.ui.shell.LinkContext
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
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

/**
 * RUNNER-CARD-STALE-1: the daemon's push frame (data `{}`) when the runner list may read differently: a runner went up, degraded or
 * down on a heartbeat, or one was added, detached, paused or resumed in any window. The card re-lists on it.
 */
const val RUNNERS_CHANGED_FRAME = "runners_changed"

/** The slot of the Runners card (`infantry.shell.config.runners`) and the card's fill id. */
const val RUNNERS_CARD_NODE = "infantry.shell.config.runners"

/** RUNNER-ADD-UI-1: the command the empty card tells the owner to run on the other machine (the one it copies). */
const val RUNNER_UP_COMMAND = "infantry runner up --name <name>"

/**
 * RUNNER-PAIR-1 (step 1 of the owner's 2 steps): the one `docker run` that starts a runner on another machine. Its log prints the
 * endpoint and the bearer to paste into Add runner; nothing else is configured.
 */
const val RUNNER_DOCKER_COMMAND = "docker run -d --name infantry-runner -e INFANTRY_MODE=runner -e INFANTRY_RUNNER_HOST=<this server's address> " +
    "-p 8790:8790 -v infantry-runner:/data ghcr.io/superiorsoft-llc/infantry-ai:latest"

/** RUNNER-PAIR-1: where the runner prints them. */
const val RUNNER_LOGS_COMMAND = "docker logs infantry-runner"

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

/**
 * RUNNER-LINK-HOST-1: the Name field's live check (the daemon's rule: 1-32 of a-z, 0-9 and '-', starting with a letter or digit);
 * null when blank (the link's own name is used) or valid. The text names an example so the owner can fix it as they type.
 */
fun runnerNameError(name: String): String? {
    val n = name.trim()
    if (n.isEmpty() || Regex("^[a-z0-9][a-z0-9-]{0,31}$").matches(n)) return null
    return "Use 1-32 of a-z, 0-9 and '-' (lower case, no spaces), e.g. docker-runner"
}

/** An op the card sends: `pause` / `resume` / `detach` / `rotate` on a runner. */
fun adminRequest(op: String, name: String): String =
    JsonObject(mapOf("op" to JsonPrimitive(op), "name" to JsonPrimitive(name))).toString()

/**
 * RUNNER-ADD-LINK-1 (docs/wiki/RunnerPairing-design.md 4, step 5): the `add` op of the ONE link a runner prints
 * (`infantry runner up --name n`). [name] overrides the name the link carries; null sends none, so the link's name wins.
 * The link is sent verbatim (it is a 10-minute secret and never shown again).
 *
 * RUNNER-GRANT-AT-PAIR-1: [grant] asks the runner to grant [projectId] and its installed harnesses in this same pairing, and is sent
 * only when the owner ticked the confirm AND the link's code carries the authority ([linkCarriesGrant]); the runner adds the ids to
 * its own `runner.projects` / `runner.harnesses` once and never again from a link frame.
 */
fun addLinkRequest(link: String, name: String? = null, grant: Boolean = false, projectId: String? = null): String = JsonObject(
    buildMap {
        put("op", JsonPrimitive("add"))
        put("link", JsonPrimitive(link.trim()))
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { put("name", JsonPrimitive(it)) }
        if (grant) {
            put("grant", JsonPrimitive("true"))
            projectId?.takeIf { it.isNotBlank() }?.let { put("projectId", JsonPrimitive(it)) }
        }
    },
).toString()

/**
 * RUNNER-PAIR-1 (step 2 of the owner's 2 steps): the `add` op with the [endpoint] and [bearer] the runner printed. The bearer
 * carries the runner's name and certificate pin, so nothing else is sent.
 */
fun addBearerRequest(endpoint: String, bearer: String): String = JsonObject(
    mapOf("op" to JsonPrimitive("add"), "endpoint" to JsonPrimitive(endpoint.trim()), "bearer" to JsonPrimitive(bearer.trim())),
).toString()

/** RUNNER-PAIR-1: whether the Endpoint field holds a whole pairing link (the older one-paste form): then no bearer is needed. */
fun isPairingLink(text: String): Boolean = text.trim().startsWith("infantry://runner?", ignoreCase = true)

/** RUNNER-PAIR-1: the Bearer field's live check: blank (not typed yet), or the shape the runner prints (`irb1.<name>.<pin>.<secret>`). */
fun runnerBearerError(bearer: String): String? {
    val b = bearer.trim()
    if (b.isEmpty() || (b.startsWith("irb1.") && b.split('.').size == 4)) return null
    return "Paste the whole 'bearer:' line the runner printed (it starts with irb1.)"
}

/**
 * RUNNER-PAIR-1: the `connect` op: the daemon runs `infantry runner pair` on the runner machine through [via] (the owner's own
 * command that runs a command there), redeems the link it prints and never shows it. [host] / [port] are the address this machine
 * reaches the runner on, when the runner cannot know it (a published container port); blank = the runner's own addresses.
 */
fun connectRequest(name: String, via: String, host: String? = null, port: String? = null): String = JsonObject(
    buildMap {
        put("op", JsonPrimitive("connect"))
        put("name", JsonPrimitive(name.trim()))
        put("via", JsonPrimitive(via.trim()))
        host?.trim()?.takeIf { it.isNotEmpty() }?.let { put("host", JsonPrimitive(it)) }
        port?.trim()?.takeIf { it.isNotEmpty() }?.let { put("port", JsonPrimitive(it)) }
    },
).toString()

/** RUNNER-PAIR-1: the Port field's live check: blank, or a port number. */
fun runnerPortError(port: String): String? {
    val p = port.trim()
    if (p.isEmpty() || p.toIntOrNull()?.let { it in 1..65535 } == true) return null
    return "A port number from 1 to 65535, e.g. the one the runner's link port is published on"
}

/**
 * RUNNER-GRANT-AT-PAIR-1: whether a pasted pairing link says its code may grant (`--grant-on-pair` on the runner). The card only
 * looks for the flag; the daemon parses the link and the runner decides.
 */
fun linkCarriesGrant(pasted: String): Boolean = pasted.trim()
    .substringAfter('?', "")
    .substringBefore('#')
    .split('&')
    .any { it.equals("grant=1", ignoreCase = true) }

/** RUNNER-GRANT-AT-PAIR-1: what the grant tick says: one project, once, chosen here. */
fun grantLabel(projectName: String?, projectId: String?): String = when {
    projectId.isNullOrBlank() -> "Grant this runner the project this window is on (no project is selected here)"
    projectName.isNullOrBlank() -> "Grant this runner project $projectId, once, at pairing"
    else -> "Grant this runner project $projectName ($projectId), once, at pairing"
}

/** RUNNER-CHECK-1: the `check` op for [name] over this window's project ([projectId]; null = the daemon checks without it). */
fun checkRequest(name: String, projectId: String? = null): String = JsonObject(
    buildMap {
        put("op", JsonPrimitive("check"))
        put("name", JsonPrimitive(name))
        projectId?.takeIf { it.isNotBlank() }?.let { put("projectId", JsonPrimitive(it)) }
    },
).toString()

/**
 * RUNNER-TEST-1: the `test` op for [name]: one throwaway turn on that runner, answered with the reply it gave. [harness] and [model]
 * choose what runs it (null = the daemon's pick: `echo` when the runner has it, else the first harness signed in there).
 */
fun testRequest(name: String, harness: String? = null, model: String? = null): String = JsonObject(
    buildMap {
        put("op", JsonPrimitive("test"))
        put("name", JsonPrimitive(name))
        harness?.trim()?.takeIf { it.isNotEmpty() }?.let { put("harness", JsonPrimitive(it)) }
        model?.trim()?.takeIf { it.isNotEmpty() }?.let { put("model", JsonPrimitive(it)) }
    },
).toString()

/**
 * RUNNER-TEST-1: what a `test` answered: the [reply] and [seconds] the runner's [harness] (and [model]) took to give it, or the [error]
 * and the [lastLines] of the transcript when no answer came back. A test that answered [ok] printed no error, and the reverse.
 */
data class RunnerTestResult(
    val harness: String,
    val model: String,
    val seconds: Double,
    val reply: String?,
    val error: String?,
    val lastLines: List<String>,
    /** The turn's exact millis (the daemon's `ms`): a fast echo answer reads as `412 ms`, not as `1 s`. 0 = not sent. */
    val millis: Long = 0,
) {
    val ok: Boolean get() = reply != null

    /** `echo 412 ms` / `claude 3.2 s`: the harness and how long it took, as the rows print them. */
    val took: String
        get() {
            val what = when {
                millis in 1..999 -> "$millis ms"
                millis >= 1_000 -> "${((millis + 50) / 100) / 10.0} s"
                else -> "$seconds s"
            }
            return (listOf(harness, model).filter { it.isNotBlank() } + what).joinToString(" ")
        }
}

/**
 * The daemon's own refusal text of a `runner_admin` answer (`error`, verbatim: it never carries the pairing code), or null
 * when the answer is an ok one. A text that is not even an answer is a failure too, never a silent success.
 */
fun runnerFailure(text: String): String? {
    val obj = parseObject(text) ?: return RUNNERS_UNREADABLE
    if (obj.okAnswer()) return null
    return obj.text("error").ifBlank { "the daemon refused the request" }
}

/** RUNNER-UI-ADD-1: the daemon's `error` refusal of a request ([text] says why) as a failed answer, so [runnerFailure] shows it. */
fun refusalAnswer(text: String): String =
    JsonObject(mapOf("ok" to JsonPrimitive("false"), "error" to JsonPrimitive(text.ifBlank { "the daemon refused the request" }))).toString()

/** The name of the runner an `add` answer paired, or null when the answer carries none. */
fun addedRunnerName(text: String): String? = parseObject(text)?.takeIf { it.okAnswer() }?.text("name")?.takeIf { it.isNotBlank() }

/**
 * RUNNER-REPAIR-1: what to tell the owner when the add moved the runner from another app (a bearer takeover), or null.
 * The runner's old link stops working, so the owner must know the other app lost it.
 */
fun addedTakeoverNotice(text: String): String? = parseObject(text)?.takeIf { it.okAnswer() }?.text("replaced")?.takeIf { it.isNotBlank() }
    ?.let { "Moved here: this runner was paired with $it. That app's link to it no longer works." }

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
 * RUNNER-TEST-1: the answer of a `test` op (or null when the text is not an answer at all).
 *
 * The daemon answers `{ok: true, harness, model, seconds, reply}` for a turn that answered, and `{ok: false, error, lastLines}` for one
 * that did not (the runner's refusal, or no answer within `limits.runners.testTurnSec`). Both shapes are read here, so the dialog shows
 * whichever came back without guessing.
 */
fun parseRunnerTest(text: String): RunnerTestResult? {
    val obj = parseObject(text) ?: return null
    val reply = obj.text("reply").takeIf { it.isNotBlank() }
    val error = obj.text("error").takeIf { it.isNotBlank() }
    if (reply == null && error == null) return null
    return RunnerTestResult(
        harness = obj.text("harness"),
        model = obj.text("model"),
        seconds = obj.text("seconds").toDoubleOrNull() ?: 0.0,
        reply = reply,
        error = error,
        lastLines = (obj["lastLines"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        millis = obj.text("ms").toLongOrNull() ?: 0,
    )
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
    /** RUNNER-PAIR-1: opens the one-step Connect a runner dialog. */
    onConnect: () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().testTag("runners-card"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Runners", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            ActionButton(RUNNER_ADMIN_ACTION, "Connect a runner", onConnect, Modifier.testTag("runners-connect"))
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
            "Run agents on another machine: start a runner there with this one command, then Add runner and paste the endpoint " +
                "and bearer its log (`$RUNNER_LOGS_COMMAND`) prints.",
            fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.testTag("runners-hint-text"),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(RUNNER_DOCKER_COMMAND, fontSize = 11.sp, modifier = Modifier.weight(1f, fill = false).testTag("runners-hint-command"))
            CopyValueButton(RUNNER_DOCKER_COMMAND, Modifier.testTag("runners-hint-copy"))
        }
    }
}

/**
 * RUNNER-ADD-UI-1: the Add runner dialog. One field for the pairing link a runner prints, masked like a password (it
 * carries the one-time code), an optional name override, and Connect, which [RUNNER_ADMIN_ACTION] gates. [sending] shows
 * the wait while the daemon tries every host in the link; a refusal lands in [error] verbatim.
 * [gate] is the link the buttons ask their gate on (null = the window's, which is what the app passes).
 * RUNNER-GRANT-AT-PAIR-1: [grantProjectId] / [grantProjectName] are the project this window is on, for the one-time grant tick.
 */
@Composable
fun AddRunnerDialog(
    sending: Boolean,
    error: String?,
    onConnect: (endpoint: String, bearer: String?, grant: Boolean) -> Unit,
    onDismiss: () -> Unit,
    gate: LinkContext? = null,
    grantProjectId: String? = null,
    grantProjectName: String? = null,
) {
    AhqAlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Add a runner") },
        text = { AddRunnerForm(sending, error, onConnect, onDismiss, gate, grantProjectId = grantProjectId, grantProjectName = grantProjectName) },
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
    /** RUNNER-PAIR-1: (endpoint, bearer, grant); bearer is null when the Endpoint field holds a whole pairing link. */
    onConnect: (endpoint: String, bearer: String?, grant: Boolean) -> Unit,
    onDismiss: () -> Unit,
    gate: LinkContext? = null,
    modifier: Modifier = Modifier,
    grantProjectId: String? = null,
    grantProjectName: String? = null,
) {
    var endpoint by remember { mutableStateOf("") }
    var bearer by remember { mutableStateOf("") }
    // RUNNER-PAIR-1: the older one-paste link still works in the Endpoint field; then the bearer is not needed.
    val linkMode = isPairingLink(endpoint)
    // RUNNER-GRANT-AT-PAIR-1: the tick exists only for a link whose code may grant, and starts ticked (the owner already chose
    // --grant-on-pair on the runner: one confirm here, not two).
    val canGrant = linkMode && linkCarriesGrant(endpoint)
    var grant by remember(endpoint) { mutableStateOf(canGrant) }
    val grantable = canGrant && !grantProjectId.isNullOrBlank()
    val bearerError = if (linkMode) null else runnerBearerError(bearer)
    val canConnect = endpoint.isNotBlank() && !sending && (linkMode || (bearer.isNotBlank() && bearerError == null))
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Paste the endpoint and the bearer the runner printed when it started (`$RUNNER_LOGS_COMMAND` on that machine shows them).",
            fontSize = 12.sp, color = Palette.Muted,
        )
        OutlinedTextField(
            value = endpoint, onValueChange = { endpoint = it }, singleLine = true,
            label = { Text("Endpoint") },
            placeholder = { Text("wss://203.0.113.5:8790") },
            // A pasted pairing link carries a one-time code: masked like the bearer.
            visualTransformation = if (linkMode) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            shape = Palette.PopupShape,
            modifier = Modifier.fillMaxWidth().testTag("runner-add-endpoint"),
        )
        if (!linkMode) {
            OutlinedTextField(
                value = bearer, onValueChange = { bearer = it }, singleLine = true,
                label = { Text("Bearer") },
                placeholder = { Text("irb1.…") },
                isError = bearerError != null,
                supportingText = bearerError?.let { e -> { Text(e, color = Palette.Danger, modifier = Modifier.testTag("runner-add-bearer-error")) } },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                shape = Palette.PopupShape,
                modifier = Modifier.fillMaxWidth().testTag("runner-add-bearer"),
            )
        }
        if (sending) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressBar(null, Modifier.width(120.dp))
                Text(if (linkMode) "Trying every address in the link…" else "Connecting to the runner…", fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.testTag("runner-add-wait"))
            }
        }
        if (canGrant) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Checkbox(grantable && grant, onCheckedChange = { if (grantable) grant = it }, enabled = grantable, modifier = Modifier.testTag("runner-add-grant"))
                Text(grantLabel(grantProjectName, grantProjectId), fontSize = 12.sp, modifier = Modifier.weight(1f).testTag("runner-add-grant-label"))
            }
            Text(
                "The runner adds it to its own runner.projects and runner.harnesses once, at this pairing; nothing else changes its grants.",
                fontSize = 11.sp, color = Palette.Muted,
            )
        }
        if (error != null) Text(error, color = Palette.Danger, fontSize = 12.sp, modifier = Modifier.testTag("runner-add-error"))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connecting adds the runner and turns runners on.", fontSize = 11.sp, color = Palette.Muted, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (!sending) onDismiss() }, enabled = !sending, modifier = Modifier.testTag("runner-add-cancel")) { Text("Cancel") }
            ActionButton(
                "runners.admin", "Connect", { onConnect(endpoint.trim(), bearer.trim().takeUnless { linkMode }, grantable && grant) },
                Modifier.testTag("runner-add-connect"), link = gate, enabled = canConnect,
            )
        }
    }
}

/**
 * RUNNER-PAIR-1: the Connect a runner dialog: a name and how to reach the runner machine, nothing else to answer. Every project and
 * every harness may run there (the owner narrows that on the runner later if they want to). [sending] while the daemon pairs.
 */
@Composable
fun ConnectRunnerDialog(
    sending: Boolean,
    error: String?,
    onConnect: (name: String, via: String, host: String?, port: String?) -> Unit,
    onDismiss: () -> Unit,
    gate: LinkContext? = null,
) {
    AhqAlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Connect a runner") },
        text = { ConnectRunnerForm(sending, error, onConnect, onDismiss, gate) },
        confirmButton = {},
    )
}

/** The body of [ConnectRunnerDialog], buttons and all (rendered and tested without the dialog's window). */
@Composable
fun ConnectRunnerForm(
    sending: Boolean,
    error: String?,
    onConnect: (name: String, via: String, host: String?, port: String?) -> Unit,
    onDismiss: () -> Unit,
    gate: LinkContext? = null,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var via by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    val nameError = if (name.isBlank()) null else runnerNameError(name)
    val portError = runnerPortError(port)
    val canConnect = !sending && name.isNotBlank() && nameError == null && via.isNotBlank() && portError == null
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "The app starts the pairing on the runner machine and connects it here. Every project and harness may run there.",
            fontSize = 12.sp, color = Palette.Muted,
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            label = { Text("Name") },
            isError = nameError != null,
            supportingText = nameError?.let { e -> { Text(e, color = Palette.Danger, modifier = Modifier.testTag("runner-connect-name-error")) } },
            shape = Palette.PopupShape,
            modifier = Modifier.fillMaxWidth().testTag("runner-connect-name"),
        )
        OutlinedTextField(
            value = via, onValueChange = { via = it }, singleLine = true,
            label = { Text("Command that runs a command on the runner machine") },
            placeholder = { Text("docker exec my-runner   or   ssh me@my-server") },
            shape = Palette.PopupShape,
            modifier = Modifier.fillMaxWidth().testTag("runner-connect-via"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = host, onValueChange = { host = it }, singleLine = true,
                label = { Text("Address from this PC (optional)") },
                placeholder = { Text("localhost, an IP or a host name") },
                shape = Palette.PopupShape,
                modifier = Modifier.weight(2f).testTag("runner-connect-host"),
            )
            OutlinedTextField(
                value = port, onValueChange = { port = it }, singleLine = true,
                label = { Text("Port (optional)") },
                isError = portError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = Palette.PopupShape,
                modifier = Modifier.weight(1f).testTag("runner-connect-port"),
            )
        }
        if (portError != null) Text(portError, color = Palette.Danger, fontSize = 12.sp, modifier = Modifier.testTag("runner-connect-port-error"))
        Text(
            "Fill in the address and port only when the runner is reached through a published or forwarded port.",
            fontSize = 11.sp, color = Palette.Muted,
        )
        if (sending) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressBar(null, Modifier.width(120.dp))
                Text("Starting the pairing on the runner and connecting…", fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.testTag("runner-connect-wait"))
            }
        }
        if (error != null) Text(error, color = Palette.Danger, fontSize = 12.sp, modifier = Modifier.testTag("runner-connect-error"))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connecting adds the runner and turns runners on.", fontSize = 11.sp, color = Palette.Muted, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (!sending) onDismiss() }, enabled = !sending, modifier = Modifier.testTag("runner-connect-cancel")) { Text("Cancel") }
            ActionButton(
                RUNNER_ADMIN_ACTION, "Connect", { onConnect(name.trim(), via.trim(), host.trim().ifBlank { null }, port.trim().ifBlank { null }) },
                Modifier.testTag("runner-connect-go"), link = gate, enabled = canConnect,
            )
        }
    }
}

/**
 * RUNNER-TEST-1: the cost of a test, said next to its button: a test on a real model harness spends one small turn of that account,
 * and `echo` spends nothing.
 */
const val RUNNER_TEST_COST = "A test with a real model harness spends one small turn of that account; echo spends nothing."

/**
 * RUNNER-ADD-UI-1 / RUNNER-CHECK-1: the check rows, as the CLI's `infantry runner check` prints them: a tick or a cross,
 * what was checked, what was found, and under a failure its fix with a copy button. [onAgain] runs the check again.
 *
 * RUNNER-TEST-1: [onTest] adds the "Test" button beside "Check again" (one throwaway turn on the runner) and [test] its answer: the
 * harness, how long it took and the reply it gave, or the error and the last lines of the transcript. [testing] is the wait.
 */
@Composable
fun RunnerCheckRows(
    rows: List<RunnerCheckRow>?,
    error: String?,
    checking: Boolean,
    onAgain: () -> Unit,
    gate: LinkContext? = null,
    modifier: Modifier = Modifier,
    onTest: (() -> Unit)? = null,
    testing: Boolean = false,
    test: RunnerTestResult? = null,
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
        if (onTest != null) RunnerTestReport(test, testing)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onTest != null) {
                Text(RUNNER_TEST_COST, fontSize = 11.sp, color = Palette.Muted, modifier = Modifier.weight(1f).testTag("runner-test-cost"))
                ActionButton("runners.admin", "Test", onTest, Modifier.testTag("runner-test"), link = gate, enabled = !testing && !checking)
            }
            ActionButton("runners.admin", "Check again", onAgain, Modifier.testTag("runner-check-again"), link = gate, enabled = !checking)
        }
    }
}

/**
 * RUNNER-TEST-1: one test's outcome: while it runs, the wait; then the harness and how long it took with the reply it gave, or the
 * failure with the last lines of the transcript. The whole block is tagged `runner-test-reply` / `runner-test-error` for the UI tests.
 */
@Composable
fun RunnerTestReport(test: RunnerTestResult?, testing: Boolean, modifier: Modifier = Modifier) {
    if (testing) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProgressBar(null, Modifier.width(120.dp))
            Text("Running one throwaway turn on the runner…", fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.testTag("runner-test-wait"))
        }
        return
    }
    if (test == null) return
    Column(modifier.fillMaxWidth().testTag(if (test.ok) "runner-test-reply" else "runner-test-error"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "${if (test.ok) "answered" else "no answer"} in ${test.took}", fontSize = 12.sp, fontWeight = FontWeight.Medium,
            color = if (test.ok) Palette.Ok else Palette.Danger, modifier = Modifier.testTag("runner-test-summary"),
        )
        test.reply?.let { Text(it, fontSize = 12.sp, modifier = Modifier.testTag("runner-test-text")) }
        test.error?.let { Text(it, fontSize = 12.sp, color = Palette.Danger, modifier = Modifier.testTag("runner-test-text")) }
        if (!test.ok && test.lastLines.isNotEmpty()) {
            Text("last lines of the test session:", fontSize = 11.sp, color = Palette.Muted)
            test.lastLines.takeLast(LAST_LINES_SHOWN).forEach { Text(it, fontSize = 11.sp, color = Palette.Muted) }
        }
    }
}

/** RUNNER-TEST-1: how many transcript lines a failed test shows (the daemon bounds the text it sends). */
private const val LAST_LINES_SHOWN = 10

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
    onTest: (() -> Unit)? = null,
    testing: Boolean = false,
    test: RunnerTestResult? = null,
    /** RUNNER-REPAIR-1: a line above the rows (the add moved the runner from another app), or null. */
    notice: String? = null,
) {
    AhqAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Runner $runner") },
        text = {
            Column {
                notice?.let { Text(it, color = Palette.Warn, modifier = Modifier.testTag("runner-check-notice")) }
                RunnerCheckRows(rows, error, checking, onAgain, gate, onTest = onTest, testing = testing, test = test)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("runner-check-close")) { Text("Close") }
        },
    )
}

/** The dialog the card has open, or null. One owner: a successful add hands the same dialog over to its check rows. */
private sealed interface CardDialog {
    /** The paste form; [sending] while the daemon tries every host in the link. */
    data class Add(val sending: Boolean, val error: String? = null) : CardDialog
    /** RUNNER-PAIR-1: the one-step connect form; [sending] while the daemon pairs through the owner's command. */
    data class Connect(val sending: Boolean, val error: String? = null) : CardDialog
    /** One runner's check rows, [running] while the answer is on its way; RUNNER-TEST-1 adds [testing] and [test]. */
    data class Check(
        val runner: String,
        val rows: List<RunnerCheckRow>? = null,
        val error: String? = null,
        val running: Boolean = false,
        val testing: Boolean = false,
        val test: RunnerTestResult? = null,
    ) : CardDialog
}

/** The fill of [RUNNERS_CARD_NODE]: lists the runners over the window's link, adds one from its link, and checks it. */
class RunnersCardBlock : Block("runnersCard", RUNNERS_CARD_NODE) {
    @Composable
    override fun Render(scope: BlockScope) {
        val shell = LocalShellContext.current ?: return
        val link = shell.link
        /** The window's project, so a check reports whether the runner may take this project (its grant row). */
        val projectId = shell.session.activeProjectId
        val projectName = shell.session.activeProject?.name
        var list by remember { mutableStateOf<RunnersList?>(null) }
        var listError by remember { mutableStateOf<String?>(null) }
        var dialog by remember { mutableStateOf<CardDialog?>(null) }
        /** RUNNER-REPAIR-1: the takeover line the check dialog opened by an add shows; cleared when that dialog closes. */
        var notice by remember { mutableStateOf<String?>(null) }
        val coroutines = rememberCoroutineScope()
        /**
         * Sends one request and returns the daemon's answer to THAT request (matched by request id), or null when nothing was sent.
         * RUNNER-UI-ADD-1: taking the next `runner_admin` frame instead let a `list` answer stand in for an `add` one, which closed
         * the dialog as if paired and dropped the add's error. A refusal (an `error` frame) comes back as a failed answer.
         */
        suspend fun call(request: String): String? = link.request(RUNNER_ADMIN_FRAME, request)?.let { reply ->
            if (reply.refused) refusalAnswer(reply.data) else reply.data
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
        /**
         * RUNNER-TEST-1: runs one throwaway turn on [runner] and shows its answer under the check rows. The rows stay as they were: a
         * test proves the agent CLI answers, it does not replace the check.
         */
        suspend fun test(runner: String) {
            dialog = CardDialog.Check(runner, testing = true)
            val text = call(testRequest(runner))
            dialog = when {
                text == null -> CardDialog.Check(runner, error = RUNNERS_OFFLINE)
                else -> parseRunnerTest(text)?.let { CardDialog.Check(runner, test = it) }
                    ?: CardDialog.Check(runner, error = runnerFailure(text) ?: RUNNERS_UNREADABLE)
            }
        }
        // RUNNER-UI-ADD-1: on connect and reconnect, not per link object (the shell hands a new one each recomposition, which
        // re-listed about once a second).
        LaunchedEffect(link.live) { if (link.live) refresh() }
        // RUNNER-CARD-STALE-1: the row follows the daemon's verdict without reopening the card (a new runner read `down` until then).
        LaunchedEffect(link.live) { if (link.live) link.frames(RUNNERS_CHANGED_FRAME).collect { refresh() } }
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
            onConnect = { dialog = CardDialog.Connect(sending = false) },
        )
        when (val open = dialog) {
            null -> Unit
            is CardDialog.Add -> AddRunnerDialog(
                sending = open.sending, error = open.error,
                onConnect = { pasted, bearer, grant ->
                    coroutines.launch {
                        dialog = CardDialog.Add(sending = true)
                        val text = call(if (bearer != null) addBearerRequest(pasted, bearer) else addLinkRequest(pasted, null, grant, projectId))
                        val failure = text?.let { runnerFailure(it) }
                        when {
                            text == null -> dialog = CardDialog.Add(sending = false, error = RUNNERS_OFFLINE)
                            failure != null -> dialog = CardDialog.Add(sending = false, error = failure)
                            else -> {
                                refresh()
                                notice = addedTakeoverNotice(text)
                                val runner = addedRunnerName(text)
                                if (runner == null) dialog = null else check(runner)
                            }
                        }
                    }
                },
                onDismiss = { dialog = null },
                gate = link,
                grantProjectId = projectId,
                grantProjectName = projectName,
            )
            is CardDialog.Connect -> ConnectRunnerDialog(
                sending = open.sending, error = open.error,
                onConnect = { name, via, host, port ->
                    coroutines.launch {
                        dialog = CardDialog.Connect(sending = true)
                        val text = call(connectRequest(name, via, host, port))
                        val failure = text?.let { runnerFailure(it) }
                        when {
                            text == null -> dialog = CardDialog.Connect(sending = false, error = RUNNERS_OFFLINE)
                            failure != null -> dialog = CardDialog.Connect(sending = false, error = failure)
                            else -> {
                                refresh()
                                // The same check rows (and Test) an add opens, so the owner sees it reachable at once.
                                check(addedRunnerName(text) ?: name)
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
                onDismiss = { dialog = null; notice = null },
                gate = link,
                onTest = { coroutines.launch { test(open.runner) } },
                testing = open.testing,
                test = open.test,
                notice = notice,
            )
        }
    }
}
