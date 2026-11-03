package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.HarnessSwitch
import com.superiorsoftllc.infantry.ui.model.PickedModel
import com.superiorsoftllc.infantry.ui.model.SWITCH_WAIT_FOR_TURN
import com.superiorsoftllc.infantry.ui.model.isBusy
import com.superiorsoftllc.infantry.ui.net.CommandResult
import kotlinx.coroutines.launch

/** SWAP-AGENT-1: the composer's Switch agent action's name and shortcut (Ctrl+N stays New agent everywhere). */
const val SWITCH_AGENT_LABEL = "Switch agent"
const val SWITCH_AGENT_SHORTCUT = "Ctrl+Shift+N"

/** SWAP-AGENT-1: the Switch agent picker, its role chips (prefix + role id, or [SWITCH_ROLE_NONE_TAG]) and its role line. */
const val SWITCH_AGENT_PICKER_TAG = "switch-agent-picker"
const val SWITCH_ROLE_TAG_PREFIX = "switch-role:"
const val SWITCH_ROLE_NONE_TAG = "switch-role-none"
const val SWITCH_ROLE_FIXED_TAG = "switch-role-fixed"

/** CMD-HARNESS-1 / SWAP-AGENT-1: the header's "<harness> · <model>" chip, and the picker's Apply, refusal and model line. */
const val EXECUTOR_SWITCH_CHIP_TAG = "executor-switch-chip"
const val EXECUTOR_SWITCH_APPLY_TAG = "executor-switch-apply"
const val EXECUTOR_SWITCH_ERROR_TAG = "executor-switch-error"
const val EXECUTOR_SWITCH_MODEL_TAG = "executor-switch-model"

/** SWAP-AGENT-1: open the Switch agent picker for [sessionId]; [harness] pre-selects one (`/harness X`), [seq] reopens it. */
data class SwitchAgentAsk(val sessionId: String, val harness: String? = null, val seq: Long = 0)

/**
 * SWAP-AGENT-1: the ONE all-in-one picker for every switch -- the composer's Switch agent, the header chip and
 * `/harness <name>` all open it. The New agent box's rows (role, then Harness -> Provider -> Model), inline above the
 * composer, pre-filled with what the session runs as now ([ask] may pre-select a harness). Apply swaps the agent of the
 * SAME session ([onApply]): same id, tab and transcript; the conversation carries over (HARNESS-CARRY-1).
 *
 * Apply needs everything resolved -- a role choice (none is one), a harness, and a model (a pick, else the harness's
 * named default) -- and something changed. An unresolved field is shown in the danger colour. While a turn is in flight
 * Apply is disabled with the tooltip [SWITCH_WAIT_FOR_TURN]. A refusal, local or the daemon's, stays in the picker; it
 * closes once the daemon confirmed. A saved agent's role is its profile's, so its role row is read-only.
 */
