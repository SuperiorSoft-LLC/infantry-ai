package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import com.superiorsoftllc.agenthq.ui.model.ProjectSettingsRegistry
import com.superiorsoftllc.agenthq.ui.model.ProjectView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.coroutines.launch

const val PROJECT_PICKER_TAG = "title-bar-project-picker"
const val PROJECT_OPTION_TAG_PREFIX = "project-option-"
const val NEW_PROJECT_BUTTON_TAG = "title-bar-new-project"
/** UX-POLISH-1: the height of the project drop-down, and so the side of the square New project button beside it. */
val PROJECT_CONTROL_HEIGHT = 24.dp

/** What the title bar's "+" does: its tooltip and screen-reader name. */
const val NEW_PROJECT_LABEL = "New project"

/** UX-POLISH-1: the Add icon inside the New project button. */
const val NEW_PROJECT_ICON_TAG = "title-bar-new-project-icon"
const val NEW_PROJECT_DIALOG_TAG = "new-project-dialog"
const val NEW_PROJECT_NAME_TAG = "new-project-name"
const val NEW_PROJECT_DIRECTORY_TAG = "new-project-directory"
const val PROJECT_DIRECTORY_FIELD_TAG = "project-directory-field"
const val PROJECT_DIRECTORY_SAVE_TAG = "project-directory-save"
const val PROJECT_DIRECTORY_CLEAR_TAG = "project-directory-clear"
const val NEW_PROJECT_CREATE_TAG = "new-project-create"
const val NEW_PROJECT_CANCEL_TAG = "new-project-cancel"

/** Edit project: the title bar's pencil beside "+" opens the same dialog prefilled with the current project's name. */
const val EDIT_PROJECT_BUTTON_TAG = "title-bar-edit-project"
const val EDIT_PROJECT_LABEL = "Project settings"
const val PROJECT_CONFIG_DIALOG_TAG = "project-config-dialog"
const val PROJECT_CONFIG_TAB_GENERAL_TAG = "project-config-tab-general"
const val PROJECT_CONFIG_TAB_LANES_TAG = "project-config-tab-lanes"
const val PROJECT_CONFIG_CAP_TAG_PREFIX = "project-config-cap-"

/** The summed caps above which the Lanes / Roles tab shows its soft warning (no hard limit: projects and machines vary). */
const val LANE_CAPS_WARN_TOTAL = 8

/**
 * M2-7: the title bar's project controls. A drop-down names the current project and lists every project
 * (picking one is the same [Controller.selectProject] the sidebar list used to call); the square "+" box beside
 * it opens the New Project modal, whose Create makes the project and selects it ([Controller.createProject]).
 */
