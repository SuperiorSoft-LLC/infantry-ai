// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.cli.BoardSummary
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** RUNNER-TICKET-ROW-1: a ticket run on a runner keeps its pinned model, checkout and branch, so the board row shows a commit tip. */
class RemoteTicketRowTest {
    private fun git(dir: File, vararg a: String) = ProcessBuilder(listOf("git", "-C", dir.path) + a).redirectErrorStream(true).start().let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }

    @Test
    fun `mirror record carries model, checkout and branch and the fetched branch gives a commit tip`() {
        val root = kotlin.io.path.createTempDirectory("rtr").toFile()
        val origin = File(root, "origin.git").also { it.mkdirs() }.also { git(it, "init", "--bare", "-b", "main") }
        val work = File(root, "work").also { it.mkdirs() }
        git(work, "init", "-b", "main"); git(work, "config", "user.email", "a@b.c"); git(work, "config", "user.name", "t")
        File(work, "a.txt").writeText("a"); git(work, "add", "."); git(work, "commit", "-m", "init")
        git(work, "remote", "add", "origin", origin.path); git(work, "push", "origin", "main")
        // the "runner" pushes a ticket branch to origin
        val branch = "ticket/321c36a4-hello-file"
        git(work, "checkout", "-b", branch); File(work, "h.txt").writeText("h"); git(work, "add", "."); git(work, "commit", "-m", "hello"); git(work, "push", "origin", branch)
        git(work, "checkout", "main"); git(work, "branch", "-D", branch)

        val placements = RunnerPlacements()
        val mirror = SessionDirectory(local = { emptyList() }, localGet = { null }, placed = { listOf("s1") }, placements = placements)
        placements.placed("s1", RunnerPlacements.Placed("claude", "sonnet", work.path, branch, "t1"))
        val rec = mirror.get("s1")!!
        assertEquals("sonnet", rec.model); assertEquals(work.path, rec.repoRoot); assertEquals("t1", rec.ticketId)
        // RUNNER-DONE-1: the project roster (`agents --live --project`) filters on these; a remote worker read "No agents here".
        placements.placed("s1", RunnerPlacements.Placed("claude", "sonnet", work.path, branch, "t1", projectId = "p1", agentId = "a1", role = "backend"))
        mirror.get("s1")!!.let { assertEquals("p1", it.projectId); assertEquals("a1", it.agentId); assertEquals("backend", it.role) }
        val agents = com.superiorsoftllc.infantry.daemon.projectAgents("p1", mirror.list()) { null }
        assertEquals(listOf("a1"), agents.map { it.agentId }, "the remote worker is one of the project's agents")

        assertEquals(null, BoardSummary.gitCommit(branch, rec.repoRoot), "before the fetch: no commit")
        assertTrue(com.superiorsoftllc.infantry.daemon.plugins.fetchRunnerTicketBranch(work, branch))
        assertNotNull(BoardSummary.gitCommit(branch, rec.repoRoot), "after the fetch: a commit tip")
    }
}
