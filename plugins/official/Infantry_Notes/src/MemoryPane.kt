package com.superiorsoftllc.infantry.plugins.official.notes

import com.superiorsoftllc.infantry.ui.*
import com.superiorsoftllc.infantry.ui.shell.gate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.MemoryHitView
import com.superiorsoftllc.infantry.ui.model.MemoryNoteView
import com.superiorsoftllc.infantry.ui.model.NoteScope
import com.superiorsoftllc.infantry.ui.model.ProjectView
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.shell.LinkContext
import com.superiorsoftllc.infantry.ui.shell.NotesContext
import com.superiorsoftllc.infantry.ui.shell.SessionContext
import kotlinx.coroutines.CoroutineScope
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
const val NOTES_PANE_TAG = "notes-pane"

/**
 * MEMORY-UI-1: what the note modal is holding. [editing] is the name of the note being edited (fixed: a rename
 * would save a second note), or null for a new note. [bodyTouched] stops a late `memory_note` answer from
 * overwriting a body the user has already started typing.
 */
internal data class NoteDraft(
    val editing: String? = null,
    val name: String = "",
    val description: String = "",
    val body: String = "",
    val bodyTouched: Boolean = false,
)

/*
 * The selected project's memory (contracts 1.20.0 / 1.21.0 / 1.51.0). Selecting a project (or going live) asks the
 * daemon for its note index (`memory_list`). MEMORY-UI-1: a header row with an icon-only "Add note" button, then the
 * search box (`memory_search`, ranked hits with snippets), then the notes. Adding or editing a note happens in one
 * modal ([NoteModal], on the shared [EditorModal]) rather than in a form under the list, which a growing list pushed off screen: "+" opens it
 * empty, clicking a note opens it for that note and fetches the body with `memory_get`, because the index withholds
 * it. Save sends `memory_save` (the daemon's default replace mode, so an edit overwrites by name) and closes on
 * Sent; a refusal stays in the modal. Everything shown comes from the notes context.
 *
 * BLOCKS-LAYOUT L17: [NotesPaneFrame] and its sections, top to bottom, as the Notes tab's tree draws them
 * (`infantry.shell.notes`, ShellNotes.kt); the modal goes over the column. RUNTIME-8: everything here reads the plugin API
 * contexts (link, session, notes); the window's AppState / Controller are not used.
 */

/**
 * BLOCKS-LAYOUT L17: what the Notes pane's sections read and act on. The frame ([NotesPaneFrame]) owns the search
 * query, the scope tab, the modal's draft and the last refusal; a section changes them only through these callbacks.
 */
class NotesPaneHost internal constructor(
    val link: LinkContext,
    val session: SessionContext,
    /** S4b: the notes and their requests (the shell's [com.superiorsoftllc.infantry.ui.shell.ShellContext.notes]). */
    val context: NotesContext,
    private val scope: CoroutineScope,
    private val queryState: MutableState<String>,
    private val draftState: MutableState<NoteDraft?>,
    private val localError: MutableState<String?>,
    private val scopeState: MutableState<String>,
) {
    val live: Boolean get() = link.live

    /** GATE-PANES-1: save (add / edit) and delete, each its named action: dimmed with the policy's reason while off. */
    val canSave: Boolean @Composable get() = link.gate("memory.save").enabled
    val canDelete: Boolean @Composable get() = link.gate("memory.delete", com.superiorsoftllc.infantry.plugin.api.ActionKind.DESTRUCTIVE).enabled
    val active: ProjectView? get() = session.activeProject
    val picked: AgentView? get() = session.selectedAgent

    /** NOTES-1: the scope tab. Role = the selected session's role, Session = the selected session. */
    val scopeName: String get() = scopeState.value
    val at: NoteScope get() = when (scopeName) {
        NoteScope.ROLE -> NoteScope(NoteScope.ROLE, role = picked?.role)
        NoteScope.SESSION -> NoteScope(NoteScope.SESSION, sessionId = picked?.id)
        else -> NoteScope.Project
    }

    /** A role / session scope with nothing selected has no owner to show notes of. */
    val ownerMissing: Boolean get() = at.let { it.scope != NoteScope.PROJECT && it.ownerId.isBlank() }

    /** The state slices' key for this project and scope; null with no project. */
    val key: String? get() = active?.let { at.key(it.id) }

    /** A project is selected and the scope has an owner: the search, the hits and the notes show. */
    val showsNotes: Boolean get() = active != null && !ownerMissing

    val query: String get() = queryState.value
    internal val draft: NoteDraft? get() = draftState.value
    val modalOpen: Boolean get() = draftState.value != null
    val error: String? get() = localError.value

    private fun run(onSent: () -> Unit = {}, request: suspend () -> CommandResult) {
        localError.value = null
        scope.launch {
            when (val r = request()) {
                is CommandResult.Sent -> onSent()
                is CommandResult.Refused -> localError.value = r.reason
                // Only prompts queue; these requests never answer Queued.
                is CommandResult.Queued -> Unit
            }
        }
    }

    private fun openDraft(d: NoteDraft) {
        localError.value = null
        draftState.value = d
    }

    fun pickScope(name: String) { scopeState.value = name; draftState.value = null; localError.value = null }

    fun changeQuery(q: String) { queryState.value = q }

    fun search() {
        val a = active ?: return
        val at = at
        val q = query
        run { context.search(a.id, q, at) }
    }

    fun openNew() = openDraft(NoteDraft())

    fun openNote(note: MemoryNoteView) {
        val a = active ?: return
        val at = at
        openDraft(NoteDraft(editing = note.name, name = note.name, description = note.description, body = note.body))
        run { context.get(a.id, note.name, at) }
    }

    internal fun change(d: NoteDraft) { draftState.value = d }

    fun saveDraft() {
        val a = active ?: return
        val d = draft ?: return
        val at = at
        run(onSent = { draftState.value = null }) { context.save(a.id, d.name, d.description, d.body, at) }
    }

    fun deleteEditing() {
        val a = active ?: return
        val name = draft?.editing ?: return
        val at = at
        run(onSent = { draftState.value = null }) { context.delete(a.id, name, at) }
    }

    fun cancelDraft() { draftState.value = null; localError.value = null }
}

