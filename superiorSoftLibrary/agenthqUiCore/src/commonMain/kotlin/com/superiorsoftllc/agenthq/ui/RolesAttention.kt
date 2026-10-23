package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.ExecutorPickerModel
import com.superiorsoftllc.agenthq.ui.model.PickedModel
import com.superiorsoftllc.agenthq.ui.model.RoleIcons
import com.superiorsoftllc.agenthq.ui.model.stateLabel
import com.superiorsoftllc.agenthq.ui.model.watchFlagOf
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.AgentProfile
import com.superiorsoftllc.agenthq.ui.protocol.Role
import com.superiorsoftllc.agenthq.ui.protocol.RoleBinding
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Role picker + Role detail (contract 1.12.0 `roles`, with the existing `agents` / `providers` frames).
 *
 * Choose a role from the daemon's catalog, see its persona and the agent profiles bound to it, create a
 * profile within the role (its instructions start as the role's persona), and start a session from a
 * profile. Everything shown comes from [AppState]; every action is a [Controller] command.
 *
 * @param onSessionStarted called after a start was sent, so the window can show the conversation.
 */
@Composable
fun RolesPane(state: AppState, controller: Controller, onSessionStarted: () -> Unit, onOpenAgent: (String) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    // ROLE-EDIT-1: the role whose editor is open; hoisted so a row's "Edit role" can open it from the list.
    var editingId by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(230.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(8.dp)) {
            Text("ROLES", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(4.dp))
            if (state.roles.isEmpty()) {
                Text(if (state.connection.isLive) "The daemon sent no roles." else "Connect to a daemon to load its roles.", color = Palette.Muted, fontSize = 11.sp)
            }
            state.roles.forEach { r ->
                val count = state.profilesFor(r.id).size
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(if (r.id == selected && !creating) Palette.Raised else Color.Transparent)
                        .clickable { selected = r.id; creating = false }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AgentAvatar(r.id, r.id, 24, r.color)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        // ROLE-ICON-1: the role's icon beside its title.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RoleIcon(r.id, r.icon, RoleIconSite.ROW, size = 13)
                            Spacer(Modifier.width(4.dp))
                            Text(r.name, color = Palette.Text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.testTag(ROLE_ROW_NAME_TAG_PREFIX + r.id))
                        }
                        Text(r.id, color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp, maxLines = 1)
                    }
                    if (r.source == "CUSTOM") Chip("custom", Palette.Info)
                    if (count > 0) { Spacer(Modifier.width(4.dp)); Chip("$count", Palette.Accent) }
                    RoleRowMenu(r, live = state.connection.isLive, onEdit = { selected = r.id; creating = false; editingId = r.id })
                }
            }
            TextButton(onClick = { creating = true }, enabled = state.connection.isLive) { Text("+ New role", fontSize = 12.sp) }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Border))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val role = state.role(selected)
            when {
                creating -> RoleEditor(null, controller, iconsSupported = state.connection.sendsRoleIcon, onSaved = { id -> creating = false; selected = id })
                role != null -> RoleDetail(
                    role, state, controller, onSessionStarted, onOpenAgent,
                    editing = editingId == role.id,
                    onEditingChange = { open -> editingId = if (open) role.id else null },
                )
                else -> Column(Modifier.padding(16.dp)) {
                    Text("Choose a role.", color = Palette.Text)
                    Text("A role's profiles bind it to a provider and model; a session starts from a profile.", color = Palette.Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun RoleDetail(
    role: Role,
    state: AppState,
    controller: Controller,
    onSessionStarted: () -> Unit,
    onOpenAgent: (String) -> Unit,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
) {
    // FORM-MODAL-2: the profile being created (id null) or edited in the shared modal; null = closed.
    var profileDraft by remember(role.id) { mutableStateOf<AgentProfile?>(null) }
    val live = state.connection.isLive
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentAvatar(role.id, role.id, 36, role.color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoleIcon(role.id, role.icon, RoleIconSite.DETAIL, size = 16, tint = Palette.Text)
                    Spacer(Modifier.width(6.dp))
                    Text(role.name, color = Palette.Text, fontWeight = FontWeight.SemiBold)
                }
                Text(role.id, color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp)
            }
            Chip(role.source.lowercase().replace('_', ' '), if (role.source == "CUSTOM") Palette.Info else Palette.Muted)
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { onEditingChange(!editing) }, enabled = state.connection.isLive) { Text(if (editing) "Close editor" else "Edit role") }
        }
        if (editing) RoleEditor(role, controller, iconsSupported = state.connection.sendsRoleIcon, onSaved = { onEditingChange(false) })
        Text("Instructions (persona)", color = Palette.Muted, fontSize = 10.sp)
        Text(role.persona.ifBlank { "(no persona)" }, color = if (role.persona.isBlank()) Palette.Muted else Palette.Text, fontFamily = Mono, fontSize = 11.sp)

        HorizontalDivider(color = Palette.Border)
        RoleBindingEditor(role, state, controller)

        HorizontalDivider(color = Palette.Border)
        RoleTraitsEditor(role, state, controller)

        HorizontalDivider(color = Palette.Border)
        val profiles = state.profilesFor(role.id)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Profiles in this role (${profiles.size})", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            IconAction(
                AhqIcons.Add, "New profile in ${role.name}",
                onClick = { profileDraft = AgentProfile(name = "", role = role.id, instructions = role.persona) },
                enabled = live, style = IconActionStyle.TEXT, modifier = Modifier.testTag(PROFILE_ADD_BUTTON_TAG),
            )
        }
        if (profiles.isEmpty()) Text("No profile yet: create one to start a session as this role.", color = Palette.Muted, fontSize = 11.sp)
        profiles.forEach { p -> ProfileCard(p, state, controller, onSessionStarted, onEdit = { profileDraft = p }) }

        val sessions = state.orderedAgents.filter { it.role == role.id }
        if (sessions.isNotEmpty()) {
            HorizontalDivider(color = Palette.Border)
            Text("Sessions of this role", color = Palette.Muted, fontSize = 10.sp)
            sessions.forEach { a ->
                Row(Modifier.fillMaxWidth().clickable { onOpenAgent(a.id) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.session.worktreeBranch ?: a.id.take(8), color = Palette.Text, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Chip(a.stateText, stateColor(a.session.state, false))
                }
            }
        }
    }
    profileDraft?.let { d -> ProfileEditorModal(role, d, state, controller, onClose = { profileDraft = null }) }
    }
}

