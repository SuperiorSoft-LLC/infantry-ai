package com.superiorsoftllc.infantry.plugins.official.roles

import com.superiorsoftllc.infantry.ui.shell.gate
import com.superiorsoftllc.infantry.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.NoteScope
import com.superiorsoftllc.infantry.ui.model.TraitLimits
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.protocol.Role
import com.superiorsoftllc.infantry.ui.protocol.RoleBinding
import kotlinx.coroutines.launch

const val ROLE_MANAGER_TAG = "role-manager"
const val ROLE_MANAGER_NEW_TAG = "role-manager-new"
const val ROLE_MANAGER_ROW_TAG_PREFIX = "role-manager-row-"
const val ROLE_MANAGER_SAVE_TAG = "role-manager-save"
const val ROLE_MANAGER_DELETE_TAG = "role-manager-delete"
const val ROLE_MANAGER_DELETE_CONFIRM_TAG = "role-manager-delete-confirm"
const val ROLE_MANAGER_DESCRIPTION_TAG = "role-manager-description"

/**
 * ROLE-EDIT-1: the role manager, a dialog opened from Configuration (kept self-contained so it can move into a
 * Configuration tab later). It lists the daemon's roles, creates one and edits one: name, description, the role notes
 * (the persona every NEW session of the role receives), colour, icon, default provider / model / effort and traits.
 * The project's role notes are shown read-only. Delete removes a custom role (the daemon refuses while a live session
 * has it) or resets a customised built-in to its bundled definition; a pure built-in cannot be deleted.
 *
 * Everything is a [RolesDeps] request (`role_save`, `role_binding_set`, `role_traits_set`, `role_delete`).
 *
 * BLOCKS-LAYOUT L12: drawn as [RoleManagerFrame] (dialog, title, Done, the page shown) and its pieces, which the shell's
 * `infantry.shell.roles` node draws as child blocks.
 */
@Composable
fun RoleManagerDialog(deps: RolesDeps, onDismiss: () -> Unit) {
    RoleManagerFrame(onDismiss) { nav ->
        RoleManagerHint()
        RoleManagerList(deps, nav)
        RoleManagerForm(deps, nav) { draft ->
            RoleFormTitle(draft)
            RoleFormIdentity(draft)
            RoleFormExecutor(draft)
            RoleFormTraits(draft)
            RoleFormNotes(draft)
            RoleFormActions(draft)
        }
    }
}

/**
 * BLOCKS-LAYOUT L12: the page the role manager shows: [editing] null = the list, "" = a new role, else the id being
 * edited; [open] switches it. [column] is the dialog's scrolling column, which the pieces draw straight into.
 */
class RoleManagerNav(val editing: String?, val open: (String?) -> Unit, internal val column: ColumnScope)

/** The role manager's frame: the dialog (title, Done) and the page shown. [content] goes straight into its scrolling column (8dp apart). */
@Composable
fun RoleManagerFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.(RoleManagerNav) -> Unit) {
    // null = the list; "" = a new role; else the id being edited.
    var editing by remember { mutableStateOf<String?>(null) }
    AhqAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ROLE_MANAGER_TAG),
        title = { Text("Roles", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content(RoleManagerNav(editing, { editing = it }, this))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** The line on when changes apply. */
@Composable
fun RoleManagerHint(modifier: Modifier = Modifier) {
    Text(
        "Changes apply to sessions started after you save; a running session keeps what it started with.",
        modifier = modifier, color = Palette.Muted, fontSize = 11.sp,
    )
}

/** The list page (New role, then a row per role); nothing while a role is open. [modifier] goes on New role. */
@Composable
fun RoleManagerList(deps: RolesDeps, nav: RoleManagerNav, modifier: Modifier = Modifier) =
    RoleManagerList(deps.roles.roles, deps.roles.bindings, deps.roles.traits, deps.link.gate("roles.save").enabled, nav, modifier)

/** S2: [RoleManagerList] over the shell context's [roles], their [bindings] and [traits]; [live] enables New role. */
@Composable
fun RoleManagerList(
    roles: List<Role>, bindings: Map<String, RoleBinding>, traits: Map<String, List<String>>, live: Boolean, nav: RoleManagerNav,
    modifier: Modifier = Modifier,
) {
    if (nav.editing != null) return
    Button(onClick = { nav.open("") }, enabled = live, modifier = modifier.testTag(ROLE_MANAGER_NEW_TAG)) { Text("New role") }
    roles.forEach { r ->
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised)
                .clickable { nav.open(r.id) }.padding(8.dp).testTag(ROLE_MANAGER_ROW_TAG_PREFIX + r.id),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.name, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Chip(r.id)
                Chip(if (r.source == "CUSTOM") "custom" else "built-in")
            }
            if (r.description.isNotBlank()) Text(r.description, color = Palette.Muted, fontSize = 11.sp)
            Text("${bindingSummary(bindings[r.id])} · ${traits[r.id].orEmpty().size} traits", color = Palette.Muted, fontSize = 10.sp)
        }
    }
}