/**
 * BLOCKS-LAYOUT L17: the Notes pane's frame: the box the modal dims, the scrolled padded column its sections draw
 * straight into ([content]), and over it [overlay] (the modal). It owns the state the sections share, asks for the
 * note index when the project, the link or the scope changes, and fills an opened note's body in from the
 * `memory_get` answer.
 */
@Composable
fun NotesPaneFrame(
    link: LinkContext,
    session: SessionContext,
    ctx: NotesContext,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(NotesPaneHost) -> Unit,
    overlay: @Composable BoxScope.(NotesPaneHost) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val active = session.activeProject
    val query = remember(active?.id) { mutableStateOf("") }
    val draft = remember(active?.id) { mutableStateOf<NoteDraft?>(null) }
    val localError = remember { mutableStateOf<String?>(null) }
    val scopeName = remember { mutableStateOf(NoteScope.PROJECT) }
    val notes = NotesPaneHost(link, session, ctx, scope, query, draft, localError, scopeName)
    val live = notes.live
    val at = notes.at
    val ownerMissing = notes.ownerMissing

    LaunchedEffect(active?.id, live, at) {
        if (live && active != null && !ownerMissing) ctx.load(active.id, at)
    }

    val open = draft.value
    if (active != null && open != null) {
        // The `memory_get` answer upserts the whole note; fill the body in unless the user already typed one.
        val stored = open.editing?.let { name -> notes.key?.let(ctx::notes)?.firstOrNull { it.name == name } }
        LaunchedEffect(stored?.body) {
            val d = draft.value ?: return@LaunchedEffect
            if (stored != null && !d.bodyTouched && stored.body.isNotEmpty()) {
                draft.value = d.copy(body = stored.body, description = d.description.ifEmpty { stored.description })
            }
        }
    }

    Box(modifier.fillMaxSize().testTag(NOTES_PANE_TAG)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            content(notes)
        }
        overlay(notes)
    }
}

/** BLOCKS-LAYOUT L17: "NOTES · project" and the Add note button; [modifier] goes on the row. */
@Composable
fun NotesHeaderSection(notes: NotesPaneHost, modifier: Modifier = Modifier) {
    val active = notes.active
    Row(modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (active != null) "NOTES · ${active.name}" else "NOTES",
            color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f),
        )
        if (active != null) {
            IconAction(
                AhqIcons.Add, "Add note", onClick = { notes.openNew() }, enabled = notes.canSave,
                style = IconActionStyle.TEXT, modifier = Modifier.testTag(MEMORY_ADD_NOTE_BUTTON_TAG),
            )
        }
    }
}

