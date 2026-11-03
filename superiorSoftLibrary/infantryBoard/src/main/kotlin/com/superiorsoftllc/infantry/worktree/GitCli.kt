// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.worktree

import java.io.File

/**
 * The outcome of one `git` invocation. A non-zero exit is DATA, never an exception: git reports
 * "branch already exists" / "not a git repository" on stderr with a normal exit, and the worktree
 * manager must be able to read that and react rather than unwind.
 */
data class GitResult(
    val args: List<String>,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0

    /** stdout without its trailing newline (git always ends a captured line with one). */
    fun out(): String = stdout.trim()

    /** stderr, falling back to stdout when git wrote its complaint to the other stream. */
    fun err(): String = stderr.trim().ifEmpty { stdout.trim() }

    override fun toString(): String = "git ${args.joinToString(" ")} -> $exitCode"
}

/**
 * The git seam (Phase 4).
 *
 * Shelling out to the real `git` is DELIBERATE, and stated so it is not "improved" later: linked
 * worktrees, branch bookkeeping and `rev-list` ahead/behind arithmetic are exactly the surface a
 * re-implementation (JGit) gets subtly wrong, and every tool in this space shells out. One narrow
 * method also makes the manager trivially testable with a fake and keeps every git detail in one file.
 */
fun interface GitCli {
    /** Run `git <args>` with [cwd] as the working directory. Never throws on a non-zero exit. */
    fun run(cwd: File, args: List<String>): GitResult
}
