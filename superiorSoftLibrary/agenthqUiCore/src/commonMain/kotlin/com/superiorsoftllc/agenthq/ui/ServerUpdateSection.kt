package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.ServerUpdateState
import com.superiorsoftllc.agenthq.ui.model.ServerUpdateView

/** SERVER-UPDATE-4: Configuration > Server. The daemon this window is connected to: its version, whether it has an update, and the button. */
object ServerUpdateEntry {
    const val TITLE = "Server"
    const val APPLIES_TO = "The Infantry AI server this window is connected to. Updating restarts it; the data folder is not touched"
}

const val SERVER_VERSION_TAG = "server-version"
const val SERVER_MISMATCH_TAG = "server-mismatch"
const val SERVER_CHECK_TAG = "server-check"
const val SERVER_UPDATE_TAG = "server-update"
const val SERVER_MESSAGE_TAG = "server-message"
const val SERVER_INSTRUCTIONS_TAG = "server-instructions"
const val SERVER_CONFIRM_YES_TAG = "server-confirm-yes"
const val SERVER_CONFIRM_NO_TAG = "server-confirm-no"

/** The section body: state in, three intents out. [mismatch] is the client/server version warning, null when they agree. */
@Composable
fun ServerUpdateSection(state: ServerUpdateState, mismatch: String?) {
    val view by state.view.collectAsState()
    ServerUpdateBody(view, mismatch, onCheck = state::check, onUpdate = state::requestUpdate)
}

@Composable
internal fun ServerUpdateBody(view: ServerUpdateView, mismatch: String?, onCheck: () -> Unit, onUpdate: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val status = view.status
        when {
            !view.supported -> Text(
                "This server is older than remote updates (it has no update routes). Update it on its own machine with `infantry update`, or replace the Docker image.",
                color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag(SERVER_INSTRUCTIONS_TAG),
            )
            status == null -> Text("Not read from the server yet", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag(SERVER_VERSION_TAG))
            else -> Text("Running ${status.version} (${status.distribution}, contract ${status.contract})", fontSize = 12.sp, modifier = Modifier.testTag(SERVER_VERSION_TAG))
        }
        if (mismatch != null) Text(mismatch, color = Palette.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(SERVER_MISMATCH_TAG))
        view.updateAvailable?.let { version ->
            Text("Update available: $version", color = Palette.Ok, fontSize = 12.sp)
            status?.available?.notes?.takeIf { it.isNotBlank() }?.let { Text(it.lines().take(8).joinToString("\n"), color = Palette.Muted, fontSize = 11.sp) }
        }
        if (status != null && !status.canSelfUpdate) {
            (status.instructions ?: status.disabledReason?.let { "Updates are off: $it." })?.let {
                Text(it, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(SERVER_INSTRUCTIONS_TAG))
            }
        }
        when (view.phase) {
            ServerUpdateView.Phase.IDLE -> Unit
            ServerUpdateView.Phase.CHECKING -> Progress("Checking for updates")
            ServerUpdateView.Phase.APPLYING -> Progress("Downloading and verifying the update")
            ServerUpdateView.Phase.RESTARTING -> Progress("The server is restarting; this window reconnects by itself")
        }
        view.message?.let { Text(it, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(SERVER_MESSAGE_TAG)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheck, enabled = view.supported && !view.busy, modifier = Modifier.testTag(SERVER_CHECK_TAG)) { Text("Check for updates") }
            if (view.canUpdate) OutlinedButton(onClick = onUpdate, modifier = Modifier.testTag(SERVER_UPDATE_TAG)) { Text("Update to ${view.updateAvailable}") }
        }
    }
}

@Composable
private fun Progress(label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Palette.Muted, fontSize = 11.sp)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** The "update anyway?" question, on the shared modal wrapper so the window behind blurs like every other dialog. Default is Cancel. */
@Composable
fun ServerUpdateConfirm(state: ServerUpdateState) {
    val view by state.view.collectAsState()
    val confirm = view.confirm ?: return
    AhqAlertDialog(
        onDismissRequest = state::cancelConfirm,
        title = { Text("Update the server to ${confirm.version}?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(confirm.question, fontSize = 13.sp)
                Text("The server restarts into the new version. Running agents are interrupted.", color = Palette.Muted, fontSize = 11.sp)
            }
        },
        dismissButton = { TextButton(onClick = state::cancelConfirm, modifier = Modifier.testTag(SERVER_CONFIRM_NO_TAG)) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = state::confirmUpdate, modifier = Modifier.testTag(SERVER_CONFIRM_YES_TAG)) { Text("Update") } },
    )
}
