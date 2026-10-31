package com.superiorsoftllc.agenthq.plugins.official.chat

import com.superiorsoftllc.agenthq.ui.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextDecoration
import com.superiorsoftllc.agenthq.ui.model.NewAgentField
import com.superiorsoftllc.agenthq.ui.protocol.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AllowedRoles
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.ExecutorPickerModel
import com.superiorsoftllc.agenthq.ui.model.NewAgentModalModel
import com.superiorsoftllc.agenthq.ui.model.PickedModel
import kotlinx.coroutines.launch

const val NEW_AGENT_DIALOG_TAG = "new-agent-dialog"
const val NEW_AGENT_ROLE_SEARCH_TAG = "new-agent-role-search"
const val NEW_AGENT_ROLE_TAG_PREFIX = "new-agent-role:"
const val NEW_AGENT_HARNESS_TAG_PREFIX = "new-agent-harness-"
const val NEW_AGENT_PROVIDER_SEARCH_TAG = "new-agent-provider-search"
const val NEW_AGENT_PROVIDER_ALL_TAG = "new-agent-provider-all"
const val NEW_AGENT_PROVIDER_TAG_PREFIX = "new-agent-provider-"
const val NEW_AGENT_MODEL_SEARCH_TAG = "new-agent-model-search"
const val NEW_AGENT_MODEL_TAG_PREFIX = "new-agent-model-"
const val NEW_AGENT_MODEL_FREETEXT_TAG = "new-agent-model-freetext"
const val NEW_AGENT_TASK_TAG = "new-agent-task"
/** CONTEXT-LIMIT-1: the context limit's off switch, its field (shown while on) and the chosen harness's support line. */
const val NEW_AGENT_CONTEXT_LIMIT_SWITCH_TAG = "new-agent-context-limit-switch"
const val NEW_AGENT_CONTEXT_LIMIT_TAG = "new-agent-context-limit"
const val NEW_AGENT_CONTEXT_LIMIT_NOTE_TAG = "new-agent-context-limit-note"
const val NEW_AGENT_EFFORT_SWITCH_TAG = "new-agent-effort-switch"
const val NEW_AGENT_EFFORT_NOTE_TAG = "new-agent-effort-note"
const val NEW_AGENT_ADD_TAG = "new-agent-add"
const val NEW_AGENT_ADD_HINT_TAG = "new-agent-add-hint"
const val NEW_AGENT_ERROR_TAG = "new-agent-error"
const val NEW_AGENT_EMPTY_ROLES_TAG = "new-agent-roles-empty"
const val NEW_AGENT_ROLE_GRID_TAG = "new-agent-role-grid"
const val NEW_AGENT_MORE_ROLES_TAG = "new-agent-more-roles"
/** The "required" mark beside a step title; the suffix is the [NewAgentField] name. */
const val NEW_AGENT_REQUIRED_TAG_PREFIX = "new-agent-required-"

private val RoleCardHeight = 48.dp
private val RoleGridGap = 6.dp
private val RoleCardMinWidth = 150.dp
private const val ROLE_GRID_VISIBLE_ROWS = 3

/**
 * UX slice 1: the New agent modal. 1) pick a role from a searchable grid; 2) pick harness, provider and model
 * (each searchable, seeded from the role's binding); 3) type the first task and press Add. [onAdd] receives the
 * role id, the picked harness+model (null = daemon defaults), the task and the context limit (null = the harness's own
 * window), and runs the existing spawn.
 */
@Composable
fun NewAgentDialog(
    state: AppState,
    /** Runs the spawn; returns null on success (the modal closes) or the refusal text (shown inside the modal, nothing closes). */
    onAdd: suspend (roleId: String, picked: PickedModel?, task: String, contextLimit: Int?, effort: String?) -> String?,
    onDismiss: () -> Unit,
) {
    NewAgentDialogFrame(state, onAdd, onDismiss) { draft ->
        NewAgentRoleStep(draft)
        NewAgentExecutorStep(draft)
        NewAgentError(draft)
        NewAgentTaskStep(draft)
    }
}

/**
 * BLOCKS-LAYOUT L11: the New agent modal's draft, as its steps read it: the current model (catalogs folded in), its
 * update, and the last refusal of Add.
 */
