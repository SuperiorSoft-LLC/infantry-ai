package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.plugins.official.changes.ChangesBranchFileRow
import com.superiorsoftllc.agenthq.plugins.official.changes.ChangesBranchFilesSection
import com.superiorsoftllc.agenthq.plugins.official.changes.ChangesBranchSection
import com.superiorsoftllc.agenthq.plugins.official.changes.ChangesCommitRow
import com.superiorsoftllc.agenthq.plugins.official.changes.ChangesEmptySection
import com.superiorsoftllc.agenthq.plugins.official.changes.ChangesFileRow
import com.superiorsoftllc.agenthq.plugins.official.changes.ChangesFilesSection
import com.superiorsoftllc.agenthq.plugins.official.changes.ChangesHeaderSection
import com.superiorsoftllc.agenthq.plugins.official.changes.ChangesHintSection
import com.superiorsoftllc.agenthq.plugins.official.changes.ChangesHistorySection
import com.superiorsoftllc.agenthq.plugins.official.changes.ChangesModeSection
import com.superiorsoftllc.agenthq.plugins.official.changes.ChangesNotGitSection
import com.superiorsoftllc.agenthq.plugins.official.changes.ChangesPaneFrame
import com.superiorsoftllc.agenthq.plugins.official.changes.ChangesTitleSection
import com.superiorsoftllc.agenthq.plugins.official.changes.ChangesUnsupportedSection
import com.superiorsoftllc.agenthq.plugins.official.changes.ChangesWorktreeSection
import com.superiorsoftllc.agenthq.plugins.official.changes.PROJECT_CHANGES_POLL_MS
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.DiffTarget
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.shell.CoreChanges
import com.superiorsoftllc.agenthq.ui.shell.CoreGit
import com.superiorsoftllc.agenthq.ui.shell.CoreLink
import com.superiorsoftllc.agenthq.ui.shell.CoreSession

/**
 * RUNTIME-8: the whole Changes pane over the window's [state] and [controller], for the pane tests. It lived in
 * Infantry_Changes; the official jar reads only the plugin API contexts, so the AppState / Controller adapter is the
 * tests' own now.
 */
@Composable
fun ProjectChangesPanel(
    state: AppState,
    controller: Controller,
    pollMs: Long = PROJECT_CHANGES_POLL_MS,
    openDiff: DiffTarget? = null,
    onOpenDiff: ((DiffTarget) -> Unit)? = null,
) {
    ChangesPaneFrame(CoreLink(state), CoreSession(state), CoreGit(state, controller), CoreChanges(state, controller), pollMs, openDiff, onOpenDiff) { changes ->
        ChangesEmptySection(changes)
        ChangesTitleSection(changes)
        ChangesUnsupportedSection(changes)
        ChangesNotGitSection(changes)
        ChangesWorktreeSection(changes)
        ChangesHeaderSection(changes)
        ChangesModeSection(changes)
        ChangesBranchSection(changes)
        ChangesHintSection(changes)
        ChangesFilesSection(changes) { ChangesFileRow(changes, it) }
        ChangesBranchFilesSection(changes) { ChangesBranchFileRow(changes, it) }
        ChangesHistorySection(changes) { ChangesCommitRow(it) }
    }
}
