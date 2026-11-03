package com.superiorsoftllc.infantry.worktree

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GIT VIEW gate, against REAL git: the worktree status carries the changed files of the SESSION'S OWN
 * worktree, each with its true status and its staged/unstaged side.
 *
 * The two shapes that matter most are the silent-wrong-answer ones: a clean worktree must say "empty"
 * (not "unknown"), and a file must never be reported as modified when git says something else.
 */
class WorktreeChangesTest {

    private val git = testGit()

    private fun cut(): Pair<File, WorktreeRecord> {
        val repo = newGitRepo()
        // Two tracked files beyond README so we can modify one and delete another.
        File(repo, "keep.txt").writeText("keep\n")
        File(repo, "gone.txt").writeText("gone\n")
        File(repo, "old name.txt").writeText("renamed content that is long enough to be detected as a rename\n")
        assertTrue(git.run(repo, listOf("add", "keep.txt", "gone.txt", "old name.txt")).ok)
        assertTrue(git.run(repo, listOf("commit", "-q", "-m", "more files")).ok)
        return repo to GitWorktreeManager(git).create("sess-changes-0001", repo, "changes")
    }

    private fun changesOf(record: WorktreeRecord, manager: GitWorktreeManager = GitWorktreeManager(git)) =
        manager.status(record).changes

    @Test
    fun aKnownModificationAndAKnownUntrackedFile_areBothReportedWithTheirOwnStatus() {
        val (_, wt) = cut()
        File(wt.path, "keep.txt").writeText("keep\nchanged\n")
        File(wt.path, "brand-new.txt").writeText("new\n")

        val changes = assertNotNull(changesOf(wt))
        assertEquals(
            setOf(
                ChangedFile("keep.txt", ChangeKind.MODIFIED, staged = false),
                ChangedFile("brand-new.txt", ChangeKind.UNTRACKED, staged = false),
            ),
            changes.toSet(),
        )
        assertEquals(2, changes.size, "no duplicates, nothing extra")
    }

    @Test
    fun aCleanWorktree_reportsEmpty_notUnknown() {
        val (_, wt) = cut()
        val changes = changesOf(wt)
        assertNotNull(changes, "a clean worktree is KNOWN clean; null would mean 'could not read'")
        assertTrue(changes.isEmpty())
        assertFalse(GitWorktreeManager(git).status(wt).dirty)
    }

    @Test
    fun everyStatusIsReportedAsWhatGitSays_notCollapsedToModified() {
        val (_, wt) = cut()
        File(wt.path, "staged-new.txt").writeText("n\n")
        assertTrue(git.run(File(wt.path), listOf("add", "staged-new.txt")).ok)       // staged ADDED
        assertTrue(git.run(File(wt.path), listOf("rm", "-q", "gone.txt")).ok)       // staged DELETED
        File(wt.path, "keep.txt").delete()                                          // unstaged DELETED
        File(wt.path, "README.md").writeText("hello\nmore\n")
        assertTrue(git.run(File(wt.path), listOf("add", "README.md")).ok)           // staged MODIFIED
        File(wt.path, "loose.txt").writeText("l\n")                                 // UNTRACKED

        val byPath = assertNotNull(changesOf(wt)).associateBy { it.path }
        assertEquals(ChangedFile("staged-new.txt", ChangeKind.ADDED, staged = true), byPath["staged-new.txt"])
        assertEquals(ChangedFile("gone.txt", ChangeKind.DELETED, staged = true), byPath["gone.txt"])
        assertEquals(ChangedFile("keep.txt", ChangeKind.DELETED, staged = false), byPath["keep.txt"])
        assertEquals(ChangedFile("README.md", ChangeKind.MODIFIED, staged = true), byPath["README.md"])
        assertEquals(ChangedFile("loose.txt", ChangeKind.UNTRACKED, staged = false), byPath["loose.txt"])
        assertEquals(5, byPath.size)
    }

    @Test
    fun aFileChangedOnBothSides_appearsOncePerSide() {
        val (_, wt) = cut()
        File(wt.path, "keep.txt").writeText("keep\nstaged edit\n")
        assertTrue(git.run(File(wt.path), listOf("add", "keep.txt")).ok)
        File(wt.path, "keep.txt").writeText("keep\nstaged edit\nworking edit\n")

        val mine = assertNotNull(changesOf(wt)).filter { it.path == "keep.txt" }
        assertEquals(
            setOf(
                ChangedFile("keep.txt", ChangeKind.MODIFIED, staged = true),
                ChangedFile("keep.txt", ChangeKind.MODIFIED, staged = false),
            ),
            mine.toSet(),
        )
        assertEquals(2, mine.size)
    }

    @Test
    fun aRename_carriesItsOldPath_andAPathWithSpacesSurvives() {
        val (_, wt) = cut()
        assertTrue(git.run(File(wt.path), listOf("mv", "old name.txt", "new name.txt")).ok)

        val renamed = assertNotNull(changesOf(wt)).single()
        assertEquals(ChangedFile("new name.txt", ChangeKind.RENAMED, staged = true, fromPath = "old name.txt"), renamed)
    }

