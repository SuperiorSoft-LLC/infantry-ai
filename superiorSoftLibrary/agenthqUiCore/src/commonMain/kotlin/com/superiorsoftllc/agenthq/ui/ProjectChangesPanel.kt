package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.BranchChangesView
import com.superiorsoftllc.agenthq.ui.model.ChangesView
import com.superiorsoftllc.agenthq.ui.model.DiffTarget
import com.superiorsoftllc.agenthq.ui.model.HistoryView
import com.superiorsoftllc.agenthq.ui.model.ProjectView
import com.superiorsoftllc.agenthq.ui.model.badge
import com.superiorsoftllc.agenthq.ui.model.branchChangesView
import com.superiorsoftllc.agenthq.ui.model.aheadHint
import com.superiorsoftllc.agenthq.ui.model.changesBranch
import com.superiorsoftllc.agenthq.ui.model.changesShownBranch
import com.superiorsoftllc.agenthq.ui.model.changesWorktree
import com.superiorsoftllc.agenthq.ui.model.changesWorktreeStatus
import com.superiorsoftllc.agenthq.ui.model.contractAtLeast
import com.superiorsoftllc.agenthq.ui.model.headline
import com.superiorsoftllc.agenthq.ui.model.label
import com.superiorsoftllc.agenthq.ui.model.markers
import com.superiorsoftllc.agenthq.ui.model.name
import com.superiorsoftllc.agenthq.ui.model.optionKey
import com.superiorsoftllc.agenthq.ui.model.projectChangesView
import com.superiorsoftllc.agenthq.ui.model.summary
import com.superiorsoftllc.agenthq.ui.model.whenText
import com.superiorsoftllc.agenthq.ui.model.worktreeChangesView
import com.superiorsoftllc.agenthq.ui.model.worktreeHeader
import com.superiorsoftllc.agenthq.ui.model.worktreeHistoryView
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.net.PROJECT_GIT_BRANCHES_UNSUPPORTED
import com.superiorsoftllc.agenthq.ui.net.PROJECT_GIT_UNSUPPORTED
import com.superiorsoftllc.agenthq.ui.protocol.BranchChangedFile
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.GitWorktree
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitWorktreeStatus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** How often the open Changes panel re-reads the active project's working tree. */
const val PROJECT_CHANGES_POLL_MS = 5_000L

/**
 * BUG-CHANGES-BRANCH-1: the longest a 1.88.0 Changes poll waits for the daemon's reply before it asks again anyway. On a
 * live link every request is answered or refused, so this only keeps a lost reply from stopping the polling for good.
 */
const val PROJECT_CHANGES_POLL_TIMEOUT_MS = 60_000L

const val PROJECT_CHANGES_REFRESH_TAG = "project-changes-refresh"

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

/**
 * BUG-CHANGES-BRANCH-1: one Changes poll, which returns only once the daemon replied to it, so the panel never has more
 * than one of each poll waiting. The daemon answers a connection's frames in order: polls that went out on every tick
 * without waiting piled up behind a slow git (409 unanswered under load) and a branch pick queued behind all of them.
 *
 * The wait ends on a [reply] frame naming [projectId], on the daemon's `<request> refused|failed: ...` error for it, or
 * on a `ready`: nothing asked on the old link is answered on a new one. A drop the panel sees cancels the polling
 * effects (`live` keys them), but a Connect to the same daemon keeps the link live throughout, so only that `ready`
 * ends this wait then. A send the controller refused waits for nothing, and [timeoutMs] bounds the wait.
 */
private suspend fun Controller.pollOnce(request: String, reply: String, projectId: String, timeoutMs: Long, ask: suspend () -> CommandResult) {
    coroutineScope {
        // Listening starts before the request goes out, so a fast reply cannot slip past.
        val replied = launch(start = CoroutineStart.UNDISPATCHED) { client.frames.first { it.endsPoll(request, reply, projectId) } }
        if (ask() !is CommandResult.Sent) return@coroutineScope replied.cancel()
        // A delay() timer, not withTimeoutOrNull: it runs on the poll interval's own clock. In a Compose UI test the
        // effects' delay() follows the test clock, and a withTimeoutOrNull wait there was never released by it.
        val timeout = launch { delay(timeoutMs); replied.cancel() }
        replied.join()
        timeout.cancel()
    }
}