/** The form page while a role is open (nothing on the list): [RoleFormFrame] for the role [nav] names. */
@Composable
fun RoleManagerForm(deps: RolesDeps, nav: RoleManagerNav, content: @Composable (RoleFormDraft) -> Unit) {
    val id = nav.editing ?: return
    RoleFormFrame(deps, existing = deps.roles.role(id), onDone = { nav.open(null) }, content)
}

/** The form's fields as typed, from the role (or empty for a new one) when the form opens. */
@Stable
class RoleFormFields(existing: Role?, binding: RoleBinding?, storedTraits: List<String>) {
    var id by mutableStateOf(existing?.id.orEmpty())
    var name by mutableStateOf(existing?.name.orEmpty())
    var description by mutableStateOf(existing?.description.orEmpty())
    var persona by mutableStateOf(existing?.persona.orEmpty())
    var color by mutableStateOf(existing?.color.orEmpty())
    var icon by mutableStateOf(existing?.icon.orEmpty())
    var provider by mutableStateOf(binding?.provider)
    var model by mutableStateOf(binding?.model.orEmpty())
    var effort by mutableStateOf(binding?.effort)
    var traits by mutableStateOf(storedTraits)
    var error by mutableStateOf<String?>(null)
    var confirmDelete by mutableStateOf(false)
}

/**
 * BLOCKS-LAYOUT L12: the open form as its pieces read it: the role ([existing], null for a new one), what it is stored
 * with, the [fields] as typed, and the actions (save, delete, back), which the frame runs.
 */
class RoleFormDraft(
    val deps: RolesDeps,
    val existing: Role?,
    val key: String,
    val binding: RoleBinding?,
    val storedTraits: List<String>,
    val fields: RoleFormFields,
    val onSave: () -> Unit,
    val onDelete: () -> Unit,
    val onDone: () -> Unit,
) {
    val live: Boolean get() = deps.link.live
    // POLICY-1: write controls are named actions gated by the daemon's policy (and any write policy); reads keep live.
    val canWrite: Boolean @Composable get() = deps.link.gate("roles.save").enabled
    /** GAP-AUDIT-1 (g): deleting a role is its own declared action, not the save's. */
    val canDelete: Boolean @Composable get() = deps.link.gate("roles.delete", com.superiorsoftllc.infantry.plugin.api.ActionKind.DESTRUCTIVE).enabled
    val canEdit: Boolean get() = deps.roles.canEdit
}

/**
 * The form's frame: the fields (kept per role), the load of the project's notes for the role, and save / delete. [content]
 * gets the draft and draws straight into the dialog's column; the frame draws nothing of its own.
 */