@Composable
fun ProjectPicker(state: AppState, controller: Controller) {
    val scope = rememberCoroutineScope()
    val live = state.connection.isLive
    var open by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val current = state.activeProject

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            Row(
                Modifier.height(PROJECT_CONTROL_HEIGHT).widthIn(min = 120.dp, max = 240.dp).clip(RoundedCornerShape(5.dp))
                    .background(Palette.Raised).border(1.dp, Palette.Border, RoundedCornerShape(5.dp))
                    .clickable(enabled = live && state.projects.isNotEmpty(), role = Role.DropdownList, onClickLabel = "Switch project") { open = true }
                    .semantics { contentDescription = "Project" }
                    .testTag(PROJECT_PICKER_TAG)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    current?.name ?: if (state.projects.isEmpty()) "No project" else "Select project",
                    color = if (current != null) Palette.Text else Palette.Muted,
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text("▾", color = Palette.Muted, fontSize = 11.sp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Palette.Raised)) {
                state.projects.forEach { p ->
                    val selected = p.id == state.activeProjectId
                    DropdownMenuItem(
                        text = {
                            Text(
                                p.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (selected) Palette.Accent else Palette.Text,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            open = false
                            if (!selected) scope.launch { controller.selectProject(p.id) }
                        },
                        modifier = Modifier.testTag(PROJECT_OPTION_TAG_PREFIX + p.id),
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        NewProjectBox(enabled = live) { creating = true }
        Spacer(Modifier.width(6.dp))
        EditProjectBox(enabled = live && current != null) { editing = true }
    }

    if (editing && current != null) ProjectConfigDialog(state, controller, current, live, onDismiss = { editing = false })

    if (creating) NewProjectDialog(live, onDismiss = { creating = false }, askDirectory = true) { name, directory ->
        when (val r = controller.createProject(name, directory)) {
            is CommandResult.Refused -> r.reason
            else -> { creating = false; null }
        }
    }
}

/**
 * The new-project "+": a small square with an orange outline, matching the selected agent card's border. UX-POLISH-1: a Material
 * Add icon (a text "+" sat off-centre on its font baseline), as tall as the drop-down beside it, centred on both axes,
 * with the shared icon-action tooltip naming what it does.
 */
@Composable
private fun NewProjectBox(enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) Palette.Accent else Palette.Border
    IconActionTooltipBox(NEW_PROJECT_LABEL, modifier = Modifier.size(PROJECT_CONTROL_HEIGHT).testTag(NEW_PROJECT_BUTTON_TAG)) { m ->
        Box(
            m.clip(RoundedCornerShape(5.dp)).border(1.5.dp, tint, RoundedCornerShape(5.dp))
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = NEW_PROJECT_LABEL, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(AhqIcons.Add, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp).testTag(NEW_PROJECT_ICON_TAG)) }
    }
}

@Composable
private fun EditProjectBox(enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) Palette.Accent else Palette.Border
    IconActionTooltipBox(EDIT_PROJECT_LABEL, modifier = Modifier.size(PROJECT_CONTROL_HEIGHT).testTag(EDIT_PROJECT_BUTTON_TAG)) { m ->
        Box(
            m.clip(RoundedCornerShape(5.dp)).border(1.5.dp, tint, RoundedCornerShape(5.dp))
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = EDIT_PROJECT_LABEL, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text("✎", color = tint, fontSize = 13.sp) }
    }
}

/**
 * The New Project modal (also the Edit Project modal: [title], [confirmLabel] and [initialName] prefill it): a name, Create (off until there is a name and a live link) and Cancel. [onCreate]
 * answers null on success, or the refusal to show inline.
 */
@Composable
private fun NewProjectDialog(
    live: Boolean,
    onDismiss: () -> Unit,
    title: String = "New Project",
    confirmLabel: String = "Create",
    initialName: String = "",
    askDirectory: Boolean = false,
    onCreate: suspend (String, String) -> String?,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialName) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var directory by remember { mutableStateOf("") }
    val nameFocus = remember { FocusRequester() }
    val canCreate = live && name.isNotBlank() && !busy
    fun create() {
        if (!canCreate) return
        busy = true
        error = null
        scope.launch { error = onCreate(name, directory); busy = false }
    }
    AhqAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(NEW_PROJECT_DIALOG_TAG).onPreviewKeyEvent { e ->
            when {
                e.type != KeyEventType.KeyDown -> false
                e.key == Key.Escape -> { onDismiss(); true }
                e.key == Key.Enter -> { create(); true }
                else -> false
            }
        },
        containerColor = Palette.Panel,
        title = { Text(title, color = Palette.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LaunchedEffect(Unit) { nameFocus.requestFocus() }
                OutlinedTextField(
                    name, { name = it; error = null }, label = { Text("Project name") }, singleLine = true, enabled = live,
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocus).testTag(NEW_PROJECT_NAME_TAG),
                )
                if (askDirectory) OutlinedTextField(
                    directory, { directory = it; error = null }, label = { Text("Directory / repo (optional)") }, singleLine = true, enabled = live,
                    supportingText = { Text("Path on the daemon machine; tickets cannot start without one.", fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth().testTag(NEW_PROJECT_DIRECTORY_TAG),
                )
                if (!live) Text("Connect to a daemon to create a project.", color = Palette.Muted, fontSize = 11.sp)
                error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
            }
        },
        confirmButton = {
            Button(
                onClick = ::create, enabled = canCreate,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
                modifier = Modifier.testTag(NEW_PROJECT_CREATE_TAG),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(NEW_PROJECT_CANCEL_TAG)) { Text("Cancel", color = Palette.Text) }
        },
    )
}

