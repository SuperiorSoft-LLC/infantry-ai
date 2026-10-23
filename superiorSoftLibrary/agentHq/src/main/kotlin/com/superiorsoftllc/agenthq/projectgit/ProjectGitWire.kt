package com.superiorsoftllc.agenthq.projectgit

import com.superiorsoftllc.agenthq.protocol.StrictJson
import com.superiorsoftllc.agenthq.worktree.ChangeKind
import com.superiorsoftllc.agenthq.worktree.ChangedFile
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/*
 * CP-2 (contract 1.36.0): the wire types of the PROJECT git view -- what the daemon reads from the git
 * working tree rooted at `Project.directory`. Three views, three answers:
 *
 *   project_git_status  (raw project id)  -> project_git          [ProjectGitStatus]
 *   project_git_diff    (ProjectGitDiffRequest) -> project_git_patch  [ProjectGitPatch]
 *   project_git_log     (ProjectGitLogRequest)  -> project_git_history [ProjectGitHistory]
 *
 * EVERY answer carries the same honest three-way distinction, because collapsing it is the
 * silent-wrong-answer shape (a view that renders "unknown" as "clean"):
 *   - `available = false`: the daemon COULD NOT READ -- no directory configured, the directory is gone,
 *     it is not a git repository, or git failed. [reason] says which, in words a user can act on. The
 *     data fields are empty and MUST NOT be read as "nothing changed".
 *   - `available = true` with an empty list / empty patch: git was asked and listed nothing (known clean).
 *
 * The schema names nothing git-specific beyond the view names (the change list reuses the neutral
 * [ChangedFile] the worktree view already publishes), so another VCS can sit behind the same frames.
 */

/** The working tree of a project's directory: its branch, HEAD and the files git reports as changed. */
@Serializable
data class ProjectGitStatus(
    val projectId: String,
    /** The project's configured directory as the daemon saw it, empty when none is configured. */
    val directory: String,
    val available: Boolean,
    /** Why [available] is false; empty when it is true. */
    val reason: String = "",
    /** The repository's top-level directory. May be above [directory] when the project lives in a subfolder. */
    val repoRoot: String = "",
    /** The checked-out branch; null when HEAD is detached. */
    val branch: String? = null,
    /** HEAD's commit id; null for a repository with no commit yet. */
    val head: String? = null,
    val changes: List<ChangedFile> = emptyList(),
    /** True when git listed more than the cap and [changes] holds only the first part of it. */
    val changesTruncated: Boolean = false,
)

/** `project_git_diff`: the diff of one changed path. [staged] picks the index side; untracked files diff against nothing. */
@Serializable
data class ProjectGitDiffRequest(
    val projectId: String,
    /** Repo-relative, forward-slash path exactly as [ProjectGitStatus.changes] lists it. */
    val path: String,
    val staged: Boolean = false,
    /**
     * UX-CHANGES-1 (1.88.0): diff in this worktree of the project's repository (a path exactly as
     * [GitWorktree.path] lists it) instead of the project's own directory. Absent or null = the project's directory.
     */
    val worktree: String? = null,
) {
    companion object {
        fun decode(text: String): ProjectGitDiffRequest =
            StrictJson.decode(serializer(), text, "project_git_diff payload")
    }
}

/** One file's unified diff. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ProjectGitPatch(
    val projectId: String,
    val path: String,
    val staged: Boolean,
    val available: Boolean,
    val reason: String = "",
    /** The unified diff text; empty for a binary file, an unchanged path or an unavailable read. */
    val patch: String = "",
    /** True when git reports the file as binary: there is no text to show. */
    val binary: Boolean = false,
    /** True when [patch] was cut at the daemon's size cap. */
    val truncated: Boolean = false,
    /**
     * BRANCH-VIEW-1 (1.65.0): the branch whose `mergeBase..branch` diff this is, when it answers a
     * `project_git_branch_diff`; null for a working-tree `project_git_diff`.
     */
    val branch: String? = null,
    /**
     * UX-CHANGES-1 (1.88.0): the worktree the diff was read in, echoed from [ProjectGitDiffRequest.worktree].
     * Absent from the frame (not null) when the request named none, so a 1.86.0 patch is byte-identical.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val worktree: String? = null,
)

/** `project_git_log`: the newest [limit] commits (default 50, at most 200). */
@Serializable
data class ProjectGitLogRequest(
    val projectId: String,
    val limit: Int = DEFAULT_LIMIT,
) {
    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200

        fun decode(text: String): ProjectGitLogRequest =
            StrictJson.decode(serializer(), text, "project_git_log payload")
    }
}

/** One commit of the history. [timestamp] is the author time in epoch SECONDS. */
@Serializable
data class GitCommit(
    val hash: String,
    val shortHash: String,
    val author: String,
    val timestamp: Long,
    val subject: String,
)

/** A project's recent history, newest first. */
@Serializable
data class ProjectGitHistory(
    val projectId: String,
    val available: Boolean,
    val reason: String = "",
    val commits: List<GitCommit> = emptyList(),
    /** True when the repository holds more commits than were returned. */
    val truncated: Boolean = false,
)