    @Test
    fun untrackedFilesInsideANewDirectory_areListedIndividually() {
        val (_, wt) = cut()
        File(wt.path, "newdir/sub").mkdirs()
        File(wt.path, "newdir/a.txt").writeText("a")
        File(wt.path, "newdir/sub/b.txt").writeText("b")

        val paths = assertNotNull(changesOf(wt)).map { it.path to it.status }.toSet()
        assertEquals(setOf("newdir/a.txt" to ChangeKind.UNTRACKED, "newdir/sub/b.txt" to ChangeKind.UNTRACKED), paths)
    }

    @Test
    fun theListIsReadFromTheSessionsOwnWorktree_neverTheProjectRoot() {
        val (repo, wt) = cut()
        // Dirty ONLY the project root. The session worktree stays clean.
        File(repo, "keep.txt").writeText("root edit\n")
        File(repo, "root-only.txt").writeText("r\n")

        val changes = assertNotNull(changesOf(wt))
        assertTrue(changes.isEmpty(), "the project root's changes must not leak into the session view: $changes")

        // And the reverse: a change in the worktree is invisible to the root.
        File(wt.path, "wt-only.txt").writeText("w\n")
        assertEquals(listOf("wt-only.txt"), assertNotNull(changesOf(wt)).map { it.path })
    }

    @Test
    fun aMissingWorktreeDirectory_isUnknown_andNeverFallsBackToTheRoot() {
        val (repo, wt) = cut()
        File(repo, "root-only.txt").writeText("r\n")           // root is dirty
        File(wt.path).deleteRecursively()                      // worktree directory vanishes

        val status = GitWorktreeManager(git).status(wt)
        assertFalse(status.exists)
        assertNull(status.changes, "gone is UNKNOWN (null), not clean (empty), and never the root's files")
    }

    @Test
    fun aDirectoryWithNoGitFile_insideTheRepo_isUnknown_notTheEnclosingReposChanges() {
        val (repo, wt) = cut()
        File(repo, "root-only.txt").writeText("r\n")
        // Replace the worktree with a plain folder (no .git) that sits INSIDE the root checkout.
        File(wt.path).deleteRecursively()
        File(wt.path).mkdirs()

        assertNull(GitWorktreeManager(git).status(wt).changes)
    }

    @Test
    fun dirtyAgreesWithTheChangeList() {
        val (_, wt) = cut()
        val manager = GitWorktreeManager(git)
        assertFalse(manager.status(wt).dirty)
        File(wt.path, "x.txt").writeText("x")
        val s = manager.status(wt)
        assertTrue(s.dirty)
        assertEquals(1, s.changes?.size)
    }

    @Test
    fun aHugeChangeList_isCapped_andSaysSo() {
        val (_, wt) = cut()
        repeat(7) { File(wt.path, "f$it.txt").writeText("$it") }

        val s = GitWorktreeManager(git, maxChanges = 3).status(wt)
        assertEquals(3, s.changes?.size)
        assertTrue(s.changesTruncated, "a capped list must not look complete")
        assertFalse(GitWorktreeManager(git).status(wt).changesTruncated)
    }

    @Test
    fun theReaderIsASeam_aNonGitSourceDrivesTheSameFrame() {
        val (_, wt) = cut()
        val canned = listOf(ChangedFile("from-another-vcs.txt", ChangeKind.ADDED, staged = false))
        val swapped = GitWorktreeManager(git, changeReader = { canned })
        assertEquals(canned, swapped.status(wt).changes, "the manager must use the injected reader, not git")
        assertTrue(swapped.status(wt).dirty)

        val unreadable = GitWorktreeManager(git, changeReader = { null })
        assertNull(unreadable.status(wt).changes, "a reader that cannot read is UNKNOWN")
        assertFalse(unreadable.status(wt).dirty)
    }

    @Test
    fun theWireShape_roundTrips_andAnOldDaemonsFrameDecodesAsUnknown() {
        val json = Json { ignoreUnknownKeys = true }
        val withChanges = WorktreeStatus(
            "s", "/r", "/r/wt", "infantry/x", "abc", exists = true, dirty = true, ahead = 1, behind = 0, removed = false,
            changes = listOf(ChangedFile("a.txt", ChangeKind.RENAMED, staged = true, fromPath = "b.txt")),
        )
        val text = json.encodeToString(WorktreeStatus.serializer(), withChanges)
        assertEquals(withChanges, json.decodeFromString(WorktreeStatus.serializer(), text))

        val old = """{"sessionId":"s","repoRoot":"/r","path":"/r/wt","branch":"b","baseRef":"abc","exists":true,"dirty":false,"ahead":0,"behind":0,"removed":false}"""
        assertNull(json.decodeFromString(WorktreeStatus.serializer(), old).changes, "absent == unknown, never empty")

        val clean = withChanges.copy(changes = emptyList(), dirty = false)
        assertEquals(emptyList(), json.decodeFromString(WorktreeStatus.serializer(), json.encodeToString(WorktreeStatus.serializer(), clean)).changes)
    }
}
