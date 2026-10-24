package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.isBusy
import com.superiorsoftllc.agenthq.ui.protocol.SessionState

const val RESTART_SESSION_LABEL = "Restart session"
const val RESTART_SESSION_DIALOG_TAG = "session-restart-dialog"
const val RESTART_SESSION_CONFIRM_TAG = "session-restart-confirm"
const val RESTART_SESSION_CANCEL_TAG = "session-restart-cancel"
const val RESTART_SESSION_MIDTURN_TAG = "session-restart-midturn"
const val RESTART_SESSION_WORK_TAG = "session-restart-work"

/** What a restart of one session would interrupt: drives the confirm's wording and whether the daemon is sent `force`. */
data class RestartPlan(val midTurn: Boolean, val openWork: List<String>) {
    /** The daemon refuses a restart of a busy session unless forced; the UI forces only after this confirm. */
    val needsForce: Boolean get() = midTurn || openWork.isNotEmpty()
}

/** Names (and pids) of the background work the scanner reports for [a]: the scanned process and the `bg run` jobs. */
fun restartPlan(a: AgentView): RestartPlan {
    val work = buildList {
        a.session.backgroundWork?.let { add(workLine(it.label, it.pid, it.count)) }
        a.session.bgJobs.forEach { add(workLine(it.label, it.pid, 1)) }
    }
    val running = a.session.state in setOf(SessionState.STARTING, SessionState.BUSY, SessionState.WAITING_INPUT)
    return RestartPlan(midTurn = isBusy(a) || (running && work.isEmpty() && a.session.turnActive != false), openWork = work)
}

private fun workLine(label: String, pid: Long, count: Int): String =
    buildString {
        append(label.ifBlank { "process" })
        if (pid > 0) append(" (pid $pid)")
        if (count > 1) append(" +${count - 1} more")
    }

/** The confirm before a restart. Focus starts on Cancel; the destructive button is the only path to [onRestart]. */
@Composable
fun RestartSessionDialog(a: AgentView, state: AppState, onDismiss: () -> Unit, onRestart: () -> Unit) {
    val plan = restartPlan(a)
    val cancelFocus = remember { FocusRequester() }
    AhqAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(RESTART_SESSION_DIALOG_TAG).onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onDismiss(); true } else false
        },
        containerColor = Palette.Panel,
        title = { Text(RESTART_SESSION_LABEL, color = Palette.Text) },
        text = {
            Column {
                Text("Restart ${state.agentTitle(a)}? It restarts as a fresh session: the provider conversation is dropped.", color = Palette.Text)
                if (plan.midTurn) {
                    Text(
                        "This session is mid-turn. Restarting force-stops the running turn.",
                        color = Palette.Danger, fontSize = 12.sp, modifier = Modifier.testTag(RESTART_SESSION_MIDTURN_TAG),
                    )
                }
                if (plan.openWork.isNotEmpty()) {
                    Text("Open background work will be ended:", color = Palette.Text, fontSize = 12.sp)
                    plan.openWork.forEach { Text("• $it", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag(RESTART_SESSION_WORK_TAG)) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRestart,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Danger, contentColor = Palette.Bg),
                modifier = Modifier.testTag(RESTART_SESSION_CONFIRM_TAG),
            ) { Text(if (plan.needsForce) "Force restart" else "Restart") }
        },
        dismissButton = {
            LaunchedEffect(Unit) { cancelFocus.requestFocus() }
            TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus).testTag(RESTART_SESSION_CANCEL_TAG)) { Text("Cancel", color = Palette.Text) }
        },
    )
}
