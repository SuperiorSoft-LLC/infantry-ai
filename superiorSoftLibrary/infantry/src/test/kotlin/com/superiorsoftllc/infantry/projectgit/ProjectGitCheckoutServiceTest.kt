package com.superiorsoftllc.infantry.projectgit

import com.superiorsoftllc.infantry.protocol.Project
import com.superiorsoftllc.infantry.worktree.newGitRepo
import com.superiorsoftllc.infantry.worktree.testGit
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BRANCH-SWITCH-1 (contract 1.74.0): `checkout` against a REAL temp repository. Every refusal is checked for
 * what it says AND for what it did not do: HEAD stays put and the uncommitted bytes are still on disk.
 */
class ProjectGitCheckoutServiceTest {

    private val git = testGit()
    private val service = ProjectGitService(git)
    private val made = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        made.reversed().forEach { runCatching { it.deleteRecursively() } }
    }

    private fun repo(): File = newGitRepo("infantry-bs1-git").also { made += it }
    private fun project(dir: File) = Project(id = "p1", name = "P", directory = dir.absolutePath)
    private fun File.git(vararg args: String): String {
        val r = git.run(this, args.toList())
        assertTrue(r.ok, "git ${args.joinToString(" ")}: ${r.err()}")
        return r.out()
    }
    private fun File.head(): String = git("symbolic-ref", "--short", "HEAD")
    private fun File.localBranches(): Set<String> =
        git("for-each-ref", "--format=%(refname:short)", "refs/heads/").lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** main (checked out) and feature, which adds feature.txt; the working tree is clean. */
    private fun twoBranchRepo(): File {
        val dir = repo()
        dir.git("branch", "-M", "main")
        dir.git("checkout", "-q", "-b", "feature")
        File(dir, "feature.txt").writeText("feature\n")
        dir.git("add", "feature.txt")
        dir.git("commit", "-q", "-m", "feature work")
        dir.git("checkout", "-q", "main")
        return dir
    }

    @Test
    fun aCleanTreeSwitchesToAnExistingLocalBranch() {
        val dir = twoBranchRepo()

        val result = service.checkout(project(dir), "feature", create = false)

        assertTrue(result.switched, result.reason)
        assertEquals("", result.code)
        assertEquals("main", result.previous)
        assertEquals("feature", result.current)
        assertEquals("feature", dir.head(), "git itself must report the new branch")
        assertTrue(File(dir, "feature.txt").isFile, "the working tree is the feature branch's")
    }

    @Test
    fun aDirtyTreeIsRefusedWithTheFileCountAndNothingIsTouched() {
        val dir = twoBranchRepo()
        File(dir, "README.md").writeText("uncommitted edit\n")
        File(dir, "staged.txt").writeText("staged\n")
        dir.git("add", "staged.txt")
        File(dir, "untracked.txt").writeText("not counted\n")

        val result = service.checkout(project(dir), "feature", create = false)

        assertFalse(result.switched)
        assertEquals(CheckoutRefusal.DIRTY, result.code)
        assertEquals(2, result.dirtyCount, "README.md (modified) + staged.txt (staged); the untracked file is not counted")
        assertTrue(result.reason.contains("2 files"), result.reason)
        assertEquals("main", result.current)
        assertEquals("main", dir.head(), "a refusal never switches")
        assertEquals("uncommitted edit\n", File(dir, "README.md").readText(), "a refusal never discards")
        assertTrue(dir.git("stash", "list").isEmpty(), "a refusal never stashes")
    }

    @Test
    fun aBranchCheckedOutInAnotherWorktreeIsRefusedWithThatWorktreesPath() {
        val dir = twoBranchRepo()
        val other = File(System.getProperty("java.io.tmpdir"), "infantry-bs1-wt-${UUID.randomUUID()}")
        dir.git("worktree", "add", "-q", other.absolutePath, "feature")
        made += other

        val result = service.checkout(project(dir), "feature", create = false)

        assertFalse(result.switched)
        assertEquals(CheckoutRefusal.HELD_BY_WORKTREE, result.code)
        val held = assertNotNull(result.worktreePath, "worktreePath must name the holding worktree")
        assertEquals(other.canonicalFile, File(held).canonicalFile)
        assertEquals("main", dir.head())
    }

    @Test
    fun aNameGitRejectsIsRefusedBeforeAnySwitch() {
        val dir = twoBranchRepo()

        // `ends.lock`, `has~tilde` and `has:colon` pass the daemon's own pre-check; only `check-ref-format` stops them.
        for (bad in listOf("bad name", "a..b", "-x", "ends.lock", "has~tilde", "has:colon", "")) {
            val result = service.checkout(project(dir), bad, create = true)
            assertFalse(result.switched, "'$bad' must not switch")
            assertEquals(CheckoutRefusal.BAD_NAME, result.code, "'$bad': ${result.reason}")
        }
        assertEquals("main", dir.head())
        assertEquals(setOf("main", "feature"), dir.localBranches(), "no branch was created")
    }

    @Test
    fun createMakesANewBranchFromTheCurrentHeadAndSwitchesToIt() {
        val dir = twoBranchRepo()
        val mainHead = dir.git("rev-parse", "HEAD")

        val result = service.checkout(project(dir), "topic/new-work", create = true)

        assertTrue(result.switched, result.reason)
        assertEquals("main", result.previous)
        assertEquals("topic/new-work", result.current)
        assertEquals("topic/new-work", dir.head())
        assertEquals(mainHead, dir.git("rev-parse", "HEAD"), "the new branch starts at the current HEAD")
    }

    @Test
    fun createRefusesAnExistingBranchAndSwitchRefusesAMissingOne() {
        val dir = twoBranchRepo()

        val exists = service.checkout(project(dir), "feature", create = true)
        assertEquals(CheckoutRefusal.EXISTS, exists.code)
        assertFalse(exists.switched)

        val missing = service.checkout(project(dir), "nope", create = false)
        assertEquals(CheckoutRefusal.NO_BRANCH, missing.code)
        assertFalse(missing.switched)
        assertEquals("main", dir.head())
        assertEquals(setOf("main", "feature"), dir.localBranches())
    }

    @Test
    fun aProjectWithNoDirectoryAnswersUnavailable() {
        val result = service.checkout(Project(id = "p1", name = "P", directory = ""), "main", create = false)
        assertFalse(result.switched)
        assertEquals(CheckoutRefusal.UNAVAILABLE, result.code)
        assertTrue(result.reason.contains("no directory"), result.reason)
    }
}
