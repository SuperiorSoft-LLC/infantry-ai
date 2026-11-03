// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.git

import com.superiorsoftllc.infantry.ui.shell.gate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.*
import com.superiorsoftllc.infantry.ui.model.BASE_BRANCH_DEFAULT_LABEL
import com.superiorsoftllc.infantry.ui.model.Notice
import com.superiorsoftllc.infantry.ui.model.baseBranchChoices
import com.superiorsoftllc.infantry.ui.model.isCurrentBase
import com.superiorsoftllc.infantry.ui.model.ChangesView
import com.superiorsoftllc.infantry.ui.model.HistoryView
import com.superiorsoftllc.infantry.ui.model.PatchView
import com.superiorsoftllc.infantry.ui.model.ProjectView
import com.superiorsoftllc.infantry.ui.model.badge
import com.superiorsoftllc.infantry.ui.model.display
import com.superiorsoftllc.infantry.ui.model.headline
import com.superiorsoftllc.infantry.ui.model.label
import com.superiorsoftllc.infantry.ui.model.projectChangesView
import com.superiorsoftllc.infantry.ui.model.projectHistoryView
import com.superiorsoftllc.infantry.ui.model.projectPatchView
import com.superiorsoftllc.infantry.ui.model.summary
import com.superiorsoftllc.infantry.ui.model.whenText
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.PROJECT_BASE_UNSUPPORTED
import com.superiorsoftllc.infantry.ui.net.PROJECT_GIT_BRANCHES_UNSUPPORTED
import com.superiorsoftllc.infantry.ui.net.PROJECT_GIT_CHECKOUT_UNSUPPORTED
import com.superiorsoftllc.infantry.ui.net.PROJECT_GIT_UNSUPPORTED
import com.superiorsoftllc.infantry.ui.protocol.ChangeKind
import com.superiorsoftllc.infantry.ui.protocol.ChangedFile
import com.superiorsoftllc.infantry.ui.shell.GitContext
import com.superiorsoftllc.infantry.ui.shell.LinkContext
import com.superiorsoftllc.infantry.ui.shell.SessionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * CP-2 (contract 1.36.0): the active project's directory and what git says about it -- the working tree, one changed
 * file's diff, and the history. Every decision (unknown vs clean, which diff is current) is made by the tested views in
 * `model/ProjectGitView.kt`. Drawn as [GitPaneFrame] (the scrolling column, its title, the tree load) and its sections,
 * which the Git tab's `infantry.shell.git` node draws as child blocks ([ShellGit]). RUNTIME-6: everything here reads the
 * plugin API contexts (link, session, git); the window's AppState / Controller are not used.
 */

/**
 * BLOCKS-LAYOUT L14: the Git pane as its sections read it: the active [project], whether the link is [live] and the daemon
 * [supported] (contract 1.36.0), the changed file whose diff is open ([picked]: path, staged side), the last refusal, and
 * [run], which sends a request and keeps its refusal. The frame owns this state; a section changes it only through these.
 */
class GitPaneHost(
    val link: LinkContext,
    val session: SessionContext,
    /** S2: the project's git and its requests (the shell's [com.superiorsoftllc.infantry.ui.shell.ShellContext.git]). */
    val context: GitContext,
    val project: ProjectView,
    val live: Boolean,
    val supported: Boolean,
    internal val scope: CoroutineScope,
    pickedState: MutableState<Pair<String, Boolean>?>,
    private val error: MutableState<String?>,
    private val dismissed: MutableLongState,
) {
    var picked by pickedState
    val localError: String? get() = error.value
    /** Refusals up to this notice time were answered by a newer action, so they are not shown again. */
    val dismissedAt: Long get() = dismissed.longValue

    fun run(onSent: () -> Unit = {}, request: suspend () -> CommandResult) {
        error.value = null
        session.projectRefusal?.let { dismissed.longValue = it.at }
        scope.launch {
            when (val r = request()) {
                is CommandResult.Sent -> onSent()
                is CommandResult.Refused -> error.value = r.reason
                is CommandResult.Queued -> Unit
            }
        }
    }
}

