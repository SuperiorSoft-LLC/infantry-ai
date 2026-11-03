package com.superiorsoftllc.infantry.projectgit

import com.superiorsoftllc.infantry.protocol.Project
import com.superiorsoftllc.infantry.worktree.ChangeKind
import com.superiorsoftllc.infantry.worktree.newGitRepo
import com.superiorsoftllc.infantry.worktree.testGit
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BRANCH-VIEW-1 (contract 1.65.0): branch list, a branch's changes since its merge-base, and one file's
 * branch diff -- read from a REAL temp git repository with two branches. The claims are about what git
 * says about the BRANCH (merge-base..branch), never about the working tree.
 */
class ProjectGitBranchServiceTest {

    private val git = testGit()
    private val service = ProjectGitService(git)
    private val made = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        made.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun repo(): File = newGitRepo("infantry-bv1-git").also { made += it }
    private fun project(dir: File) = Project(id = "p1", name = "P", directory = dir.absolutePath)
    private fun File.write(path: String, text: String): File = File(this, path).apply { parentFile.mkdirs(); writeText(text) }
    private fun File.git(vararg args: String): String {
        val r = git.run(this, args.toList())
        assertTrue(r.ok, "git ${args.joinToString(" ")}: ${r.err()}")
        return r.out()
    }
    private fun File.commitAll(message: String) { git("add", "-A"); git("commit", "-q", "-m", message) }

    /**
     * main: init -> main-only change (after the fork). feature: forks at init, edits README, adds
     * feature.txt, renames nothing. The working tree is left DIRTY on main with an unrelated file, so a
     * service that read the working tree instead of the branch would list it.
     */
    private fun twoBranchRepo(): File {
        val dir = repo()
        dir.git("branch", "-M", "main")
        dir.git("checkout", "-q", "-b", "feature")
        dir.write("README.md", "hello\nfrom feature\n")
        dir.write("src/feature.txt", "feature\n")
        dir.commitAll("feature work")
        dir.git("checkout", "-q", "main")
        dir.write("main-only.txt", "main moved on\n")
        dir.commitAll("main moves")
        dir.write("dirty-working-tree.txt", "not on any branch\n")
        return dir
    }

    @Test
    fun branchChangesListExactlyTheFilesChangedOnTheBranchSinceTheMergeBase() {
        val dir = twoBranchRepo()
        val forkPoint = dir.git("rev-parse", "main~1")

        val answer = service.branchChanges(project(dir), branch = "feature", base = "main")

        assertTrue(answer.available, answer.reason)
        assertEquals("feature", answer.branch)
        assertEquals("main", answer.base)
        assertEquals(forkPoint, answer.mergeBase, "the merge-base is the fork point, not main's tip")
        assertEquals(
            setOf("README.md" to ChangeKind.MODIFIED, "src/feature.txt" to ChangeKind.ADDED),
            answer.changes.map { it.path to it.status }.toSet(),
            "only merge-base..feature: no main-only.txt, no dirty working-tree file",
        )
        val readme = answer.changes.single { it.path == "README.md" }
        assertEquals(1, readme.added)
        assertEquals(0, readme.removed)
        assertFalse(answer.truncated)
    }

    @Test
    fun aRenameOnTheBranchCarriesItsFromPath() {
        val dir = twoBranchRepo()
        dir.git("checkout", "-q", "feature")
        dir.git("mv", "src/feature.txt", "src/renamed.txt")
        dir.git("commit", "-q", "-m", "rename") // only the staged rename, never the untracked dirty file
        dir.git("checkout", "-q", "main")

        val answer = service.branchChanges(project(dir), branch = "feature", base = "main")

        val renamed = answer.changes.single { it.path == "src/renamed.txt" }
        // feature.txt did not exist at the merge-base, so relative to it the file is simply ADDED.
        assertEquals(ChangeKind.ADDED, renamed.status)
        assertNull(renamed.fromPath)

        // Against the branch's previous commit it IS a rename.
        val prev = dir.git("rev-parse", "feature~1")
        val vsPrev = service.branchChanges(project(dir), branch = "feature", base = prev)
        val r = vsPrev.changes.single()
        assertEquals(ChangeKind.RENAMED, r.status)
        assertEquals("src/renamed.txt", r.path)
        assertEquals("src/feature.txt", r.fromPath)
    }