const val ROLE_EDITOR_ID_TAG = "role-editor-id"
const val ROLE_EDITOR_NAME_TAG = "role-editor-name"
const val ROLE_EDITOR_SAVE_TAG = "role-editor-save"
const val ROLE_EDITOR_PERSONA_TAG = "role-editor-persona"
/** ROLE-EDIT-1: a role row's title, its "⋯" button and the menu's "Edit role" item, each suffixed with the role id. */
const val ROLE_ROW_NAME_TAG_PREFIX = "role-row-name:"
const val ROLE_ROW_MENU_TAG_PREFIX = "role-row-menu:"
const val ROLE_ROW_EDIT_TAG_PREFIX = "role-row-edit:"
const val ROLE_BINDING_CURRENT_TAG = "role-binding-current"
const val ROLE_BINDING_SAVE_TAG = "role-binding-save"
const val ROLE_BINDING_MODEL_TAG_PREFIX = "role-binding-model:"
const val ROLE_BINDING_PROVIDER_TAG_PREFIX = "role-binding-provider:"
const val ROLE_BINDING_EFFORT_TAG_PREFIX = "role-binding-effort:"

/** The role editor's published tags (models keep their "<harness>|<id>" form), so its tests see the same nodes. */
private val ROLE_BINDING_EXECUTOR_TAGS = ExecutorPickerTags(
    harness = { ROLE_BINDING_PROVIDER_TAG_PREFIX + it },
    providerSearch = "role-binding-provider-search",
    providerAll = "role-binding-provider-all",
    provider = { "role-binding-group:$it" },
    modelSearch = "role-binding-model-search",
    model = { harness, id -> "$ROLE_BINDING_MODEL_TAG_PREFIX$harness|$id" },
    freeText = "role-binding-model-freetext",
    daemonDefault = "role-binding-provider-default",
)

