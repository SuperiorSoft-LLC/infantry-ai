package com.superiorsoftllc.infantry.projectgit

import com.superiorsoftllc.infantry.protocol.Project
import com.superiorsoftllc.infantry.worktree.ChangeKind
import com.superiorsoftllc.infantry.worktree.ChangedFile
import com.superiorsoftllc.infantry.worktree.newGitRepo
import com.superiorsoftllc.infantry.worktree.testGit
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CP-2 slice (b): the git service rooted at a PROJECT's directory.
 *
 * Every test runs against a REAL throwaway git repository in the system temp dir (never the OneDrive
 * workspace), the way `WorktreeManagerTest` does: the claims are about what git actually says, and a
 * fake `GitCli` would only prove the service agrees with itself. The service must read the project's
 * OWN directory -- never the daemon's working directory, which in these tests is a different repo.
 */
class ProjectGitServiceTest {

    private val git = testGit()
    private val service = ProjectGitService(git)
    private val made = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        made.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun repo(): File = newGitRepo("infantry-cp2-git").also { made += it }

    private fun project(dir: File?, id: String = "p1") = Project(id = id, name = "P", directory = dir?.absolutePath.orEmpty())

    private fun File.write(path: String, text: String): File = File(this, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun File.commitAll(message: String) {
        assertTrue(git.run(this, listOf("add", "-A")).ok)
        assertTrue(git.run(this, listOf("commit", "-q", "-m", message)).ok, "commit '$message'")
    }

    private fun File.headSha(): String = git.run(this, listOf("rev-parse", "HEAD")).out()

    // ---- status ---------------------------------------------------------------------------------

    @Test
    fun statusListsModifiedStagedAndUntrackedFilesWithBranchAndHead() {
        val dir = repo()
        dir.write("README.md", "hello\nchanged\n")                    // unstaged modification of a tracked file
        dir.write("staged.txt", "new\n")
        assertTrue(git.run(dir, listOf("add", "staged.txt")).ok)       // staged addition
        dir.write("sub/untracked.txt", "u\n")                          // untracked

        val status = service.status(project(dir))

        assertTrue(status.available, "a real repository is readable: ${status.reason}")
        assertEquals(dir.headSha(), status.head, "HEAD is the commit git reports")
        assertNotNull(status.branch, "a checked-out branch is named")
        assertEquals(
            git.run(dir, listOf("symbolic-ref", "--short", "HEAD")).out(),
            status.branch,
        )
        assertEquals(
            setOf(
                ChangedFile("README.md", ChangeKind.MODIFIED, staged = false),
                ChangedFile("staged.txt", ChangeKind.ADDED, staged = true),
                ChangedFile("sub/untracked.txt", ChangeKind.UNTRACKED, staged = false),
            ),
            status.changes.toSet(),
        )
        assertFalse(status.changesTruncated)
        assertEquals(dir.canonicalFile, File(status.repoRoot).canonicalFile)
    }

    @Test
    fun aCleanRepositoryIsKnownCleanNotUnknown() {
        val status = service.status(project(repo()))

        assertTrue(status.available, "git was asked: ${status.reason}")
        assertTrue(status.changes.isEmpty(), "and listed nothing: ${status.changes}")
    }

    @Test
    fun aProjectWithNoDirectoryIsUnavailableWithAReasonNotClean() {
        val status = service.status(project(null))

        assertFalse(status.available, "no directory is UNKNOWN, never a clean tree")
        assertTrue(status.reason.contains("no directory", ignoreCase = true), "reason: '${status.reason}'")
    }

    @Test
    fun aDirectoryThatIsNotARepositoryIsUnavailableWithAReason() {
        val plain = File(System.getProperty("java.io.tmpdir"), "infantry-cp2-plain-${UUID.randomUUID()}").apply { mkdirs() }
        made += plain

        val status = service.status(project(plain))

        assertFalse(status.available)
        assertTrue(status.reason.contains("not a git repository", ignoreCase = true), "reason: '${status.reason}'")
    }

    @Test
    fun aDirectoryThatVanishedAfterItWasConfiguredIsUnavailable() {
        val dir = repo()
        val gone = project(dir)
        dir.deleteRecursively()

        val status = service.status(gone)

        assertFalse(status.available)
        assertTrue(status.reason.contains("does not exist", ignoreCase = true), "reason: '${status.reason}'")
    }

    @Test
    fun aRepositoryWithNoCommitHasABranchButNoHead() {
        val dir = File(System.getProperty("java.io.tmpdir"), "infantry-cp2-unborn-${UUID.randomUUID()}").apply { mkdirs() }
        made += dir
        assertTrue(git.run(dir, listOf("init", "-q")).ok)
        dir.write("a.txt", "a\n")

        val status = service.status(project(dir))

        assertTrue(status.available, status.reason)
        assertNull(status.head, "no commit yet")
        assertNotNull(status.branch)
        assertEquals(listOf(ChangedFile("a.txt", ChangeKind.UNTRACKED, staged = false)), status.changes)
    }

    @Test
    fun aProjectInASubfolderReadsItsRepositoryFromTheTopLevel() {
        val dir = repo()
        val sub = File(dir, "app").apply { mkdirs() }
        dir.write("app/code.kt", "x\n")

        val status = service.status(project(sub))

        assertTrue(status.available, status.reason)
        assertEquals(dir.canonicalFile, File(status.repoRoot).canonicalFile, "repoRoot is the top level, above the project directory")
        assertTrue(status.changes.any { it.path == "app/code.kt" }, "paths are repo-relative: ${status.changes}")
    }

    @Test
    fun theServiceReadsTheProjectsOwnDirectoryNeverAnotherRepository() {
        val a = repo()
        val b = repo()
        a.write("only-in-a.txt", "a\n")
        b.write("only-in-b.txt", "b\n")

        val statusA = service.status(project(a, "pa"))
        val statusB = service.status(project(b, "pb"))

        assertEquals(listOf("only-in-a.txt"), statusA.changes.map { it.path })
        assertEquals(listOf("only-in-b.txt"), statusB.changes.map { it.path })
        assertEquals("pa", statusA.projectId)
    }

    @Test
    fun theChangeListIsCappedAndSaysSo() {
        val dir = repo()
        repeat(5) { dir.write("f$it.txt", "x\n") }

        val status = ProjectGitService(git, changeCap = 3).status(project(dir))

        assertEquals(3, status.changes.size)
        assertTrue(status.changesTruncated, "a cut list must say it was cut")
    }

    // ---- diff -----------------------------------------------------------------------------------

    @Test
    fun diffOfAnUnstagedModificationIsTheUnifiedPatch() {
        val dir = repo()
        dir.write("README.md", "hello\nsecond line\n")

        val patch = service.diff(project(dir), "README.md", staged = false)

        assertTrue(patch.available, patch.reason)
        assertTrue(patch.patch.contains("+second line"), "added line is in the patch:\n${patch.patch}")
        assertTrue(patch.patch.contains("@@"), "it is a unified diff:\n${patch.patch}")
        assertFalse(patch.binary)
        assertFalse(patch.truncated)
    }

    @Test
    fun diffPicksTheStagedOrUnstagedSide() {
        val dir = repo()
        dir.write("README.md", "hello\nstaged line\n")
        assertTrue(git.run(dir, listOf("add", "README.md")).ok)
        dir.write("README.md", "hello\nstaged line\nunstaged line\n")

        val staged = service.diff(project(dir), "README.md", staged = true)
        val unstaged = service.diff(project(dir), "README.md", staged = false)

        assertTrue(staged.patch.contains("+staged line") && !staged.patch.contains("+unstaged line"), "staged side:\n${staged.patch}")
        assertTrue(unstaged.patch.contains("+unstaged line") && !unstaged.patch.contains("+staged line"), "unstaged side:\n${unstaged.patch}")
    }

    @Test
    fun diffOfAnUntrackedFileShowsItAsAllAdded() {
        val dir = repo()
        dir.write("new/notes.txt", "brand new\nfile\n")

        val patch = service.diff(project(dir), "new/notes.txt", staged = false)

        assertTrue(patch.available, patch.reason)
        assertTrue(patch.patch.contains("+brand new") && patch.patch.contains("+file"), "every line is an addition:\n${patch.patch}")
    }

    @Test
    fun aBinaryFileIsFlaggedNotDumped() {
        val dir = repo()
        File(dir, "blob.bin").writeBytes(byteArrayOf(0, 1, 2, 0, 3, 0, 0, 4))

        val patch = service.diff(project(dir), "blob.bin", staged = false)

        assertTrue(patch.available, patch.reason)
        assertTrue(patch.binary, "git calls it binary")
        assertEquals("", patch.patch, "no text to show for a binary file")
    }

    @Test
    fun anOversizedPatchIsCutAndSaysSo() {
        val dir = repo()
        dir.write("big.txt", (1..500).joinToString("\n") { "line number $it" } + "\n")

        val patch = ProjectGitService(git, patchCap = 200).diff(project(dir), "big.txt", staged = false)

        assertTrue(patch.available, patch.reason)
        assertTrue(patch.truncated)
        assertTrue(patch.patch.length <= 200, "cut at the cap, was ${patch.patch.length}")
    }

    @Test
    fun aPathThatCouldEscapeTheRepositoryIsRefusedBeforeGitIsAsked() {
        val dir = repo()
        val p = project(dir)

        for (bad in listOf("../outside.txt", "a/../../outside.txt", "..\\outside.txt", "/etc/passwd", "C:\\Windows\\win.ini", "", "  ", "a\u0000b")) {
            assertFailsWith<IllegalArgumentException>("path '$bad' must be refused") {
                service.diff(p, bad, staged = false)
            }
        }
    }

    @Test
    fun diffWithNoDirectoryIsUnavailableNotAnEmptyPatch() {
        val patch = service.diff(project(null), "a.txt", staged = false)

        assertFalse(patch.available)
        assertTrue(patch.reason.contains("no directory", ignoreCase = true), patch.reason)
    }

    // ---- log ------------------------------------------------------------------------------------

    @Test
    fun logListsCommitsNewestFirstWithAuthorSubjectAndHash() {
        val dir = repo()
        dir.write("a.txt", "a\n"); dir.commitAll("second commit")
        dir.write("b.txt", "b\n"); dir.commitAll("third commit")

        val history = service.log(project(dir), limit = 10)

        assertTrue(history.available, history.reason)
        assertEquals(listOf("third commit", "second commit", "init"), history.commits.map { it.subject })
        assertEquals(dir.headSha(), history.commits.first().hash, "newest first, with the full commit id")
        assertTrue(history.commits.first().hash.startsWith(history.commits.first().shortHash))
        assertEquals("Infantry Test", history.commits.first().author)
        assertTrue(history.commits.first().timestamp > 1_600_000_000L, "epoch seconds: ${history.commits.first().timestamp}")
        assertFalse(history.truncated)
    }

    @Test
    fun logHonoursTheLimitAndSaysWhenThereIsMore() {
        val dir = repo()
        dir.write("a.txt", "a\n"); dir.commitAll("two")
        dir.write("b.txt", "b\n"); dir.commitAll("three")

        val history = service.log(project(dir), limit = 2)

        assertEquals(listOf("three", "two"), history.commits.map { it.subject })
        assertTrue(history.truncated, "a third commit exists, so the answer says it was cut")
    }

    @Test
    fun logOfARepositoryWithNoCommitIsKnownEmptyNotAnError() {
        val dir = File(System.getProperty("java.io.tmpdir"), "infantry-cp2-unborn-log-${UUID.randomUUID()}").apply { mkdirs() }
        made += dir
        assertTrue(git.run(dir, listOf("init", "-q")).ok)

        val history = service.log(project(dir), limit = 5)

        assertTrue(history.available, history.reason)
        assertTrue(history.commits.isEmpty())
    }

    @Test
    fun logWithNoDirectoryIsUnavailable() {
        val history = service.log(project(null), limit = 5)

        assertFalse(history.available)
        assertTrue(history.commits.isEmpty())
    }
}
