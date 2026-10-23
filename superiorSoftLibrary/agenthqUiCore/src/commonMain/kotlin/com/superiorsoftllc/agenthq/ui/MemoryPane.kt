package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.NoteScope
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.coroutines.launch

const val NOTES_SCOPE_PROJECT_TAG = "notes-scope-project"
const val NOTES_SCOPE_ROLE_TAG = "notes-scope-role"
const val NOTES_SCOPE_SESSION_TAG = "notes-scope-session"
const val NOTES_SCOPE_BANNER_TAG = "notes-scope-banner"
const val MEMORY_NOTE_DELETE_TAG = "memory-note-delete"
const val MEMORY_ADD_NOTE_BUTTON_TAG = "memory-add-note"
const val MEMORY_NOTE_DIALOG_TAG = "memory-note-dialog"
const val MEMORY_NOTE_NAME_TAG = "memory-note-name"
const val MEMORY_NOTE_DESCRIPTION_TAG = "memory-note-description"
const val MEMORY_NOTE_BODY_TAG = "memory-note-body"
const val MEMORY_NOTE_SAVE_TAG = "memory-note-save"
const val MEMORY_NOTE_CANCEL_TAG = "memory-note-cancel"

/**
 * MEMORY-UI-1: what the note modal is holding. [editing] is the name of the note being edited (fixed: a rename
 * would save a second note), or null for a new note. [bodyTouched] stops a late `memory_note` answer from
 * overwriting a body the user has already started typing.
 */
private data class NoteDraft(
    val editing: String? = null,
    val name: String = "",
    val description: String = "",
    val body: String = "",
    val bodyTouched: Boolean = false,
)

/**
 * The selected project's memory (contracts 1.20.0 / 1.21.0 / 1.51.0). Selecting a project (or going live) asks the
 * daemon for its note index (`memory_list`). MEMORY-UI-1: a header row with an icon-only "Add note" button, then the
 * search box (`memory_search`, ranked hits with snippets), then the notes. Adding or editing a note happens in one
 * modal ([NoteModal], on the shared [EditorModal]) rather than in a form under the list, which a growing list pushed off screen: "+" opens it
 * empty, clicking a note opens it for that note and fetches the body with `memory_get`, because the index withholds
 * it. Save sends `memory_save` (the daemon's default replace mode, so an edit overwrites by name) and closes on
 * Sent; a refusal stays in the modal. Everything shown comes from [state].
 */
@Composable
fun MemoryPane(state: AppState, controller: Controller) {
    val scope = rememberCoroutineScope()
    val live = state.connection.isLive
    val active = state.activeProject
    var query by remember(active?.id) { mutableStateOf("") }
    var draft by remember(active?.id) { mutableStateOf<NoteDraft?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    // NOTES-1: the scope tab. Role = the selected session's role, Session = the selected session.
    var scopeName by remember { mutableStateOf(NoteScope.PROJECT) }
    val picked = state.selected
    val at = when (scopeName) {
        NoteScope.ROLE -> NoteScope(NoteScope.ROLE, role = picked?.role)
        NoteScope.SESSION -> NoteScope(NoteScope.SESSION, sessionId = picked?.id)
        else -> NoteScope.Project
    }
    // A role / session scope with nothing selected has no owner to show notes of.
    val ownerMissing = at.scope != NoteScope.PROJECT && at.ownerId.isBlank()
    val key = active?.let { at.key(it.id) }

    LaunchedEffect(active?.id, live, at) {
        if (live && active != null && !ownerMissing) controller.loadMemory(active.id, at)
    }

    fun run(onSent: () -> Unit = {}, request: suspend () -> CommandResult) {
        localError = null
        scope.launch {
            when (val r = request()) {
                is CommandResult.Sent -> onSent()
                is CommandResult.Refused -> localError = r.reason
                // Only prompts queue; these requests never answer Queued.
                is CommandResult.Queued -> Unit
            }
        }
    }

    fun openDraft(d: NoteDraft) {
        localError = null
        draft = d
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (active != null) "NOTES · ${active.name}" else "NOTES",
                    color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f),
                )
                if (active != null) {
                    IconAction(
                        AhqIcons.Add, "Add note", onClick = { openDraft(NoteDraft()) }, enabled = live,
                        style = IconActionStyle.TEXT, modifier = Modifier.testTag(MEMORY_ADD_NOTE_BUTTON_TAG),
                    )
                }
            }
            if (active == null) {
                Text("Select a project to see its notes.", color = Palette.Muted, fontSize = 12.sp)
                return@Column
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    Triple(NoteScope.PROJECT, "Project", NOTES_SCOPE_PROJECT_TAG),
                    Triple(NoteScope.ROLE, "Role", NOTES_SCOPE_ROLE_TAG),
                    Triple(NoteScope.SESSION, "Session", NOTES_SCOPE_SESSION_TAG),
                ).forEach { (name, label, tag) ->
                    OutlinedButton(
                        onClick = { scopeName = name; draft = null; localError = null },
                        modifier = Modifier.testTag(tag),
                    ) { Text(if (scopeName == name) "● $label" else label, fontSize = 12.sp) }
                }
            }
            Text(
                scopeBanner(at, picked?.id, picked?.role),
                color = if (at.scope == NoteScope.SESSION) Palette.Danger else Palette.Muted, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 6.dp).testTag(NOTES_SCOPE_BANNER_TAG),
            )
            if (ownerMissing) {
                Text(
                    if (at.scope == NoteScope.ROLE) "Select a session to see its role's notes." else "Select a session to see its session notes.",
                    color = Palette.Muted, fontSize = 12.sp,
                )
                return@Column
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    query, { query = it }, label = { Text("Search memory") }, singleLine = true, enabled = live,
                    textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.weight(1f).testTag("memory-search-input"),
                )
                OutlinedButton(
                    onClick = { run { controller.searchMemory(active.id, query, at) } },
                    enabled = live && query.isNotBlank(), modifier = Modifier.testTag("memory-search-go"),
                ) { Text("Search", fontSize = 12.sp) }
            }
            state.memorySearch?.takeIf { it.projectId == key }?.let { search ->
                if (search.hits.isEmpty()) {
                    Text("No hits for “${search.query}”.", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                } else {
                    search.hits.forEach { hit ->
                        Column(
                            Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(8.dp),
                        ) {
                            Text(hit.name, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            if (hit.description.isNotBlank()) Text(hit.description, color = Palette.Muted, fontSize = 11.sp)
                            if (hit.snippet.isNotBlank()) Text(hit.snippet, color = Palette.Muted, fontSize = 11.sp, maxLines = 3)
                        }
                    }
                }
            }
            // While the modal is open its refusals show inside it; otherwise (a search refused) here.
            if (draft == null) localError?.let { Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }

            HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(vertical = 8.dp))

            val notes = state.memoryNotes[key].orEmpty()
            if (notes.isEmpty()) Text("No notes yet.", color = Palette.Muted, fontSize = 12.sp)
            notes.forEach { note ->
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Panel)
                        .clickable(enabled = live, onClickLabel = "Edit note", role = Role.Button) {
                            openDraft(NoteDraft(editing = note.name, name = note.name, description = note.description, body = note.body))
                            run { controller.getMemory(active.id, note.name, at) }
                        }
                        .padding(10.dp),
                ) {
                    Text(note.name, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    if (note.description.isNotBlank()) Text(note.description, color = Palette.Muted, fontSize = 11.sp)
                }
            }
        }

        val open = draft
        if (active != null && open != null) {
            // The `memory_get` answer upserts the whole note; fill the body in unless the user already typed one.
            val stored = open.editing?.let { name -> state.memoryNotes[key]?.firstOrNull { it.name == name } }
            LaunchedEffect(stored?.body) {
                val d = draft ?: return@LaunchedEffect
                if (stored != null && !d.bodyTouched && stored.body.isNotEmpty()) {
                    draft = d.copy(body = stored.body, description = d.description.ifEmpty { stored.description })
                }
            }
            NoteModal(
                draft = open, live = live, error = localError,
                onChange = { draft = it },
                onSave = { d -> run(onSent = { draft = null }) { controller.saveMemory(active.id, d.name, d.description, d.body, at) } },
                onDelete = open.editing?.let { name -> { run(onSent = { draft = null }) { controller.deleteMemory(active.id, name, at) } } },
                scopeLabel = scopeLabel(at),
                onCancel = { draft = null; localError = null },
            )
        }
    }
}

