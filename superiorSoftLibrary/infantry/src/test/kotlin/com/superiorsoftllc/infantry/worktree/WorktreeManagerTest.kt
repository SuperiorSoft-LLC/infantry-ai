// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.worktree

import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Phase 4 gate, against REAL git (no fake, no JGit): two sessions on one repository get their own
 * worktree and branch and cannot collide, an agent's work is isolated and independently recoverable,
 * and cleanup never silently destroys uncommitted work.
 *
 * A throwaway repository is created per test under the system temp dir (NOT the OneDrive-synced
 * workspace) with an injected committer identity, so the machine's global git config is untouched.
 */
class WorktreeManagerTest {

    private val git = testGit()

    private fun newRepo(): File = newGitRepo()

    private fun manager() = GitWorktreeManager(git)

    @Test
    fun create_cutsBranchAndWorktree_atTheRepoConventionPath() {
        val repo = newRepo()
        val record = manager().create("session-abc12345", repo, "phase 4 worktree")

        assertEquals("infantry/phase-4-worktree-abc12345", record.branch)
        assertTrue(File(record.path, ".git").exists(), "a linked worktree carries a .git file")
        assertEquals(File(repo, ".worktrees/infantry-phase-4-worktree-abc12345").absolutePath, record.path)
        assertTrue(git.run(repo, listOf("branch", "--list", record.branch)).out().contains(record.branch))
        // `git worktree list` prints forward-slash paths on every platform, so compare normalized.
        val listed = git.run(repo, listOf("worktree", "list", "--porcelain")).out().replace('\\', '/')
        assertTrue(listed.contains(record.path.replace('\\', '/')))
    }

    @Test
    fun twoSessions_onOneRepo_doNotCollide_andStayIsolated() {
        val repo = newRepo()
        val manager = manager()

        // SAME task hint on purpose: uniqueness must come from the session id, not the hint.
        val a = manager.create("aaaa1111", repo, "build the thing")
        val b = manager.create("bbbb2222", repo, "build the thing")

        assertNotEquals(a.branch, b.branch, "two sessions must never share a branch")
        assertNotEquals(a.path, b.path, "two sessions must never share a path")
        assertTrue(File(a.path, ".git").exists())
        assertTrue(File(b.path, ".git").exists())
        assertTrue(manager.status(a).exists && manager.status(b).exists, "both worktrees exist at once")

        // Isolation: a file written in A exists in neither B nor the main checkout.
        File(a.path, "only-a.txt").writeText("a")
        assertTrue(File(a.path, "only-a.txt").exists())
        assertFalse(File(b.path, "only-a.txt").exists(), "B must not see A's untracked file")
        assertFalse(File(repo, "only-a.txt").exists(), "the main checkout must not see A's file")

        // Commit in A, and only A's branch moves.
        git.run(File(a.path), listOf("add", "-A"))
        assertTrue(git.run(File(a.path), listOf("commit", "-q", "-m", "a work")).ok)
        assertEquals(1, manager.status(a).ahead)
        assertEquals(0, manager.status(b).ahead)
    }

    @Test
    fun status_reportsDirtyThenCleanAhead() {
        val repo = newRepo()
        val manager = manager()
        val record = manager.create("cccc3333", repo, "status")

        assertFalse(manager.status(record).dirty)
        assertEquals(0, manager.status(record).ahead)

        File(record.path, "wip.txt").writeText("wip")
        assertTrue(manager.status(record).dirty, "an untracked file makes the worktree dirty")

        git.run(File(record.path), listOf("add", "-A"))
        git.run(File(record.path), listOf("commit", "-q", "-m", "wip"))
        val clean = manager.status(record)
        assertFalse(clean.dirty)
        assertEquals(1, clean.ahead)
        assertEquals(0, clean.behind)
    }

