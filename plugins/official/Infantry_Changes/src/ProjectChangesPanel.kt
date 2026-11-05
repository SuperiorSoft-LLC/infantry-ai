// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.changes

import com.superiorsoftllc.infantry.ui.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.BranchChangesView
import com.superiorsoftllc.infantry.ui.model.ChangesView
import com.superiorsoftllc.infantry.ui.model.DiffTarget
import com.superiorsoftllc.infantry.ui.model.HistoryView
import com.superiorsoftllc.infantry.ui.model.ProjectView
import com.superiorsoftllc.infantry.ui.model.badge
import com.superiorsoftllc.infantry.ui.model.branchChangesView
import com.superiorsoftllc.infantry.ui.model.aheadHint
import com.superiorsoftllc.infantry.ui.model.contractAtLeast
import com.superiorsoftllc.infantry.ui.model.headline
import com.superiorsoftllc.infantry.ui.model.label
import com.superiorsoftllc.infantry.ui.model.markers
import com.superiorsoftllc.infantry.ui.model.name
import com.superiorsoftllc.infantry.ui.model.optionKey
import com.superiorsoftllc.infantry.ui.model.projectChangesView
import com.superiorsoftllc.infantry.ui.model.summary
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitWorktreeList
import com.superiorsoftllc.infantry.ui.model.whenText
import com.superiorsoftllc.infantry.ui.model.worktreeChangesView
import com.superiorsoftllc.infantry.ui.model.worktreeHeader
import com.superiorsoftllc.infantry.ui.model.worktreeHistoryView
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.shell.ChangesContext
import com.superiorsoftllc.infantry.ui.shell.GitContext
import com.superiorsoftllc.infantry.ui.shell.LinkContext
import com.superiorsoftllc.infantry.ui.shell.SessionContext
import com.superiorsoftllc.infantry.ui.net.PROJECT_GIT_BRANCHES_UNSUPPORTED
import com.superiorsoftllc.infantry.ui.net.PROJECT_GIT_UNSUPPORTED
import com.superiorsoftllc.infantry.ui.protocol.BranchChangedFile
import com.superiorsoftllc.infantry.ui.protocol.ChangedFile
import com.superiorsoftllc.infantry.ui.protocol.GitCommit
import com.superiorsoftllc.infantry.ui.protocol.GitWorktree
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitWorktreeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** How often the open Changes panel re-reads the active project's working tree. */
const val PROJECT_CHANGES_POLL_MS = 5_000L

/**
 * BUG-CHANGES-BRANCH-1: the longest a 1.88.0 Changes poll waits for the daemon's reply before it asks again anyway. On a
 * live link every request is answered or refused, so this only keeps a lost reply from stopping the polling for good.
 */
const val PROJECT_CHANGES_POLL_TIMEOUT_MS = 60_000L

const val PROJECT_CHANGES_REFRESH_TAG = "project-changes-refresh"

/** BLOCKS-LAYOUT L18: the Changes tab's scrolled column, which its sections draw into. */
const val CHANGES_PANE_TAG = "changes-pane"

/** BRANCH-VIEW-2: the branch selector's anchor; each entry is [BRANCH_SELECT_OPTION_TAG] + branch name. */
const val BRANCH_SELECT_TAG = "branch-select"
const val BRANCH_SELECT_OPTION_TAG = "branch-select-option-"
const val BRANCH_SELECT_NAME_TAG = "branch-select-name-"
const val BRANCH_SELECT_TAIL_TAG = "branch-select-tail-"

/** Caps the branch dropdown so a long branch name cannot stretch it across the whole panel. */
val BRANCH_MENU_MAX_WIDTH = 360.dp

/** The selector key of the default entry, "Working tree (current)". */
const val WORKING_TREE_KEY = "working-tree"

/** BRANCH-VIEW-2: one row of a branch's file list is [BRANCH_CHANGE_ROW_TAG] + its path. */
const val BRANCH_CHANGE_ROW_TAG = "branch-change-row-"

const val NOT_A_GIT_REPOSITORY = "Not a git repository: this project has no worktrees or changes to show."
const val NOT_A_GIT_REPOSITORY_TAG = "changes-not-a-git-repository"

/** The daemon answered that the project folder is not a git checkout: a final state, not an unknown one. */
internal fun isNotGitRepository(list: ProjectGitWorktreeList?): Boolean =
    list != null && !list.available && list.reason.contains("not a git repository", ignoreCase = true)