/*
 * BRANCH-VIEW-1 (contract 1.65.0): the BRANCH view -- local branches, and what one branch changed since its
 * merge-base with a base. Read-only over refs and objects: nothing is checked out, the working tree is
 * never read, and the same honesty rule holds (`available = false` with a reason is unknown, never clean).
 *
 *   project_git_branches       (ProjectGitBranchesRequest)      -> project_git_branch_list     [ProjectGitBranchList]
 *   project_git_branch_changes (ProjectGitBranchChangesRequest) -> project_git_branch_diffstat [ProjectGitBranchDiffstat]
 *   project_git_branch_diff    (ProjectGitBranchDiffRequest)    -> project_git_patch           [ProjectGitPatch] (with `branch`)
 */

@Serializable
data class ProjectGitBranchesRequest(val projectId: String) {
    companion object {
        fun decode(text: String): ProjectGitBranchesRequest =
            StrictJson.decode(serializer(), text, "project_git_branches payload")
    }
}

/** One local branch. [head] is its commit id; [worktreePath] is set when a worktree has it checked out. */
@Serializable
data class GitBranch(
    val name: String,
    val head: String,
    val upstream: String? = null,
    val isWorktree: Boolean = false,
    val worktreePath: String? = null,
)

@Serializable
data class ProjectGitBranchList(
    val projectId: String,
    val available: Boolean,
    val reason: String = "",
    /** The branch checked out in the project's directory; null when HEAD is detached. */
    val current: String? = null,
    val branches: List<GitBranch> = emptyList(),
    /** True when there are more local branches than the cap. */
    val truncated: Boolean = false,
)

/** [base] is a local branch name or a full commit id; null picks the default base (see CONTRACT.md 1.65.0). */
@Serializable
data class ProjectGitBranchChangesRequest(
    val projectId: String,
    val branch: String,
    val base: String? = null,
) {
    companion object {
        fun decode(text: String): ProjectGitBranchChangesRequest =
            StrictJson.decode(serializer(), text, "project_git_branch_changes payload")
    }
}

/** One path the branch changed since the merge-base. [added]/[removed] are line counts; 0 with [binary] for a binary file. */
@Serializable
data class BranchChangedFile(
    val path: String,
    val status: ChangeKind,
    val fromPath: String? = null,
    val added: Int = 0,
    val removed: Int = 0,
    val binary: Boolean = false,
)

