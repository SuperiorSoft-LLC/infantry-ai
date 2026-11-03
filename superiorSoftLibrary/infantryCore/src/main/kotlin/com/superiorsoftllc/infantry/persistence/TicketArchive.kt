// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.persistence

import com.superiorsoftllc.infantry.db.InfantryDatabaseHandle
import com.superiorsoftllc.infantry.db.Archived_tickets

/** ARCHIVE-1: one archived ticket with everything it had: its row, its links, and its epic's and project's snapshot (JSON text). */
data class ArchivedTicket(
    val row: TicketRow,
    val archivedAt: Long,
    val links: List<TicketLinkRow>,
    val epicJson: String?,
    val projectJson: String?,
)

/** The slim view the related-work resolver reads (no scan of full rows). */
data class ArchivedInfo(val id: String, val title: String, val description: String, val status: String, val projectId: String?, val branch: String?, val base: String?)

/**
 * ARCHIVE-1: the archive store. An archived ticket leaves `tickets` / `ticket_links` (so a board read never scans it) and lives in
 * `archived_tickets` / `archived_ticket_links` with every column. `ticket_files` is untouched, which keeps the related-ticket file lists complete.
 */
interface TicketArchiveRepository {
    /** Moves a ticket (and its links) into the archive; false when no live ticket has that id. Renumbers the column it left. */
    fun archive(id: String, epicJson: String?, projectJson: String?, now: Long, renumber: Boolean = true): Boolean
    /** Closes the gaps left in a lane by archives made with `renumber = false`. */
    fun renumber(status: String)
    /** Puts it back exactly as it was, at its original lane position, with the links whose other end is live; false when it is not archived. */
    fun restore(id: String): Boolean
    fun get(id: String): ArchivedTicket?
    fun list(projectId: String?, epicId: String?, limit: Int, offset: Int): List<ArchivedTicket>
    /** Case-insensitive substring match on title, description and id. */
    fun search(projectId: String?, text: String, limit: Int): List<ArchivedTicket>
    fun count(projectId: String?): Int
    fun info(id: String): ArchivedInfo?
    fun infos(projectId: String?): List<ArchivedInfo>
}

internal class SqlTicketArchive(private val handle: InfantryDatabaseHandle, private val store: InfantryStore) : TicketArchiveRepository {
    private val q get() = handle.database.archiveQueries

    override fun archive(id: String, epicJson: String?, projectJson: String?, now: Long, renumber: Boolean): Boolean = store.writeTransaction {
        val live = handle.database.ticketsQueries.getTicketById(id).executeAsOneOrNull() ?: return@writeTransaction false
        q.archive(archived_at = now, epic_json = epicJson, project_json = projectJson, id = id)
        q.archiveLinks(id)
        handle.database.ticketsQueries.deleteTicket(id)
        if (renumber) q.renumberStatus(live.status)
        true
    }

    override fun renumber(status: String) { store.writeTransaction { q.renumberStatus(status) } }

    override fun restore(id: String): Boolean = store.writeTransaction {
        if (q.getArchived(id).executeAsOneOrNull() == null) return@writeTransaction false
        q.makeRoom(id)
        q.restore(id)
        q.restoreLinks(id)
        q.deleteArchivedLinksOf(id)
        q.removeArchived(id)
        true
    }

    override fun get(id: String) = q.getArchived(id).executeAsOneOrNull()?.let(::withLinks)

    override fun list(projectId: String?, epicId: String?, limit: Int, offset: Int) =
        q.listArchived(projectId, epicId, limit.toLong(), offset.toLong()).executeAsList().map(::withLinks)

    override fun search(projectId: String?, text: String, limit: Int): List<ArchivedTicket> {
        val like = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return q.searchArchived(projectId, like, limit.toLong()).executeAsList().map(::withLinks)
    }

    override fun count(projectId: String?) = q.countArchived(projectId).executeAsOne().toInt()
    override fun info(id: String) = q.archivedInfo(id).executeAsOneOrNull()?.let { ArchivedInfo(it.id, it.title, it.description, it.status, it.project_id, it.branch_name, it.base_commit) }
    override fun infos(projectId: String?) = q.allArchivedInfo(projectId).executeAsList().map { ArchivedInfo(it.id, it.title, it.description, it.status, it.project_id, it.branch_name, it.base_commit) }

    private fun withLinks(a: Archived_tickets) = ArchivedTicket(
        row = a.toRow(), archivedAt = a.archived_at, epicJson = a.epic_json, projectJson = a.project_json,
        links = q.archivedLinks(a.id).executeAsList().map { TicketLinkRow(it.ticket_id, it.related_id, it.type, it.created_at) },
    )
}

private fun Archived_tickets.toRow() = TicketRow(
    id = id, title = title, description = description, ticketType = ticket_type, status = status, position = position.toInt(),
    wontFix = wont_fix != 0L, createdAt = created_at, updatedAt = updated_at, agentId = agent_id, provider = provider, model = model,
    useWorktree = use_worktree?.let { it != 0L }, worktreeBranch = worktree_branch,
    includeLinkedTasksContext = include_linked_tasks_context?.let { it != 0L }, queuedFor = queued_for, queuedAt = queued_at,
    sessionId = session_id, projectId = project_id, epicId = epic_id, branch = branch_name, base = base_commit,
    skillIds = kotlinx.serialization.json.Json.decodeFromString(TRAIT_IDS_JSON, skill_ids), skillsMode = skills_mode,
    includeSkillsContext = include_skills_context?.let { it != 0L }, startedBy = started_by,
    images = kotlinx.serialization.json.Json.decodeFromString(TRAIT_IDS_JSON, images),
    weight = weight?.toInt(), contextLimit = context_limit?.toInt(), effort = effort,
)