/** UX-CHANGES-1: the worktree picker's anchor; each entry is [WORKTREE_SELECT_OPTION_TAG] + its branch (else folder). */
const val WORKTREE_SELECT_TAG = "worktree-select"
const val WORKTREE_SELECT_OPTION_TAG = "worktree-select-option-"

/** UX-CHANGES-1: the header line of the shown worktree. */
const val CHANGES_BRANCH_TAG = "changes-branch"
const val CHANGES_AHEAD_TAG = "changes-ahead"
const val CHANGES_BEHIND_TAG = "changes-behind"
const val CHANGES_BASE_TAG = "changes-base"
const val CHANGES_UNCOMMITTED_TAG = "changes-uncommitted"

/** UX-CHANGES-1: the Working tree | History toggle, and one History row ([CHANGES_HISTORY_ROW_TAG] + short hash). */
const val CHANGES_MODE_WORKING_TREE_TAG = "changes-mode-working-tree"
const val CHANGES_MODE_HISTORY_TAG = "changes-mode-history"
const val CHANGES_HISTORY_ROW_TAG = "changes-history-row-"

/** UX-CHANGES-1: the worktree list moves rarely (a ticket start cuts one), so it is re-read this many polls apart. */
private const val WORKTREE_LIST_POLLS = 6

/** What the Changes tab's body shows under the header. */
enum class ChangesMode { WORKING_TREE, HISTORY }

/*
 * UX-CHANGES-1: the right-hand Changes tab, which also took over the old Worktree tab's job. On a 1.88.0 daemon its
 * header is a worktree picker (the project's worktrees; it opens on the SELECTED SESSION's checkout, its worktree or the
 * shared one, as the daemon decides), the branch with ahead/behind its base and the uncommitted count, then a Working
 * tree | History toggle. Working tree is the file list below; History is the shown worktree's recent commits
 * (read-only). Nothing here fetches, pulls, pushes or commits. An older daemon gets the panel described below.
 *
 * The active project's working tree as a right-hand side panel, next to the session: branch line, changed files
 * with their status, and a Refresh button. The same `project_git_status` answer the Git tab draws, decided by the
 * same tested view ([projectChangesView]): an unreadable tree is "unknown" with the daemon's reason, never "clean".
 *
 * The daemon computes status on demand, so a panel that only listened would show the tree as of connect time while
 * an agent is editing files beside it. While the panel is open on the working tree it asks again every [pollMs] (the
 * effect leaves composition with the tab, which stops the polling); a daemon older than 1.36.0 is sent nothing. From
 * 1.88.0 a poll is asked again only [pollMs] after the daemon replied to the last one ([pollOnce], BUG-CHANGES-BRANCH-1).
 *
 * BRANCH-VIEW-2: the branch line is a selector. "Working tree (current)" is the default; picking a local branch shows
 * what that branch changed vs its base (`project_git_branch_changes`, contract 1.65.0). The pick lives in the window state
 * (per project, so it survives a re-render) and selecting another project resets it to the working tree.
 *
 * DIFF-MODAL: clicking a file calls [onOpenDiff] (the window opens [ProjectDiffModal] over everything); [openDiff]
 * is the file shown there, highlighted in the list. With no callback the rows are not clickable.
 *
 * BLOCKS-LAYOUT L18: [ChangesPaneFrame] and its sections, top to bottom, as the Changes tab's tree draws them
 * (`infantry.shell.changes`, ShellChanges.kt). RUNTIME-8: everything here reads the plugin API contexts (link, session,
 * git, changes); the window's AppState / Controller are not used.
 */

/** BLOCKS-LAYOUT L18: one working-tree file as the file template draws it: the file and the group it is listed in. */
data class ChangesFileItem(val file: ChangedFile, val staged: Boolean)

/**
 * BLOCKS-LAYOUT L18: what the Changes tab's sections read and act on. The frame ([ChangesPaneFrame]) owns the Working
 * tree | History mode; a section changes it, the shown worktree or branch, or the open diff only through these callbacks.
 */
