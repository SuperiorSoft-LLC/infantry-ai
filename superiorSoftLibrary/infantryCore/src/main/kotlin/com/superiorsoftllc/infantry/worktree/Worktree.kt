// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.worktree

import kotlinx.serialization.Serializable

/**
 * The live, per-worktree state a client shows: does it still exist, is it dirty, and how far has the
 * agent's branch moved from the point it was cut. Computed from git on demand, never cached.
 */
@Serializable
data class WorktreeStatus(
    val sessionId: String,
    val repoRoot: String,
    val path: String,
    val branch: String,
    val baseRef: String,
    val exists: Boolean,
    val dirty: Boolean,
    /** Commits on the branch that are not on [baseRef]. */
    val ahead: Int,
    /** Commits on [baseRef] that are not on the branch. */
    val behind: Int,
    val removed: Boolean,
    /**
     * The files git reports as changed in THIS worktree (never the project root), in git's own order.
     *
     * Three states, deliberately distinct -- conflating them is the silent-wrong-answer shape:
     *  - `null`: UNKNOWN. The directory is gone, or git could not be read. Absent on the wire from a
     *    daemon that predates the field, which a client reads the same way.
     *  - empty: KNOWN CLEAN. Git was asked and listed nothing.
     *  - non-empty: the changes. A file changed on both sides appears twice (once staged, once not).
     */
    val changes: List<ChangedFile>? = null,
    /** True when git listed more than the cap and [changes] holds only the first part of it. */
    val changesTruncated: Boolean = false,
)

/** What git says happened to a path. `TYPE_CHANGE` is folded into [MODIFIED]: it is still a content change. */
@Serializable
enum class ChangeKind { ADDED, MODIFIED, DELETED, RENAMED, UNTRACKED, CONFLICTED }

/**
 * One changed path in a worktree. [staged] says which side of the index the [status] describes: an
 * `UNTRACKED` or `CONFLICTED` file is never staged. [fromPath] is the old path of a [ChangeKind.RENAMED] file.
 */
@Serializable
data class ChangedFile(
    val path: String,
    val status: ChangeKind,
    val staged: Boolean,
    val fromPath: String? = null,
)

/**
 * The honest result of a cleanup. [removed] is whether a worktree directory was actually removed;
 * [branchKept] says whether the agent's branch survives as the recoverable archive; [message] is the
 * human explanation, in particular WHY a removal was refused so an agent never silently loses work.
 */
@Serializable
data class CleanupResult(
    val sessionId: String,
    val path: String,
    val branch: String,
    val removed: Boolean,
    val branchKept: Boolean,
    val dirty: Boolean,
    val forced: Boolean,
    val message: String,
)

/**
 * The same identity as [WorktreeRecord] without the timestamp, used to rebuild the manager's registry
 * from persisted sessions at daemon startup. Defined here (not in the session package) so the
 * worktree package never depends on the lifecycle package -- the dependency runs one way.
 */
@Serializable
data class WorktreeRef(
    val sessionId: String,
    val repoRoot: String,
    val path: String,
    val branch: String,
    val baseRef: String,
)