/** True when this frame ends the wait for a [request] about [projectId]: its [reply] answer, its error, or a new link. */
private fun Envelope.endsPoll(request: String, reply: String, projectId: String): Boolean = when (type) {
    reply -> runCatching { Json.parseToJsonElement(data).jsonObject["projectId"]?.jsonPrimitive?.contentOrNull }.getOrNull() == projectId
    Envelope.ERROR -> data.startsWith("$request ")
    Envelope.READY -> true
    else -> false
}

/**
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
 * what that branch changed vs its base (`project_git_branch_changes`, contract 1.65.0). The pick lives in [AppState]
 * (per project, so it survives a re-render) and selecting another project resets it to the working tree.
 *
 * DIFF-MODAL: clicking a file calls [onOpenDiff] (the window opens [ProjectDiffModal] over everything); [openDiff]
 * is the file shown there, highlighted in the list. With no callback the rows are not clickable.
 */
@Composable
fun ProjectChangesPanel(
    state: AppState,
    controller: Controller,
    pollMs: Long = PROJECT_CHANGES_POLL_MS,
    openDiff: DiffTarget? = null,
    onOpenDiff: ((DiffTarget) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val live = state.connection.isLive
    val supported = contractAtLeast(state.connection.contract, 1, 36)
    val branchesSupported = contractAtLeast(state.connection.contract, 1, 65)
    val worktreesSupported = contractAtLeast(state.connection.contract, 1, 88)
    val active = state.activeProject
    val worktree = if (worktreesSupported) state.changesWorktree else null
    // BUG-CHANGES-WRONG-BRANCH: from 1.88.0 the inner picker follows the worktree the header shows.
    val branch = if (worktreesSupported) state.changesShownBranch else state.changesBranch
    var mode by remember { mutableStateOf(ChangesMode.WORKING_TREE) }

    // Before 1.88.0: the project directory's working tree, polled only while it is the one shown (a branch's changes
    // do not move under the panel). From 1.88.0 the shown worktree's own status replaces it (below).
    LaunchedEffect(active?.id, active?.directory, live, supported, branch == null, worktreesSupported) {
        val id = active?.id ?: return@LaunchedEffect
        if (!live || !supported || branch != null || worktreesSupported) return@LaunchedEffect
        while (true) {
            controller.loadProjectTree(id)
            delay(pollMs)
        }
    }
    // UX-CHANGES-1: the project's worktrees, naming the selected session so the daemon opens the view on its checkout.
    LaunchedEffect(active?.id, active?.directory, live, worktreesSupported, state.selectedId) {
        val id = active?.id ?: return@LaunchedEffect
        if (!live || !worktreesSupported) return@LaunchedEffect
        while (true) {
            controller.pollOnce(Envelope.PROJECT_GIT_WORKTREES, Envelope.PROJECT_GIT_WORKTREE_LIST, id, PROJECT_CHANGES_POLL_TIMEOUT_MS) {
                controller.loadProjectWorktrees(id, state.selectedId)
            }
            delay(pollMs * WORKTREE_LIST_POLLS)
        }
    }
    // UX-CHANGES-1: the shown worktree's branch, ahead/behind, changes and history, polled while it is shown.
    LaunchedEffect(active?.id, live, worktree) {
        val id = active?.id ?: return@LaunchedEffect
        val path = worktree ?: return@LaunchedEffect
        if (!live) return@LaunchedEffect
        while (true) {
            controller.pollOnce(Envelope.PROJECT_GIT_WORKTREE_STATUS, Envelope.PROJECT_GIT_WORKTREE, id, PROJECT_CHANGES_POLL_TIMEOUT_MS) {
                controller.loadWorktreeStatus(id, path)
            }
            delay(pollMs)
        }
    }
    LaunchedEffect(active?.id, active?.directory, live, branchesSupported) {
        val id = active?.id ?: return@LaunchedEffect
        if (live && branchesSupported) controller.loadProjectBranches(id)
    }
    LaunchedEffect(active?.id, active?.directory, live, branch) {
        val id = active?.id ?: return@LaunchedEffect
        if (live && branchesSupported && branch != null) controller.loadBranchChanges(id, branch)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (active == null) {
            Text("Select a project to see its changes.", color = Palette.Muted, fontSize = 12.sp)
            return@Column
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("CHANGES · ${active.name}", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1)
            // UX-ICON-1: a refresh icon in place of the "Refresh" text button.
            IconAction(
                AhqIcons.Refresh, "Refresh",
                onClick = {
                    scope.launch {
                        if (worktreesSupported) {
                            controller.loadProjectWorktrees(active.id, state.selectedId)
                            worktree?.let { controller.loadWorktreeStatus(active.id, it) }
                            if (branch != null) controller.loadBranchChanges(active.id, branch)
                        } else if (branch == null) controller.loadProjectTree(active.id) else controller.loadBranchChanges(active.id, branch)
                        if (branchesSupported) controller.loadProjectBranches(active.id)
                    }
                },
                enabled = live && supported,
                modifier = Modifier.testTag(PROJECT_CHANGES_REFRESH_TAG),
            )
        }
        if (!supported) {
            Text(PROJECT_GIT_UNSUPPORTED, color = Palette.Muted, fontSize = 12.sp)
            return@Column
        }
        if (!worktreesSupported) {
            BranchSelect(state, controller, active, branch, branchesSupported)
            if (branch == null) WorkingTreeChanges(projectChangesView(state.projectGit[active.id], state.projectGitFailure), null, openDiff, onOpenDiff)
            else BranchChanges(state, active, branch, openDiff, onOpenDiff)
            return@Column
        }
        val status = state.changesWorktreeStatus
        WorktreeSelect(state, controller, active, worktree)
        WorktreeHeaderLine(status)
        ModeToggle(mode) { mode = it }
        when (mode) {
            ChangesMode.WORKING_TREE -> {
                BranchSelect(state, controller, active, branch, branchesSupported)
                if (branch == null) {
                    aheadHint(status)?.let { Text(it, color = Palette.Muted, fontSize = 11.sp) }
                    WorkingTreeChanges(worktreeChangesView(status), worktree, openDiff, onOpenDiff)
                }
                else BranchChanges(state, active, branch, openDiff, onOpenDiff)
            }
            ChangesMode.HISTORY -> WorktreeHistory(status)
        }
    }
}

/** UX-CHANGES-1: the worktree picker: the shown worktree (branch and whose checkout it is), then every worktree. */
@Composable
private fun WorktreeSelect(state: AppState, controller: Controller, active: ProjectView, worktree: String?) {
    var open by remember { mutableStateOf(false) }
    val list = state.projectWorktrees[active.id]
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
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(max = BRANCH_MENU_MAX_WIDTH).background(Palette.Raised)) {
            when {
                list == null -> Note("Loading worktrees…")
                !list.available -> Note("Worktrees unknown: ${list.reason.ifBlank { "the daemon could not list them" }}")
                else -> list.worktrees.filter { !it.bare }.forEach { w ->
                    DropdownMenuItem(
                        text = { WorktreeOptionLabel(w, selected = w.path == worktree) },
                        onClick = { open = false; controller.selectChangesWorktree(active.id, w.path) },
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
private fun WorktreeHeaderLine(status: ProjectGitWorktreeStatus?) {
    val header = worktreeHeader(status)
    if (header == null) {
        val why = if (status == null) "Reading the worktree…" else "Git unknown: ${status.reason.ifBlank { "the daemon could not read the worktree" }}"
        Text(why, color = Palette.Muted, fontSize = 11.sp)
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("⎇", color = Palette.Muted, fontSize = 11.sp)
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
private fun ModeToggle(mode: ChangesMode, onMode: (ChangesMode) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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
private fun WorktreeHistory(status: ProjectGitWorktreeStatus?) {
    when (val history = worktreeHistoryView(status)) {
        is HistoryView.Unknown -> Text("History unknown: ${history.reason}", color = Palette.Muted, fontSize = 11.sp)
        HistoryView.Empty -> Text("No commits yet.", color = Palette.Muted, fontSize = 11.sp)
        is HistoryView.Commits -> {
            history.commits.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().testTag(CHANGES_HISTORY_ROW_TAG + c.shortHash).padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(c.shortHash, color = Palette.Accent, fontFamily = Mono, fontSize = 11.sp)
                    Column(Modifier.weight(1f)) {
                        Text(c.subject, color = Palette.Text, fontSize = 12.sp)
                        Text("${c.author} · ${c.whenText()}", color = Palette.Muted, fontSize = 10.sp)
                    }
                }
            }
            if (history.truncated) Text("Older commits exist; showing the newest ${history.commits.size}.", color = Palette.Muted, fontSize = 10.sp)
        }
    }
}

/** The branch line as a drop-down: "Working tree (current)" first, then the project's local branches. */
@Composable
private fun BranchSelect(state: AppState, controller: Controller, active: ProjectView, branch: String?, supported: Boolean) {
    var open by remember { mutableStateOf(false) }
    val status = state.projectGit[active.id]
    val list = state.projectGitBranches[active.id]
    val shown = branch ?: "Working tree (current)"
    val detail = if (branch == null && status?.available == true) status.headline() else null
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised)
                .clickable(role = Role.Button, onClickLabel = "Choose what the Changes panel shows") { open = true }
                .semantics { contentDescription = "Showing: $shown" }
                .testTag(BRANCH_SELECT_TAG)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⎇ ", color = Palette.Muted, fontSize = 11.sp)
            Text(shown, color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(" · ", color = Palette.Muted, fontSize = 11.sp)
                Text(detail, color = Palette.Muted, fontSize = 11.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.weight(1f))
            Text(" ▾", color = Palette.Muted, fontSize = 11.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(max = BRANCH_MENU_MAX_WIDTH).background(Palette.Raised)) {
            DropdownMenuItem(
                text = { Text("Working tree (current)", color = Palette.Text, fontSize = 12.sp, fontWeight = if (branch == null) FontWeight.SemiBold else FontWeight.Normal) },
                onClick = { open = false; controller.selectChangesBranch(active.id, null) },
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
                            onClick = { open = false; controller.selectChangesBranch(active.id, b.name) },
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
private fun WorkingTreeChanges(changes: ChangesView, worktree: String?, openDiff: DiffTarget?, onOpenDiff: ((DiffTarget) -> Unit)?) {
    when (changes) {
        is ChangesView.Unknown -> Text("Git unknown: ${changes.reason}", color = Palette.Muted, fontSize = 11.sp)
        ChangesView.Clean -> Text("Working tree clean. Nothing to commit.", color = Palette.Ok, fontSize = 11.sp)
        is ChangesView.Files -> {
            Text(changes.summary(), color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            val picked = openDiff?.takeIf { it.branch == null && it.worktree == worktree }?.let { it.path to it.staged }
            ChangeGroup("Staged", changes.staged, staged = true, picked = picked, onPick = onOpenDiff?.let { open -> { f -> open(DiffTarget(f.path, true, worktree = worktree)) } })
            ChangeGroup("Not staged", changes.unstaged, staged = false, picked = picked, onPick = onOpenDiff?.let { open -> { f -> open(DiffTarget(f.path, false, worktree = worktree)) } })
        }
    }
}

/** What [branch] changed vs its base. Unknown shows the daemon's reason; an available empty list is a known "no changes". */
@Composable
private fun BranchChanges(state: AppState, active: ProjectView, branch: String, openDiff: DiffTarget?, onOpenDiff: ((DiffTarget) -> Unit)?) {
    when (val view = branchChangesView(state.projectGitBranchChanges[active.id], branch)) {
        is BranchChangesView.Unknown -> Text("Branch unknown: ${view.reason}", color = Palette.Muted, fontSize = 11.sp)
        is BranchChangesView.Files -> {
            Text(view.header(), color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono)
            if (view.files.isEmpty()) Text("No changes vs ${view.base}.", color = Palette.Ok, fontSize = 11.sp)
            view.files.forEach { f -> BranchChangeRow(f, picked = openDiff == DiffTarget(f.path, false, branch), onPick = onOpenDiff?.let { open -> { open(DiffTarget(f.path, false, branch)) } }) }
            if (view.truncated) Text("More files changed than are listed.", color = Palette.Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun BranchChangeRow(f: BranchChangedFile, picked: Boolean, onPick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().then(if (onPick != null) Modifier.clickable(role = Role.Button, onClick = onPick) else Modifier)
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
