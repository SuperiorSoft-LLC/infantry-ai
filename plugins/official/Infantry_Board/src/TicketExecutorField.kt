package com.superiorsoftllc.infantry.plugins.official.board

import com.superiorsoftllc.infantry.ui.*
import com.superiorsoftllc.infantry.ui.shell.blocks.*

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.ExecutorPickerModel
import com.superiorsoftllc.infantry.ui.model.PickedModel
import com.superiorsoftllc.infantry.ui.model.modelForProvider
import com.superiorsoftllc.infantry.ui.shell.ExecutorContext
import com.superiorsoftllc.infantry.ui.shell.RolesContext

/** TICKET-EXEC-UI: the ticket forms' assignee chips, tagged with their value ("unassigned", `role:<r>`, a profile id). */
const val TICKET_ASSIGNEE_TAG_PREFIX = "ticket-assignee-"

/** TICKET-EXEC-UI: the "inherit" chip that drops the ticket's model pin while keeping its provider pin. */
const val TICKET_MODEL_INHERIT_TAG = "ticket-model-inherit"

/** TICKET-EXEC-UI: a card's effective executor line ("runs on <provider> (<source>) / <model> (<source>)"), + ticket id. */
const val TICKET_EXECUTOR_LINE_TAG_PREFIX = "ticket-executor-"

/** CONTEXT-LIMIT-2: a card's effective context limit line, + ticket id. */
const val TICKET_CONTEXT_LIMIT_LINE_TAG_PREFIX = "ticket-context-limit-line-"

/** EFFORT-1: a card's effective effort line, + ticket id. */
const val TICKET_EFFORT_LINE_TAG_PREFIX = "ticket-effort-line-"

/** EFFORT-1: the ticket forms' effort switch and support line. */
val TICKET_EFFORT_TAGS = EffortTags("ticket-effort-switch", "ticket-effort-note")

/** CONTEXT-LIMIT-2: the ticket forms' context limit switch, size field and support line. */
val TICKET_CONTEXT_LIMIT_TAGS = ContextLimitTags("ticket-context-limit-switch", "ticket-context-limit", "ticket-context-limit-note")

/** TICKET-EXEC-UI: the ticket forms' executor picker tags; the harness "inherit" chip is `ticket-harness-inherit`. */
val TICKET_EXECUTOR_TAGS = ExecutorPickerTags(
    harness = { "ticket-harness-$it" },
    providerSearch = "ticket-provider-search",
    providerAll = "ticket-provider-all",
    provider = { "ticket-provider-$it" },
    modelSearch = "ticket-model-search",
    model = { _, id -> "ticket-model-$id" },
    freeText = "ticket-model-freetext",
    daemonDefault = "ticket-harness-inherit",
)

/**
 * TICKET-EXEC-UI: who gets the ticket (BUG-ROLE-3: no "auto", the daemon never picks a role); "unassigned" is "",
 * then each role lane (`role:<role>`) and each profile. An assignee no longer listed (a deleted profile, another
 * project's lane) still shows as a selected chip, so opening an edit never hides what the ticket holds.
 */
@Composable
internal fun TicketAssigneePicker(roles: RolesContext, assignee: String?, focus: FocusRequester? = null, onPick: (String?) -> Unit) {
    val lanes = roles.profiles.map { it.role }.distinct().map { "role:$it" }
    val profiles = roles.profiles.mapNotNull { p -> p.id?.let { it to p.name } }
    val known = lanes.toSet() + profiles.map { it.first }
    // The focus target is the first role lane (what a no-role ticket needs), else the "unassigned" chip.
    val focusFirst = { if (focus != null) Modifier.focusRequester(focus) else Modifier }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Assignee", color = Palette.Muted, fontSize = 11.sp)
        AssigneeChip("unassigned", "unassigned", "", assignee, onPick, extra = if (lanes.isEmpty()) focusFirst() else Modifier)
        assignee?.takeIf { it.isNotEmpty() && it !in known }?.let { AssigneeChip(it, it, it, assignee, onPick) }
        lanes.forEachIndexed { i, it -> AssigneeChip(it, it, it, assignee, onPick, laneRole = it.removePrefix("role:"), roles = roles, extra = if (i == 0) focusFirst() else Modifier) }
        profiles.forEach { (id, name) -> AssigneeChip(name, id, id, assignee, onPick) }
    }
}