/**
 * Project settings (replaces Rename Project): a tabbed dialog on the shared blurred modal. The sections and their toggles are
 * registered in [ProjectSettingsRegistry]. General renames the project; Pipeline holds the on/off switches; Lanes / Roles sets, per role, the most agents that run at once (default 1, no upper limit). A cap change is
 * applied at once (the daemon pushes the project back); lowering it never stops a running agent.
 */
@Composable
private fun ProjectConfigDialog(state: AppState, controller: Controller, project: ProjectView, live: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(ProjectSettingsRegistry.GENERAL) }
    var name by remember { mutableStateOf(project.name) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val canSave = live && name.isNotBlank() && !busy
    fun save() {
        if (!canSave) return
        busy = true
        error = null
        scope.launch {
            when (val r = controller.renameProject(project.id, name)) {
                is CommandResult.Refused -> error = r.reason
                else -> onDismiss()
            }
            busy = false
        }
    }
    AhqAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(PROJECT_CONFIG_DIALOG_TAG).onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onDismiss(); true } else false
        },
        containerColor = Palette.Panel,
        title = { Text(EDIT_PROJECT_LABEL, color = Palette.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(min = 360.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ProjectSettingsRegistry.sections.forEach { t ->
                        val selected = t.id == tab
                        Text(
                            t.label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) Palette.Accent else Palette.Muted,
                            modifier = Modifier.clickable(role = Role.Tab, onClickLabel = t.label) { tab = t.id }
                                .padding(vertical = 4.dp).testTag(t.tag),
                        )
                    }
                }
                when (tab) {
                    ProjectSettingsRegistry.GENERAL -> {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        OutlinedTextField(
                            name, { name = it; error = null }, label = { Text("Project name") }, singleLine = true, enabled = live,
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(NEW_PROJECT_NAME_TAG),
                        )
                        if (!live) Text("Connect to a daemon to change the project.", color = Palette.Muted, fontSize = 11.sp)
                                        DirectorySetting(state, controller, project, live)
                        error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
                    }
                    ProjectSettingsRegistry.LANES -> LanesTab(state, controller, project, live)
                    else -> ToggleSection(state, controller, project, live, tab)
                }
            }
        },
        confirmButton = {
            if (tab == ProjectSettingsRegistry.GENERAL) Button(
                onClick = ::save, enabled = canSave,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
                modifier = Modifier.testTag(NEW_PROJECT_CREATE_TAG),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(NEW_PROJECT_CANCEL_TAG)) {
                Text(if (tab == ProjectSettingsRegistry.GENERAL) "Cancel" else "Close", color = Palette.Text)
            }
        },
    )
}

/** Per role: the most agents that run at once. A role with no entry runs 1; the project frame the daemon pushes back updates the numbers. */
@Composable
private fun LanesTab(state: AppState, controller: Controller, project: ProjectView, live: Boolean) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val roleIds = (state.roles.map { it.id } + project.laneCaps.keys).distinct()
    val names = state.roles.associate { it.id to it.name }
    val total = roleIds.sumOf { project.laneCaps[it] ?: 1 }
    Text(
        "Most agents of a role that run at once. 1 keeps today's behaviour: a second ticket of the role waits. " +
            "Lowering a number never stops a running agent.",
        color = Palette.Muted, fontSize = 11.sp,
    )
    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        roleIds.forEach { id ->
            val cap = project.laneCaps[id] ?: 1
            fun set(n: Int) {
                error = null
                scope.launch { (controller.setLaneCap(project.id, id, n) as? CommandResult.Refused)?.let { error = it.reason } }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(names[id] ?: id, color = Palette.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                CapStep("-", enabled = live && cap > 1, tag = "project-config-cap-minus-$id") { set(cap - 1) }
                Text(
                    cap.toString(), color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 32.dp).testTag(PROJECT_CONFIG_CAP_TAG_PREFIX + id),
                )
                CapStep("+", enabled = live, tag = "project-config-cap-plus-$id") { set(cap + 1) }
            }
        }
    }
    if (total > LANE_CAPS_WARN_TOTAL) {
        Text(
            "$total agents may run at once. That is a lot of RAM and provider usage; check both before you start many tickets.",
            color = Palette.Danger, fontSize = 11.sp,
        )
    }
    error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
}