class ChangesPaneHost internal constructor(
    val link: LinkContext,
    val session: SessionContext,
    /** S4b: the working tree and the branch list (the shell's [com.superiorsoftllc.infantry.ui.shell.ShellContext.git]). */
    val git: GitContext,
    /** S4b: the worktrees, branch changes and polls (the shell's [com.superiorsoftllc.infantry.ui.shell.ShellContext.changes]). */
    val context: ChangesContext,
    private val scope: CoroutineScope,
    private val modeState: MutableState<ChangesMode>,
    /** The file the diff modal shows, highlighted in the lists. */
    val openDiff: DiffTarget?,
    private val onOpenDiff: ((DiffTarget) -> Unit)?,
) {
    val live: Boolean get() = link.live
    val supported: Boolean get() = link.contractAtLeast(1, 36)
    val branchesSupported: Boolean get() = link.contractAtLeast(1, 65)
    val worktreesSupported: Boolean get() = link.contractAtLeast(1, 88)
    val active: ProjectView? get() = session.activeProject
    val worktree: String? get() = if (worktreesSupported) context.worktree else null

    /** BUG-CHANGES-WRONG-BRANCH: from 1.88.0 the inner picker follows the worktree the header shows. */
    val branch: String? get() = if (worktreesSupported) context.shownBranch else context.branch
    val mode: ChangesMode get() = modeState.value
    val status: ProjectGitWorktreeStatus? get() = context.worktreeStatus

    /** A daemon from 1.36.0, older than 1.88.0: the branch line and its list, no worktrees. */
    val showsOlder: Boolean get() = active != null && supported && !worktreesSupported

    /** A 1.88.0 daemon answered that the project folder is not a git checkout. */
    val notGit: Boolean get() = active?.let { supported && worktreesSupported && isNotGitRepository(context.worktrees(it.id)) } ?: false

    /** A 1.88.0 daemon on a git checkout: the worktree picker, its header line and the mode toggle. */
    val showsWorktrees: Boolean get() = active != null && supported && worktreesSupported && !notGit

    /** The branch line and the list under it show: an older daemon, or the Working tree mode. */
    val showsList: Boolean get() = showsOlder || (showsWorktrees && mode == ChangesMode.WORKING_TREE)

    /** A click on a file opens it in the diff modal; false leaves the rows unclickable. */
    val opensDiff: Boolean get() = onOpenDiff != null

    fun refresh() {
        val a = active ?: return
        val worktree = worktree
        val branch = branch
        scope.launch {
            if (worktreesSupported) {
                context.loadWorktrees(a.id, session.selectedAgentId)
                worktree?.let { context.loadWorktreeStatus(a.id, it) }
                if (branch != null) context.loadBranchChanges(a.id, branch)
            } else if (branch == null) context.loadTree(a.id) else context.loadBranchChanges(a.id, branch)
            if (branchesSupported) git.loadBranches(a.id)
        }
    }

    fun pickMode(mode: ChangesMode) { modeState.value = mode }

    fun selectWorktree(path: String) { active?.let { context.selectWorktree(it.id, path) } }

    /** Null is "Working tree (current)". */
    fun selectBranch(name: String?) { active?.let { context.selectBranch(it.id, name) } }

    fun open(target: DiffTarget) { onOpenDiff?.invoke(target) }
}

/**
 * BLOCKS-LAYOUT L18: the Changes tab's frame: the scrolled padded column its sections draw straight into ([content]), 6 dp
 * apart. It owns the Working tree | History mode and the polling (below), which leaves composition with the tab.
 */