/** BLOCKS-LAYOUT L17: what the pane says with no project selected; [modifier] goes on the line. */
@Composable
fun NotesEmptySection(notes: NotesPaneHost, modifier: Modifier = Modifier) {
    if (notes.active != null) return
    Text("Select a project to see its notes.", color = Palette.Muted, fontSize = 12.sp, modifier = modifier)
}

/** BLOCKS-LAYOUT L17: the Project / Role / Session scope tabs, with a project selected; [modifier] goes on the row. */
@Composable
fun NotesScopesSection(notes: NotesPaneHost, modifier: Modifier = Modifier) {
    if (notes.active == null) return
    Row(modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            Triple(NoteScope.PROJECT, "Project", NOTES_SCOPE_PROJECT_TAG),
            Triple(NoteScope.ROLE, "Role", NOTES_SCOPE_ROLE_TAG),
            Triple(NoteScope.SESSION, "Session", NOTES_SCOPE_SESSION_TAG),
        ).forEach { (name, label, tag) ->
            OutlinedButton(
                onClick = { notes.pickScope(name) },
                modifier = Modifier.testTag(tag),
            ) { Text(if (notes.scopeName == name) "● $label" else label, fontSize = 12.sp) }
        }
    }
}

/** BLOCKS-LAYOUT L17: whose notes these are (a session's are temporary), with a project selected; [modifier] goes on the line. */
@Composable
fun NotesBannerSection(notes: NotesPaneHost, modifier: Modifier = Modifier) {
    if (notes.active == null) return
    val at = notes.at
    Text(
        scopeBanner(at, notes.picked?.id, notes.picked?.role),
        color = if (at.scope == NoteScope.SESSION) Palette.Danger else Palette.Muted, fontSize = 11.sp,
        modifier = modifier.padding(bottom = 6.dp).testTag(NOTES_SCOPE_BANNER_TAG),
    )
}

/** BLOCKS-LAYOUT L17: what a role / session scope says with no session selected; [modifier] goes on the line. */
@Composable
fun NotesNoOwnerSection(notes: NotesPaneHost, modifier: Modifier = Modifier) {
    if (notes.active == null || !notes.ownerMissing) return
    Text(
        if (notes.at.scope == NoteScope.ROLE) "Select a session to see its role's notes." else "Select a session to see its session notes.",
        color = Palette.Muted, fontSize = 12.sp, modifier = modifier,
    )
}

/** BLOCKS-LAYOUT L17: the search box and its button; [modifier] goes on the row. */
@Composable
fun NotesSearchSection(notes: NotesPaneHost, modifier: Modifier = Modifier) {
    if (!notes.showsNotes) return
    val live = notes.live
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            notes.query, { notes.changeQuery(it) }, label = { Text("Search memory") }, singleLine = true, enabled = live,
            textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.weight(1f).testTag("memory-search-input"),
        )
        OutlinedButton(
            onClick = { notes.search() },
            enabled = live && notes.query.isNotBlank(), modifier = Modifier.testTag("memory-search-go"),
        ) { Text("Search", fontSize = 12.sp) }
    }
}

/**
 * BLOCKS-LAYOUT L17: the last search's hits for this scope ("No hits" when it found none, wearing the placement), each
 * drawn by [hit] straight into the pane's column. With hits, a placement other than the default gets a Column to wear it.
 */
@Composable
fun NotesHitsSection(notes: NotesPaneHost, modifier: Modifier = Modifier, hit: @Composable (MemoryHitView) -> Unit) {
    if (!notes.showsNotes) return
    val search = notes.context.search?.takeIf { it.projectId == notes.key } ?: return
    if (search.hits.isEmpty()) {
        Text("No hits for “${search.query}”.", color = Palette.Muted, fontSize = 11.sp, modifier = modifier.padding(top = 6.dp))
    } else {
        NotesPlaced(modifier) { search.hits.forEach { hit(it) } }
    }
}

/** BLOCKS-LAYOUT L17: one search hit (name, description, snippet) with the gap above it; [modifier] goes on its column. */
@Composable
fun NotesHitRow(hit: MemoryHitView, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(8.dp),
    ) {
        Text(hit.name, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        if (hit.description.isNotBlank()) Text(hit.description, color = Palette.Muted, fontSize = 11.sp)
        if (hit.snippet.isNotBlank()) Text(hit.snippet, color = Palette.Muted, fontSize = 11.sp, maxLines = 3)
    }
}

