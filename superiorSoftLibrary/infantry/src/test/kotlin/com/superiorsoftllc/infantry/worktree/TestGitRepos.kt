// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.worktree

import java.io.File
import java.util.UUID
import kotlin.test.assertTrue

/**
 * The committer identity a test injects into git, so the machine's global git config is untouched.
 * Shared by [WorktreeManagerTest] and the daemon ticket-start tests, which cut real worktrees.
 */
fun testGitEnv(): Map<String, String> = mapOf(
    "GIT_AUTHOR_NAME" to "Infantry Test",
    "GIT_AUTHOR_EMAIL" to "infantry@test.local",
    "GIT_COMMITTER_NAME" to "Infantry Test",
    "GIT_COMMITTER_EMAIL" to "infantry@test.local",
)

fun testGit(): SystemGitCli = SystemGitCli(extraEnv = testGitEnv())

/**
 * A throwaway REAL git repository under the system temp dir (NOT the OneDrive-synced workspace), with
 * one initial commit, so a worktree can be cut from it. Promoted from [WorktreeManagerTest] so the
 * daemon ticket-start tests reuse it rather than growing a second copy.
 */
fun newGitRepo(prefix: String = "infantry-wt"): File {
    val git = testGit()
    val dir = File(System.getProperty("java.io.tmpdir"), "$prefix-${UUID.randomUUID()}").apply { mkdirs() }
    assertTrue(git.run(dir, listOf("init", "-q")).ok, "git init must succeed")
    File(dir, "README.md").writeText("hello\n")
    assertTrue(git.run(dir, listOf("add", "-A")).ok)
    assertTrue(git.run(dir, listOf("commit", "-q", "-m", "init")).ok, "initial commit must succeed")
    return dir
}