@Composable
fun ChangesPaneFrame(
    link: LinkContext,
    session: SessionContext,
    git: GitContext,
    ctx: ChangesContext,
    pollMs: Long = PROJECT_CHANGES_POLL_MS,
    openDiff: DiffTarget? = null,
    onOpenDiff: ((DiffTarget) -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(ChangesPaneHost) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val mode = remember { mutableStateOf(ChangesMode.WORKING_TREE) }
    val changes = ChangesPaneHost(link, session, git, ctx, scope, mode, openDiff, onOpenDiff)
    val live = changes.live
    val supported = changes.supported
    val branchesSupported = changes.branchesSupported
    val worktreesSupported = changes.worktreesSupported
    val active = changes.active
    val worktree = changes.worktree
    val branch = changes.branch

    // Before 1.88.0: the project directory's working tree, polled only while it is the one shown (a branch's changes
    // do not move under the panel). From 1.88.0 the shown worktree's own status replaces it (below).
    LaunchedEffect(active?.id, active?.directory, live, supported, branch == null, worktreesSupported) {
        val id = active?.id ?: return@LaunchedEffect
        if (!live || !supported || branch != null || worktreesSupported) return@LaunchedEffect
        while (true) {
            ctx.loadTree(id)
            delay(pollMs)
        }
    }
    // UX-CHANGES-1: the project's worktrees, naming the selected session so the daemon opens the view on its checkout.
    val selectedId = session.selectedAgentId
    LaunchedEffect(active?.id, active?.directory, live, worktreesSupported, selectedId) {
        val id = active?.id ?: return@LaunchedEffect
        if (!live || !worktreesSupported) return@LaunchedEffect
        while (true) {
            ctx.pollWorktrees(id, selectedId, PROJECT_CHANGES_POLL_TIMEOUT_MS)
            delay(pollMs * WORKTREE_LIST_POLLS)
        }
    }
    // UX-CHANGES-1: the shown worktree's branch, ahead/behind, changes and history, polled while it is shown.
    LaunchedEffect(active?.id, live, worktree) {
        val id = active?.id ?: return@LaunchedEffect
        val path = worktree ?: return@LaunchedEffect
        if (!live) return@LaunchedEffect
        while (true) {
            ctx.pollWorktreeStatus(id, path, PROJECT_CHANGES_POLL_TIMEOUT_MS)
            delay(pollMs)
        }
    }
    LaunchedEffect(active?.id, active?.directory, live, branchesSupported) {
        val id = active?.id ?: return@LaunchedEffect
        if (live && branchesSupported) git.loadBranches(id)
    }
    LaunchedEffect(active?.id, active?.directory, live, branch) {
        val id = active?.id ?: return@LaunchedEffect
        if (live && branchesSupported && branch != null) ctx.loadBranchChanges(id, branch)
    }

    Column(modifier.fillMaxSize().testTag(CHANGES_PANE_TAG).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        content(changes)
    }
}

/** BLOCKS-LAYOUT L18: what the tab says with no project selected; [modifier] goes on the line. */
@Composable
fun ChangesEmptySection(changes: ChangesPaneHost, modifier: Modifier = Modifier) {
    if (changes.active != null) return
    Text("Select a project to see its changes.", color = Palette.Muted, fontSize = 12.sp, modifier = modifier)
}

/** BLOCKS-LAYOUT L18: "CHANGES · project" and the refresh icon, with a project selected; [modifier] goes on the row. */
@Composable
fun ChangesTitleSection(changes: ChangesPaneHost, modifier: Modifier = Modifier) {
    val active = changes.active ?: return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("CHANGES · ${active.name}", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1)
        // UX-ICON-1: a refresh icon in place of the "Refresh" text button.
        IconAction(
            AhqIcons.Refresh, "Refresh",
            onClick = { changes.refresh() },
            enabled = changes.live && changes.supported,
            modifier = Modifier.testTag(PROJECT_CHANGES_REFRESH_TAG),
            compact = true,
        )
    }
}

/** BLOCKS-LAYOUT L18: the line for a daemon older than 1.36.0, with a project selected; [modifier] goes on the line. */
@Composable
fun ChangesUnsupportedSection(changes: ChangesPaneHost, modifier: Modifier = Modifier) {
    if (changes.active == null || changes.supported) return
    Text(PROJECT_GIT_UNSUPPORTED, color = Palette.Muted, fontSize = 12.sp, modifier = modifier)
}

/** BLOCKS-LAYOUT L18: [NOT_A_GIT_REPOSITORY], when the daemon says so; [modifier] goes on the line. */
@Composable
fun ChangesNotGitSection(changes: ChangesPaneHost, modifier: Modifier = Modifier) {
    if (!changes.notGit) return
    Text(NOT_A_GIT_REPOSITORY, color = Palette.Muted, fontSize = 12.sp, modifier = modifier.testTag(NOT_A_GIT_REPOSITORY_TAG))
}

/**
 * BLOCKS-LAYOUT L18: the worktree picker (and why the worktrees are unknown), from 1.88.0. A placement other than the
 * default gets a Column to wear it.
 */
@Composable
fun ChangesWorktreeSection(changes: ChangesPaneHost, modifier: Modifier = Modifier) {
    if (!changes.showsWorktrees) return
    ChangesPlaced(modifier) { WorktreeSelect(changes) }
}

/** BLOCKS-LAYOUT L18: the shown worktree's branch, ahead/behind, base and uncommitted count; [modifier] goes on the row. */
@Composable
fun ChangesHeaderSection(changes: ChangesPaneHost, modifier: Modifier = Modifier) {
    if (!changes.showsWorktrees) return
    WorktreeHeaderLine(changes.status, modifier)
}