class NewAgentDraft(
    val model: NewAgentModalModel,
    val update: (NewAgentModalModel) -> Unit,
    val error: String?,
    /** Where each step sits, so the Add hint can scroll the missing one into view (and focus it, for a text field). */
    val targets: NewAgentTargets = NewAgentTargets(),
)

/** One scroll target per step, and a focus target per text field. Owned by the frame; the steps attach them. */
class NewAgentTargets {
    val role = BringIntoViewRequester()
    val executor = BringIntoViewRequester()
    val task = BringIntoViewRequester()
    val roleFocus = FocusRequester()
    val taskFocus = FocusRequester()

    /** Scroll [field]'s step into view and focus its text field when it has one. */
    suspend fun jumpTo(field: NewAgentField) {
        when (field) {
            NewAgentField.ROLE -> { role.bringIntoView(); runCatching { roleFocus.requestFocus() } }
            NewAgentField.HARNESS, NewAgentField.MODEL, NewAgentField.CONTEXT_LIMIT -> executor.bringIntoView()
            NewAgentField.TASK -> { task.bringIntoView(); runCatching { taskFocus.requestFocus() } }
        }
    }
}

/**
 * The New agent modal's frame: the dialog (title, Add with its hint, Cancel) and the draft it owns. [content] gets the
 * draft and goes straight into the dialog's scrolling column, top to bottom (16dp apart).
 */