/**
 * BLOCKS-LAYOUT L17: the last refusal while the modal is closed (a refused search; the modal shows its own); [modifier]
 * goes on the line.
 */
@Composable
fun NotesErrorSection(notes: NotesPaneHost, modifier: Modifier = Modifier) {
    if (!notes.showsNotes || notes.modalOpen) return
    notes.error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = modifier.padding(top = 6.dp)) }
}

/**
 * BLOCKS-LAYOUT L17: the divider, then "No notes yet." or each note drawn by [note] straight into the pane's column. A
 * placement other than the default gets a Column to wear it.
 */
@Composable
fun NotesListSection(notes: NotesPaneHost, modifier: Modifier = Modifier, note: @Composable (MemoryNoteView) -> Unit) {
    if (!notes.showsNotes) return
    val list = notes.key?.let(notes.context::notes).orEmpty()
    NotesPlaced(modifier) {
        HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(vertical = 8.dp))
        if (list.isEmpty()) Text("No notes yet.", color = Palette.Muted, fontSize = 12.sp)
        list.forEach { note(it) }
    }
}

/** BLOCKS-LAYOUT L17: one note (name, description) with the gap under it; a click opens it in the modal. [modifier] goes on its column. */
@Composable
fun NotesNoteRow(notes: NotesPaneHost, note: MemoryNoteView, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Panel)
            .clickable(enabled = notes.live, onClickLabel = "Edit note", role = Role.Button) { notes.openNote(note) }
            .padding(10.dp),
    ) {
        Text(note.name, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        if (note.description.isNotBlank()) Text(note.description, color = Palette.Muted, fontSize = 11.sp)
    }
}

/** BLOCKS-LAYOUT L17: the add / edit modal over the pane while it is open; [modifier] goes on its panel. */
@Composable
fun NotesModalSection(notes: NotesPaneHost, modifier: Modifier = Modifier) {
    if (notes.active == null) return
    val open = notes.draft ?: return
    NoteModal(
        draft = open, canSave = notes.canSave, canDelete = notes.canDelete, error = notes.error,
        onChange = { notes.change(it) },
        onSave = { notes.saveDraft() },
        onDelete = open.editing?.let { { notes.deleteEditing() } },
        scopeLabel = scopeLabel(notes.at),
        onCancel = { notes.cancelDraft() },
        modifier = modifier,
    )
}

/** A section that draws several items: a placement other than the default gets a Column to wear it. */
@Composable
private fun NotesPlaced(modifier: Modifier, content: @Composable () -> Unit) {
    if (modifier == Modifier) content() else Column(modifier) { content() }
}

/** MEMORY-UI-1: the add / edit note form inside the shared [EditorModal]. The name is fixed while editing. */
@Composable
private fun NoteModal(
    draft: NoteDraft,
    canSave: Boolean,
    canDelete: Boolean,
    error: String?,
    onChange: (NoteDraft) -> Unit,
    onSave: (NoteDraft) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
    scopeLabel: String = "project note",
    modifier: Modifier = Modifier,
) {
    EditorModal(
        title = (if (draft.editing == null) "Add " else "Edit ") + scopeLabel,
        onSave = { onSave(draft) }, onCancel = onCancel,
        modifier = modifier.testTag(MEMORY_NOTE_DIALOG_TAG),
        saveEnabled = canSave && draft.name.isNotBlank(), error = error,
        saveTag = MEMORY_NOTE_SAVE_TAG, cancelTag = MEMORY_NOTE_CANCEL_TAG,
    ) {
        if (onDelete != null) {
            OutlinedButton(onClick = onDelete, enabled = canDelete, modifier = Modifier.testTag(MEMORY_NOTE_DELETE_TAG)) {
                Text("Delete this $scopeLabel", fontSize = 12.sp, color = Palette.Danger)
            }
        }
        OutlinedTextField(
            draft.name, { onChange(draft.copy(name = it)) }, label = { Text("Name") }, singleLine = true,
            enabled = canSave && draft.editing == null,
            textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().testTag(MEMORY_NOTE_NAME_TAG),
        )
        OutlinedTextField(
            draft.description, { onChange(draft.copy(description = it)) }, label = { Text("Description") }, singleLine = true, enabled = canSave,
            textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().testTag(MEMORY_NOTE_DESCRIPTION_TAG),
        )
        OutlinedTextField(
            draft.body, { onChange(draft.copy(body = it, bodyTouched = true)) }, label = { Text("Body") }, minLines = 5, maxLines = 14, enabled = canSave,
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
