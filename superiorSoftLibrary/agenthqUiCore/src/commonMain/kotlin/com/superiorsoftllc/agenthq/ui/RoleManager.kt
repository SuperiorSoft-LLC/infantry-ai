package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.NoteScope
import com.superiorsoftllc.agenthq.ui.model.TraitLimits
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.Role
import kotlinx.coroutines.launch

const val ROLE_MANAGER_TAG = "role-manager"
const val ROLE_MANAGER_NEW_TAG = "role-manager-new"
const val ROLE_MANAGER_ROW_TAG_PREFIX = "role-manager-row-"
const val ROLE_MANAGER_SAVE_TAG = "role-manager-save"
const val ROLE_MANAGER_DELETE_TAG = "role-manager-delete"
const val ROLE_MANAGER_DELETE_CONFIRM_TAG = "role-manager-delete-confirm"
const val ROLE_MANAGER_DESCRIPTION_TAG = "role-manager-description"
const val ROLE_MANAGER_OPEN_TAG = "role-manager-open"

/**
 * ROLE-EDIT-1: the role manager, a dialog opened from Configuration (kept self-contained so it can move into a
 * Configuration tab later). It lists the daemon's roles, creates one and edits one: name, description, the role notes
 * (the persona every NEW session of the role receives), colour, icon, default provider / model / effort and traits.
 * The project's role notes are shown read-only. Delete removes a custom role (the daemon refuses while a live session
 * has it) or resets a customised built-in to its bundled definition; a pure built-in cannot be deleted.
 *
 * Everything is an existing [Controller] command (`role_save`, `role_binding_set`, `role_traits_set`) plus `role_delete`.
 */