    @Test
    fun theDefaultBaseIsTheBranchsRecordedBaseThenFeatureInfantryMvpThenTheDefaultBranch() {
        val dir = twoBranchRepo()
        // No expected-bases record and no feature-agenthq-mvp: the default branch (main) is the base.
        val byDefaultBranch = service.branchChanges(project(dir), branch = "feature", base = null)
        assertTrue(byDefaultBranch.available, byDefaultBranch.reason)
        assertEquals("main", byDefaultBranch.base)

        // feature-agenthq-mvp exists: it wins over the default branch.
        dir.git("branch", "feature-agenthq-mvp", "main")
        assertEquals("feature-agenthq-mvp", service.branchChanges(project(dir), "feature", null).base)

        // The branch records its own base in expected-bases.properties (on the branch): that wins.
        val init = dir.git("rev-parse", "main~1")
        dir.git("checkout", "-q", "feature")
        dir.write(ProjectGitService.EXPECTED_BASES_PATH, "# bases\nfeature=$init\nother=deadbeef\n")
        dir.commitAll("declare base")
        dir.git("checkout", "-q", "main")
        val recorded = service.branchChanges(project(dir), "feature", null)
        assertEquals(init, recorded.base)
        assertEquals(init, recorded.mergeBase)
    }

    @Test
    fun branchDiffIsTheBranchsPatchForOnePathNotTheWorkingTree() {
        val dir = twoBranchRepo()
        dir.write("README.md", "hello\nworking tree edit\n")

        val patch = service.branchDiff(project(dir), branch = "feature", base = "main", path = "README.md")

        assertTrue(patch.available, patch.reason)
        assertEquals("feature", patch.branch)
        assertTrue(patch.patch.contains("+from feature"), patch.patch)
        assertFalse(patch.patch.contains("working tree edit"), patch.patch)
        assertFalse(patch.staged)
    }

    @Test
    fun branchListNamesLocalBranchesTheCurrentOneAndWorktrees() {
        val dir = twoBranchRepo()
        val wt = File(System.getProperty("java.io.tmpdir"), "infantry-bv1-wt-${UUID.randomUUID()}").also { made += it }
        dir.git("worktree", "add", "-q", "-b", "wt-branch", wt.absolutePath, "main")

        val list = service.branches(project(dir))

        assertTrue(list.available, list.reason)
        assertEquals("main", list.current)
        assertEquals(setOf("main", "feature", "wt-branch"), list.branches.map { it.name }.toSet())
        assertEquals(dir.git("rev-parse", "feature"), list.branches.single { it.name == "feature" }.head)
        val w = list.branches.single { it.name == "wt-branch" }
        assertTrue(w.isWorktree)
        assertEquals(wt.canonicalFile, File(w.worktreePath!!).canonicalFile)
        assertFalse(list.branches.single { it.name == "feature" }.isWorktree)
        assertFalse(list.truncated)
    }

    @Test
    fun maliciousBranchNamesAreRefusedBeforeGitSeesThem() {
        val dir = twoBranchRepo()
        val seen = mutableListOf<List<String>>()
        val spying = ProjectGitService({ cwd, args -> seen += args; git.run(cwd, args) })
        for (bad in listOf("--upload-pack=x", "../x", "-x", "feat\u0000ure", "refs/../x", "HEAD@{1}", "")) {
            assertFailsWith<IllegalArgumentException>("'$bad' must be refused") {
                spying.branchChanges(project(dir), branch = bad, base = "main")
            }
            assertFailsWith<IllegalArgumentException>("'$bad' as a base must be refused") {
                spying.branchChanges(project(dir), branch = "feature", base = bad)
            }
        }
        assertTrue(seen.none { args -> args.any { it.contains("upload-pack") || it.contains("../x") } }, seen.toString())
        // A well-formed name that is not an existing local branch is refused too.
        assertFailsWith<IllegalArgumentException> { service.branchChanges(project(dir), "no-such-branch", "main") }
        assertFailsWith<IllegalArgumentException> { service.branchDiff(project(dir), "feature", "main", "../secret.txt") }
    }

    @Test
    fun anUnreadableProjectIsUnavailableNeverClean() {
        val gone = Project(id = "p1", name = "P", directory = File(System.getProperty("java.io.tmpdir"), "nope-${UUID.randomUUID()}").path)
        val list = service.branches(gone)
        assertFalse(list.available)
        assertTrue(list.reason.isNotBlank())
        val changes = service.branchChanges(gone, "feature", null)
        assertFalse(changes.available)
        assertTrue(changes.reason.isNotBlank())
    }

    @Test
    fun unrelatedHistoriesAnswerUnavailableNotAnEmptyDiff() {
        val dir = twoBranchRepo()
        dir.git("checkout", "-q", "--orphan", "island")
        dir.git("rm", "-rq", "--cached", ".")
        dir.write("island.txt", "alone\n")
        dir.git("add", "island.txt")
        dir.git("commit", "-q", "-m", "island")
        dir.git("checkout", "-q", "-f", "main")

        val answer = service.branchChanges(project(dir), "island", "main")
        assertFalse(answer.available, "no merge-base is unknown, not clean")
        assertTrue(answer.reason.contains("merge-base", ignoreCase = true), answer.reason)
        assertTrue(answer.changes.isEmpty())
    }
}
