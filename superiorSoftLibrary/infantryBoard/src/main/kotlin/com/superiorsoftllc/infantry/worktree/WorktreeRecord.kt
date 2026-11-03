// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.worktree

import kotlinx.serialization.Serializable

/**
 * One session's git worktree: the durable identity of where an agent works.
 *
 * Serialized both to the session store (as fields on `SessionRecord`) and, live, to a client over the
 * WebSocket, so "which agent is where" is answerable without touching the filesystem.
 *
 * [baseRef] is the immutable COMMIT the worktree was cut from -- not a branch name. That is what
 * makes the archive meaningful: the base never moves, so ahead/behind stay correct forever and the
 * worktree can be restored from an exact point even after the main checkout has advanced.
 */
@Serializable
data class WorktreeRecord(
    val sessionId: String,
    val repoRoot: String,
    val path: String,
    val branch: String,
    val baseRef: String,
    val createdAt: Long,
    /** Set when the worktree was explicitly removed; the branch is kept unless deleteBranch asked otherwise. */
    val removedAt: Long? = null,
) {
    /** True when the worktree directory was deliberately removed (an archive, not a loss). */
    val removed: Boolean get() = removedAt != null
}
