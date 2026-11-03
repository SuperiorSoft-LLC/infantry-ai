package com.superiorsoftllc.infantry.projectgit

import com.superiorsoftllc.infantry.protocol.Project
import com.superiorsoftllc.infantry.worktree.newGitRepo
import com.superiorsoftllc.infantry.worktree.testGit
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UX-CHANGES-1 (contract 1.88.0): the worktree view over a REAL temp repository whose linked worktree sits INSIDE the
 * main checkout (`.worktrees/feature`, the layout ticket starts use), so "which worktree holds this directory" must
 * pick the deepest one. Also the boundary: a path the repository does not list as a worktree is refused, never read.
 */
class ProjectGitWorktreeServiceTest {

    private val git = testGit()
    private val service = ProjectGitService(git)
    private val made = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        made.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun project(dir: File) = Project(id = "p1", name = "P", directory = dir.absolutePath)
    private fun File.write(path: String, text: String): File = File(this, path).apply { parentFile.mkdirs(); writeText(text) }
    private fun File.git(vararg args: String): String {
        val r = git.run(this, args.toList())
        assertTrue(r.ok, "git ${args.joinToString(" ")}: ${r.err()}")
        return r.out()
    }

    /** main (1 commit + an untracked file) and `.worktrees/feature` on `feature`, 2 commits ahead, 1 file dirty. */
    private fun repoWithNestedWorktree(): Pair<File, File> {
        val main = newGitRepo("infantry-uxc1-git").also { made += it }
        main.git("branch", "-M", "main")
        main.write(".gitignore", ".worktrees/\n")
        main.git("add", ".gitignore")
        main.git("commit", "-q", "-m", "ignore worktrees")
        val wt = File(main, ".worktrees/feature")
        main.git("worktree", "add", "-q", "-b", "feature", wt.absolutePath)
        wt.write("feature.txt", "one\n")
        wt.git("add", "feature.txt")
        wt.git("commit", "-q", "-m", "first feature commit")
        wt.write("feature.txt", "one\ntwo\n")
        wt.git("commit", "-q", "-am", "second feature commit")
        wt.write("feature.txt", "one\ntwo\nnot committed\n")
        main.write("main-only.txt", "untracked on main\n")
        return main to wt
    }

    @Test
    fun `the list marks the deepest worktree holding the session's directory and opens on it`() {
        val (main, wt) = repoWithNestedWorktree()
        // The session runs in a SUBFOLDER of the nested worktree, which is itself inside the main checkout.
        val inner = File(wt, "src").apply { mkdirs() }

        val list = service.worktrees(project(main), sessionDir = inner.absolutePath)

        assertTrue(list.available, list.reason)
        assertEquals(listOf("main", "feature"), list.worktrees.map { it.branch })
        val (m, f) = list.worktrees
        assertTrue(m.main && m.project && !m.session, "main is the shared checkout and the project's: $m")
        assertTrue(!f.main && !f.project && f.session, "feature holds the session's directory: $f")
        assertEquals(f.path, list.selected)
    }

    @Test
    fun `with no session the list opens on the project's checkout`() {
        val (main, _) = repoWithNestedWorktree()
        val list = service.worktrees(project(main), sessionDir = null)
        assertTrue(list.worktrees.none { it.session })
        assertEquals(list.worktrees.single { it.main }.path, list.selected)
    }

    @Test
    fun `a worktree's status counts ahead and behind its base, its uncommitted paths and its history`() {
        val (main, _) = repoWithNestedWorktree()
        val path = service.worktrees(project(main), null).worktrees.single { it.branch == "feature" }.path

        val s = service.worktreeStatus(project(main), path, historyLimit = 2)

        assertTrue(s.available, s.reason)
        assertEquals("feature", s.branch)
        assertEquals("main", s.base)
        assertEquals(2, s.ahead)
        assertEquals(0, s.behind)
        assertEquals(1, s.uncommitted)
        assertEquals(listOf("feature.txt"), s.changes.map { it.path })
        assertEquals(listOf("second feature commit", "first feature commit"), s.commits.map { it.subject })
        assertTrue(s.commitsTruncated, "the branch has more commits than the 2 asked for")
    }

    @Test
    fun `the main worktree has no base of its own and says why instead of counting zero`() {
        val (main, _) = repoWithNestedWorktree()
        val path = service.worktrees(project(main), null).worktrees.single { it.main }.path

        val s = service.worktreeStatus(project(main), path, historyLimit = 30)

        assertTrue(s.available, s.reason)
        assertEquals("main", s.branch)
        assertNull(s.ahead)
        assertNull(s.behind)
        assertTrue(s.baseReason.startsWith("no base found"), s.baseReason)
        assertEquals(listOf("main-only.txt"), s.changes.map { it.path })
    }

    @Test
    fun `a directory the repository does not list as a worktree is refused for status and for a diff`() {
        val (main, _) = repoWithNestedWorktree()
        val elsewhere = newGitRepo("infantry-uxc1-other").also { made += it }
        val plainDir = Files.createTempDirectory("infantry-uxc1-plain").toFile().also { made += it }

        for (path in listOf(elsewhere.absolutePath, plainDir.absolutePath, File(main, "src").absolutePath)) {
            val status = assertFailsWith<IllegalArgumentException> { service.worktreeStatus(project(main), path, 30) }
            assertTrue(status.message!!.startsWith("not a worktree of this project's repository"), status.message)
            assertFailsWith<IllegalArgumentException> { service.diff(project(main), "README.md", staged = false, worktree = path) }
        }
    }

    @Test
    fun `a diff in a worktree reads that worktree's file and echoes it`() {
        val (main, _) = repoWithNestedWorktree()
        val path = service.worktrees(project(main), null).worktrees.single { it.branch == "feature" }.path

        val patch = service.diff(project(main), "feature.txt", staged = false, worktree = path)

        assertTrue(patch.available, patch.reason)
        assertEquals(path, patch.worktree)
        assertTrue(patch.patch.contains("+not committed"), patch.patch)
        // Without a worktree the same path is read in the project's directory, where feature.txt does not exist.
        assertFalse(service.diff(project(main), "feature.txt", staged = false).patch.contains("+not committed"))
    }
}