/** The reasoning-effort levels a binding may pin (contract 1.40.0); null = the harness default. */
val ROLE_EFFORT_LEVELS = listOf("low", "medium", "high", "xhigh", "max")

/** One line saying what a binding pins, for the role detail and the New agent box. */
fun bindingSummary(b: RoleBinding?): String =
    if (b == null || (b.provider == null && b.model == null && b.effort == null)) "daemon default"
    else listOfNotNull(b.provider, b.model, b.effort?.let { "effort $it" }).joinToString(" / ")

/**
 * ROLE-1c: what the ROLE runs on. Provider chips come from the live `providers` frame (code plugins and JSON
 * descriptors alike), models from the live `models` catalogue; Save writes the role's binding, never a profile.
 * Changing it later changes future sessions only. A session already running keeps what it started with.
 */
@Composable
private fun RoleBindingEditor(role: Role, state: AppState, controller: Controller) {
    val actionScope = rememberCoroutineScope()
    val stored = state.roleBindings[role.id]
    val storedPick = stored?.let { PickedModel.fromBinding(it) }
    // ROLE-FIRST S7: harness + model go through the shared ExecutorPicker. The frames can land after the editor
    // opens: fold them in, re-seeding from the stored binding until the user has touched the picker, then from
    // the user's own choice, so a late catalog never wipes an edit.
    var touched by remember(role.id, stored) { mutableStateOf(false) }
    var draft by remember(role.id, stored) { mutableStateOf(ExecutorPickerModel.seeded(state.providers, state.modelCatalog, storedPick)) }
    val executor = if (draft.harnesses != state.providers || draft.catalog != state.modelCatalog) {
        ExecutorPickerModel.seeded(state.providers, state.modelCatalog, if (touched) draft.picked else storedPick)
    } else draft
    var effort by remember(role.id, stored) { mutableStateOf(stored?.effort) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Runs on (new sessions of this role)", color = Palette.Muted, fontSize = 10.sp)
        Text("Currently: ${bindingSummary(stored)}", color = Palette.Text, fontSize = 12.sp, modifier = Modifier.testTag(ROLE_BINDING_CURRENT_TAG))
        ExecutorPicker(
            executor, { draft = it; touched = true }, tags = ROLE_BINDING_EXECUTOR_TAGS,
            onDaemonDefault = { draft = ExecutorPickerModel(state.providers, state.modelCatalog); touched = true },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Effort", color = Palette.Muted, fontSize = 11.sp)
            Chip("default", if (effort == null) Palette.Accent else Palette.Muted, Modifier.clickable { effort = null })
            ROLE_EFFORT_LEVELS.forEach { level ->
                Chip(level, if (effort == level) Palette.Accent else Palette.Muted, Modifier.testTag(ROLE_BINDING_EFFORT_TAG_PREFIX + level).clickable { effort = level })
            }
        }
        Button(
            enabled = state.connection.isLive,
            modifier = Modifier.testTag(ROLE_BINDING_SAVE_TAG),
            onClick = {
                // Untouched = the stored harness/model exactly, even when the frames have not landed or no longer list it.
                val saved = if (touched) executor.picked else storedPick
                actionScope.launch { controller.saveRoleBinding(role.id, saved?.provider, saved?.model, effort) }
            },
        ) { Text("Save binding") }
    }
}