@Composable
fun SwitchAgentPicker(
    state: AppState,
    a: AgentView,
    ask: SwitchAgentAsk,
    onApply: suspend (role: String?, picked: PickedModel) -> CommandResult,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val key = a.id to ask.seq
    val agentId = a.session.agentId?.takeIf { it.isNotBlank() }
    val currentRole = (a.session.role ?: a.role)?.takeIf { it.isNotBlank() }
    val executor = state.sessionExecutor(a)
    val stored = executor.harness?.let { PickedModel(it, a.session.model?.takeIf { m -> m.isNotBlank() }) }
    // `/harness X`: X pre-selected, keeping the current model only when X lists it (else X's default).
    val seed = ask.harness?.takeIf { it != stored?.provider }?.let { h -> PickedModel(h, HarnessSwitch.keptModel(state, stored?.provider, stored?.model, h)) }
    var role by remember(key) { mutableStateOf(currentRole) }
    var picked by remember(key) { mutableStateOf(seed ?: stored) }
    var refusal by remember(key) { mutableStateOf<String?>(null) }
    var awaiting by remember(key) { mutableStateOf(false) }
    // The daemon confirmed this window's swap (agent_swapped, or session_swapped for a session with no agent).
    val confirmed: Any? = if (agentId != null) state.agentSwaps[agentId] else state.sessionSwaps[a.id]
    LaunchedEffect(confirmed) { if (awaiting && confirmed != null) { awaiting = false; onClose() } }

    val resolvedModel = picked?.let { p -> p.model ?: state.providers.firstOrNull { it.id == p.provider }?.defaultModel?.takeIf { it.isNotBlank() } }
    val busy = isBusy(a)
    val changed = role != currentRole || picked != stored
    val canApply = state.connection.isLive && !busy && !awaiting && picked != null && resolvedModel != null && changed

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(Palette.Raised).border(1.dp, Palette.Border)
            .padding(10.dp).testTag(SWITCH_AGENT_PICKER_TAG),
    ) {
        Text(SWITCH_AGENT_LABEL, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Text("Same session: the conversation carries over to the agent you pick.", color = Palette.Muted, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Role", color = Palette.Muted, fontSize = 11.sp)
            if (a.session.global) {
                // ASSISTANT-1: the global Assistant keeps its role; only its model is free.
                val shown = currentRole ?: "assistant"
                RoleChip(shown, shown, currentRole?.let { state.role(it)?.icon }, RoleIconSite.SWITCH, Palette.Accent, Modifier.testTag(SWITCH_ROLE_TAG_PREFIX + shown))
                Text("fixed: the Assistant always runs as $shown", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(SWITCH_ROLE_FIXED_TAG))
            } else if (agentId != null) {
                val shown = currentRole ?: "none"
                RoleChip(shown, shown, currentRole?.let { state.role(it)?.icon }, RoleIconSite.SWITCH, Palette.Accent, Modifier.testTag(SWITCH_ROLE_TAG_PREFIX + shown))
                val name = state.profiles.firstOrNull { it.id == agentId }?.name ?: agentId
                Text("set by $name's profile", color = Palette.Muted, fontSize = 11.sp)
            } else {
                // ROLE-PILL-ICONS-1: each role pill paints the role card's icon; "none" paints the neutral fallback.
                RoleChip(
                    "none", "none", null, RoleIconSite.SWITCH, if (role == null) Palette.Accent else Palette.Muted,
                    Modifier.testTag(SWITCH_ROLE_NONE_TAG).clickable { role = null; refusal = null },
                )
                state.roles.forEach { r ->
                    RoleChip(
                        r.id, r.id, r.icon, RoleIconSite.SWITCH, if (role == r.id) Palette.Accent else Palette.Muted,
                        Modifier.testTag(SWITCH_ROLE_TAG_PREFIX + r.id).clickable { role = r.id; refusal = null },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        SeededExecutorPicker(
            state.providers, state.modelCatalog, seed ?: stored, key = key, tags = ExecutorPickerTags(),
            onPick = { picked = it; refusal = null },
        )
        val p = picked
        Text(
            when {
                p == null -> "Pick a provider to switch."
                resolvedModel == null -> "Pick a model for ${p.provider} to switch."
                else -> "Model: $resolvedModel" + if (p.model == null) " (default)" else ""
            },
            color = if (resolvedModel == null) Palette.Danger else Palette.Muted, fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp).testTag(EXECUTOR_SWITCH_MODEL_TAG),
        )
        // The daemon's refusal of this window's swap, or a local one, never swallowed.
        (refusal ?: if (agentId != null) state.agentError?.takeIf { it.startsWith("agent_swap ") } else state.sessionSwitchError)?.let {
            Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp).testTag(EXECUTOR_SWITCH_ERROR_TAG))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
            OutlinedButton(onClick = onClose) { Text("Cancel") }
            IconActionTooltipBox(if (busy) SWITCH_WAIT_FOR_TURN else "Switch this session to the picked agent", describe = false) { m ->
                Button(
                    onClick = {
                        val choice = picked ?: return@Button
                        scope.launch {
                            when (val r = onApply(role, choice)) {
                                is CommandResult.Refused -> refusal = r.reason
                                else -> { refusal = null; awaiting = true }
                            }
                        }
                    },
                    enabled = canApply,
                    modifier = m.testTag(EXECUTOR_SWITCH_APPLY_TAG),
                ) { Text("Apply") }
            }
        }
    }
}
