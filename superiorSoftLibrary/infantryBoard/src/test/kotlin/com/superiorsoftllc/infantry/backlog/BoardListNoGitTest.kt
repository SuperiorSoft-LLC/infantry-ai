// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.daemon.api.board.BoardGit
import com.superiorsoftllc.infantry.daemon.api.board.BoardHost
import com.superiorsoftllc.infantry.daemon.api.board.BranchHandoffFacts
import com.superiorsoftllc.infantry.protocol.TicketSave
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * BUG PLUGIN-SOURCE-REFRESH-FREEZE-2 (owner's jstack, packaged app): every event stamp and plugin tool call lists the board, and
 * list() ran git for every todo ticket with a branch, under the board lock and a SQL transaction: thousands of git processes in two
 * minutes froze the whole app. list() must run no git itself; the "has work" facts are checked in the background, once per ttl.
 */
class BoardListNoGitTest {
    private val repo: File = Files.createTempDirectory("infantry-haswork").toFile().also { File(it, ".git").mkdirs() }

    @AfterTest
    fun cleanUp() { repo.deleteRecursively() }

    private class GitCountingHost(val inner: InMemoryBoardHost, repo: File) : BoardHost by inner {
        val callers = CopyOnWriteArrayList<String>()
        override val git: BoardGit = object : BoardGit by inner.git {
            override fun defaultRepo(): String = repo.path
            override fun handoffFacts(repo: String, branch: String, base: String, worktree: String?, maxCommits: Int): BranchHandoffFacts {
                callers += Thread.currentThread().name
                return BranchHandoffFacts(tip = "abc1234", ahead = 2, subjects = emptyList(), dirtyFiles = null)
            }
        }
    }

    private fun board(ttl: Long): Pair<TicketBoard, GitCountingHost> {
        val host = GitCountingHost(InMemoryBoardHost(), repo)
        val ids = generateSequence(1) { it + 1 }.map { "t$it" }.iterator()
        val board = TicketBoard(host, idFactory = { ids.next() }, hasWorkTtlMs = { ttl })
        repeat(30) { board.save(TicketSave(title = "work $it")) }
        // A todo ticket that ran before keeps its recorded branch and base (an unassign): the case that runs git.
        val table = host.inner.storage.tables.tickets
        table.all().forEach { table.update(it.copy(branch = "ticket/${it.id}", base = "base-${it.id}")) }
        return board to host
    }

    private fun await(what: String, check: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < until) { if (check()) return; Thread.sleep(20) }
        fail("timed out waiting for $what")
    }

    @Test
    fun listRunsNoGitOnTheCallersThreadAndChecksEachBranchOnceInTheBackground() {
        val (board, host) = board(ttl = 60_000)
        val me = Thread.currentThread().name
        val first = board.list()
        assertEquals(30, first.size)
        assertTrue(host.callers.none { it == me }, "list() ran git on the caller's thread: ${host.callers}")
        await("the background checks") { host.callers.size == 30 }
        assertTrue(host.callers.all { it == "board-has-work" }, host.callers.toString())
        await("the chips") { board.list().all { it.hasWork?.ahead == 2 } }
        repeat(5) { board.list() }
        Thread.sleep(200)
        assertEquals(30, host.callers.size, "lists within the ttl run no further git")
    }

    @Test
    fun ttlZeroKeepsTheOldCheckOnEveryList() {
        val (board, host) = board(ttl = 0)
        assertTrue(board.list().all { it.hasWork?.tip == "abc1234" })
        assertEquals(30, host.callers.size)
    }
}