@Composable
private fun ProfileCard(p: AgentProfile, state: AppState, controller: Controller, onSessionStarted: () -> Unit, onEdit: () -> Unit) {
    val actionScope = rememberCoroutineScope()
    var first by remember(p.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, color = Palette.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip(p.provider ?: state.providers.firstOrNull { it.default }?.id?.let { "$it (default)" } ?: "default provider")
            Spacer(Modifier.width(4.dp))
            Chip(p.model ?: "default model")
            TextButton(onClick = onEdit, enabled = state.connection.isLive, modifier = Modifier.testTag(PROFILE_EDIT_TAG_PREFIX + (p.id ?: p.name))) {
                Text("Edit", fontSize = 11.sp)
            }
            TextButton(onClick = { p.id?.let { id -> actionScope.launch { controller.deleteProfile(id) } } }, enabled = state.connection.isLive) {
                Text("Delete", color = Palette.Danger, fontSize = 11.sp)
            }
        }
        p.instructions?.let { Text(it, color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp, maxLines = 3) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                first, { first = it }, label = { Text("First message") }, singleLine = true,
                modifier = Modifier.weight(1f), textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp),
            )
            Spacer(Modifier.width(6.dp))
            Button(
                enabled = state.connection.isLive && first.isNotBlank() && p.id != null,
                onClick = {
                    actionScope.launch {
                        if (controller.spawnAs(p.id!!, first.trim()) is CommandResult.Sent) { first = ""; onSessionStarted() }
                    }
                },
            ) { Text("Start session") }
        }
    }
}

const val PROFILE_ADD_BUTTON_TAG = "profile-add"
const val PROFILE_DIALOG_TAG = "profile-dialog"
const val PROFILE_NAME_TAG = "profile-name-input"
const val PROFILE_INSTRUCTIONS_TAG = "profile-instructions-input"
const val PROFILE_SAVE_TAG = "profile-save"
const val PROFILE_CANCEL_TAG = "profile-cancel"
/** A profile card's Edit button, suffixed with the profile id. */
const val PROFILE_EDIT_TAG_PREFIX = "profile-edit-"

/**
 * FORM-MODAL-2: create ([initial] has no id) or edit a profile of [role] in the shared [EditorModal], never in a form
 * under the growing profile list. Provider from the daemon's list, model optional, instructions seeded from the persona
 * on create. Save keeps the id (and effort / project) so `agent_save` updates in place; it closes on Sent, a refusal
 * stays in the modal. Cancel discards and sends nothing.
 */
@Composable
private fun ProfileEditorModal(role: Role, initial: AgentProfile, state: AppState, controller: Controller, onClose: () -> Unit) {
    val actionScope = rememberCoroutineScope()
    val key = initial.id ?: role.id
    var name by remember(key) { mutableStateOf(initial.name) }
    var provider by remember(key) { mutableStateOf(initial.provider) }
    var model by remember(key) { mutableStateOf(initial.model.orEmpty()) }
    var instructions by remember(key) { mutableStateOf(initial.instructions.orEmpty()) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    val live = state.connection.isLive
    EditorModal(
        title = if (initial.id == null) "New profile in ${role.name}" else "Edit profile",
        onSave = {
            error = null
            actionScope.launch {
                val profile = initial.copy(
                    name = name.trim(), role = role.id,
                    provider = provider, model = model.trim().ifEmpty { null },
                    instructions = instructions.trim().ifEmpty { null },
                    projectId = initial.projectId ?: state.activeProjectId,
                )
                when (val r = controller.saveProfile(profile)) {
                    is CommandResult.Sent -> onClose()
                    is CommandResult.Refused -> error = r.reason
                    is CommandResult.Queued -> Unit
                }
            }
        },
        onCancel = onClose,
        modifier = Modifier.testTag(PROFILE_DIALOG_TAG),
        saveEnabled = live && name.isNotBlank(),
        error = error ?: state.agentError,
        saveLabel = "Save profile",
        saveTag = PROFILE_SAVE_TAG, cancelTag = PROFILE_CANCEL_TAG,
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(PROFILE_NAME_TAG))
        ProfileExecutorField(state, key, provider, model) { p, m -> provider = p; model = m }
        OutlinedTextField(
            instructions, { instructions = it }, label = { Text("Instructions (start of every first message)") },
            minLines = 3, maxLines = 10, modifier = Modifier.fillMaxWidth().testTag(PROFILE_INSTRUCTIONS_TAG),
            textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp),
        )
    }
}

/**
 * ROLE-EDIT-1: a role row's "⋯" menu. "Edit role" selects the role and opens its editor (title + instructions); saving
 * is the existing `role_save`, keyed by the unchanged id. Disabled while disconnected, like every command.
 */
