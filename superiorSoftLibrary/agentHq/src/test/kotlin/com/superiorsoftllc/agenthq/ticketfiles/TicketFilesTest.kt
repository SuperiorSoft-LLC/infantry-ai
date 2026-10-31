package com.superiorsoftllc.agenthq.ticketfiles

import com.superiorsoftllc.agenthq.db.AgentHQDatabaseFactory
import com.superiorsoftllc.agenthq.backlog.toInfo
import com.superiorsoftllc.agenthq.persistence.TicketRow
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.worktree.newGitRepo
import com.superiorsoftllc.agenthq.worktree.testGit
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TICKET-FILES normal path over a real git repo: two tickets merged as `Merge ticket/<id> ...` (their branches then
 * deleted) are backfilled from the merges, pinned under refs/agenthq/tickets, and found by id, by path and as related.
 */
class TicketFilesTest {

    @Test
    fun backfillsMergedTicketsAndAnswersByIdPathAndRelated() {
        val repo = newGitRepo("agenthq-ticket-files")
        val git = testGit()
        fun g(vararg args: String) = git.run(repo, args.toList()).also { assertTrue(it.ok, "git ${args.joinToString(" ")}: ${it.err()}") }
        val main = g("rev-parse", "--abbrev-ref", "HEAD").out()

        fun ticketBranch(short: String, files: Map<String, String>) {
            g("checkout", "-q", "-b", "ticket/$short-x", main)
            files.forEach { (path, text) -> File(repo, path).apply { parentFile.mkdirs() }.writeText(text) }
            g("add", "-A")
            g("commit", "-q", "-m", "work $short")
            g("checkout", "-q", main)
            g("merge", "-q", "--no-ff", "-m", "Merge ticket/$short (work) into $main", "ticket/$short-x")
            g("branch", "-q", "-D", "ticket/$short-x")
        }
        ticketBranch("aaaa1111", mapOf("src/Board.kt" to "a\nb\n", "src/Only.kt" to "x\n"))
        ticketBranch("bbbb2222", mapOf("src/Board.kt" to "a\nb\nc\n"))

        val db = AgentHQDatabaseFactory.open(File(repo.parentFile, "${repo.name}.db").absolutePath).database
        val rows = listOf("aaaa1111", "bbbb2222", "cccc3333").map {
            TicketRow(id = "ticket-$it", title = "T $it", status = TicketStatus.DONE, position = 0, createdAt = 0, updatedAt = 0).toInfo()
        }
        val files = TicketFiles(db, { rows }, { repo }, git)

        val report = files.backfill()
        assertEquals(2, report.recovered[FileSource.MERGE])
        assertEquals(listOf("ticket-cccc3333"), report.notRecovered)

        assertEquals(listOf("src/Board.kt" to "A", "src/Only.kt" to "A"), files.forTicket("ticket-aaaa1111").map { it.path to it.status })
        assertEquals(2L, files.forTicket("ticket-aaaa1111").first().added)
        assertEquals(setOf("ticket-aaaa1111", "ticket-bbbb2222"), files.forPaths(listOf("Board.kt")).map { it.ticketId }.toSet())
        val related = files.related("ticket-bbbb2222").single()
        assertEquals("ticket-aaaa1111", related.ticketId)
        assertEquals(0.5, related.jaccard)
        assertTrue(g("rev-parse", "--verify", "refs/agenthq/tickets/ticket-aaaa1111").ok, "the tip is pinned")

        val text = TicketFilesText(files) { id -> rows.firstOrNull { it.id == id }?.title }
            .answer(TicketFilesQuery(ticketId = "ticket-bbbb2222", related = true), maxLines = 1, top = 5)
        assertTrue(text.contains("ticket-aaaa1111") && text.contains("1 more lines"), text)
    }
}
