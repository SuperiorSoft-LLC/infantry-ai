package com.superiorsoftllc.agenthq.worktree

/**
 * What a ticket start needs to run in its own branch: the branch, the commit it was cut from, and
 * where its worktree lives. [base] is an immutable COMMIT, the same rule as [WorktreeRecord.baseRef].
 */
data class TicketWorktree(
    val branch: String,
    val base: String,
    val path: String,
    val repoRoot: String,
)
