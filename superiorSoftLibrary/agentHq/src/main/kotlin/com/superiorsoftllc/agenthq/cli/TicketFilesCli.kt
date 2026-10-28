package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.ticketfiles.TicketFilesQuery
import com.superiorsoftllc.agenthq.ticketfiles.TicketFilesReport

/**
 * TICKET-FILES (contract 1.137.0): `agenthq ticket files <id> [--related] | --path <glob>... | --backfill [--all]`, and
 * the one request the MCP tools `agenthq_ticket_files` / `agenthq_ticket_related_files` share with it.
 */
internal object TicketFilesCli {
    const val USAGE = "usage: agenthq ticket files <id> [--related] [--limit <n>] | agenthq ticket files --path <glob> [--path <glob>...] [--limit <n>] | agenthq ticket files --backfill [--all]"

    suspend fun request(stream: FrameStream, query: TicketFilesQuery): String {
        val answer = stream.request(
            Envelope(Envelope.TICKET_FILES, query.encode()), Envelope.TICKET_FILES_REPORT, "ticket_files",
            // A backfill runs one git diff per done ticket (minutes); the reads are SQL.
            idempotent = !query.backfill, timeoutMs = if (query.backfill) 30L * 60_000L else 45_000L,
        )
        return TicketFilesReport.decode(answer.data).text
    }

    fun usageError(id: String?, paths: List<String>, backfill: Boolean, limit: String?): String? = when {
        limit != null && (limit.toIntOrNull() ?: 0) < 1 -> "--limit expects a whole number of lines, 1 or more"
        listOf(id != null, paths.isNotEmpty(), backfill).count { it } != 1 -> USAGE
        else -> null
    }
}