    @Test
    fun remove_refusesDirtyWorktree_thenForceKeepsBranchRecoverable() {
        val repo = newRepo()
        val manager = manager()
        val record = manager.create("dddd4444", repo, "cleanup")

        File(record.path, "uncommitted.txt").writeText("do not lose me")
        val refused = manager.remove(record.sessionId)
        assertFalse(refused.removed, "a dirty worktree must not be removed by default")
        assertTrue(refused.dirty)
        assertTrue(File(record.path).exists(), "the refused worktree must still be on disk")

        // Commit, then remove: the worktree goes, the BRANCH stays -> recoverable, nothing lost.
        git.run(File(record.path), listOf("add", "-A"))
        git.run(File(record.path), listOf("commit", "-q", "-m", "save the work"))
        val removed = manager.remove(record.sessionId)
        assertTrue(removed.removed)
        assertTrue(removed.branchKept, "the branch is the archive; it must be kept")
        assertFalse(File(record.path).exists())
        assertTrue(git.run(repo, listOf("branch", "--list", record.branch)).out().contains(record.branch))

        // Reversible: restore the worktree from the kept branch, with the committed work present.
        val restore = git.run(repo, listOf("worktree", "add", record.path, record.branch))
        assertTrue(restore.ok, "the kept branch must be restorable: ${restore.err()}")
        assertTrue(File(record.path, "uncommitted.txt").exists())
    }

    @Test
    fun create_retriesToAUniqueName_whenTheTargetPathIsTaken() {
        val repo = newRepo()
        // A leftover directory exactly where the first attempt would land.
        File(repo, ".worktrees/infantry-collide-eee55555").mkdirs()
        val record = manager().create("eee55555", repo, "collide")

        assertEquals("infantry/collide-eee55555-1", record.branch)
        assertTrue(File(record.path, ".git").exists())
    }

    @Test
    fun create_isIdempotentPerSession() {
        val repo = newRepo()
        val manager = manager()
        val first = manager.create("ffff6666", repo, "idempotent")
        val second = manager.create("ffff6666", repo, "a different hint")
        assertEquals(first.path, second.path)
        assertEquals(first.branch, second.branch)
    }

    /**
     * The daemon inherits its launching environment, and `GIT_DIR` in that environment overrides
     * git's own repository discovery for EVERY git call. An over-long value makes git refuse with
     * "'$GIT_DIR' too big" -- the failure reported as "$GIT_DIR too big" once INFANTRY_REPO routes
     * sessions through worktrees. The worktree must still be cut from the repo the caller named.
     */
    @Test
    fun create_ignoresAnInheritedOverLongGitDir() {
        val repo = newRepo()
        val inherited = SystemGitCli(extraEnv = testGitEnv() + ("GIT_DIR" to "x".repeat(300)))
        val record = GitWorktreeManager(inherited).create("gggg7777", repo, "inherited gitdir")

        assertTrue(File(record.path, ".git").exists(), "the worktree must be cut despite an inherited GIT_DIR")
        assertEquals(repo.canonicalFile.path, File(record.repoRoot).canonicalFile.path)
    }

    /**
     * A VALID `GIT_DIR` pointing at a different repository must not redirect the cut: the worktree
     * belongs to the repository named by the caller, never to whichever repo the variable points at.
     */
    @Test
    fun create_ignoresAnInheritedGitDirOfAnotherRepository() {
        val repo = newRepo()
        val other = newGitRepo("infantry-other")
        val inherited = SystemGitCli(extraEnv = testGitEnv() + ("GIT_DIR" to File(other, ".git").absolutePath))
        val record = GitWorktreeManager(inherited).create("hhhh8888", repo, "other gitdir")

        assertEquals(repo.canonicalFile.path, File(record.repoRoot).canonicalFile.path, "must cut from the named repo")
        assertTrue(File(repo, ".worktrees").exists())
        assertFalse(File(other, ".worktrees").exists(), "the other repository must be untouched")
    }

    @Test
    fun create_rejectsANonGitDirectory() {
        val dir = File(System.getProperty("java.io.tmpdir"), "infantry-nogit-${UUID.randomUUID()}").apply { mkdirs() }
        val ex = kotlin.runCatching { manager().create("x", dir, null) }.exceptionOrNull()
        assertTrue(ex is WorktreeException, "a non-repo must fail loudly, got $ex")
    }
}
