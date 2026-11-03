// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.archive.ArchiveReport
import com.superiorsoftllc.infantry.archive.ArchiveRequest
import com.superiorsoftllc.infantry.protocol.Envelope

/**
 * ARCHIVE-1 (contract 1.149.0): `infantry ticket archive <id>... | --epic <id> | --filter <lane>[:<days>]`,
 * `infantry ticket restore <id>...`, and `infantry archive list|show|search|count|restore`. One request each, printing the daemon's text.
 */
internal object ArchiveCli {
    const val USAGE = "usage: infantry ticket archive <id>... | --epic <id> | --filter <lane>[:<days>] [--project <id>] [--dry-run] | " +
        "infantry ticket restore <id>... | infantry archive list|show <id>|search <text>|count|restore <id>...|--epic <id> [--project <id>] [--limit <n>]"

    suspend fun request(stream: FrameStream, query: Boolean, req: ArchiveRequest): ArchiveReport {
        val answer = stream.request(
            Envelope(if (query) Envelope.ARCHIVE_QUERY else Envelope.ARCHIVE_APPLY, req.encode()), Envelope.ARCHIVE_REPORT, "archive",
            idempotent = query, timeoutMs = 120_000L,
        )
        return ArchiveReport.decode(answer.data)
    }

    /** `--filter done:30` = lane `done`, older than 30 days. */
    fun parseFilter(filter: String): Pair<String, Int?> {
        val lane = filter.substringBefore(':').trim()
        val days = filter.substringAfter(':', "").trim().takeIf { it.isNotEmpty() }
        require(lane.isNotEmpty()) { "--filter expects <lane>[:<days>]" }
        return lane to days?.let { requireNotNull(it.toIntOrNull()?.takeIf { d -> d >= 0 }) { "--filter days must be a whole number" } }
    }

    /** The request for [action] (`archive`, `restore`, `list`, `show`, `search`, `count`); the flag is true for a read. */
    fun build(action: String, ids: List<String>, project: String?, epic: String?, filter: String?, limit: String?, offset: String?, dryRun: Boolean): Pair<Boolean, ArchiveRequest> {
        val (lane, days) = filter?.let(::parseFilter) ?: (null to null)
        val lim = limit?.toIntOrNull()
        return when (action) {
            "archive" -> false to ArchiveRequest("archive", ids = ids, projectId = project, epicId = epic, status = lane, olderThanDays = days, dryRun = dryRun)
            "restore" -> false to ArchiveRequest("restore", ids = ids, projectId = project, epicId = epic, dryRun = dryRun)
            "list" -> true to ArchiveRequest("list", projectId = project, epicId = epic, limit = lim, offset = offset?.toIntOrNull())
            "show" -> true to ArchiveRequest("show", ids = ids)
            "search" -> true to ArchiveRequest("search", projectId = project, text = ids.joinToString(" "), limit = lim)
            "count" -> true to ArchiveRequest("count", projectId = project)
            else -> throw IllegalArgumentException(USAGE)
        }
    }

    fun usageError(action: String?, ids: List<String>, epic: String?, filter: String?): String? = when (action) {
        "archive" -> if (ids.isEmpty() && epic == null && filter == null) USAGE else null
        "restore" -> if (ids.isEmpty() && epic == null) USAGE else null
        "show" -> if (ids.size != 1) USAGE else null
        "search" -> if (ids.isEmpty()) USAGE else null
        "list", "count" -> null
        else -> USAGE
    }
}
