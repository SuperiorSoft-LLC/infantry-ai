package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.GitWorktree
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitWorktreeStatus

/*
 * UX-CHANGES-1: the Changes tab's worktree picker and header, derived purely from the daemon's
 * `project_git_worktree_list` / `project_git_worktree` answers (contract 1.88.0). Which worktree is the session's,
 * the base, ahead/behind and the uncommitted count are all the daemon's; this file only decides what is shown.
 */

/**
 * The worktree the user picked in the Changes header, for ONE project while ONE session is selected. Selecting another
 * session (or project) drops back to the daemon's choice for it: the session's own checkout.
 */
data class ChangesWorktreePick(val projectId: String, val sessionId: String?, val path: String)

/** The [AppState.worktreeStatus] key of [path] in [projectId] (two projects may share a repository). */
fun worktreeStatusKey(projectId: String, path: String): String = "$projectId\n$path"

/** The worktree the active project's Changes tab shows: the user's pick for this session, else the daemon's `selected`. */
val AppState.changesWorktree: String?
    get() {
        val projectId = activeProjectId ?: return null
        changesWorktreePick?.takeIf { it.projectId == projectId && it.sessionId == selectedId }?.let { return it.path }
        return projectWorktrees[projectId]?.selected
    }

/** The last `project_git_worktree` answer for the worktree [changesWorktree] names, or null when none was read yet. */
val AppState.changesWorktreeStatus: ProjectGitWorktreeStatus?
    get() {
        val projectId = activeProjectId ?: return null
        val path = changesWorktree ?: return null
        return worktreeStatus[worktreeStatusKey(projectId, path)]
    }

fun AppState.withChangesWorktree(projectId: String, path: String): AppState =
    copy(changesWorktreePick = ChangesWorktreePick(projectId, selectedId, path))

/**
 * The tail markers of one worktree in the picker: whose checkout it is. A linked worktree carries no marker of its own
 * (the picker lists worktrees), so "this session" survives the header's width.
 */
fun GitWorktree.markers(): List<String> = listOfNotNull(
    "shared checkout".takeIf { main },
    "this session".takeIf { session },
    "missing".takeIf { prunable },
)

/** The name of one worktree in the picker: its branch, else `detached @ <sha>`, else its folder. */
fun GitWorktree.name(): String = branch ?: head?.let { "detached @ ${it.take(8)}" } ?: folder()

/** The last segment of [GitWorktree.path], whichever separator git used. */
fun GitWorktree.folder(): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

/** The picker option's stable key: the branch, else the folder (a detached worktree has no branch). */
fun GitWorktree.optionKey(): String = branch ?: folder()

/** The header line of the shown worktree: every piece null when there is nothing honest to show for it. */
data class WorktreeHeader(
    val branch: String,
    val ahead: String?,
    val behind: String?,
    val base: String,
    val uncommitted: String,
)

/** The header of [status]; null while it was not read yet or could not be read (the reason is shown instead). */
fun worktreeHeader(status: ProjectGitWorktreeStatus?): WorktreeHeader? {
    if (status == null || !status.available) return null
    val counted = status.ahead != null && status.behind != null
    val base = status.base.let { if (FULL_SHA.matches(it)) it.take(8) else it }
    return WorktreeHeader(
        branch = status.branch ?: status.head?.let { "detached @ ${it.take(8)}" } ?: "no commit yet",
        ahead = status.ahead?.takeIf { counted }?.let { "↑$it" },
        behind = status.behind?.takeIf { counted }?.let { "↓$it" },
        base = if (counted) "vs $base" else "no base",
        uncommitted = if (status.uncommitted == 0) "clean" else "${status.uncommitted} uncommitted",
    )
}

/** The working-tree list of [status], the same three honest states as [projectChangesView]. */
fun worktreeChangesView(status: ProjectGitWorktreeStatus?): ChangesView = when {
    status == null -> ChangesView.Unknown("not read yet")
    !status.available -> ChangesView.Unknown(status.reason.ifBlank { "the daemon could not read the worktree" })
    status.changes.isEmpty() -> ChangesView.Clean
    else -> ChangesView.Files(
        staged = status.changes.filter { it.staged }.sortedBy { it.path },
        unstaged = status.changes.filter { !it.staged }.sortedBy { it.path },
        truncated = status.changesTruncated,
    )
}

/** The History list of [status]: unknown is not empty, empty is not "not read yet". */
fun worktreeHistoryView(status: ProjectGitWorktreeStatus?): HistoryView = when {
    status == null -> HistoryView.Unknown("not read yet")
    !status.available -> HistoryView.Unknown(status.reason.ifBlank { "the daemon could not read the history" })
    status.commits.isEmpty() -> HistoryView.Empty
    else -> HistoryView.Commits(status.commits, status.commitsTruncated)
}

private val FULL_SHA = Regex("^[0-9a-f]{40}$")