/** MEMORY-UI-1: the add / edit note form inside the shared [EditorModal]. The name is fixed while editing. */
@Composable
private fun NoteModal(
    draft: NoteDraft,
    live: Boolean,
    error: String?,
    onChange: (NoteDraft) -> Unit,
    onSave: (NoteDraft) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
    scopeLabel: String = "project note",
) {
    EditorModal(
        title = (if (draft.editing == null) "Add " else "Edit ") + scopeLabel,
        onSave = { onSave(draft) }, onCancel = onCancel,
        modifier = Modifier.testTag(MEMORY_NOTE_DIALOG_TAG),
        saveEnabled = live && draft.name.isNotBlank(), error = error,
        saveTag = MEMORY_NOTE_SAVE_TAG, cancelTag = MEMORY_NOTE_CANCEL_TAG,
    ) {
        if (onDelete != null) {
            OutlinedButton(onClick = onDelete, enabled = live, modifier = Modifier.testTag(MEMORY_NOTE_DELETE_TAG)) {
                Text("Delete this $scopeLabel", fontSize = 12.sp, color = Palette.Danger)
            }
        }
        OutlinedTextField(
            draft.name, { onChange(draft.copy(name = it)) }, label = { Text("Name") }, singleLine = true,
            enabled = live && draft.editing == null,
            textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().testTag(MEMORY_NOTE_NAME_TAG),
        )
        OutlinedTextField(
            draft.description, { onChange(draft.copy(description = it)) }, label = { Text("Description") }, singleLine = true, enabled = live,
            textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().testTag(MEMORY_NOTE_DESCRIPTION_TAG),
        )
        OutlinedTextField(
            draft.body, { onChange(draft.copy(body = it, bodyTouched = true)) }, label = { Text("Body") }, minLines = 5, maxLines = 14, enabled = live,
            textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().testTag(MEMORY_NOTE_BODY_TAG),
        )
    }
}

/** NOTES-1: the noun a modal and a delete button use for the scope. */
internal fun scopeLabel(at: NoteScope): String = when (at.scope) {
    NoteScope.ROLE -> "role note"
    NoteScope.SESSION -> "session note"
    else -> "project note"
}

/** NOTES-1: the banner that says whose notes these are and, for a session, that they are temporary. */
internal fun scopeBanner(at: NoteScope, sessionId: String?, role: String?): String = when (at.scope) {
    NoteScope.ROLE -> "Role notes: shared by every session of the '${role.orEmpty()}' role in this project, kept across sessions and restarts."
    NoteScope.SESSION -> "Session notes: TEMPORARY, only for session ${sessionId.orEmpty().take(8)}. They are deleted when that session is removed."
    else -> "Project notes: shared by every role and every session of this project."
}
