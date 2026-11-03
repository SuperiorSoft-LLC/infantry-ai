package com.superiorsoftllc.infantry.plugins.official.notes

import com.superiorsoftllc.infantry.ui.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.Place
import com.superiorsoftllc.infantry.ui.block.layout.RenderPlaced
import com.superiorsoftllc.infantry.ui.block.layout.RenderPlain
import com.superiorsoftllc.infantry.ui.model.MemoryHitView
import com.superiorsoftllc.infantry.ui.model.MemoryNoteView

private const val NOTES = "infantry.shell.notes"
private const val MODAL = "$NOTES.modal"

/** The Notes pane's host; null outside [NotesPaneBlock] (a child moved elsewhere draws nothing). */
private val LocalNotesPaneHost = compositionLocalOf<NotesPaneHost?> { null }

/** The hit a hit row is drawn for; null outside [NotesHitsBlock] (a hit row moved elsewhere draws nothing). */
private val LocalNotesHit = compositionLocalOf<MemoryHitView?> { null }

/** The note a note row is drawn for; null outside [NotesListBlock] (a note row moved elsewhere draws nothing). */
private val LocalNotesNote = compositionLocalOf<MemoryNoteView?> { null }

/**
 * BLOCKS-LAYOUT L17: the Notes tab's content (Infantry_Notes), a container of child blocks `infantry.shell.notes.<name>`
 * drawn straight into the pane's scrolled column, top to bottom; `modal` alone goes over the column, in the pane's box
 * (it dims the pane). The frame (box, column, the query, the scope tab, the draft, the last refusal) stays its own. It
 * is not in [CoreShell.root]: the tab draws it ([NotesTab]), and overrides address it by id the same way ([CoreShell.tabs]).
 */
class NotesPaneBlock(children: List<Block>, place: Place = Place()) : Block("notesPane", NOTES, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val ctx = LocalShellContext.current ?: return
        val (overlay, column) = children.partition { it.id == MODAL }
        NotesPaneFrame(
            ctx.link, ctx.session, ctx.notes, scope.modifier,
            content = { notes -> CompositionLocalProvider(LocalNotesPaneHost provides notes) { RenderPlaced(column) } },
            overlay = { notes -> CompositionLocalProvider(LocalNotesPaneHost provides notes) { RenderPlaced(overlay) } },
        )
    }

    override fun withChildren(children: List<Block>) = NotesPaneBlock(children, place)
}

/** "NOTES · project" and the Add note button; its placement goes on the row. */
object NotesHeader : Block("notesHeader", "$NOTES.header") {
    @Composable
    override fun Render(scope: BlockScope) {
        NotesHeaderSection(LocalNotesPaneHost.current ?: return, scope.modifier)
    }
}

/** "Select a project to see its notes.", with no project; its placement goes on the line. */
object NotesEmpty : Block("notesEmpty", "$NOTES.empty") {
    @Composable
    override fun Render(scope: BlockScope) {
        NotesEmptySection(LocalNotesPaneHost.current ?: return, scope.modifier)
    }
}

/** The Project / Role / Session tabs; its placement goes on the row. */
object NotesScopes : Block("notesScopes", "$NOTES.scopes") {
    @Composable
    override fun Render(scope: BlockScope) {
        NotesScopesSection(LocalNotesPaneHost.current ?: return, scope.modifier)
    }
}

/** Whose notes these are; its placement goes on the line. */
object NotesBanner : Block("notesBanner", "$NOTES.banner") {
    @Composable
    override fun Render(scope: BlockScope) {
        NotesBannerSection(LocalNotesPaneHost.current ?: return, scope.modifier)
    }
}

/** What a role / session scope says with no session selected; its placement goes on the line. */
object NotesNoOwner : Block("notesNoOwner", "$NOTES.noOwner") {
    @Composable
    override fun Render(scope: BlockScope) {
        NotesNoOwnerSection(LocalNotesPaneHost.current ?: return, scope.modifier)
    }
}

/** The search box and its button; its placement goes on the row. */
object NotesSearch : Block("notesSearch", "$NOTES.search") {
    @Composable
    override fun Render(scope: BlockScope) {
        NotesSearchSection(LocalNotesPaneHost.current ?: return, scope.modifier)
    }
}

/**
 * The last search's hits ("No hits" wears its placement; with hits a placement other than the default gets a Column).
 * Its children are drawn once per hit straight into the pane's column, with that hit in a composition local:
 * `infantry.shell.notes.hit` is the hit template, so an override of it applies to every hit.
 */
class NotesHitsBlock(children: List<Block>, place: Place = Place()) : Block("notesHits", "$NOTES.hits", place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        NotesHitsSection(LocalNotesPaneHost.current ?: return, scope.modifier) { hit ->
            CompositionLocalProvider(LocalNotesHit provides hit) { RenderPlain(children) }
        }
    }

    override fun withChildren(children: List<Block>) = NotesHitsBlock(children, place)
}

/** The hit template: one hit's name, description and snippet; its placement goes on its column. */
object NotesHit : Block("notesHit", "$NOTES.hit") {
    @Composable
    override fun Render(scope: BlockScope) {
        LocalNotesPaneHost.current ?: return
        NotesHitRow(LocalNotesHit.current ?: return, scope.modifier)
    }
}

/** The last refusal while the modal is closed; its placement goes on the line. */
object NotesError : Block("notesError", "$NOTES.error") {
    @Composable
    override fun Render(scope: BlockScope) {
        NotesErrorSection(LocalNotesPaneHost.current ?: return, scope.modifier)
    }
}

/**
 * The divider and the notes ("No notes yet." with none); a placement other than the default gets a Column. Its children
 * are drawn once per note straight into the pane's column, with that note in a composition local:
 * `infantry.shell.notes.note` is the note template, so an override of it applies to every note.
 */
class NotesListBlock(children: List<Block>, place: Place = Place()) : Block("notesList", "$NOTES.list", place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        NotesListSection(LocalNotesPaneHost.current ?: return, scope.modifier) { note ->
            CompositionLocalProvider(LocalNotesNote provides note) { RenderPlain(children) }
        }
    }

    override fun withChildren(children: List<Block>) = NotesListBlock(children, place)
}

/** The note template: one note's name and description, a click opens it; its placement goes on its column. */
object NotesNote : Block("notesNote", "$NOTES.note") {
    @Composable
    override fun Render(scope: BlockScope) {
        NotesNoteRow(LocalNotesPaneHost.current ?: return, LocalNotesNote.current ?: return, scope.modifier)
    }
}

/** The add / edit modal over the pane while it is open, drawn in the pane's box; its placement goes on its panel. */
object NotesModal : Block("notesModal", MODAL) {
    @Composable
    override fun Render(scope: BlockScope) {
        NotesModalSection(LocalNotesPaneHost.current ?: return, scope.modifier)
    }
}

val ShellNotes = NotesPaneBlock(
    listOf(
        NotesHeader, NotesEmpty, NotesScopes, NotesBanner, NotesNoOwner, NotesSearch,
        NotesHitsBlock(listOf(NotesHit)), NotesError, NotesListBlock(listOf(NotesNote)), NotesModal,
    ),
)

/** The Notes tab's content: [ShellNotes] as the window resolved it (a module's override in), filling the tab. */
@Composable
fun NotesTab() {
    (LocalShellContext.current?.layout?.tabTree(NOTES) ?: ShellNotes).Render(BlockScope(Modifier, null))
}