/** BLOCKS-LAYOUT L18: the Working tree | History toggle; [modifier] goes on the row. */
@Composable
fun ChangesModeSection(changes: ChangesPaneHost, modifier: Modifier = Modifier) {
    if (!changes.showsWorktrees) return
    ModeToggle(changes.mode, modifier) { changes.pickMode(it) }
}

/** BLOCKS-LAYOUT L18: the branch line (Working tree (current) or a local branch) as a drop-down; [modifier] goes on its box. */
@Composable
fun ChangesBranchSection(changes: ChangesPaneHost, modifier: Modifier = Modifier) {
    if (!changes.showsList) return
    BranchSelect(changes, modifier)
}

/** BLOCKS-LAYOUT L18: "N commits ahead" over a clean shown worktree; [modifier] goes on the line. */
@Composable
fun ChangesHintSection(changes: ChangesPaneHost, modifier: Modifier = Modifier) {
    if (!changes.showsWorktrees || !changes.showsList || changes.branch != null) return
    aheadHint(changes.status)?.let { Text(it, color = Palette.Muted, fontSize = 11.sp, modifier = modifier) }
}

/**
 * BLOCKS-LAYOUT L18: the working tree's files: unknown, clean, or the summary then the Staged and Not staged groups, each
 * file drawn by [file] straight into the tab's column. With files, a placement other than the default gets a Column.
 */
@Composable
fun ChangesFilesSection(changes: ChangesPaneHost, modifier: Modifier = Modifier, file: @Composable (ChangesFileItem) -> Unit) {
    if (!changes.showsList || changes.branch != null) return
    val active = changes.active ?: return
    val view = if (changes.worktreesSupported) worktreeChangesView(changes.status) else projectChangesView(changes.git.status(active.id), changes.git.failure)
    WorkingTreeChanges(view, modifier, file)
}

/**
 * BLOCKS-LAYOUT L18: what the picked branch changed vs its base: unknown, or the header, each file drawn by [row]
 * straight into the tab's column, and the truncation line. With files, a placement other than the default gets a Column.
 */
@Composable
fun ChangesBranchFilesSection(changes: ChangesPaneHost, modifier: Modifier = Modifier, row: @Composable (BranchChangedFile) -> Unit) {
    if (!changes.showsList) return
    val active = changes.active ?: return
    val branch = changes.branch ?: return
    BranchChanges(changes.context, active, branch, modifier, row)
}

/**
 * BLOCKS-LAYOUT L18: the shown worktree's recent commits in the History mode, each drawn by [commit] straight into the
 * tab's column. With commits, a placement other than the default gets a Column.
 */
@Composable
fun ChangesHistorySection(changes: ChangesPaneHost, modifier: Modifier = Modifier, commit: @Composable (GitCommit) -> Unit) {
    if (!changes.showsWorktrees || changes.mode != ChangesMode.HISTORY) return
    WorktreeHistory(changes.status, modifier, commit)
}

/** BLOCKS-LAYOUT L18: one working-tree file (badge, path, status); a click opens its diff. [modifier] goes on the row. */
@Composable
fun ChangesFileRow(changes: ChangesPaneHost, item: ChangesFileItem, modifier: Modifier = Modifier) {
    val worktree = changes.worktree
    val picked = changes.openDiff?.takeIf { it.branch == null && it.worktree == worktree }?.let { it.path to it.staged }
    val target = DiffTarget(item.file.path, item.staged, worktree = worktree)
    ChangedFileRow(item.file, open = picked == (item.file.path to item.staged), onPick = if (changes.opensDiff) { { changes.open(target) } } else null, modifier = modifier)
}

/** BLOCKS-LAYOUT L18: one file the picked branch changed; a click opens its diff. [modifier] goes on the row. */
@Composable
fun ChangesBranchFileRow(changes: ChangesPaneHost, f: BranchChangedFile, modifier: Modifier = Modifier) {
    val branch = changes.branch ?: return
    val target = DiffTarget(f.path, false, branch)
    BranchChangeRow(f, picked = changes.openDiff == target, onPick = if (changes.opensDiff) { { changes.open(target) } } else null, modifier = modifier)
}