/**
 * The Git pane's frame: the scrolling column, its title, and the load of the tree when the project, its directory or the
 * link changes. With no active project it says so and draws no section; else [content] gets the host and draws straight
 * into the column. [modifier] goes on the column.
 */
@Composable
fun GitPaneFrame(link: LinkContext, session: SessionContext, ctx: GitContext, modifier: Modifier = Modifier, content: @Composable ColumnScope.(GitPaneHost) -> Unit) {
    val scope = rememberCoroutineScope()
    val live = link.live
    val supported = link.contractAtLeast(1, 36)
    val active = session.activeProject
    val localError = remember { mutableStateOf<String?>(null) }
    val dismissedAt = remember { mutableLongStateOf(0L) }
    // The changed file whose diff is open (path, staged side); a new project or directory closes it.
    val picked = remember(active?.id, active?.directory) { mutableStateOf<Pair<String, Boolean>?>(null) }

    LaunchedEffect(active?.id, active?.directory, live, supported) {
        if (live && supported && active != null) ctx.load(active.id)
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(if (active != null) "GIT · ${active.name}" else "GIT", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 6.dp))
        if (active == null) {
            Text("Select a project to see its git tree.", color = Palette.Muted, fontSize = 12.sp)
            return@Column
        }
        content(GitPaneHost(link, session, ctx, active, live, supported, scope, picked, localError, dismissedAt))
    }
}

/** The branch new tickets are cut from (BASE-1b), first so a long list below never buries it. [modifier] goes on its heading. */
@Composable
fun GitBaseSection(git: GitPaneHost, modifier: Modifier = Modifier) {
    BaseBranchPicker(git.link, git.session, git.context, git.project.id, git.project.baseBranch, git.live, modifier)
}

/** The project's directory (project-scoped settings) and the last refusal, under a divider. [modifier] goes on the directory line. */
@Composable
fun GitDirectorySection(git: GitPaneHost, modifier: Modifier = Modifier) {
    val session = git.session
    val ctx = git.context
    val active = git.project
    HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(vertical = 10.dp))
    var draft by remember(active.id, active.directory) { mutableStateOf(active.directory) }
    // GATE-PANES-1: the directory is part of the project (`projects.save`), dimmed while the policy refuses it.
    val canEdit = git.link.gate("projects.save").enabled && git.supported
    Text("Directory: ${active.directory.ifBlank { "not set" }}", modifier = modifier, color = Palette.Text, fontSize = 12.sp, fontFamily = Mono)
    OutlinedTextField(
        draft, { draft = it }, label = { Text("Project directory") }, singleLine = true, enabled = canEdit,
        textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
    val picker = LocalDirectoryPicker.current
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (picker != null) {
            OutlinedButton(
                onClick = {
                    git.scope.launch {
                        val chosen = picker.pick(draft.ifBlank { active.directory })?.trim().orEmpty()
                        if (chosen.isNotEmpty()) {
                            draft = chosen
                            git.run { ctx.saveDirectory(active.id, chosen) }
                        }
                    }
                },
                enabled = canEdit,
            ) { Text("Open directory…", fontSize = 12.sp) }
        }
        OutlinedButton(
            onClick = { git.run { ctx.saveDirectory(active.id, draft) } },
            enabled = canEdit && draft.trim() != active.directory,
        ) { Text("Save directory", fontSize = 12.sp) }
        OutlinedButton(
            onClick = { git.run { ctx.saveDirectory(active.id, "") } },
            enabled = canEdit && active.directory.isNotBlank(),
        ) { Text("Clear directory", fontSize = 12.sp) }
    }
    // A `project_base` refusal is drawn by the base-branch control, next to the choice it refused.
    (git.localError ?: session.projectRefusal?.takeIf { it.at > git.dismissedAt && !it.text.startsWith(PROJECT_BASE_REFUSED) }?.text)
        ?.let { Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
}

/**
 * The working tree under a divider: its heading and Refresh, the branch line, and the changed files (picking one opens its
 * diff); on a daemon older than 1.36.0, the line that says so instead. [modifier] goes on the heading row (or that line).
 */
