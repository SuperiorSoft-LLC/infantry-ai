// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.archive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ARCHIVE-1 (contract 1.149.0): the `archive_query` / `archive_apply` frames.
 *  - archive_query [op]: `list` (newest archived first; [projectId], [epicId], [limit], [offset]), `show` ([ids] = one id), `search` ([text]), `count`.
 *  - archive_apply [op]: `archive` ([ids] tickets, or [epicId] an epic with its tickets, or [status] + [olderThanDays] a lane filter within
 *    [projectId]), `unarchive` ([ids], or [epicId] every archived ticket of the epic). [dryRun] only counts what would move.
 */
@Serializable
data class ArchiveRequest(
    val op: String,
    val ids: List<String> = emptyList(),
    val projectId: String? = null,
    val epicId: String? = null,
    val status: String? = null,
    val olderThanDays: Int? = null,
    val text: String? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val dryRun: Boolean = false,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun decode(text: String): ArchiveRequest = json.decodeFromString(serializer(), text)
    }
}

/** One archived ticket as the lists and the UI read it. */
@Serializable
data class ArchiveItem(
    val id: String,
    val title: String,
    val status: String,
    val projectId: String? = null,
    val epicId: String? = null,
    val epicName: String? = null,
    val archivedAt: Long,
    val updatedAt: Long,
    val branch: String? = null,
    /** Only on `show`. */
    val description: String? = null,
    val links: List<String> = emptyList(),
)

/** The answer: compact [text] (CLI / MCP) plus the structured [items] (UI); [count] is the total in the archive for the scope, or how many moved. */
@Serializable
data class ArchiveReport(val text: String, val items: List<ArchiveItem> = emptyList(), val count: Int = 0) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun decode(text: String): ArchiveReport = json.decodeFromString(serializer(), text)
    }
}