@Composable
fun NewAgentDialogFrame(
    state: AppState,
    onAdd: suspend (roleId: String, picked: PickedModel?, task: String, contextLimit: Int?, effort: String?) -> String?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(NewAgentDraft) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(NewAgentModalModel(AllowedRoles.filter(state.allowedRoles, state.roles), state.roleBindings, state.providers, state.modelCatalog)) }
    // The catalogs arrive after the window opens (opening triggers the refresh): fold them in without losing the user's draft.
    val executor = if (state.providers != draft.harnesses || state.modelCatalog != draft.catalog) {
        ExecutorPickerModel.seeded(state.providers, state.modelCatalog, draft.executor.picked).let { if (draft.roleId != null) it.withSoleModel() else it }
    } else draft.executor
    val cur = draft.copy(roles = AllowedRoles.filter(state.allowedRoles, state.roles), bindings = state.roleBindings, harnesses = state.providers, catalog = state.modelCatalog, executor = executor)
    val update: (NewAgentModalModel) -> Unit = { draft = it }
    val targets = remember { NewAgentTargets() }

    AhqAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(NEW_AGENT_DIALOG_TAG),
        title = { Text("New agent", fontWeight = FontWeight.Bold) },
        text = {
            // Four steps only, so a plain scrolling Column: every step stays composed (a lazy list dropped the task
            // field once the searchable provider/model lists made step 2 taller than the viewport). Ticket 90580b06:
            // the scroll cue column fades the clipped edge, so content below the fold is visible as such.
            ScrollCueColumn(
                Modifier.heightIn(max = 520.dp).padding(horizontal = 2.dp, vertical = 4.dp),
                fade = AlertDialogDefaults.containerColor,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content(NewAgentDraft(cur, update, error, targets))
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Ticket 90580b06: the hint names the missing field and, clicked, scrolls to it (and focuses it).
                cur.missingField?.let { field ->
                    Text(
                        "\u2193 " + cur.addBlockedReason, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.weight(1f, fill = false).clickable(onClickLabel = "Show the missing field") { scope.launch { targets.jumpTo(field) } }
                            .testTag(NEW_AGENT_ADD_HINT_TAG),
                    )
                }
                Button(
                    onClick = {
                        cur.roleId?.let { role ->
                            adding = true; error = null
                            scope.launch { error = onAdd(role, cur.executor.picked, cur.task.trim(), cur.contextLimit, cur.effort); adding = false }
                        }
                    },
                    enabled = cur.canAdd && !adding, modifier = Modifier.testTag(NEW_AGENT_ADD_TAG),
                ) { Text("Add") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Step 1: the role catalog (search, then the grid of roles); [modifier] goes on the step's column. */
@Composable
fun NewAgentRoleStep(draft: NewAgentDraft, modifier: Modifier = Modifier) = RoleStep(draft.model, draft.update, draft.targets, modifier)

/** Step 2: harness, provider and model, closed until a role is chosen; [modifier] goes on the step's column. */
@Composable
fun NewAgentExecutorStep(draft: NewAgentDraft, modifier: Modifier = Modifier) =
    ExecutorStep(draft.model, draft.update, modifier.bringIntoViewRequester(draft.targets.executor))

/** Add's last refusal, while there is one. */
@Composable
fun NewAgentError(draft: NewAgentDraft, modifier: Modifier = Modifier) {
    draft.error?.let { e -> Text("Could not add the agent: $e", color = Palette.Danger, fontSize = 12.sp, modifier = modifier.testTag(NEW_AGENT_ERROR_TAG)) }
}

/** Step 3: the first task; [modifier] goes on the step's column. */
@Composable
fun NewAgentTaskStep(draft: NewAgentDraft, modifier: Modifier = Modifier) {
    val cur = draft.model
    Column(modifier.bringIntoViewRequester(draft.targets.task), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StepTitle("3. First task", NewAgentField.TASK)
        OutlinedTextField(
            cur.task, { draft.update(cur.withTask(it)) }, placeholder = { Text("What should this agent do first?") },
            modifier = Modifier.fillMaxWidth().focusRequester(draft.targets.taskFocus).testTag(NEW_AGENT_TASK_TAG), minLines = 2, maxLines = 4, colors = visibleBorderFieldColors(),
        )
    }
}

/** A step's bold title with the required mark: an asterisk plus the word, so it is not color or symbol alone. */
@Composable
private fun StepTitle(text: String, field: NewAgentField) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("* required", color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.testTag(NEW_AGENT_REQUIRED_TAG_PREFIX + field.name))
    }
}

@Composable
private fun RoleStep(m: NewAgentModalModel, update: (NewAgentModalModel) -> Unit, targets: NewAgentTargets, modifier: Modifier) {
    Column(modifier.bringIntoViewRequester(targets.role), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StepTitle("1. Role", NewAgentField.ROLE)
        OutlinedTextField(
            m.roleQuery, { update(m.withRoleQuery(it)) }, placeholder = { Text("Search roles...") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(targets.roleFocus).testTag(NEW_AGENT_ROLE_SEARCH_TAG), colors = visibleBorderFieldColors(),
        )
        val options = m.roleOptions
        when {
            m.rolesLoading -> Text(
                "Loading roles... (connect to the daemon if this stays empty)", color = Palette.Muted, fontSize = 11.sp,
                modifier = Modifier.testTag(NEW_AGENT_EMPTY_ROLES_TAG),
            )
            options.isEmpty() -> Text(
                "No role matches \"${m.roleQuery}\"", color = Palette.Muted, fontSize = 11.sp,
                modifier = Modifier.testTag(NEW_AGENT_EMPTY_ROLES_TAG),
            )
            else -> RoleGrid(m, update, options)
        }
    }
}

/**
 * The role cards, at most [ROLE_GRID_VISIBLE_ROWS] whole rows tall (ticket 90580b06: the old height cap cut a row in
 * half). Cards have a fixed height and the grid is sized to whole rows; while more rows sit below, a line says so.
 */
@Composable
private fun RoleGrid(m: NewAgentModalModel, update: (NewAgentModalModel) -> Unit, options: List<Role>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The Adaptive rule, done here so the row count (and so the height) is known.
        val columns = ((maxWidth + RoleGridGap) / (RoleCardMinWidth + RoleGridGap)).toInt().coerceAtLeast(1)
        val rows = (options.size + columns - 1) / columns
        val shown = rows.coerceAtMost(ROLE_GRID_VISIBLE_ROWS)
        val gridState = rememberLazyGridState()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns), state = gridState,
                modifier = Modifier.fillMaxWidth().height(RoleCardHeight * shown + RoleGridGap * (shown - 1)).testTag(NEW_AGENT_ROLE_GRID_TAG),
                horizontalArrangement = Arrangement.spacedBy(RoleGridGap), verticalArrangement = Arrangement.spacedBy(RoleGridGap),
            ) {
                items(options, key = { it.id }) { r ->
                    val on = m.roleId == r.id
                    val shape = RoundedCornerShape(6.dp)
                    Row(
                        Modifier.fillMaxWidth().height(RoleCardHeight).clip(shape)
                            .border(BorderStroke(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)), shape)
                            .clickable { update(m.withRole(r.id)) }.padding(8.dp).testTag(NEW_AGENT_ROLE_TAG_PREFIX + r.id),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AgentAvatar(r.id, r.id, size = 28, catalogColor = r.color)
                        Column {
                            // ROLE-ICON-1: the role's icon beside its name in the picker.
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                RoleIcon(r.id, r.icon, RoleIconSite.PICKER, size = 13)
                                Text(r.name, fontSize = 12.sp, maxLines = 1, color = AlertDialogDefaults.textContentColor)
                            }
                            Text(r.id, fontSize = 10.sp, maxLines = 1, color = Palette.Muted)
                        }
                    }
                }
            }
            if (gridState.canScrollForward) {
                Text(
                    "More roles below: scroll the grid or search", color = Palette.Muted, fontSize = 11.sp,
                    modifier = Modifier.testTag(NEW_AGENT_MORE_ROLES_TAG),
                )
            }
        }
    }
}