@Composable
fun GitTreeSection(git: GitPaneHost, modifier: Modifier = Modifier) {
    val session = git.session
    val ctx = git.context
    val active = git.project
    HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(vertical = 10.dp))
    if (!git.supported) {
        Text(PROJECT_GIT_UNSUPPORTED, modifier = modifier, color = Palette.Muted, fontSize = 12.sp)
        return
    }
    val status = ctx.status(active.id)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("WORKING TREE", color = Palette.Muted, fontSize = 10.sp)
        OutlinedButton(
            onClick = {
                git.run {
                    val tree = ctx.load(active.id)
                    // A refresh also re-reads the diff that is open, so it is never older than the list beside it.
                    git.picked?.let { (path, staged) -> ctx.loadDiff(active.id, path, staged) } ?: tree
                }
            },
            enabled = git.live,
        ) { Text("Refresh", fontSize = 11.sp) }
    }
    if (status?.available == true) {
        Text(status.headline(), color = Palette.Text, fontSize = 12.sp, fontFamily = Mono, modifier = Modifier.padding(vertical = 4.dp))
    }
    val changes = projectChangesView(status, ctx.failure)
    when (changes) {
        // Unknown is stated as unknown. It is never drawn as "clean".
        is ChangesView.Unknown -> Text("Git unknown: ${changes.reason}", color = Palette.Muted, fontSize = 11.sp)
        ChangesView.Clean -> Text("Working tree clean. Nothing to commit.", color = Palette.Ok, fontSize = 11.sp)
        is ChangesView.Files -> {
            Text(changes.summary(), color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            ChangeGroup("Staged", changes.staged, staged = true, picked = git.picked, collapseOver = GIT_LIST_COLLAPSE_OVER) { f ->
                git.picked = f.path to true
                git.run { ctx.loadDiff(active.id, f.path, true) }
            }
            ChangeGroup("Not staged", changes.unstaged, staged = false, picked = git.picked, collapseOver = GIT_LIST_COLLAPSE_OVER) { f ->
                git.picked = f.path to false
                git.run { ctx.loadDiff(active.id, f.path, false) }
            }
            if (git.picked == null) Text("Pick a changed file to see its diff.", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** The picked file's diff; nothing while no file is picked (or on a daemon older than 1.36.0). [modifier] goes on its heading. */
@Composable
fun GitDiffSection(git: GitPaneHost, modifier: Modifier = Modifier) {
    if (!git.supported) return
    val (path, staged) = git.picked ?: return
    Text(
        "Diff: $path${if (staged) " (staged)" else ""}", color = Palette.Muted, fontSize = 10.sp,
        modifier = modifier.padding(top = 8.dp, bottom = 2.dp),
    )
    when (val patch = projectPatchView(git.context.patch(git.project.id), path, staged)) {
        PatchView.None -> Text("Loading diff…", color = Palette.Muted, fontSize = 11.sp)
        is PatchView.Unknown -> Text("Diff unknown: ${patch.reason}", color = Palette.Muted, fontSize = 11.sp)
        PatchView.Binary -> Text("Binary file: no text diff.", color = Palette.Muted, fontSize = 11.sp)
        is PatchView.Text -> {
            patch.lines.forEach { line -> Text(line.ifEmpty { " " }, color = diffColor(line), fontFamily = Mono, fontSize = 11.sp) }
            if (patch.truncated) Text("Diff cut by the daemon (too large to show whole).", color = Palette.Muted, fontSize = 10.sp)
        }
    }
}

/** Branches under a divider: switch, or create from the current one (BRANCH-SWITCH-1). [modifier] goes on its heading. */
@Composable
fun GitBranchesSection(git: GitPaneHost, modifier: Modifier = Modifier) {
    if (!git.supported) return
    HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(vertical = 10.dp))
    BranchSwitcher(git.link, git.context, git.project.id, git.context.status(git.project.id)?.branch, git.live, modifier)
}

/** The optional verification pipeline (PIPE-2) under a divider. [modifier] goes on its heading row. */
@Composable
fun GitPipelineSection(git: GitPaneHost, modifier: Modifier = Modifier) {
    if (!git.supported) return
    HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(vertical = 10.dp))
    ProjectPipelineSection(git.link, git.context, git.project.id, git.live, modifier)
}

/** The history under a divider: a row per commit, newest first. [modifier] goes on its heading. */
@Composable
fun GitHistorySection(git: GitPaneHost, modifier: Modifier = Modifier) {
    if (!git.supported) return
    HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(vertical = 10.dp))
    Text("HISTORY", color = Palette.Muted, fontSize = 10.sp, modifier = modifier.padding(bottom = 4.dp))
    when (val history = projectHistoryView(git.context.history(git.project.id))) {
        is HistoryView.Unknown -> Text("History unknown: ${history.reason}", color = Palette.Muted, fontSize = 11.sp)
        HistoryView.Empty -> Text("No commits yet.", color = Palette.Muted, fontSize = 11.sp)
        is HistoryView.Commits -> {
            history.commits.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

/** Test tags of the branch switcher: one Switch button per local branch, plus the new-branch box and button. */
const val GIT_SWITCH_TAG = "git-switch-"
const val GIT_NEW_BRANCH_FIELD_TAG = "git-new-branch-field"
const val GIT_NEW_BRANCH_TAG = "git-new-branch"
const val GIT_CHECKOUT_RESULT_TAG = "git-checkout-result"

/**
 * BRANCH-SWITCH-1 (contract 1.74.0): the project's local branches, each with a "Switch" action, and "New branch from
 * current". The daemon decides: a refusal (dirty tree, branch held by another worktree, bad name) comes back as the
 * `project_git_checkout_result` and its reason is drawn here, inline. The list is re-read whenever the checked-out
 * branch changes, which a switch makes happen through the `project_git` push that follows it.
 */
@Composable
private fun BranchSwitcher(link: LinkContext, ctx: GitContext, projectId: String, currentBranch: String?, live: Boolean, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val listSupported = link.contractAtLeast(1, 65)
    val switchSupported = link.contractAtLeast(1, 74)
    var localError by remember(projectId) { mutableStateOf<String?>(null) }
    var newName by remember(projectId) { mutableStateOf("") }

    LaunchedEffect(projectId, currentBranch, live, listSupported) {
        if (live && listSupported) ctx.loadBranches(projectId)
    }

    fun checkout(branch: String, create: Boolean) {
        localError = null
        scope.launch {
            val r = ctx.checkout(projectId, branch, create)
            if (r is CommandResult.Refused) localError = r.reason
            else if (create && r is CommandResult.Sent) newName = ""
        }
    }

    Text("BRANCHES", color = Palette.Muted, fontSize = 10.sp, modifier = modifier.padding(bottom = 4.dp))
    if (!listSupported) {
        Text(PROJECT_GIT_BRANCHES_UNSUPPORTED, color = Palette.Muted, fontSize = 11.sp)
        return
    }
    val list = ctx.branches(projectId)
    when {
        list == null -> Text("Loading branches…", color = Palette.Muted, fontSize = 11.sp)
        !list.available -> Text("Branches unknown: ${list.reason.ifBlank { "the daemon could not list them" }}", color = Palette.Muted, fontSize = 11.sp)
        list.branches.isEmpty() -> Text("No local branches.", color = Palette.Muted, fontSize = 11.sp)
        else -> {
            list.branches.forEach { b ->
                val isCurrent = b.name == list.current
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        b.name, color = if (isCurrent) Palette.Accent else Palette.Text, fontFamily = Mono, fontSize = 12.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    val tail = listOfNotNull("checked out".takeIf { isCurrent }, "worktree".takeIf { b.isWorktree && !isCurrent }).joinToString(", ")
                    if (tail.isNotEmpty()) Text(tail, color = Palette.Muted, fontSize = 10.sp, maxLines = 1, softWrap = false)
                    if (!isCurrent && switchSupported) {
                        OutlinedButton(
                            onClick = { checkout(b.name, create = false) },
                            enabled = link.gate("project.git.checkout").enabled,
                            modifier = Modifier.testTag(GIT_SWITCH_TAG + b.name),
                        ) { Text("Switch", fontSize = 11.sp) }
                    }
                }
            }
            if (list.truncated) Text("More branches exist than are listed.", color = Palette.Muted, fontSize = 10.sp)
        }
    }

    if (switchSupported) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                newName, { newName = it }, label = { Text("New branch name") }, singleLine = true, enabled = link.gate("project.git.checkout").enabled,
                textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.weight(1f).testTag(GIT_NEW_BRANCH_FIELD_TAG),
            )
            OutlinedButton(
                onClick = { checkout(newName, create = true) },
                enabled = link.gate("project.git.checkout").enabled && newName.isNotBlank(),
                modifier = Modifier.testTag(GIT_NEW_BRANCH_TAG),
            ) { Text("New branch from current", fontSize = 11.sp) }
        }
    } else {
        Text(PROJECT_GIT_CHECKOUT_UNSUPPORTED, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }

    // The newest answer for this project: a refusal's reason in red, a switch that happened in green.
    val result = ctx.checkoutResult(projectId)
    val (text, color) = when {
        localError != null -> localError to Palette.Danger
        result == null -> null to Palette.Muted
        !result.switched -> "Cannot switch to '${result.branch}': ${result.reason}" to Palette.Danger
        result.reason.isNotBlank() -> result.reason.replaceFirstChar { it.uppercase() } to Palette.Muted
        result.create -> "Created and switched to '${result.current ?: result.branch}'." to Palette.Ok
        else -> "Switched to '${result.current ?: result.branch}'." to Palette.Ok
    }
    text?.let { Text(it, color = color, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp).testTag(GIT_CHECKOUT_RESULT_TAG)) }
}