@Composable
fun RoleManagerDialog(state: AppState, controller: Controller, onDismiss: () -> Unit) {
    // null = the list; "" = a new role; else the id being edited.
    var editing by remember { mutableStateOf<String?>(null) }
    AhqAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ROLE_MANAGER_TAG),
        title = { Text("Roles", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Changes apply to sessions started after you save; a running session keeps what it started with.",
                    color = Palette.Muted, fontSize = 11.sp,
                )
                val id = editing
                if (id == null) {
                    RoleList(state, onNew = { editing = "" }, onEdit = { editing = it })
                } else {
                    RoleForm(state, controller, existing = state.roles.firstOrNull { it.id == id }, onDone = { editing = null })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun RoleList(state: AppState, onNew: () -> Unit, onEdit: (String) -> Unit) {
    Button(onClick = onNew, enabled = state.connection.isLive, modifier = Modifier.testTag(ROLE_MANAGER_NEW_TAG)) { Text("New role") }
    state.roles.forEach { r ->
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised)
                .clickable { onEdit(r.id) }.padding(8.dp).testTag(ROLE_MANAGER_ROW_TAG_PREFIX + r.id),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.name, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Chip(r.id)
                Chip(if (r.source == "CUSTOM") "custom" else "built-in")
            }
            if (r.description.isNotBlank()) Text(r.description, color = Palette.Muted, fontSize = 11.sp)
            Text("${bindingSummary(state.roleBindings[r.id])} · ${state.roleTraits[r.id].orEmpty().size} traits", color = Palette.Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun RoleForm(state: AppState, controller: Controller, existing: Role?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val key = existing?.id ?: "new"
    val binding = existing?.let { state.roleBindings[it.id] }
    val storedTraits = existing?.let { state.roleTraits[it.id] }.orEmpty()
    var id by remember(key) { mutableStateOf(existing?.id.orEmpty()) }
    var name by remember(key) { mutableStateOf(existing?.name.orEmpty()) }
    var description by remember(key) { mutableStateOf(existing?.description.orEmpty()) }
    var persona by remember(key) { mutableStateOf(existing?.persona.orEmpty()) }
    var color by remember(key) { mutableStateOf(existing?.color.orEmpty()) }
    var icon by remember(key) { mutableStateOf(existing?.icon.orEmpty()) }
    var provider by remember(key) { mutableStateOf(binding?.provider) }
    var model by remember(key) { mutableStateOf(binding?.model.orEmpty()) }
    var effort by remember(key) { mutableStateOf(binding?.effort) }
    var traits by remember(key) { mutableStateOf(storedTraits) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(key) { mutableStateOf(false) }
    val live = state.connection.isLive
    val canEdit = state.connection.sendsRoleEdit

    // The project's own notes for this role, read-only here (they are edited in the Memory pane).
    val projectId = state.activeProjectId
    LaunchedEffect(existing?.id, projectId) {
        if (existing != null && projectId != null) controller.loadMemory(projectId, NoteScope(NoteScope.ROLE, existing.id))
    }

    Text(if (existing == null) "New role" else "Edit ${existing.name}", color = Palette.Text, fontWeight = FontWeight.SemiBold)
    if (!canEdit) Text("This daemon is older than contract 1.108.0: descriptions and deleting roles are unavailable.", color = Palette.Muted, fontSize = 10.sp)
    if (existing == null) {
        OutlinedTextField(id, { id = it.lowercase() }, label = { Text("Role id (a-z, 0-9, -)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(ROLE_EDITOR_ID_TAG))
    }
    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(ROLE_EDITOR_NAME_TAG))
    OutlinedTextField(
        description, { description = it.replace("\n", " ") }, label = { Text("Description (one line, not sent to sessions)") },
        singleLine = true, enabled = canEdit, modifier = Modifier.fillMaxWidth().testTag(ROLE_MANAGER_DESCRIPTION_TAG),
    )
    OutlinedTextField(
        persona, { persona = it }, label = { Text("Role notes: every new session of this role receives these at start") }, minLines = 4, maxLines = 14,
        modifier = Modifier.fillMaxWidth().testTag(ROLE_EDITOR_PERSONA_TAG), textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp),
    )
    OutlinedTextField(color, { color = it }, label = { Text("Colour #rrggbb (empty = keep / generated)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    RoleIconPicker(roleId = id.trim(), selected = icon, enabled = state.connection.sendsRoleIcon, onPick = { icon = it })

    Text("Default provider and model", color = Palette.Muted, fontSize = 10.sp)
    ProfileExecutorField(state, key, provider, model) { p, m -> provider = p; model = m }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Effort", color = Palette.Muted, fontSize = 10.sp)
        (listOf<String?>(null) + ROLE_EFFORT_LEVELS).forEach { level ->
            TextButton(onClick = { effort = level }) {
                Text(level ?: "default", fontSize = 11.sp, fontWeight = if (effort == level) FontWeight.Bold else FontWeight.Normal, color = if (effort == level) Palette.Accent else Palette.Muted)
            }
        }
    }

    Text("Traits (up to ${TraitLimits.PER_ROLE_MAX})", color = Palette.Muted, fontSize = 10.sp)
    if (state.traits.isEmpty()) Text("No traits defined yet.", color = Palette.Muted, fontSize = 11.sp)
    state.traits.forEach { t ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = t.id in traits,
                onCheckedChange = { on -> traits = if (on) (traits + t.id).distinct().take(TraitLimits.PER_ROLE_MAX) else traits - t.id },
            )
            Text(t.name, fontSize = 12.sp)
        }
    }

    if (existing != null) {
        val notes = projectId?.let { state.memoryNotes[NoteScope(NoteScope.ROLE, existing.id).key(it)] }.orEmpty()
        Text("This project's notes for the role (read-only; edit them in Memory)", color = Palette.Muted, fontSize = 10.sp)
        if (notes.isEmpty()) Text("None.", color = Palette.Muted, fontSize = 11.sp)
        notes.forEach { n -> Text("• ${n.name}${if (n.description.isNotBlank()) ": ${n.description}" else ""}", fontSize = 11.sp, color = Palette.Text) }
    }

    error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        if (existing != null && existing.source == "CUSTOM") {
            TextButton(onClick = { confirmDelete = true }, enabled = live && canEdit, modifier = Modifier.testTag(ROLE_MANAGER_DELETE_TAG)) {
                Text("Delete", color = Palette.Danger, fontSize = 12.sp)
            }
        }
        TextButton(onClick = onDone) { Text("Back", fontSize = 12.sp) }
        Button(
            enabled = live && id.isNotBlank() && name.isNotBlank(),
            modifier = Modifier.testTag(ROLE_MANAGER_SAVE_TAG),
            onClick = {
                error = null
                scope.launch {
                    val iconChanged = icon.takeIf { it != existing?.icon.orEmpty() }
                    val descChanged = description.trim().takeIf { canEdit && it != existing?.description.orEmpty() }
                    val saved = controller.saveRole(id, name, color, persona, iconChanged, descChanged)
                    if (saved is CommandResult.Refused) { error = saved.reason; return@launch }
                    val rid = id.trim()
                    if (provider != binding?.provider || model.trim() != binding?.model.orEmpty() || effort != binding?.effort) {
                        val r = controller.saveRoleBinding(rid, provider, model, effort)
                        if (r is CommandResult.Refused) { error = r.reason; return@launch }
                    }
                    if (traits != storedTraits) {
                        val r = controller.setRoleTraits(rid, traits)
                        if (r is CommandResult.Refused) { error = r.reason; return@launch }
                    }
                    onDone()
                }
            },
        ) { Text("Save role", fontSize = 12.sp) }
    }

    if (confirmDelete && existing != null) {
        AhqAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            text = {
                Text(
                    "A custom role is removed; a customised built-in role is reset to its bundled definition. " +
                        "The daemon refuses while a session of this role is live.",
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag(ROLE_MANAGER_DELETE_CONFIRM_TAG),
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            val r = controller.deleteRole(existing.id)
                            if (r is CommandResult.Refused) error = r.reason else onDone()
                        }
                    },
                ) { Text("Delete", color = Palette.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