/** ROLE-ICON-1: a role lane (`role:<id>`, [laneRole]) carries that role's icon; unassigned and profiles do not. */
@Composable
private fun AssigneeChip(label: String, key: String, value: String?, selected: String?, onPick: (String?) -> Unit, laneRole: String? = null, roles: RolesContext? = null, extra: Modifier = Modifier) {
    val color = if (selected == value) Palette.Accent else Palette.Muted
    val modifier = extra.testTag(TICKET_ASSIGNEE_TAG_PREFIX + key).clickable { onPick(value) }
    if (laneRole != null && roles != null) {
        RoleChip(label, laneRole, roles.role(laneRole)?.icon, RoleIconSite.ASSIGNEE, color, modifier)
    } else {
        Chip(label, color, modifier)
    }
}

/**
 * TICKET-EXEC-UI: the ticket's provider (harness) and model pins, through the shared [ExecutorPicker]. [provider]
 * null is "inherit" (the profile's, the role's or the daemon's harness), and so is [model] null. A model belongs to its
 * provider: choosing another harness keeps the model only when that harness lists it ([modelForProvider]), else it
 * falls back to inherit; "inherit" on the harness drops both pins. [key] resets the draft (another ticket, a reopened form).
 */
@Composable
internal fun TicketExecutorField(executor: ExecutorContext, key: Any?, provider: String?, model: String?, onChange: (String?, String?) -> Unit) {
    val harnesses = executor.providers
    val full = ExecutorPickerModel.catalogWithProviderLists(harnesses, executor.modelCatalog)
    val stored = provider?.let { PickedModel(it, model) }
    var draft by remember(key) { mutableStateOf(ExecutorPickerModel.seeded(harnesses, full, stored)) }
    // The caller's value wins whenever it differs from the draft (a save landed, a reopen) or new frames arrived; the
    // draft only carries the user's open searches and the vendor they narrowed to.
    val shown = if (draft.harnesses != harnesses || draft.catalog != full || draft.picked != stored) {
        ExecutorPickerModel.seeded(harnesses, full, stored).let { s -> draft.group?.let { g -> s.withGroup(g) } ?: s }
    } else draft
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (provider != null && harnesses.isNotEmpty() && harnesses.none { it.id == provider }) {
            Text("Pinned to \"$provider\", which this daemon does not report.", color = Palette.Danger, fontSize = 11.sp)
        }
        ExecutorPicker(
            shown,
            { next ->
                draft = next
                if (next.picked != shown.picked) {
                    val p = next.harness
                    val m = next.picked?.model
                    // A new harness: the model survives only if that harness lists it (a free-text harness types its own).
                    onChange(p, if (p != shown.harness) modelForProvider(harnesses, executor.modelCatalog, p, m) else m)
                }
            },
            tags = TICKET_EXECUTOR_TAGS,
            onDaemonDefault = {
                draft = ExecutorPickerModel(harnesses, full)
                onChange(null, null)
            },
            daemonDefaultLabel = "inherit",
            noHarnessText = "No provider pinned: the ticket inherits its assignee's (profile, role, then the daemon default).",
        )
        if (shown.harness != null && shown.model != null) {
            Chip(
                "inherit model", Palette.Muted,
                Modifier.testTag(TICKET_MODEL_INHERIT_TAG).clickable {
                    draft = shown.withModel("")
                    onChange(shown.harness, null)
                },
            )
        }
    }
}