/** Test tags of the base-branch control (BASE-1b). */
const val GIT_BASE_TOGGLE_TAG = "git-base-toggle"
const val GIT_BASE_SUMMARY_TAG = "git-base-summary"
/** A changed-file list longer than this starts collapsed so it cannot push other content out of view. */
const val GIT_LIST_COLLAPSE_OVER = 20
const val GIT_BASE_SEARCH_TAG = "git-base-search"
const val GIT_BASE_ITEM_TAG = "git-base-item-"
const val GIT_BASE_DEFAULT_ITEM_TAG = "git-base-item-default"
const val GIT_BASE_RESULT_TAG = "git-base-result"

/** How the daemon names a refused `project_base` (contract 1.81.0). */
internal const val PROJECT_BASE_REFUSED = "project_base refused"

/**
 * BASE-1b (contract 1.81.0): "Base branch for new tasks" -- the branch a ticket start in this project cuts its own
 * branch from. A dropdown: the button shows the setting, opening it shows a search box over the project's local
 * branches (case-insensitive substring, [baseBranchChoices]) with "Default (checked-out HEAD)" first, which clears it.
 *
 * Picking saves through `project_base`; the daemon's `project` answer carries the stored setting, which is what the
 * button then shows. A refusal (`project_base refused: ...`, e.g. the branch does not resolve where tickets are cut) is
 * drawn here, inline. The branch list is the one [BranchSwitcher] asks for when the Git tab opens.
 */