/** The modal's published tags, kept verbatim so NewAgentDialogUiTest and any automation see the same nodes. */
private val NEW_AGENT_EXECUTOR_TAGS = ExecutorPickerTags(
    harness = { NEW_AGENT_HARNESS_TAG_PREFIX + it },
    providerSearch = NEW_AGENT_PROVIDER_SEARCH_TAG,
    providerAll = NEW_AGENT_PROVIDER_ALL_TAG,
    provider = { NEW_AGENT_PROVIDER_TAG_PREFIX + it },
    modelSearch = NEW_AGENT_MODEL_SEARCH_TAG,
    model = { _, id -> NEW_AGENT_MODEL_TAG_PREFIX + id },
    freeText = NEW_AGENT_MODEL_FREETEXT_TAG,
)

/** Step 2: closed until a role is chosen (its binding seeds the choice), then the shared [ExecutorPicker]. */
@Composable
private fun ExecutorStep(m: NewAgentModalModel, update: (NewAgentModalModel) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StepTitle("2. Harness, provider and model", NewAgentField.MODEL)
        if (!m.executorOpen) {
            Text("Pick a role first: its binding seeds these choices.", color = Palette.Muted, fontSize = 11.sp)
            return@Column
        }
        // The dialog surface is light: rows take its content color instead of the dark panels' Palette.Text.
        ExecutorPicker(m.executor, { update(m.withExecutor(it)) }, tags = NEW_AGENT_EXECUTOR_TAGS, textColor = AlertDialogDefaults.textContentColor)
        NewAgentContextLimit(m, update)
        NewAgentEffort(m, update)
    }
}

/**
 * EFFORT-1: the optional reasoning effort. Off (the default) = the role binding's effort, else the harness default; on, one
 * of the levels. The line under it says how the chosen harness treats an effort (applied, best effort, or recorded only).
 */
@Composable
private fun NewAgentEffort(m: NewAgentModalModel, update: (NewAgentModalModel) -> Unit) {
    EffortField(
        m.effortOn, m.effortLevel, m.effortNote, EffortTags(NEW_AGENT_EFFORT_SWITCH_TAG, NEW_AGENT_EFFORT_NOTE_TAG),
        onOn = { update(m.withEffortOn(it)) }, onLevel = { update(m.withEffortLevel(it)) },
    )
}

/**
 * CONTEXT-LIMIT-1: the optional context limit. Off (the default) = the harness's own window; on, a size such as 200k.
 * The line under it says how the chosen harness treats a limit (applied, best effort, or recorded only).
 */
@Composable
private fun NewAgentContextLimit(m: NewAgentModalModel, update: (NewAgentModalModel) -> Unit) {
    ContextLimitField(
        m.contextLimitOn, m.contextLimitText, m.contextLimitNote, m.missingField == NewAgentField.CONTEXT_LIMIT,
        ContextLimitTags(NEW_AGENT_CONTEXT_LIMIT_SWITCH_TAG, NEW_AGENT_CONTEXT_LIMIT_TAG, NEW_AGENT_CONTEXT_LIMIT_NOTE_TAG),
        onOn = { update(m.withContextLimitOn(it)) }, onText = { update(m.withContextLimitText(it)) },
    )
}
