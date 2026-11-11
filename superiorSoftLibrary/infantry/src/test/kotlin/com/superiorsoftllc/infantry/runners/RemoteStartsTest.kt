// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerEvent
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartWorktree
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RUNNER-POOL-6 (owner decision): a remote runner clones the project's remote, cuts the ticket worktree and pushes only
 * branches under `ticket/`; a start naming another branch is refused, and a session on another branch is never pushed.
 */
class RemoteStartsTest {

    private val dir: File = Files.createTempDirectory("infantry-remote-starts").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun git(cwd: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false") + args)
            .directory(cwd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, "git ${args.joinToString(" ")} in $cwd: $out")
        return out.trim()
    }

    /** A bare origin with one commit on main. */
    private fun origin(): File {
        val origin = File(dir, "origin.git").also { it.mkdirs() }
        git(origin, "init", "--bare", "-b", "main")
        val seed = File(dir, "seed")
        git(dir, "clone", origin.absolutePath, seed.absolutePath)
        git(seed, "checkout", "-b", "main")
        File(seed, "README.md").writeText("hello\n")
        git(seed, "add", "README.md")
        git(seed, "commit", "-m", "init")
        git(seed, "push", "origin", "main")
        return origin
    }

    private fun starts() = RemoteStarts(cloneRoot = { File(dir, RemoteStarts.DEFAULT_FOLDER) }, outboxMax = { 10 }, log = {})

    @Test
    fun `a session started over the link without a ticket reports its status and turn end`() {
        // RUNNER-SESSION-GHOST-1: the app's New agent start has no ticket worktree; its status and turn end still reach the
        // orchestrator (before, only ticket starts did, and the app showed the session STARTING for ever). An unknown one reports nothing.
        val starts = starts()
        starts.linked("app-s1")
        starts.onStatus("app-s1", "BUSY")
        starts.onTurnEnd("app-s1", 0)
        starts.onStatus("stranger", "BUSY")
        starts.onTurnEnd("stranger", 0)
        assertEquals(listOf(RunnerEvent.STATUS to "BUSY", RunnerEvent.FINISHED to "0"), starts.events(0L).map { it.kind to it.payload })
        assertTrue(starts.events(0L).all { it.sessionId == "app-s1" })
    }

    @Test
    fun `only ticket branches count as pushable`() {
        assertTrue(RemoteStarts.isTicketBranch("ticket/4659b768-remote-work"))
        for (b in listOf("main", "dev", "feature-agenthq-mvp", "ticket/", "ticket/../main", "ticket/a:refs/heads/main", "refs/heads/main", "ticket/a b")) {
            assertFalse(RemoteStarts.isTicketBranch(b), b)
        }
    }

    @Test
    fun `a start that names a branch outside ticket slash is refused before any clone`() {
        val origin = origin()
        val refused = assertFailsWith<IllegalArgumentException> {
            starts().checkout(origin.absolutePath, "p1", "ticket-4659b768", "Remote work", "main", branch = "main")
        }
        assertTrue(refused.message.orEmpty().contains("ticket/"), refused.message)
        assertFalse(File(dir, RemoteStarts.DEFAULT_FOLDER).exists(), "nothing was cloned")
    }

    @Test
    fun `a ticket start is cut from the remote and its branch pushed, another branch never is`() {
        val origin = origin()
        val starts = starts()
        val wt = starts.checkout(origin.absolutePath, "p1", "ticket-4659b768", "Remote work", "main", branch = null)
        assertTrue(wt.branch.startsWith("ticket/"), wt.branch)
        File(wt.path, "work.txt").writeText("done\n")
        git(File(wt.path), "add", "work.txt")
        git(File(wt.path), "commit", "-m", "work")
        starts.started("s1", wt)
        starts.onTurnEnd("s1", 0)
        assertEquals(git(File(wt.path), "rev-parse", "HEAD"), git(origin, "rev-parse", "refs/heads/${wt.branch}"))

        // A session whose worktree is on main (never cut by checkout, which refuses it) still never pushes main.
        val mainBefore = git(origin, "rev-parse", "refs/heads/main")
        git(File(wt.path), "checkout", "-b", "main-copy")
        File(wt.path, "more.txt").writeText("more\n")
        git(File(wt.path), "add", "more.txt")
        git(File(wt.path), "commit", "-m", "more")
        git(File(wt.path), "update-ref", "refs/heads/main", "HEAD") // the clone's main is ahead: a push of it would move origin
        starts.started("s2", StartWorktree(wt.path, "main", wt.base, wt.repoRoot))
        starts.onTurnEnd("s2", 0)
        assertEquals(mainBefore, git(origin, "rev-parse", "refs/heads/main"), "main is untouched")
        assertEquals(listOf(RunnerEvent.FINISHED, RunnerEvent.FINISHED), starts.events(0).map { it.kind })
    }
}