@Composable
fun RoleFormFrame(deps: RolesDeps, existing: Role?, onDone: () -> Unit, content: @Composable (RoleFormDraft) -> Unit) {
    val scope = rememberCoroutineScope()
    val key = existing?.id ?: "new"
    val binding = existing?.let { deps.roles.bindings[it.id] }
    val storedTraits = existing?.let { deps.roles.traits[it.id] }.orEmpty()
    val f = remember(key) { RoleFormFields(existing, binding, storedTraits) }
    val canEdit = deps.roles.canEdit

    // The project's own notes for this role, read-only here (they are edited in the Memory pane).
    val projectId = deps.session.activeProjectId
    LaunchedEffect(existing?.id, projectId) {
        if (existing != null && projectId != null) deps.notes.load(projectId, NoteScope(NoteScope.ROLE, existing.id))
    }

    val save: () -> Unit = {
        f.error = null
        scope.launch {
            val iconChanged = f.icon.takeIf { it != existing?.icon.orEmpty() }
            val descChanged = f.description.trim().takeIf { canEdit && it != existing?.description.orEmpty() }
            val saved = deps.roles.save(f.id, f.name, f.color, f.persona, iconChanged, descChanged)
            if (saved is CommandResult.Refused) { f.error = saved.reason; return@launch }
            val rid = f.id.trim()
            if (f.provider != binding?.provider || f.model.trim() != binding?.model.orEmpty() || f.effort != binding?.effort) {
                val r = deps.roles.saveBinding(rid, f.provider, f.model, f.effort)
                if (r is CommandResult.Refused) { f.error = r.reason; return@launch }
            }
            if (f.traits != storedTraits) {
                val r = deps.traits.setForRole(rid, f.traits)
                if (r is CommandResult.Refused) { f.error = r.reason; return@launch }
            }
            onDone()
        }
    }
    val delete: () -> Unit = {
        f.confirmDelete = false
        if (existing != null) {
            scope.launch {
                val r = deps.roles.delete(existing.id)
                if (r is CommandResult.Refused) f.error = r.reason else onDone()
            }
        }
    }
    content(RoleFormDraft(deps, existing, key, binding, storedTraits, f, save, delete, onDone))
}

/** The form's title, and the notice when the daemon is too old to edit descriptions or delete. [modifier] goes on the title. */
@Composable
fun RoleFormTitle(draft: RoleFormDraft, modifier: Modifier = Modifier) {
    val existing = draft.existing
    Text(if (existing == null) "New role" else "Edit ${existing.name}", modifier = modifier, color = Palette.Text, fontWeight = FontWeight.SemiBold)
    if (!draft.canEdit) Text("This daemon is older than contract 1.108.0: descriptions and deleting roles are unavailable.", color = Palette.Muted, fontSize = 10.sp)
}