@Serializable
data class ProjectGitBranchDiffstat(
    val projectId: String,
    val branch: String,
    /** The base actually used (the request's, or the resolved default); empty when none could be resolved. */
    val base: String = "",
    /** The merge-base commit of [base] and [branch]; empty when unavailable. */
    val mergeBase: String = "",
    val available: Boolean,
    val reason: String = "",
    val changes: List<BranchChangedFile> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class ProjectGitBranchDiffRequest(
    val projectId: String,
    val branch: String,
    val base: String? = null,
    val path: String,
) {
    companion object {
        fun decode(text: String): ProjectGitBranchDiffRequest =
            StrictJson.decode(serializer(), text, "project_git_branch_diff payload")
    }
}

/*
 * BRANCH-SWITCH-1 (contract 1.74.0): the ONE writing verb of the project git view -- switch the branch
 * checked out in the project's directory. It never forces, never stashes and never discards:
 *
 *   project_git_checkout (ProjectGitCheckoutRequest) -> project_git_checkout_result [ProjectGitCheckoutResult]
 *
 * After a switch that happened the daemon also pushes a fresh `project_git` (status) frame to every client.
 * A switch git was not asked to make (dirty tree, branch held by another worktree, bad name, ...) is NOT an
 * error frame: it is a result with `switched = false`, a stable [ProjectGitCheckoutResult.code] and a reason,
 * so the pane can show it next to the branch it was about.
 */

/** `project_git_checkout`: switch to local [branch], or create it from the current HEAD and switch when [create]. */
@Serializable
data class ProjectGitCheckoutRequest(
    val projectId: String,
    val branch: String,
    val create: Boolean = false,
) {
    companion object {
        fun decode(text: String): ProjectGitCheckoutRequest =
            StrictJson.decode(serializer(), text, "project_git_checkout payload")
    }
}

/** The answer to `project_git_checkout`. [code] is empty when [switched]; otherwise one of [CheckoutRefusal]. */
@Serializable
data class ProjectGitCheckoutResult(
    val projectId: String,
    val branch: String,
    val create: Boolean = false,
    val switched: Boolean,
    val code: String = "",
    val reason: String = "",
    /** The branch checked out before the request; null when HEAD was detached or unknown. */
    val previous: String? = null,
    /** The branch checked out after the request; equal to [previous] when nothing switched. */
    val current: String? = null,
    /** For [CheckoutRefusal.DIRTY]: how many tracked paths have uncommitted changes. */
    val dirtyCount: Int = 0,
    /** For [CheckoutRefusal.HELD_BY_WORKTREE]: the worktree that has [branch] checked out. */
    val worktreePath: String? = null,
)

/** The stable refusal codes of [ProjectGitCheckoutResult.code]. */
object CheckoutRefusal {
    /** The project directory cannot be read as a git repository; the reason says why. */
    const val UNAVAILABLE = "unavailable"
    /** The branch name is one git (or the daemon) rejects. */
    const val BAD_NAME = "bad_name"
    /** Tracked files have uncommitted changes; [ProjectGitCheckoutResult.dirtyCount] says how many. */
    const val DIRTY = "dirty"
    /** Another worktree has the branch checked out; [ProjectGitCheckoutResult.worktreePath] says which. */
    const val HELD_BY_WORKTREE = "held_by_worktree"
    /** Switch asked for a local branch that does not exist. */
    const val NO_BRANCH = "no_branch"
    /** Create asked for a branch that already exists. */
    const val EXISTS = "exists"
    /** Git itself refused or failed; the reason carries its first error line. */
    const val GIT_FAILED = "git_failed"
}

/*
 * UX-CHANGES-1 (contract 1.88.0): the WORKTREE view -- every worktree of the project's repository, and one
 * worktree's branch, ahead/behind against its base, uncommitted changes and recent history. Read-only: nothing is
 * fetched, checked out or written, no index lock is taken, and the honesty rule holds (`available = false` with a
 * reason is unknown, never clean).
 *
 *   project_git_worktrees       (ProjectGitWorktreesRequest)      -> project_git_worktree_list [ProjectGitWorktreeList]
 *   project_git_worktree_status (ProjectGitWorktreeStatusRequest) -> project_git_worktree      [ProjectGitWorktreeStatus]
 *
 * A worktree is only ever read when `git worktree list` of the PROJECT's repository names it: a path that is not one
 * of them is refused (`project_git_worktree_status refused: ...`), so the frame cannot read an arbitrary directory.
 */

/** `project_git_worktrees`: [sessionId], when set, marks the worktree that session runs in and opens the view on it. */
@Serializable
data class ProjectGitWorktreesRequest(
    val projectId: String,
    val sessionId: String? = null,
) {
    companion object {
        fun decode(text: String): ProjectGitWorktreesRequest =
            StrictJson.decode(serializer(), text, "project_git_worktrees payload")
    }
}

/** One worktree of the repository, as `git worktree list --porcelain` lists it. */
@Serializable
data class GitWorktree(
    /** The worktree's directory, exactly as git lists it; the key every later request names. */
    val path: String,
    /** The checked-out branch; null when HEAD is detached (or the worktree is bare). */
    val branch: String? = null,
    /** HEAD's commit id; null for a bare worktree or a branch with no commit yet. */
    val head: String? = null,
    /** True for the repository's main worktree (the first one git lists). */
    val main: Boolean = false,
    /** True when the project's configured directory is (inside) this worktree. */
    val project: Boolean = false,
    /** True when the request's session runs (inside) this worktree. */
    val session: Boolean = false,
    val detached: Boolean = false,
    val bare: Boolean = false,
    /** True when git reports the worktree's directory as gone (`prunable`): it cannot be read. */
    val prunable: Boolean = false,
)

@Serializable
data class ProjectGitWorktreeList(
    val projectId: String,
    val available: Boolean,
    val reason: String = "",
    /** The main worktree first, then the linked ones in git's order. */
    val worktrees: List<GitWorktree> = emptyList(),
    /**
     * The worktree the view opens on: the session's when the request named a session that runs in this repository,
     * else the project's directory's; null when unavailable.
     */
    val selected: String? = null,
)

/** `project_git_worktree_status`: [path] is a [GitWorktree.path]; [historyLimit] commits of history (default 30, at most 200). */
@Serializable
data class ProjectGitWorktreeStatusRequest(
    val projectId: String,
    val path: String,
    val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
) {
    companion object {
        const val DEFAULT_HISTORY_LIMIT = 30

        fun decode(text: String): ProjectGitWorktreeStatusRequest =
            StrictJson.decode(serializer(), text, "project_git_worktree_status payload")
    }
}

/** One worktree's state. [ahead]/[behind] are null when no base could be resolved ([baseReason] says why). */
@Serializable
data class ProjectGitWorktreeStatus(
    val projectId: String,
    val path: String,
    val available: Boolean,
    val reason: String = "",
    /** The checked-out branch; null when HEAD is detached. */
    val branch: String? = null,
    val head: String? = null,
    /** The base [ahead]/[behind] count against (the branch's recorded base, else the default base, as 1.65.0). */
    val base: String = "",
    val ahead: Int? = null,
    val behind: Int? = null,
    /** Why there is no ahead/behind count; empty when there is one. */
    val baseReason: String = "",
    /** How many distinct paths have uncommitted changes (staged, unstaged or untracked), measured before the cap. */
    val uncommitted: Int = 0,
    val changes: List<ChangedFile> = emptyList(),
    val changesTruncated: Boolean = false,
    /** HEAD's recent history, newest first. */
    val commits: List<GitCommit> = emptyList(),
    val commitsTruncated: Boolean = false,
)