@Composable
private fun BaseBranchPicker(link: LinkContext, session: SessionContext, ctx: GitContext, projectId: String, baseBranch: String, live: Boolean, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val supported = link.contractAtLeast(1, 81)
    var open by remember(projectId) { mutableStateOf(false) }
    var query by remember(projectId) { mutableStateOf("") }
    var localError by remember(projectId) { mutableStateOf<String?>(null) }
    // The newest refusal seen before the last pick: only a refusal after it answers that pick.
    var seenRefusal by remember(projectId) { mutableStateOf<Notice?>(null) }
    // The choice sent and not yet answered: the label it should become (null = nothing pending).
    var pending by remember(projectId) { mutableStateOf<String?>(null) }

    val refusal = session.notices.lastOrNull { it.sessionId == null && it.text.startsWith(PROJECT_BASE_REFUSED) }
    val newRefusal = refusal?.takeIf { it !== seenRefusal }
    val saving = pending != null && pending != baseBranch && newRefusal == null

    Text("BASE BRANCH FOR NEW TASKS", color = Palette.Muted, fontSize = 10.sp, modifier = modifier.padding(bottom = 4.dp))
    if (!supported) {
        Text(PROJECT_BASE_UNSUPPORTED, color = Palette.Muted, fontSize = 11.sp)
        return
    }
    val label = baseBranch.ifEmpty { BASE_BRANCH_DEFAULT_LABEL }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // A missing base stands out: after BASE-1 an unset base refuses ticket starts.
        if (baseBranch.isEmpty()) {
            Text(
                "Not set: new tasks will be refused", color = Palette.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).testTag(GIT_BASE_SUMMARY_TAG),
            )
        } else {
            Text(
                "Base for new tasks: $label", color = Palette.Text, fontSize = 12.sp, fontFamily = Mono, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag(GIT_BASE_SUMMARY_TAG),
            )
        }
        OutlinedButton(
            onClick = { open = !open; if (!open) query = "" },
            // GATE-PANES-1: the base branch is `project_base` (`projects.save`).
            enabled = link.gate("projects.save").enabled,
            modifier = Modifier.testTag(GIT_BASE_TOGGLE_TAG),
        ) { Text("Change ${if (open) "▴" else "▾"}", fontSize = 12.sp) }
    }

    if (open) {
        val list = ctx.branches(projectId)
        val names = list?.takeIf { it.available }?.branches?.map { it.name }.orEmpty()
        val all = baseBranchChoices(names, list?.current, "")
        val shown = baseBranchChoices(names, list?.current, query)
        SearchableSelect(
            items = shown,
            query = query,
            onQueryChange = { query = it },
            onPick = { choice ->
                open = false
                query = ""
                localError = null
                if (isCurrentBase(choice, baseBranch)) return@SearchableSelect
                seenRefusal = refusal
                pending = choice.branch.orEmpty()
                scope.launch {
                    val r = ctx.setBaseBranch(projectId, choice.branch)
                    if (r is CommandResult.Refused) { localError = r.reason; pending = null }
                }
            },
            key = { it.branch ?: ":default" }, // a git ref name never contains ":"
            label = { it.label },
            searchTag = GIT_BASE_SEARCH_TAG,
            itemTag = { c -> c.branch?.let { GIT_BASE_ITEM_TAG + it } ?: GIT_BASE_DEFAULT_ITEM_TAG },
            isSelected = { isCurrentBase(it, baseBranch) },
            badge = { if (it.checkedOut) "checked out" else null },
            placeholder = "Filter branches...",
            totalCount = all.size,
            maxListHeight = 220.dp,
            modifier = Modifier.padding(top = 4.dp),
        )
        when {
            list == null -> Text("Loading branches…", color = Palette.Muted, fontSize = 11.sp)
            !list.available -> Text("Branches unknown: ${list.reason.ifBlank { "the daemon could not list them" }}", color = Palette.Muted, fontSize = 11.sp)
            list.truncated -> Text("More branches exist than are listed.", color = Palette.Muted, fontSize = 10.sp)
        }
    }

    val (text, color) = when {
        localError != null -> localError to Palette.Danger
        saving -> "Saving…" to Palette.Muted
        newRefusal != null -> newRefusal.text to Palette.Danger
        else -> null to Palette.Muted
    }
    text?.let { Text(it, color = color, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp).testTag(GIT_BASE_RESULT_TAG)) }
}

/** Unified-diff colouring: additions green, removals red, hunk headers accented, everything else plain. */
private fun diffColor(line: String): Color = when {
    line.startsWith("+++") || line.startsWith("---") -> Palette.Muted
    line.startsWith("@@") -> Palette.Accent
    line.startsWith("+") -> Palette.Ok
    line.startsWith("-") -> Palette.Danger
    else -> Palette.Text
}