/** Id (a new role only), name, description, role notes, colour and icon. [modifier] goes on the name field. */
@Composable
fun RoleFormIdentity(draft: RoleFormDraft, modifier: Modifier = Modifier) {
    val f = draft.fields
    if (draft.existing == null) {
        OutlinedTextField(f.id, { f.id = it.lowercase() }, label = { Text("Role id (a-z, 0-9, -)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(ROLE_EDITOR_ID_TAG))
    }
    OutlinedTextField(f.name, { f.name = it }, label = { Text("Name") }, singleLine = true, modifier = modifier.fillMaxWidth().testTag(ROLE_EDITOR_NAME_TAG))
    OutlinedTextField(
        f.description, { f.description = it.replace("\n", " ") }, label = { Text("Description (one line, not sent to sessions)") },
        singleLine = true, enabled = draft.canEdit, modifier = Modifier.fillMaxWidth().testTag(ROLE_MANAGER_DESCRIPTION_TAG),
    )
    OutlinedTextField(
        f.persona, { f.persona = it }, label = { Text("Role notes: every new session of this role receives these at start") }, minLines = 4, maxLines = 14,
        modifier = Modifier.fillMaxWidth().testTag(ROLE_EDITOR_PERSONA_TAG), textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp),
    )
    OutlinedTextField(f.color, { f.color = it }, label = { Text("Colour #rrggbb (empty = keep / generated)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    RoleIconPicker(roleId = f.id.trim(), selected = f.icon, enabled = draft.deps.roles.iconsSupported, onPick = { f.icon = it })
}

/** Default provider, model and effort. [modifier] goes on the heading. */
@Composable
fun RoleFormExecutor(draft: RoleFormDraft, modifier: Modifier = Modifier) {
    val f = draft.fields
    Text("Default provider and model", modifier = modifier, color = Palette.Muted, fontSize = 10.sp)
    ProfileExecutorField(draft.deps.executor, draft.key, f.provider, f.model) { p, m -> f.provider = p; f.model = m }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Effort", color = Palette.Muted, fontSize = 10.sp)
        (listOf<String?>(null) + ROLE_EFFORT_LEVELS).forEach { level ->
            TextButton(onClick = { f.effort = level }) {
                Text(level ?: "default", fontSize = 11.sp, fontWeight = if (f.effort == level) FontWeight.Bold else FontWeight.Normal, color = if (f.effort == level) Palette.Accent else Palette.Muted)
            }
        }
    }
}

/** The role's traits, a checkbox each (up to the limit). [modifier] goes on the heading. */
@Composable
fun RoleFormTraits(draft: RoleFormDraft, modifier: Modifier = Modifier) {
    val f = draft.fields
    val traits = draft.deps.traits.all
    Text("Traits (up to ${TraitLimits.PER_ROLE_MAX})", modifier = modifier, color = Palette.Muted, fontSize = 10.sp)
    if (traits.isEmpty()) Text("No traits defined yet.", color = Palette.Muted, fontSize = 11.sp)
    traits.forEach { t ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = t.id in f.traits,
                onCheckedChange = { on -> f.traits = if (on) (f.traits + t.id).distinct().take(TraitLimits.PER_ROLE_MAX) else f.traits - t.id },
            )
            Text(t.name, fontSize = 12.sp)
        }
    }
}

/** The active project's notes for the role, read-only (an existing role only; nothing for a new one). [modifier] goes on the heading. */
@Composable
fun RoleFormNotes(draft: RoleFormDraft, modifier: Modifier = Modifier) {
    val existing = draft.existing ?: return
    val deps = draft.deps
    val notes = deps.session.activeProjectId?.let { deps.notes.notes(NoteScope(NoteScope.ROLE, existing.id).key(it)) }.orEmpty()
    Text("This project's notes for the role (read-only; edit them in Memory)", modifier = modifier, color = Palette.Muted, fontSize = 10.sp)
    if (notes.isEmpty()) Text("None.", color = Palette.Muted, fontSize = 11.sp)
    notes.forEach { n -> Text("• ${n.name}${if (n.description.isNotBlank()) ": ${n.description}" else ""}", fontSize = 11.sp, color = Palette.Text) }
}

/** The last refusal, Delete (a custom role), Back and Save, and Delete's confirmation. [modifier] goes on the button row. */
@Composable
fun RoleFormActions(draft: RoleFormDraft, modifier: Modifier = Modifier) {
    val f = draft.fields
    val existing = draft.existing
    f.error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        if (existing != null && existing.source == "CUSTOM") {
            TextButton(onClick = { f.confirmDelete = true }, enabled = draft.canDelete && draft.canEdit, modifier = Modifier.testTag(ROLE_MANAGER_DELETE_TAG)) {
                Text("Delete", color = Palette.Danger, fontSize = 12.sp)
            }
        }
        TextButton(onClick = draft.onDone) { Text("Back", fontSize = 12.sp) }
        Button(
            enabled = draft.canWrite && f.id.isNotBlank() && f.name.isNotBlank(),
            modifier = Modifier.testTag(ROLE_MANAGER_SAVE_TAG),
            onClick = draft.onSave,
        ) { Text("Save role", fontSize = 12.sp) }
    }

    if (f.confirmDelete && existing != null) {
        AhqAlertDialog(
            onDismissRequest = { f.confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            text = {
                Text(
                    "A custom role is removed; a customised built-in role is reset to its bundled definition. " +
                        "The daemon refuses while a session of this role is live.",
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                TextButton(modifier = Modifier.testTag(ROLE_MANAGER_DELETE_CONFIRM_TAG), onClick = draft.onDelete) { Text("Delete", color = Palette.Danger) }
            },
            dismissButton = { TextButton(onClick = { f.confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
