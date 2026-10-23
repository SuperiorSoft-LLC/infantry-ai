package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
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
const val NEW_AGENT_ADD_TAG = "new-agent-add"
const val NEW_AGENT_ADD_HINT_TAG = "new-agent-add-hint"
const val NEW_AGENT_ERROR_TAG = "new-agent-error"
const val NEW_AGENT_EMPTY_ROLES_TAG = "new-agent-roles-empty"

/**
 * UX slice 1: the New agent modal. 1) pick a role from a searchable grid; 2) pick harness, provider and model
 * (each searchable, seeded from the role's binding); 3) type the first task and press Add. [onAdd] receives the
 * role id, the picked harness+model (null = daemon defaults) and the task, and runs the existing spawn.
 */
@Composable
fun NewAgentDialog(
    state: AppState,
    /** Runs the spawn; returns null on success (the modal closes) or the refusal text (shown inside the modal, nothing closes). */
    onAdd: suspend (roleId: String, picked: PickedModel?, task: String) -> String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(NewAgentModalModel(state.roles, state.roleBindings, state.providers, state.modelCatalog)) }
    // The catalogs arrive after the window opens (opening triggers the refresh): fold them in without losing the user's draft.
    val executor = if (state.providers != draft.harnesses || state.modelCatalog != draft.catalog) {
        ExecutorPickerModel.seeded(state.providers, state.modelCatalog, draft.executor.picked)
    } else draft.executor
    val cur = draft.copy(roles = state.roles, bindings = state.roleBindings, harnesses = state.providers, catalog = state.modelCatalog, executor = executor)
    val update: (NewAgentModalModel) -> Unit = { draft = it }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(NEW_AGENT_DIALOG_TAG),
        title = { Text("New agent", fontWeight = FontWeight.Bold) },
        text = {
            // Four steps only, so a plain scrolling Column: every step stays composed (a lazy list dropped the task
            // field once the searchable provider/model lists made step 2 taller than the viewport).
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            ) {
                RoleStep(cur, update)
                ExecutorStep(cur, update)
                error?.let { e -> Text("Could not add the agent: $e", color = Palette.Danger, fontSize = 12.sp, modifier = Modifier.testTag(NEW_AGENT_ERROR_TAG)) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("3. First task", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    OutlinedTextField(
                        cur.task, { update(cur.withTask(it)) }, placeholder = { Text("What should this agent do first?") },
                        modifier = Modifier.fillMaxWidth().testTag(NEW_AGENT_TASK_TAG), minLines = 2, maxLines = 4, colors = visibleBorderFieldColors(),
                    )
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                cur.addBlockedReason?.let { Text(it, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(NEW_AGENT_ADD_HINT_TAG)) }
                Button(
                    onClick = {
                        cur.roleId?.let { role ->
                            adding = true; error = null
                            scope.launch { error = onAdd(role, cur.executor.picked, cur.task.trim()); adding = false }
                        }
                    },
                    enabled = cur.canAdd && !adding, modifier = Modifier.testTag(NEW_AGENT_ADD_TAG),
                ) { Text("Add") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RoleStep(m: NewAgentModalModel, update: (NewAgentModalModel) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("1. Role", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        OutlinedTextField(
            m.roleQuery, { update(m.withRoleQuery(it)) }, placeholder = { Text("Search roles...") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(NEW_AGENT_ROLE_SEARCH_TAG), colors = visibleBorderFieldColors(),
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
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(options, key = { it.id }) { r ->
                    val on = m.roleId == r.id
                    val shape = RoundedCornerShape(6.dp)
                    Row(
                        Modifier.fillMaxWidth().clip(shape)
                            .border(BorderStroke(if (on) 2.dp else 1.dp, if (on) Palette.Accent else Palette.Muted.copy(alpha = 0.4f)), shape)
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
private fun ExecutorStep(m: NewAgentModalModel, update: (NewAgentModalModel) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("2. Harness, provider and model", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (!m.executorOpen) {
            Text("Pick a role first: its binding seeds these choices.", color = Palette.Muted, fontSize = 11.sp)
            return@Column
        }
        // The dialog surface is light: rows take its content color instead of the dark panels' Palette.Text.
        ExecutorPicker(m.executor, { update(m.withExecutor(it)) }, tags = NEW_AGENT_EXECUTOR_TAGS, textColor = AlertDialogDefaults.textContentColor)
    }
}

/** The theme's outline is the panel border colour, which vanishes on the dialog surface: give modal fields a visible rest border and an accent focus border. */
@Composable
internal fun visibleBorderFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = Palette.Muted, focusedBorderColor = Palette.Accent,
)