@Composable
private fun RoleRowMenu(r: Role, live: Boolean, onEdit: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.padding(start = 4.dp).size(width = 22.dp, height = 18.dp).clip(RoundedCornerShape(4.dp))
                .clickable(enabled = live, onClickLabel = "Role actions") { menuOpen = true }
                .semantics { contentDescription = "Role actions" }
                .testTag(ROLE_ROW_MENU_TAG_PREFIX + r.id),
            contentAlignment = Alignment.Center,
        ) { Text("⋯", color = if (live) Palette.Muted else Palette.Border, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.background(Palette.Raised)) {
            DropdownMenuItem(
                text = { Text("Edit role", color = Palette.Text, fontSize = 12.sp) },
                onClick = { menuOpen = false; onEdit() },
                modifier = Modifier.testTag(ROLE_ROW_EDIT_TAG_PREFIX + r.id),
            )
        }
    }
}

/** Create a role ([existing] null) or edit one; saving answers with the role pushed back to every window. */
@Composable
private fun RoleEditor(existing: Role?, controller: Controller, iconsSupported: Boolean, onSaved: (String) -> Unit) {
    val actionScope = rememberCoroutineScope()
    var id by remember(existing?.id) { mutableStateOf(existing?.id.orEmpty()) }
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var color by remember(existing?.id) { mutableStateOf(existing?.color.orEmpty()) }
    var persona by remember(existing?.id) { mutableStateOf(existing?.persona.orEmpty()) }
    // ROLE-ICON-1: the chosen icon key; "" = the role kind's default. Saved only when the owner changed it, so an edit
    // of the title alone never rewrites the icon (null on the wire = keep).
    var icon by remember(existing?.id) { mutableStateOf(existing?.icon.orEmpty()) }
    Column(Modifier.fillMaxWidth().padding(if (existing == null) 14.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (existing == null) "New role" else "Edit ${existing.name}", color = Palette.Text, fontWeight = FontWeight.SemiBold)
        if (existing == null) {
            OutlinedTextField(id, { id = it.lowercase() }, label = { Text("Role id (a-z, 0-9, -)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(ROLE_EDITOR_ID_TAG))
        }
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(ROLE_EDITOR_NAME_TAG))
        OutlinedTextField(color, { color = it }, label = { Text("Colour #rrggbb (empty = keep / generated)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            persona, { persona = it }, label = { Text("Instructions (persona): applies to sessions started after you save") }, minLines = 4, maxLines = 14,
            modifier = Modifier.fillMaxWidth().testTag(ROLE_EDITOR_PERSONA_TAG), textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp),
        )
        RoleIconPicker(roleId = id.trim(), selected = icon, enabled = iconsSupported, onPick = { icon = it })
        Button(
            modifier = Modifier.testTag(ROLE_EDITOR_SAVE_TAG),
            enabled = id.isNotBlank() && name.isNotBlank(),
            onClick = {
                val changed = icon.takeIf { it != existing?.icon.orEmpty() }
                actionScope.launch { if (controller.saveRole(id, name, color, persona, changed) is CommandResult.Sent) onSaved(id.trim()) }
            },
        ) { Text("Save role") }
        Text("The daemon validates it; a refusal appears in the notice strip with the reason.", color = Palette.Muted, fontSize = 10.sp)
    }
}

/** ROLE-ICON-1: one option of the Edit role icon picker, suffixed with its key (`default` for the role kind's default). */
const val ROLE_EDITOR_ICON_TAG_PREFIX = "role-editor-icon:"
const val ROLE_EDITOR_ICON_DEFAULT = "default"

/**
 * ROLE-ICON-1: the Edit role icon picker. "Default" (key "") paints the role kind's own icon; every other option is a key
 * of [RoleIcons.KEYS]. Disabled against a daemon below contract 1.93.0, which would refuse a save naming an icon.
 */
@Composable
private fun RoleIconPicker(roleId: String, selected: String, enabled: Boolean, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (enabled) "Icon" else "Icon (this daemon is older than contract 1.93.0 and cannot store one)",
            color = Palette.Muted, fontSize = 10.sp,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (listOf("") + RoleIcons.KEYS).forEach { key ->
                val on = key == selected
                val shape = RoundedCornerShape(6.dp)
                val tag = ROLE_EDITOR_ICON_TAG_PREFIX + key.ifEmpty { ROLE_EDITOR_ICON_DEFAULT }
                Box(
                    Modifier.size(30.dp).clip(shape)
                        .border(if (on) 2.dp else 1.dp, if (on) Palette.Accent else Palette.Border, shape)
                        .clickable(enabled = enabled) { onPick(key) }
                        .semantics { contentDescription = if (key.isEmpty()) "default icon" else "$key icon" }
                        .testTag(tag),
                    contentAlignment = Alignment.Center,
                ) {
                    // The default option previews what "default" means for THIS role.
                    RoleIcon(roleId, key.ifEmpty { null }, "editor", size = 16, tint = if (on) Palette.Accent else Palette.Text)
                }
            }
        }
    }
}

/**
 * Attention (contract 1.9.0): the durable record of what needs a human -- stalled or overdue unattended
 * runs, tripped triggers, conversation ceilings -- newest first, each acknowledgeable from here.
 */
@Composable
internal fun AttentionPane(state: AppState, controller: Controller) {
    val actionScope = rememberCoroutineScope()
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(state.attention.size) { now = Clock.System.now().toEpochMilliseconds() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ASK-1: the owner questions (open, answered, auto-picked, cancelled) come first; an auto-pick can be overridden.
        if (state.questions.isNotEmpty()) {
            QuestionHistory(
                state.questionsNewestFirst, live = state.connection.isLive,
                onOverride = { answer -> actionScope.launch { controller.answerQuestion(answer) } },
                onShow = controller::showQuestions,
            )
        }
        WatchSection(state, now)
        if (state.attention.isEmpty()) {
            if (state.questions.isNotEmpty()) return
            Text("Nothing needs your attention.", color = Palette.Text)
            Text(
                "Unattended runs that stall or pass their deadline, tripped triggers and conversation ceilings are recorded here, durably.",
                color = Palette.Muted, fontSize = 11.sp,
            )
            return
        }
        state.attentionNewestFirst.forEach { item ->
            val open = item.ackedAt == null
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(item.kind.replace('_', ' '), if (open) Palette.Accent else Palette.Muted)
                    Spacer(Modifier.width(6.dp))
                    Text(ago(item.at, now) + " ago", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    if (open) {
                        TextButton(onClick = { actionScope.launch { controller.ackAttention(item.id) } }, enabled = state.connection.isLive) {
                            Text("Acknowledge", fontSize = 11.sp)
                        }
                    } else {
                        Text("acknowledged", color = Palette.Muted, fontSize = 10.sp)
                    }
                }
                Text(item.detail, color = if (open) Palette.Text else Palette.Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    item.ticketId?.let { Chip("ticket ${it.take(8)}") }
                    item.podId?.let { Chip("pod ${it.take(8)}") }
                    item.triggerId?.let { Chip("trigger ${it.take(12)}") }
                    item.sessionId?.takeIf { it in state.agents }?.let { sid ->
                        TextButton(onClick = { controller.select(sid) }) { Text("Open agent", fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}

/** PIPELINE-WATCHDOG: every agent the daemon flags (stalled, unreported, quiet, waiting), with its pending item and how long. */
@Composable
private fun WatchSection(state: AppState, now: Long) {
    val flagged = state.agents.values.mapNotNull { a -> watchFlagOf(a.session.watch)?.let { Triple(a, a.session.watch!!, it) } }
        .sortedWith(compareBy({ !it.third.urgent }, { it.third.ordinal }))
    if (flagged.isEmpty()) return
    flagged.forEach { (a, w, flag) ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp).testTag("watch-${a.id}")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WatchFlagChip(flag)
                Spacer(Modifier.width(6.dp))
                Text(state.agentTitle(a), color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                Text("last activity " + ago(w.lastActivityAt, now) + " ago", color = Palette.Muted, fontSize = 10.sp)
            }
            val since = w.pendingSince?.let { " for " + ago(it, now) } ?: ""
            Text((w.pending ?: flag.label) + since + if (w.nudges > 0) " (${w.nudges} nudge${if (w.nudges == 1) "" else "s"})" else "", color = Palette.Text, fontSize = 12.sp)
        }
    }
}