/** BASICS-KILL-1: the "remove done workers" switch (now in the Pipeline section). */
const val PROJECT_CONFIG_AUTO_REMOVE_TAG = "project-config-auto-remove-done-workers"

/** A section made only of registered switches ([ProjectSettingsRegistry.toggles]); a change applies at once and the pushed project redraws it. */
@Composable
private fun ToggleSection(state: AppState, controller: Controller, project: ProjectView, live: Boolean, sectionId: String) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    ProjectSettingsRegistry.togglesFor(sectionId, state.connection).forEach { toggle ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = toggle.isOn(project),
                enabled = live,
                onCheckedChange = { on ->
                    error = null
                    scope.launch { (toggle.write(controller, project.id, on) as? CommandResult.Refused)?.let { error = it.reason } }
                },
                modifier = Modifier.testTag(toggle.tag),
            )
            Text(toggle.label, color = Palette.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
    }
    error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
}

@Composable
private fun CapStep(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val tint = if (enabled) Palette.Accent else Palette.Border
    Box(
        Modifier.testTag(tag).size(26.dp).clip(RoundedCornerShape(5.dp)).border(1.dp, tint, RoundedCornerShape(5.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = tint, fontSize = 14.sp) }
}

/**
 * General settings: the project's directory. Save and Clear go through the same [Controller.saveProjectDirectory] the Git
 * pane uses; the daemon's refusal (for instance a relative path) is drawn here, the stored directory stays as it was.
 */
@Composable
private fun DirectorySetting(state: AppState, controller: Controller, project: ProjectView, live: Boolean) {
    val scope = rememberCoroutineScope()
    var draft by remember(project.id, project.directory) { mutableStateOf(project.directory) }
    var localError by remember { mutableStateOf<String?>(null) }
    var seenAt by remember { mutableStateOf(state.projectsRefusal()?.at ?: 0L) }
    fun run(directory: String) {
        state.projectsRefusal()?.let { seenAt = it.at }
        localError = null
        scope.launch { (controller.saveProjectDirectory(project.id, directory) as? CommandResult.Refused)?.let { localError = it.reason } }
    }
    OutlinedTextField(
        draft, { draft = it; localError = null }, label = { Text("Project directory") }, singleLine = true, enabled = live,
        supportingText = { Text(if (project.directory.isBlank()) "Not set; tickets cannot start without one." else "Path on the daemon machine.", fontSize = 10.sp) },
        modifier = Modifier.fillMaxWidth().testTag(PROJECT_DIRECTORY_FIELD_TAG),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { run(draft) }, enabled = live && draft.trim() != project.directory,
            modifier = Modifier.testTag(PROJECT_DIRECTORY_SAVE_TAG),
        ) { Text("Save directory", fontSize = 12.sp) }
        OutlinedButton(
            onClick = { draft = ""; run("") }, enabled = live && project.directory.isNotBlank(),
            modifier = Modifier.testTag(PROJECT_DIRECTORY_CLEAR_TAG),
        ) { Text("Clear directory", fontSize = 12.sp) }
    }
    (localError ?: state.projectsRefusal()?.takeIf { it.at > seenAt }?.text)
        ?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
}
