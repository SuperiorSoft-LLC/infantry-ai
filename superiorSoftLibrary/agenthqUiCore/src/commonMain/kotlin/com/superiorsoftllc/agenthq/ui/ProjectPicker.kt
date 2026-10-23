package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
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
const val NEW_PROJECT_CREATE_TAG = "new-project-create"
const val NEW_PROJECT_CANCEL_TAG = "new-project-cancel"

/** Edit project: the title bar's pencil beside "+" opens the same dialog prefilled with the current project's name. */
const val EDIT_PROJECT_BUTTON_TAG = "title-bar-edit-project"
const val EDIT_PROJECT_LABEL = "Rename project"

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

    if (editing && current != null) NewProjectDialog(
        live, onDismiss = { editing = false }, title = "Edit Project", confirmLabel = "Save", initialName = current.name,
    ) { name ->
        when (val r = controller.renameProject(current.id, name)) {
            is CommandResult.Refused -> r.reason
            else -> { editing = false; null }
        }
    }

    if (creating) NewProjectDialog(live, onDismiss = { creating = false }) { name ->
        when (val r = controller.createProject(name)) {
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
    onCreate: suspend (String) -> String?,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialName) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val canCreate = live && name.isNotBlank() && !busy
    fun create() {
        if (!canCreate) return
        busy = true
        error = null
        scope.launch { error = onCreate(name); busy = false }
    }
    AlertDialog(
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
                if (!live) Text("Connect to a daemon to create a project.", color = Palette.Muted, fontSize = 11.sp)
                error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
            }
        },
        confirmButton = {
            Button(
                onClick = ::create, enabled = canCreate,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color(0xFF1A1200)),
                modifier = Modifier.testTag(NEW_PROJECT_CREATE_TAG),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(NEW_PROJECT_CANCEL_TAG)) { Text("Cancel", color = Palette.Text) }
        },
    )
}
