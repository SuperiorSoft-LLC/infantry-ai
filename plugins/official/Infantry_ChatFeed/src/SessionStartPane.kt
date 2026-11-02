package com.superiorsoftllc.agenthq.plugins.official.chatfeed

import com.superiorsoftllc.agenthq.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.SessionPaneAction
import com.superiorsoftllc.agenthq.ui.model.StartPlan

/** GAP-006b: the empty pane's Start session button (no first turn yet). */
const val START_SESSION_TAG = "session-start"
/** GAP-006b: the empty pane's Resume button (a conversation exists). */
const val RESUME_SESSION_TAG = "session-resume"
/** GAP-006b: the start card listing what will run, and its two buttons. */
const val START_CARD_TAG = "session-start-card"
const val START_APPLY_TAG = "session-start-apply"
const val START_CANCEL_TAG = "session-start-cancel"

/**
 * GAP-006b: what an empty chat pane offers for the session it shows. [SessionPaneAction.Resume] is one Resume button
 * (reattach, keep the context). [SessionPaneAction.Start] is a Start session button that opens the start card: the
 * settings that will run (role, provider, model, branch, worktree) with Apply and Cancel, and nothing starts before Apply.
 * Neither ever opens the New agent box: the session's own settings run.
 */
@Composable
fun SessionStartPane(action: SessionPaneAction, onApply: (StartPlan) -> Unit, onResume: (String) -> Unit) {
    // GATE-PANES-1: Resume is `chat.resume`; Start runs the session's first prompt (`chat.prompt`) or moves its ticket.
    val resume = com.superiorsoftllc.agenthq.ui.shell.rememberGate("chat.resume")
    val start = if ((action as? SessionPaneAction.Start)?.plan?.ticketId != null) com.superiorsoftllc.agenthq.ui.shell.rememberGate("board.ticket.move")
        else com.superiorsoftllc.agenthq.ui.shell.rememberGate("chat.prompt")
    when (action) {
        is SessionPaneAction.Resume ->
            Button(onClick = { onResume(action.sessionId) }, enabled = resume.allowed, modifier = Modifier.testTag(RESUME_SESSION_TAG)) { Text("Resume") }
        is SessionPaneAction.Start -> {
            var confirming by remember(action.plan.sessionId) { mutableStateOf(false) }
            if (!confirming) {
                Button(onClick = { confirming = true }, enabled = start.allowed, modifier = Modifier.testTag(START_SESSION_TAG)) { Text(start.label("Start session")) }
            } else {
                StartCard(action.plan, applyEnabled = start.allowed, onApply = { confirming = false; onApply(action.plan) }, onCancel = { confirming = false })
            }
        }
    }
}

@Composable
private fun StartCard(plan: StartPlan, applyEnabled: Boolean, onApply: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.widthIn(max = 460.dp)
            .background(Palette.Raised, RoundedCornerShape(8.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(8.dp))
            .padding(14.dp)
            .testTag(START_CARD_TAG),
    ) {
        Text(
            plan.ticketId?.let { "Start ticket ${it.takeLast(8)}${plan.ticketTitle?.let { t -> ": $t" } ?: ""}" } ?: "Start this session",
            color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        )
        Spacer(Modifier.padding(top = 6.dp))
        Setting("Role", plan.role ?: "-")
        Setting("Provider", plan.provider)
        Setting("Model", plan.model ?: "(the harness's default)")
        Setting("Branch", plan.branch ?: "-")
        Setting("Worktree", plan.worktree)
        Spacer(Modifier.padding(top = 8.dp))
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.testTag(START_CANCEL_TAG)) { Text("Cancel") }
            Button(onClick = onApply, enabled = applyEnabled, modifier = Modifier.testTag(START_APPLY_TAG)) { Text("Apply") }
        }
    }
}

@Composable
private fun Setting(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.width(72.dp))
        Text(value, color = Palette.Text, fontSize = 12.sp)
    }
}
