package com.superiorsoftllc.agenthq.archive

import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.persistence.ArchivedTicket

/**
 * ARCHIVE-1: archive / restore / list / show / search over [AgentHqStore.ticketArchive], and the auto-archive policy.
 *
 * Generic mechanisms only: which tickets to archive is the caller's filter (ids, an epic, a lane + age); the auto policy is the setting
 * `board.archive.doneAfterDays` (a project value `board.archive.doneAfterDays.<projectId>` wins; absent or <= 0 = off).
 * The epic and the project are kept as a snapshot on each archived ticket ([epicSnapshot], [projectSnapshot]); the live epic row stays
 * (it is referenced, and is no scan cost). `ticket_files` is never touched, so related-file lists keep finding archived tickets.
 */
class ArchiveService(
    private val store: AgentHqStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** (epic name, epic JSON) of an epic id, or null. */
    private val epicSnapshot: (epicId: String) -> Pair<String, String>? = { null },
    private val projectSnapshot: (projectId: String) -> String? = { null },
    private val setting: (String) -> String? = { null },
    /** Called after tickets moved, with their ids (the wiring pushes a board refresh). */
    private val onMoved: (archived: Boolean, ids: List<String>) -> Unit = { _, _ -> },
) {
    fun query(req: ArchiveRequest): ArchiveReport {
        val archive = store.ticketArchive
        return when (req.op) {
            "list" -> {
                val limit = (req.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
                val items = archive.list(req.projectId, req.epicId, limit, (req.offset ?: 0).coerceAtLeast(0)).map { item(it, false) }
                val total = archive.count(req.projectId)
                ArchiveReport(text(items, total), items, total)
            }
            "search" -> {
                val q = req.text?.trim().orEmpty()
                require(q.isNotEmpty()) { "search needs text" }
                val items = archive.search(req.projectId, q, (req.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)).map { item(it, false) }
                ArchiveReport(text(items, items.size), items, items.size)
            }
            "show" -> {
                val id = resolveId(req.ids.singleOrNull() ?: throw IllegalArgumentException("show needs exactly one id"))
                val a = archive.get(id) ?: throw IllegalArgumentException("no archived ticket $id")
                val it = item(a, true)
                ArchiveReport("${it.id} [${it.status}] ${it.title}\narchived ${it.archivedAt}${it.branch?.let { b -> " branch $b" }.orEmpty()}\n\n${a.row.description}", listOf(it), 1)
            }
            "count" -> archive.count(req.projectId).let { ArchiveReport("$it archived", emptyList(), it) }
            else -> throw IllegalArgumentException("unknown archive query '${req.op}' (list | show | search | count)")
        }
    }

    fun apply(req: ArchiveRequest): ArchiveReport = when (req.op) {
        "archive" -> {
            val ids = pickToArchive(req)
            val moved = if (req.dryRun) ids else archiveMany(ids)
            if (!req.dryRun && moved.isNotEmpty()) onMoved(true, moved)
            ArchiveReport("${if (req.dryRun) "would archive" else "archived"} ${moved.size} ticket(s)", emptyList(), moved.size)
        }
        "restore" -> {
            val ids = when {
                req.ids.isNotEmpty() -> req.ids.map(::resolveId)
                req.epicId != null -> store.ticketArchive.list(req.projectId, req.epicId, MAX_BULK, 0).map { it.row.id }
                else -> throw IllegalArgumentException("restore needs ids or an epicId")
            }
            val moved = if (req.dryRun) ids else ids.filter { store.ticketArchive.restore(it) }
            if (!req.dryRun && moved.isNotEmpty()) onMoved(false, moved)
            ArchiveReport("${if (req.dryRun) "would restore" else "restored"} ${moved.size} ticket(s)", emptyList(), moved.size)
        }
        else -> throw IllegalArgumentException("unknown archive action '${req.op}' (archive | restore)")
    }

    /** The auto policy: archives every done ticket older than its project's `board.archive.doneAfterDays`. Returns how many moved. */
    fun autoArchive(): Int {
        val global = days(setting(KEY))
        val now = clock()
        val due = store.tickets.all().filter { it.status == DONE }.filter { t ->
            val d = (t.projectId?.let { days(setting("$KEY.$it")) } ?: 0).takeIf { it > 0 } ?: global
            d > 0 && t.updatedAt <= now - d * DAY_MS
        }.map { it.id }
        val moved = archiveMany(due)
        if (moved.isNotEmpty()) onMoved(true, moved)
        return moved.size
    }

    private fun pickToArchive(req: ArchiveRequest): List<String> {
        if (req.ids.isNotEmpty()) return req.ids.map(::resolveLiveId)
        val scoped = store.tickets.all().filter { req.projectId == null || it.projectId == req.projectId }
        if (req.epicId != null) return scoped.filter { it.epicId == req.epicId && it.status != IN_PROGRESS }.map { it.id }
        val status = req.status ?: throw IllegalArgumentException("archive needs ids, an epicId, or a status (+ olderThanDays)")
        val cutoff = req.olderThanDays?.let { clock() - it * DAY_MS }
        return scoped.filter { it.status == status && status != IN_PROGRESS && (cutoff == null || it.updatedAt <= cutoff) }.map { it.id }
    }

    /** Archives [ids] keeping each one's original position, then closes the gaps once per lane. */
    private fun archiveMany(ids: List<String>): List<String> {
        val lanes = HashSet<String>()
        val moved = ids.filter { id -> val st = store.tickets.get(id)?.status; archiveOne(id, false).also { if (it && st != null) lanes += st } }
        lanes.forEach(store.ticketArchive::renumber)
        return moved
    }

    private fun archiveOne(id: String, renumber: Boolean = true): Boolean {
        val t = store.tickets.get(id) ?: return false
        if (t.status == IN_PROGRESS) return false
        return store.ticketArchive.archive(id, t.epicId?.let { epicSnapshot(it)?.second }, t.projectId?.let(projectSnapshot), clock(), renumber)
    }

    private fun item(a: ArchivedTicket, full: Boolean) = ArchiveItem(
        id = a.row.id, title = a.row.title, status = a.row.status, projectId = a.row.projectId, epicId = a.row.epicId,
        epicName = a.row.epicId?.let { epicSnapshot(it)?.first },
        archivedAt = a.archivedAt, updatedAt = a.row.updatedAt, branch = a.row.branch,
        description = if (full) a.row.description else null,
        links = if (full) a.links.map { "${it.ticketId} ${it.type} ${it.relatedId}" } else emptyList(),
    )

    private fun text(items: List<ArchiveItem>, total: Int) =
        if (items.isEmpty()) "no archived tickets" else "$total archived\n" + items.joinToString("\n") { "${it.id} [${it.status}] ${it.title}" }

    private fun resolveId(raw: String): String {
        val id = raw.trim()
        if (store.ticketArchive.info(id) != null) return id
        return "ticket-" + id.removePrefix("ticket-")
    }

    private fun resolveLiveId(raw: String): String {
        val id = raw.trim()
        return if (store.tickets.get(id) != null) id else "ticket-" + id.removePrefix("ticket-")
    }

    private fun days(v: String?) = v?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 0

    companion object {
        const val KEY = "board.archive.doneAfterDays"
        private const val DONE = "done"
        private const val IN_PROGRESS = "in_progress"
        private const val DAY_MS = 86_400_000L
        private const val DEFAULT_LIMIT = 100
        private const val MAX_LIMIT = 500
        private const val MAX_BULK = 5_000
    }
}
