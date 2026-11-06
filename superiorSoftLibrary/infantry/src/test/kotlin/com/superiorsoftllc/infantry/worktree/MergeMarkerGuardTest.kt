// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.worktree

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * MERGE-MARKERS-1: merge b18a2c65 put conflict markers into dev. The repo's git hooks (`scripts/git-hooks`,
 * enabled by `core.hooksPath`) refuse any merge commit whose staged tree has unresolved conflicts or marker lines.
 * Each case runs real git against a scratch repo with two branches editing the same line. Before the fix (no hooks)
 * the first two cases DO create a commit with markers; [withoutHooksTheBadMergeLands] keeps that repro.
 */
class MergeMarkerGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "AGENTS.md").isFile }
    private val hooks = File(root, "scripts/git-hooks")
    private val hooksPath = hooks.absolutePath.replace(File.separatorChar, '/')
    private val repo: File = Files.createTempDirectory("merge-marker-guard").toFile()

    @AfterTest fun cleanup() { repo.deleteRecursively() }

    private data class R(val code: Int, val out: String)

    private fun git(vararg args: String): R {
        val pb = ProcessBuilder(listOf("git", "-c", "commit.gpgsign=false", "-c", "core.autocrlf=false") + args)
            .directory(repo).redirectErrorStream(true)
        pb.environment().keys.removeIf { it.startsWith("GIT_") }
        pb.environment()["GIT_AUTHOR_NAME"] = "t"; pb.environment()["GIT_AUTHOR_EMAIL"] = "t@t"
        pb.environment()["GIT_COMMITTER_NAME"] = "t"; pb.environment()["GIT_COMMITTER_EMAIL"] = "t@t"
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        return R(p.waitFor(), out)
    }

    private fun ok(vararg args: String): String = git(*args).also { assertEquals(0, it.code, "git ${args.joinToString(" ")}: ${it.out}") }.out

    private fun head() = ok("rev-parse", "HEAD").trim()

    /** main and topic both change line 2 of doc.md; returns with main checked out. */
    private fun conflictingBranches(useHooks: Boolean) {
        ok("init", "-q", "-b", "main")
        if (useHooks) ok("config", "core.hooksPath", hooksPath)
        File(repo, "doc.md").writeText("title\nversion: 1\nend\n")
        ok("add", "-A"); ok("commit", "-q", "-m", "base")
        ok("checkout", "-q", "-b", "topic")
        File(repo, "doc.md").writeText("title\nversion: 1 topic\nend\n")
        ok("commit", "-q", "-am", "topic")
        ok("checkout", "-q", "main")
        File(repo, "doc.md").writeText("title\nversion: 2\nend\n")
        ok("commit", "-q", "-am", "main")
    }

    private fun noCommitHasMarkers() {
        val all = ok("rev-list", "--all").lines().filter { it.isNotBlank() }
        for (c in all) {
            val grep = git("grep", "-n", "-E", "^(<<<<<<<|>>>>>>>)( |$)", c, "--", ".")
            assertNotEquals(0, grep.code, "commit $c holds conflict markers: ${grep.out}")
        }
    }

    @Test
    fun withoutHooksTheBadMergeLands() {
        conflictingBranches(useHooks = false)
        assertEquals(1, git("merge", "--no-ff", "topic").code)
        ok("add", "-A")
        ok("commit", "-q", "--no-edit")
        assertTrue(ok("show", "HEAD:doc.md").contains("<<<<<<<"), "repro: the hand-concluded merge committed markers")
    }

    @Test
    fun handConcludedMergeWithMarkersIsRefused() {
        conflictingBranches(useHooks = true)
        val before = head()
        assertEquals(1, git("merge", "--no-ff", "topic").code)
        ok("add", "-A") // the b18a2c65 path: conflicted file staged as is
        val commit = git("commit", "-q", "--no-edit")
        assertNotEquals(0, commit.code, commit.out)
        assertTrue(commit.out.contains("merge-marker-guard"), commit.out)
        assertEquals(before, head())
        ok("merge", "--abort")
        noCommitHasMarkers()
    }

    @Test
    fun cleanMergeOfABranchCarryingMarkersIsRefused() {
        ok("init", "-q", "-b", "main")
        File(repo, "doc.md").writeText("a\n")
        ok("add", "-A"); ok("commit", "-q", "-m", "base")
        ok("checkout", "-q", "-b", "topic")
        File(repo, "doc.md").writeText("a\n<<<<<<< HEAD\nx\n=======\ny\n>>>>>>> other\n")
        ok("commit", "-q", "-am", "topic with markers") // committed before the hooks are on
        ok("checkout", "-q", "main")
        File(repo, "other.md").writeText("b\n")
        ok("add", "-A"); ok("commit", "-q", "-m", "main")
        ok("config", "core.hooksPath", hooksPath)
        val before = head()
        val merge = git("merge", "--no-ff", "-m", "merge topic", "topic")
        assertNotEquals(0, merge.code, merge.out)
        assertTrue(merge.out.contains("doc.md"), merge.out)
        assertEquals(before, head())
        ok("merge", "--abort")
    }

    @Test
    fun resolvedMergeIsAccepted() {
        conflictingBranches(useHooks = true)
        assertEquals(1, git("merge", "--no-ff", "topic").code)
        File(repo, "doc.md").writeText("title\nversion: 2 topic\nend\n")
        ok("add", "-A")
        ok("commit", "-q", "--no-edit")
        assertEquals(2, ok("show", "-s", "--format=%P", "HEAD").trim().split(" ").size)
        assertFalse(File(repo, ".git/MERGE_HEAD").exists())
        noCommitHasMarkers()
    }

    @Test
    fun plainCommitIsNotChecked() {
        ok("init", "-q", "-b", "main")
        ok("config", "core.hooksPath", hooksPath)
        File(repo, "fixture.txt").writeText("<<<<<<< HEAD\nkept on purpose\n")
        ok("add", "-A"); ok("commit", "-q", "-m", "fixture")
        File(repo, "fixture.txt").appendText(">>>>>>> theirs\n")
        ok("commit", "-q", "-am", "plain commit with a marker line")
    }
}
