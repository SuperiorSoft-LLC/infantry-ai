// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val ARCHIVE_BUTTON_TAG = "board-archive-button"
const val ARCHIVE_SEARCH_TAG = "board-archive-search"
const val ARCHIVE_RESTORE_TAG_PREFIX = "board-archive-restore-"

/** ARCHIVE-1: one archived ticket as the daemon's `infantry_archive` line carries it: `<id> [<status>] <title>`. */
data class ArchivedTicketLine(val id: String, val status: String, val title: String)

/** Parses the lines of an `infantry_archive` list / search answer (the first "N archived" summary line is skipped). */
fun parseArchiveLines(text: String): List<ArchivedTicketLine> = text.lineSequence().mapNotNull { line ->
    val m = Regex("""^(ticket-[0-9a-f]+) \[([^\]]*)] (.*)$""").find(line.trim()) ?: return@mapNotNull null
    ArchivedTicketLine(m.groupValues[1], m.groupValues[2], m.groupValues[3])
}.toList()

/**
 * ARCHIVE-1: the read-only Archive view of the active project: search, and a Restore button per ticket (RESTORE puts it back on the
 * live board exactly as it was). Data comes through the board plugin's tools, so the view holds only what is typed and listed.
 */
@Composable
fun BoardArchiveDialog(deps: BoardDeps, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val project = deps.session.activeProjectId
    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<ArchivedTicketLine>?>(null) }
    var total by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val canRestore = deps.can("board.archive.write")

    suspend fun load() {
        val args = buildJsonObject {
            put("op", if (query.isBlank()) "list" else "search")
            if (query.isNotBlank()) put("text", query.trim())
            project?.let { put("projectId", it) }
            put("limit", 200)
        }
        deps.tickets.pluginTool("infantry_archive", args)
            .onSuccess { rows = parseArchiveLines(it); total = it.lineSequence().firstOrNull().orEmpty(); error = null }
            .onFailure { rows = emptyList(); error = it.message }
    }
    LaunchedEffect(query, project) { load() }

    AhqAlertDialog(
        onDismissRequest = onClose,
        title = { Text("Archive", fontSize = 16.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search archived tickets") },
                    modifier = Modifier.fillMaxWidth().testTag(ARCHIVE_SEARCH_TAG),
                )
                Text(total, color = Palette.Muted, fontSize = 11.sp)
                error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
                val list = rows
                when {
                    list == null -> Text("Loading…", color = Palette.Muted, fontSize = 11.sp)
                    list.isEmpty() -> Text("No archived tickets.", color = Palette.Muted, fontSize = 12.sp)
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(list, key = { it.id }) { t ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.title, fontSize = 12.sp, maxLines = 2)
                                    Text("${t.id.takeLast(8)} · ${t.status}", color = Palette.Muted, fontSize = 10.sp)
                                }
                                TextButton(
                                    enabled = canRestore, modifier = Modifier.testTag(ARCHIVE_RESTORE_TAG_PREFIX + t.id),
                                    onClick = {
                                        scope.launch {
                                            deps.tickets.pluginTool("infantry_ticket_restore", buildJsonObject {
                                                put("ids", JsonArray(listOf(JsonPrimitive(t.id))))
                                            }).onFailure { error = it.message }
                                            load()
                                        }
                                    },
                                ) { Text("Restore", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
