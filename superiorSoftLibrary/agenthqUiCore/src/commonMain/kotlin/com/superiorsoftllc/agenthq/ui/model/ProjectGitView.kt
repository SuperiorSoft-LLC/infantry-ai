package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.BranchChangedFile
import com.superiorsoftllc.agenthq.ui.protocol.GitCommit
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitBranchDiffstat
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitHistory
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitPatch
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitStatus
import com.superiorsoftllc.agenthq.ui.protocol.TicketColumns
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * What the git panel shows for a project's working tree, derived purely from the daemon's `project_git` answer.
 * Reuses [ChangesView] (the worktree view's three states): `available = false` is UNKNOWN with the daemon's own
 * reason and is never drawn as clean; `available = true` with nothing listed is known clean.
 */
fun projectChangesView(status: ProjectGitStatus?, failure: String? = null): ChangesView = when {
    // BUG-PGIT-LOAD: a request the daemon answered with an error frame is never "not read yet" -- say why.
    status == null -> ChangesView.Unknown(failure ?: "not read yet")
    !status.available -> ChangesView.Unknown(status.reason.ifBlank { "the daemon could not read the tree" })
    status.changes.isEmpty() -> ChangesView.Clean
    else -> ChangesView.Files(
        staged = status.changes.filter { it.staged }.sortedBy { it.path },
        unstaged = status.changes.filter { !it.staged }.sortedBy { it.path },
        truncated = status.changesTruncated,
    )
}

/** What the history list shows: unknown is not empty, empty is not "not asked yet". */
sealed interface HistoryView {
    data class Unknown(val reason: String) : HistoryView
    data object Empty : HistoryView
    data class Commits(val commits: List<GitCommit>, val truncated: Boolean) : HistoryView
}

fun projectHistoryView(history: ProjectGitHistory?): HistoryView = when {
    history == null -> HistoryView.Unknown("not read yet")
    !history.available -> HistoryView.Unknown(history.reason.ifBlank { "the daemon could not read the history" })
    history.commits.isEmpty() -> HistoryView.Empty
    else -> HistoryView.Commits(history.commits, history.truncated)
}

/** What the diff viewer shows for the file the user picked. */
sealed interface PatchView {
    /** Nothing picked, or the answer on hand is for a different file/side. */
    data object None : PatchView
    data class Unknown(val reason: String) : PatchView
    data object Binary : PatchView
    data class Text(val lines: List<String>, val truncated: Boolean) : PatchView
}

/**
 * The answer is shown only for the file AND index side AND source that is picked now; anything else is stale.
 * [branch] null is the working tree; a name is that branch's `mergeBase..branch` diff (BRANCH-VIEW-2).
 * [worktree] null is the project's directory; a path is that worktree's tree (UX-CHANGES-1).
 */
fun projectPatchView(patch: ProjectGitPatch?, path: String?, staged: Boolean, branch: String? = null, worktree: String? = null): PatchView = when {
    patch == null || path == null -> PatchView.None
    patch.path != path || patch.staged != staged || patch.branch != branch || patch.worktree != worktree -> PatchView.None
    !patch.available -> PatchView.Unknown(patch.reason.ifBlank { "the daemon could not read the diff" })
    patch.binary -> PatchView.Binary
    else -> PatchView.Text(patch.patch.lines().let { if (it.lastOrNull() == "") it.dropLast(1) else it }, patch.truncated)
}

private const val SHORT = 8

/** One line under the section title, e.g. `branch main @ 1a2b3c4d`, `detached HEAD @ ...`, `branch main, no commits yet`. */
fun ProjectGitStatus.headline(): String {
    val where = if (branch != null) "branch $branch" else "detached HEAD"
    return if (head == null) "$where, no commits yet" else "$where @ ${head.take(SHORT)}"
}

/** `2023-11-14 10:13 PM` (UTC) from the commit's epoch seconds. */
fun GitCommit.whenText(): String {
    val t = Instant.fromEpochSeconds(timestamp).toLocalDateTime(TimeZone.UTC)
    return "${t.date} ${TimeFormat.clock12(t.hour, t.minute)}"
}

/** BRANCH-VIEW-2: the Changes panel shows [branch] (vs its base) instead of [projectId]'s working tree. */
data class ChangesBranchPick(val projectId: String, val branch: String)

/** The branch the active project's Changes panel shows, or null for the working tree (the default). */
val AppState.changesBranch: String?
    get() = changesBranchPick?.takeIf { it.projectId == activeProjectId }?.branch

/** Show [branch] in [projectId]'s Changes panel; null goes back to the working tree. */
fun AppState.withChangesBranch(projectId: String, branch: String?): AppState =
    copy(changesBranchPick = branch?.let { ChangesBranchPick(projectId, it) })

/**
 * TASK-BRANCH-FOLLOW: the branch the selected session is on when it is an in-progress ticket's worker, as a pick for
 * that ticket's project (the active one for a legacy ticket). Its live worktree wins, then the session's recorded
 * worktree branch, then the ticket's own; null when it is no such worker or has no branch yet.
 */
val AppState.selectedWorkerBranch: ChangesBranchPick?
    get() {
        val id = selectedId ?: return null
        val ticket = tickets.firstOrNull { it.sessionId == id && it.status == TicketColumns.IN_PROGRESS } ?: return null
        val branch = listOf(worktrees[id]?.takeIf { !it.removed }?.branch, agents[id]?.session?.worktreeBranch, ticket.branch)
            .firstOrNull { !it.isNullOrBlank() } ?: return null
        val projectId = ticket.projectId ?: activeProjectId ?: return null
        return ChangesBranchPick(projectId, branch)
    }

/**
 * TASK-BRANCH-FOLLOW: the Changes panel follows the selected worker's branch when the selection or that branch moved
 * since [previous]; a worker without a branch, or no move, leaves the pick alone (so a manual pick survives). Returns
 * this same instance when nothing changes.
 */
fun AppState.followWorkerBranch(previous: AppState): AppState {
    val pick = selectedWorkerBranch ?: return this
    if (selectedId == previous.selectedId && pick == previous.selectedWorkerBranch) return this
    return if (changesBranchPick == pick) this else copy(changesBranchPick = pick)
}

/** What the Changes panel shows for a branch: unknown carries a reason and is never drawn as "no changes". */
sealed interface BranchChangesView {
    data class Unknown(val reason: String) : BranchChangesView
    data class Files(
        val branch: String,
        val base: String,
        val files: List<BranchChangedFile>,
        val truncated: Boolean,
    ) : BranchChangesView {
        val added: Int get() = files.sumOf { it.added }
        val removed: Int get() = files.sumOf { it.removed }

        /** `side vs main · 1 file · +1 −0`. */
        fun header(): String = "$branch vs ${base.ifBlank { "?" }} · ${files.size} ${if (files.size == 1) "file" else "files"} · +$added −$removed"
    }
}

/** Only an answer for [branch] counts; an answer for another branch is "loading", never that branch's files. */
fun branchChangesView(diffstat: ProjectGitBranchDiffstat?, branch: String): BranchChangesView = when {
    diffstat == null || diffstat.branch != branch -> BranchChangesView.Unknown("loading $branch…")
    !diffstat.available -> BranchChangesView.Unknown(diffstat.reason.ifBlank { "the daemon could not read $branch" })
    else -> BranchChangesView.Files(branch, diffstat.base, diffstat.changes.sortedBy { it.path }, diffstat.truncated)
}
