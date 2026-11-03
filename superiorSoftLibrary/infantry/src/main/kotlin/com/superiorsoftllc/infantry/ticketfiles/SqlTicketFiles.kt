package com.superiorsoftllc.infantry.ticketfiles

import com.superiorsoftllc.infantry.db.InfantryDatabase
import com.superiorsoftllc.infantry.worktree.GitCli
import com.superiorsoftllc.infantry.worktree.SystemGitCli
import java.io.File

/** [TicketFileRows] over the daemon's SQLite `ticket_files` table (DP-5e: the SQL stays in core). */
class SqlTicketFileRows(db: InfantryDatabase) : TicketFileRows {
    private val q = db.ticket_filesQueries

    private fun file(it: com.superiorsoftllc.infantry.db.Ticket_files) =
        TicketFile(it.ticket_id, it.path, it.status, it.old_path, it.added, it.removed, it.tip_sha, it.merge_sha, it.recorded_at)

    override fun filesForTicket(ticketId: String) = q.filesForTicket(ticketId).executeAsList().map(::file)
    override fun filesForPathGlob(glob: String) = q.filesForPathGlob(glob).executeAsList().map(::file)
    override fun relatedTickets(ticketId: String) =
        q.relatedTickets(ticketId).executeAsList().map { RelatedCount(it.ticket_id, it.shared.toInt(), it.total.toInt()) }
    override fun recordedTickets(): Set<String> = q.recordedTickets().executeAsList().toSet()
    override fun pathFrequency(path: String): Int = q.pathFrequency(path).executeAsOne().toInt()
    override fun saveFilesRead(ticketId: String, line: String, at: Long) { q.saveFilesRead(ticketId, line, at) }
    override fun filesReadFor(ticketId: String): String? = q.filesReadFor(ticketId).executeAsOneOrNull()
    override fun replaceFiles(ticketId: String, rows: List<TicketFile>) {
        q.transaction {
            q.deleteForTicket(ticketId)
            for (r in rows) q.insertFile(r.ticketId, r.path, r.status, r.oldPath, r.added, r.removed, r.tipSha, r.mergeSha, r.recordedAt)
        }
    }
}

/** The daemon's [TicketFiles]: SQLite rows, the OS git. (Same name as the class, so every caller that passes a database is unchanged.) */
fun TicketFiles(
    db: InfantryDatabase,
    tickets: () -> List<TicketInfo>,
    repoFor: (projectId: String?) -> File?,
    git: GitCli = SystemGitCli(timeoutMs = 120_000),
    clock: () -> Long = System::currentTimeMillis,
): TicketFiles = TicketFiles(SqlTicketFileRows(db), tickets, repoFor, git, clock)
