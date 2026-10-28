package com.superiorsoftllc.agenthq.ticketfiles

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * TICKET-FILES (contract 1.137.0): the `ticket_files` frame. Exactly one of: [ticketId] (that ticket's files; with
 * [related], the union of the files of its top related tickets), [paths] (the tickets that changed matching files), or
 * [backfill] (rebuild rows for done tickets from git; [all] re-records those that have rows). [limit] / [top] override
 * `limits.ticketFiles.maxLines` / `limits.ticketFiles.relatedTop` for this answer only.
 */
@Serializable
data class TicketFilesQuery(
    val ticketId: String? = null,
    val paths: List<String> = emptyList(),
    val related: Boolean = false,
    val backfill: Boolean = false,
    val all: Boolean = false,
    val limit: Int? = null,
    val top: Int? = null,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun decode(text: String): TicketFilesQuery = json.decodeFromString(serializer(), text)
    }
}

/** The `ticket_files_report` answer: compact text, already capped, the same for the CLI and the MCP tools. */
@Serializable
data class TicketFilesReport(val text: String) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(text: String): TicketFilesReport = json.decodeFromString(serializer(), text)
    }
}

/** Renders [TicketFiles] answers as the compact lines every caller prints. */
class TicketFilesText(private val files: TicketFiles, private val titleOf: (String) -> String?) {

    fun answer(query: TicketFilesQuery, maxLines: Int, top: Int): String {
        val limit = (query.limit ?: maxLines).coerceAtLeast(1)
        val id = query.ticketId?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            query.backfill -> backfill(files.backfill(query.all), limit)
            id != null && query.related -> related(id, (query.top ?: top).coerceAtLeast(1), limit)
            id != null -> forTicket(id, limit)
            query.paths.isNotEmpty() -> forPaths(query.paths, limit)
            else -> throw IllegalArgumentException("ticket_files needs a ticketId, a path glob, or backfill")
        }
    }

    private fun forTicket(id: String, limit: Int): String {
        val rows = files.forTicket(id)
        if (rows.isEmpty()) return "$id: no files recorded (not done yet, or not found in git; `agenthq ticket files --backfill`)"
        val head = "$id ${title(id)}: ${rows.size} files, +${rows.sumOf { it.added ?: 0 }} -${rows.sumOf { it.removed ?: 0 }}" +
            (rows.firstNotNullOfOrNull { it.tipSha ?: it.mergeSha }?.let { " @${it.take(8)}" } ?: "")
        return capped(head, rows.map(::fileLine), limit)
    }

    private fun forPaths(globs: List<String>, limit: Int): String {
        val rows = files.forPaths(globs)
        if (rows.isEmpty()) return "no ticket changed ${globs.joinToString(", ")}"
        val byPath = rows.groupBy { it.path }.entries.sortedWith(compareByDescending<Map.Entry<String, List<TicketFile>>> { it.value.size }.thenBy { it.key })
        val tickets = rows.map { it.ticketId }.distinct()
        val head = "${byPath.size} files changed by ${tickets.size} tickets (${globs.joinToString(", ")})"
        val lines = byPath.map { (path, changes) -> "$path  ${changes.joinToString(" ") { "${it.ticketId}:${it.status}" }}" }
        val legend = tickets.take(limit).map { "  $it ${title(it)}" }
        return capped(head, lines, limit) + "\ntickets:\n" + legend.joinToString("\n") +
            (if (tickets.size > legend.size) "\n  ... ${tickets.size - legend.size} more" else "")
    }

    private fun related(id: String, top: Int, limit: Int): String {
        val (chosen, byPath) = files.filesForRelated(id, top)
        if (files.forTicket(id).isEmpty()) return "$id: no files recorded, so no related tickets (`agenthq ticket files --backfill`)"
        if (chosen.isEmpty()) return "$id: no other ticket changed any of its files"
        val head = "top ${chosen.size} tickets related to $id by shared files:\n" + chosen.joinToString("\n") {
            "  ${it.ticketId} ${title(it.ticketId)}  shared ${it.shared}/${it.total} jaccard ${"%.2f".format(it.jaccard)}"
        } + "\n${byPath.size} files they changed (path  ticket:status):"
        val lines = byPath.entries.sortedWith(compareByDescending<Map.Entry<String, List<TicketFile>>> { it.value.size }.thenBy { it.key })
            .map { (path, changes) -> "$path  ${changes.joinToString(" ") { "${it.ticketId}:${it.status}" }}" }
        return capped(head, lines, limit)
    }

    private fun backfill(r: BackfillReport, limit: Int): String =
        "backfill: ${r.considered} done tickets, ${r.alreadyRecorded} already recorded, ${r.recoveredTotal} recovered " +
            "(${FileSource.entries.joinToString(", ") { "${it.name.lowercase()} ${r.recovered[it] ?: 0}" }}), " +
            "${r.notRecovered.size} not recovered" +
            (if (r.notRecovered.isNotEmpty()) ": " + r.notRecovered.take(limit).joinToString(" ") +
                (if (r.notRecovered.size > limit) " ... ${r.notRecovered.size - limit} more" else "") else "")

    private fun title(id: String) = titleOf(id)?.let { "\"${it.take(60)}\"" } ?: ""

    private fun capped(head: String, lines: List<String>, limit: Int): String =
        (listOf(head) + lines.take(limit) + if (lines.size > limit) listOf("... ${lines.size - limit} more lines (--limit)") else emptyList())
            .joinToString("\n")

    companion object {
        fun fileLine(f: TicketFile): String {
            val counts = if (f.added == null && f.removed == null) "bin" else "+${f.added ?: 0} -${f.removed ?: 0}"
            return "${f.status} $counts  ${f.path}" + (f.oldPath?.let { " (from $it)" } ?: "")
        }
    }
}
