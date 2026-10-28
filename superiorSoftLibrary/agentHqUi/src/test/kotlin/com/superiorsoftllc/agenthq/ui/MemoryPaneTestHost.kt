package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.plugins.official.notes.NotesBannerSection
import com.superiorsoftllc.agenthq.plugins.official.notes.NotesEmptySection
import com.superiorsoftllc.agenthq.plugins.official.notes.NotesErrorSection
import com.superiorsoftllc.agenthq.plugins.official.notes.NotesHeaderSection
import com.superiorsoftllc.agenthq.plugins.official.notes.NotesHitRow
import com.superiorsoftllc.agenthq.plugins.official.notes.NotesHitsSection
import com.superiorsoftllc.agenthq.plugins.official.notes.NotesListSection
import com.superiorsoftllc.agenthq.plugins.official.notes.NotesModalSection
import com.superiorsoftllc.agenthq.plugins.official.notes.NotesNoOwnerSection
import com.superiorsoftllc.agenthq.plugins.official.notes.NotesNoteRow
import com.superiorsoftllc.agenthq.plugins.official.notes.NotesPaneFrame
import com.superiorsoftllc.agenthq.plugins.official.notes.NotesScopesSection
import com.superiorsoftllc.agenthq.plugins.official.notes.NotesSearchSection
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.shell.CoreLink
import com.superiorsoftllc.agenthq.ui.shell.CoreNotes
import com.superiorsoftllc.agenthq.ui.shell.CoreSession

/**
 * RUNTIME-8: the whole Notes pane over the window's [state] and [controller], for the pane tests. It lived in
 * Infantry_Notes; the official jar reads only the plugin API contexts, so the AppState / Controller adapter is the tests'
 * own now.
 */
@Composable
fun MemoryPane(state: AppState, controller: Controller) {
    NotesPaneFrame(
        CoreLink(state), CoreSession(state), CoreNotes(state, controller),
        content = { notes ->
            NotesHeaderSection(notes)
            NotesEmptySection(notes)
            NotesScopesSection(notes)
            NotesBannerSection(notes)
            NotesNoOwnerSection(notes)
            NotesSearchSection(notes)
            NotesHitsSection(notes) { NotesHitRow(it) }
            NotesErrorSection(notes)
            NotesListSection(notes) { NotesNoteRow(notes, it) }
        },
        overlay = { notes -> NotesModalSection(notes) },
    )
}