/** A section that draws several items: a placement other than the default gets a Column (6 dp apart, as the tab) to wear it. */
@Composable
private fun ChangesPlaced(modifier: Modifier, content: @Composable () -> Unit) {
    if (modifier == Modifier) content() else Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/** UX-CHANGES-1: the worktree picker: the shown worktree (branch and whose checkout it is), then every worktree. */
@Composable
private fun WorktreeSelect(changes: ChangesPaneHost) {
    val active = changes.active ?: return
    val worktree = changes.worktree
    var open by remember { mutableStateOf(false) }
    val list = changes.context.worktrees(active.id)
    val shown = list?.worktrees?.firstOrNull { it.path == worktree }
    val label = when {
        list == null -> "Loading worktrees…"
        !list.available -> "Worktrees unknown"
        shown != null -> (listOf(shown.name()) + shown.markers()).joinToString(" · ")
        else -> worktree ?: "No worktree"
    }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised)
                .clickable(role = Role.Button, onClickLabel = "Choose the worktree the Changes tab shows") { open = true }
                .semantics { contentDescription = "Worktree: $label" }
                .testTag(WORKTREE_SELECT_TAG)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("▤ ", color = Palette.Muted, fontSize = 11.sp)
            Text(label, color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            Text(" ▾", color = Palette.Muted, fontSize = 11.sp)
        }
        com.superiorsoftllc.infantry.ui.InfantryDropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(max = BRANCH_MENU_MAX_WIDTH).background(Palette.Raised)) {
            when {
                list == null -> Note("Loading worktrees…")
                !list.available -> Note("Worktrees unknown: ${list.reason.ifBlank { "the daemon could not list them" }}")
                else -> list.worktrees.filter { !it.bare }.forEach { w ->
                    DropdownMenuItem(
                        text = { WorktreeOptionLabel(w, selected = w.path == worktree) },
                        onClick = { open = false; changes.selectWorktree(w.path) },
                        modifier = Modifier.testTag(WORKTREE_SELECT_OPTION_TAG + w.optionKey()),
                    )
                }
            }
        }
    }
    if (list != null && !list.available) {
        Text("Worktrees unknown: ${list.reason.ifBlank { "the daemon could not list them" }}", color = Palette.Muted, fontSize = 11.sp)
    }
}

/** One worktree in the picker: its branch (ellipsized), whose checkout it is, and its folder on a second line. */
@Composable
private fun WorktreeOptionLabel(w: GitWorktree, selected: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                w.name(), color = Palette.Text, fontSize = 12.sp, fontFamily = Mono,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            w.markers().takeIf { it.isNotEmpty() }?.let { markers ->
                Text("  ${markers.joinToString(", ")}", color = Palette.Muted, fontSize = 10.sp, maxLines = 1, softWrap = false)
            }
        }
        Text(w.path, color = Palette.Muted, fontSize = 9.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The shown worktree's branch, ahead/behind its base and uncommitted count; the daemon's reason when it is unknown. */
@Composable
private fun WorktreeHeaderLine(status: ProjectGitWorktreeStatus?, modifier: Modifier = Modifier) {
    val header = worktreeHeader(status)
    if (header == null) {
        val why = if (status == null) "Reading the worktree…" else "Git unknown: ${status.reason.ifBlank { "the daemon could not read the worktree" }}"
        Text(why, color = Palette.Muted, fontSize = 11.sp, modifier = modifier)
        return
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AhqIcons.Branch, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(12.dp))
        Text(
            header.branch, color = Palette.Text, fontSize = 12.sp, fontFamily = Mono, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag(CHANGES_BRANCH_TAG),
        )
        header.ahead?.let {
            Text(it, color = if ((status?.ahead ?: 0) > 0) Palette.Ok else Palette.Muted, fontSize = 11.sp, fontFamily = Mono, maxLines = 1, softWrap = false, modifier = Modifier.testTag(CHANGES_AHEAD_TAG))
        }
        header.behind?.let {
            Text(it, color = if ((status?.behind ?: 0) > 0) Palette.Accent else Palette.Muted, fontSize = 11.sp, fontFamily = Mono, maxLines = 1, softWrap = false, modifier = Modifier.testTag(CHANGES_BEHIND_TAG))
        }
        Text(header.base, color = Palette.Muted, fontSize = 10.sp, maxLines = 1, softWrap = false, modifier = Modifier.testTag(CHANGES_BASE_TAG))
        Spacer(Modifier.weight(1f))
        Text(
            header.uncommitted, color = if (status?.uncommitted == 0) Palette.Ok else Palette.Accent, fontSize = 10.sp,
            maxLines = 1, softWrap = false, modifier = Modifier.testTag(CHANGES_UNCOMMITTED_TAG),
        )
    }
}

