// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.runners

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.shell.ActionButton
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull

/** The `runner_admin` frame (the CLI's `infantry runner ...` sends the same one) and the policy action that gates it. */
const val RUNNER_ADMIN_FRAME = "runner_admin"
const val RUNNER_ADMIN_ACTION = "runners.admin"

/** The slot of the Runners card (`infantry.shell.config.runners`) and the card's fill id. */
const val RUNNERS_CARD_NODE = "infantry.shell.config.runners"

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
    /** `local` is this machine: it has no pairing, so no detach or rotate. */
    val isLocal: Boolean get() = name == "local"
}

/** The `list` answer: whether runners are on, and the rows. Null when the text is not an ok answer. */
data class RunnersList(val enabled: Boolean, val rows: List<RunnerRow>)

private val lenient = Json { ignoreUnknownKeys = true }

fun parseRunnersList(text: String): RunnersList? {
    val obj = runCatching { lenient.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    val rows = obj["runners"] as? JsonArray ?: return null
    if ((obj["ok"] as? JsonPrimitive)?.contentOrNull != "true") return null
    fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    return RunnersList(
        enabled = (obj["enabled"] as? JsonPrimitive)?.let { runCatching { it.boolean }.getOrNull() } ?: false,
        rows = rows.mapNotNull { it as? JsonObject }.map { r ->
            RunnerRow(r.s("name"), r.s("health").ifBlank { "down" }, r.s("fingerprint"), r.s("workers").toIntOrNull() ?: 0, r.s("cap").toIntOrNull() ?: 0, r.s("paused") == "true")
        },
    )
}

/** An op the card sends: `pause` / `resume` / `detach` / `rotate` on a runner. */
fun adminRequest(op: String, name: String): String =
    JsonObject(mapOf("op" to JsonPrimitive(op), "name" to JsonPrimitive(name))).toString()

/**
 * RUNNER-POOL-11: the rows of the Runners card. Nothing is drawn while runners are off. The buttons call [onOp] (`pause`, `resume`,
 * `detach`, `rotate`; each is the `runner_admin` op the CLI uses) and are gated by [RUNNER_ADMIN_ACTION].
 */
@Composable
fun RunnersRows(list: RunnersList?, error: String?, onOp: (op: String, name: String) -> Unit, modifier: Modifier = Modifier) {
    if (list == null || !list.enabled) return
    Column(modifier.fillMaxWidth().testTag("runners-rows"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Runners", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
                ActionButton(RUNNER_ADMIN_ACTION, if (r.paused) "Resume" else "Pause", { onOp(if (r.paused) "resume" else "pause", r.name) }, Modifier.testTag("runner-pause:${r.name}"))
                if (!r.isLocal) {
                    ActionButton(RUNNER_ADMIN_ACTION, "Rotate", { onOp("rotate", r.name) }, Modifier.testTag("runner-rotate:${r.name}"))
                    ActionButton(RUNNER_ADMIN_ACTION, "Detach", { onOp("detach", r.name) }, Modifier.testTag("runner-detach:${r.name}"))
                }
            }
        }
        if (error != null) Text(error, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp).testTag("runners-error"))
    }
}

private const val FINGERPRINT_SHOWN = 16

/** The fill of [RUNNERS_CARD_NODE]: lists the runners over the window's link and sends the ops. */
class RunnersCardBlock : Block("runnersCard", RUNNERS_CARD_NODE) {
    @Composable
    override fun Render(scope: BlockScope) {
        val link = LocalShellContext.current?.link ?: return
        var list by remember { mutableStateOf<RunnersList?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        val coroutines = rememberCoroutineScope()
        /** Sends one request and returns the daemon's next `runner_admin` answer (subscribed before the send, so it cannot be missed). */
        suspend fun call(op: String, name: String): String? = kotlinx.coroutines.coroutineScope {
            val answer = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { link.frames(RUNNER_ADMIN_FRAME).first() }
            if (!link.sendFrame(RUNNER_ADMIN_FRAME, adminRequest(op, name))) { answer.cancel(); null } else answer.await()
        }
        suspend fun refresh() {
            val text = call("list", "") ?: return
            parseRunnersList(text)?.let { list = it; error = null } ?: run { error = failure(text) }
        }
        LaunchedEffect(link) { refresh() }
        RunnersRows(list, error, { op, name ->
            coroutines.launch {
                val text = call(op, name)
                if (text != null && failure(text) != null) error = failure(text) else refresh()
            }
        }, scope.modifier)
    }

    private fun failure(text: String): String? =
        (runCatching { lenient.parseToJsonElement(text) as? JsonObject }.getOrNull()?.get("error") as? JsonPrimitive)?.contentOrNull
}