/** The Working tree | History segmented toggle: the panel's full width, each segment half of it. */
@Composable
private fun ModeToggle(mode: ChangesMode, modifier: Modifier = Modifier, onMode: (ChangesMode) -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Segment("Working tree", mode == ChangesMode.WORKING_TREE, CHANGES_MODE_WORKING_TREE_TAG) { onMode(ChangesMode.WORKING_TREE) }
        Segment("History", mode == ChangesMode.HISTORY, CHANGES_MODE_HISTORY_TAG) { onMode(ChangesMode.HISTORY) }
    }
}

@Composable
private fun RowScope.Segment(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Palette.Text else Palette.Muted,
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).clip(RoundedCornerShape(5.dp)).background(if (selected) Palette.Border else Palette.Raised)
            .clickable(role = Role.Tab, onClickLabel = "Show $label") { onClick() }
            .testTag(tag)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** The shown worktree's recent commits, newest first: short hash, subject, author and time. Read-only. */
@Composable
private fun WorktreeHistory(status: ProjectGitWorktreeStatus?, modifier: Modifier, commit: @Composable (GitCommit) -> Unit) {
    when (val history = worktreeHistoryView(status)) {
        is HistoryView.Unknown -> Text("History unknown: ${history.reason}", color = Palette.Muted, fontSize = 11.sp, modifier = modifier)
        HistoryView.Empty -> Text("No commits yet.", color = Palette.Muted, fontSize = 11.sp, modifier = modifier)
        is HistoryView.Commits -> ChangesPlaced(modifier) {
            history.commits.forEach { commit(it) }
            if (history.truncated) Text("Older commits exist; showing the newest ${history.commits.size}.", color = Palette.Muted, fontSize = 10.sp)
        }
    }
}

/** BLOCKS-LAYOUT L18: one commit (short hash, subject, author and time); [modifier] goes on the row. */
@Composable
fun ChangesCommitRow(c: GitCommit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().testTag(CHANGES_HISTORY_ROW_TAG + c.shortHash).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(c.shortHash, color = Palette.Accent, fontFamily = Mono, fontSize = 11.sp)
        Column(Modifier.weight(1f)) {
            Text(c.subject, color = Palette.Text, fontSize = 12.sp)
            Text("${c.author} · ${c.whenText()}", color = Palette.Muted, fontSize = 10.sp)
        }
    }
}

/** The branch line as a drop-down: "Working tree (current)" first, then the project's local branches. */
@Composable
private fun BranchSelect(changes: ChangesPaneHost, modifier: Modifier) {
    val active = changes.active ?: return
    val branch = changes.branch
    val supported = changes.branchesSupported
    var open by remember { mutableStateOf(false) }
    val status = changes.git.status(active.id)
    val list = changes.git.branches(active.id)
    val shown = branch ?: "Working tree (current)"
    val detail = if (branch == null && status?.available == true) status.headline() else null
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised)
                .clickable(role = Role.Button, onClickLabel = "Choose what the Changes panel shows") { open = true }
                .semantics { contentDescription = "Showing: $shown" }
                .testTag(BRANCH_SELECT_TAG)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AhqIcons.Branch, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp))
            Text(shown, color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(" · ", color = Palette.Muted, fontSize = 11.sp)
                Text(detail, color = Palette.Muted, fontSize = 11.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.weight(1f))
            Text(" ▾", color = Palette.Muted, fontSize = 11.sp)
        }
        com.superiorsoftllc.infantry.ui.InfantryDropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(max = BRANCH_MENU_MAX_WIDTH).background(Palette.Raised)) {
            DropdownMenuItem(
                text = { Text("Working tree (current)", color = Palette.Text, fontSize = 12.sp, fontWeight = if (branch == null) FontWeight.SemiBold else FontWeight.Normal) },
                onClick = { open = false; changes.selectBranch(null) },
                modifier = Modifier.testTag(BRANCH_SELECT_OPTION_TAG + WORKING_TREE_KEY),
            )
            when {
                !supported -> Note(PROJECT_GIT_BRANCHES_UNSUPPORTED)
                list == null -> Note("Loading branches…")
                !list.available -> Note("Branches unknown: ${list.reason.ifBlank { "the daemon could not list them" }}")
                list.branches.isEmpty() -> Note("No local branches.")
                else -> {
                    list.branches.forEach { b ->
                        val tail = listOfNotNull("checked out".takeIf { b.name == list.current }, "worktree".takeIf { b.isWorktree }).joinToString(", ")
                        DropdownMenuItem(
                            text = { BranchOptionLabel(b.name, tail, selected = b.name == branch) },
                            onClick = { open = false; changes.selectBranch(b.name) },
                            modifier = Modifier.testTag(BRANCH_SELECT_OPTION_TAG + b.name),
                        )
                    }
                    if (list.truncated) Note("More branches exist than are listed.")
                }
            }
        }
    }
}

/**
 * BUG-BRANCH-WRAP: one branch entry. The name ellipsizes and yields width; the tail never wraps, so a long
 * name cannot squeeze "worktree" into a one-character column.
 * Public so the desktop shell's Compose UI tests can lay it out (the core module carries no desktop runtime).
 */
@Composable
fun BranchOptionLabel(name: String, tail: String, selected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            name, color = Palette.Text, fontSize = 12.sp, fontFamily = Mono,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).testTag(BRANCH_SELECT_NAME_TAG + name),
        )
        if (tail.isNotEmpty()) {
            Text("  $tail", color = Palette.Muted, fontSize = 10.sp, maxLines = 1, softWrap = false, modifier = Modifier.testTag(BRANCH_SELECT_TAIL_TAG + name))
        }
    }
}

@Composable
private fun Note(text: String) = Text(text, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))

/** A working tree's files; [worktree] null is the project's directory (pre-1.88.0), a path is that worktree. */
@Composable
private fun WorkingTreeChanges(changes: ChangesView, modifier: Modifier, file: @Composable (ChangesFileItem) -> Unit) {
    when (changes) {
        is ChangesView.Unknown -> Text("Git unknown: ${changes.reason}", color = Palette.Muted, fontSize = 11.sp, modifier = modifier)
        ChangesView.Clean -> Text("Working tree clean. Nothing to commit.", color = Palette.Ok, fontSize = 11.sp, modifier = modifier)
        is ChangesView.Files -> ChangesPlaced(modifier) {
            Text(changes.summary(), color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            ChangeGroup("Staged", changes.staged, staged = true, picked = null, row = { file(ChangesFileItem(it, staged = true)) }, onPick = null)
            ChangeGroup("Not staged", changes.unstaged, staged = false, picked = null, row = { file(ChangesFileItem(it, staged = false)) }, onPick = null)
        }
    }
}

/** What [branch] changed vs its base. Unknown shows the daemon's reason; an available empty list is a known "no changes". */
@Composable
private fun BranchChanges(ctx: ChangesContext, active: ProjectView, branch: String, modifier: Modifier, row: @Composable (BranchChangedFile) -> Unit) {
    when (val view = branchChangesView(ctx.branchChanges(active.id), branch)) {
        is BranchChangesView.Unknown -> Text("Branch unknown: ${view.reason}", color = Palette.Muted, fontSize = 11.sp, modifier = modifier)
        is BranchChangesView.Files -> ChangesPlaced(modifier) {
            Text(view.header(), color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono)
            if (view.files.isEmpty()) Text("No changes vs ${view.base}.", color = Palette.Ok, fontSize = 11.sp)
            view.files.forEach { row(it) }
            if (view.truncated) Text("More files changed than are listed.", color = Palette.Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun BranchChangeRow(f: BranchChangedFile, picked: Boolean, onPick: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().then(if (onPick != null) Modifier.clickable(role = Role.Button, onClick = onPick) else Modifier)
            .testTag(BRANCH_CHANGE_ROW_TAG + f.path).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(f.status.badge(), color = Palette.Accent, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(12.dp))
        ChangedPathText(if (f.fromPath != null) "${f.fromPath} -> ${f.path}" else f.path, if (picked) Palette.Accent else Palette.Text)
        if (f.binary) {
            Text("binary", color = Palette.Muted, fontSize = 9.sp, maxLines = 1, softWrap = false)
        } else {
            Text("+${f.added}", color = Palette.Ok, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, softWrap = false)
            Text("−${f.removed}", color = Palette.Danger, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, softWrap = false)
        }
        Text(f.status.label(), color = Palette.Muted, fontSize = 9.sp, maxLines = 1, softWrap = false)
    }
}
