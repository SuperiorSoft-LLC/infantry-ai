// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.*
import com.superiorsoftllc.infantry.ui.model.parseChatMarkdownSafely
import kotlinx.coroutines.launch

const val COMMENT_TAG_PREFIX = "ticket-comment-"
const val COMMENT_STATE_TAG_PREFIX = "ticket-comment-state-"
const val COMMENT_RESOLVE_TAG_PREFIX = "ticket-comment-resolve-"
const val COMMENT_INPUT_TAG = "ticket-comment-input"
const val COMMENT_SEND_TAG = "ticket-comment-send"
const val COMMENT_RESOLVED_TOGGLE_TAG = "ticket-comment-post-resolved"
const val COMMENT_CREATED_BY_TAG = "ticket-created-by"
const val COMMENT_NOTIFY_TAG_PREFIX = "ticket-notify-"
const val COMMENT_NOTIFY_SAVE_TAG = "ticket-notify-save"
const val COMMENT_ERROR_TAG = "ticket-comments-error"

/**
 * TICKET-COMMENTS-3: the modal's comments area: who created the ticket and whom to notify, the thread (oldest first, open ones
 * stand out, each can be resolved), and a composer. All data comes through [api]; this holds only what is being typed.
 */
@Composable
fun TicketCommentsPanel(ticketId: String, api: TicketCommentsApi, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var comments by remember(ticketId) { mutableStateOf<List<TicketComment>?>(null) }
    var meta by remember(ticketId) { mutableStateOf<TicketCommentMeta?>(null) }
    var candidates by remember(ticketId) { mutableStateOf<List<NotifyAgent>?>(null) }
    var error by remember(ticketId) { mutableStateOf<String?>(null) }

    suspend fun reload() {
        api.comments(ticketId).onSuccess { comments = it; error = null }.onFailure { comments = emptyList(); error = "Comments: ${it.message}" }
        api.meta(ticketId).onSuccess { meta = it }.onFailure { error = error ?: "Ticket: ${it.message}" }
    }
    LaunchedEffect(ticketId) {
        reload()
        api.notifyCandidates().onSuccess { candidates = it }.onFailure { candidates = emptyList(); error = error ?: "Agents: ${it.message}" }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Comments", color = Palette.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        NotifyBlock(ticketId, meta, candidates, api, onError = { error = it }, onSaved = { scope.launch { reload() } })
        error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.testTag(COMMENT_ERROR_TAG)) }
        val list = comments
        when {
            list == null -> Text("Loading comments…", color = Palette.Muted, fontSize = 11.sp)
            list.isEmpty() -> Text("No comments yet.", color = Palette.Muted, fontSize = 11.sp)
            else -> {
                // AGENT-TASKS-6: the latest handoff is its own block above the thread; any other kind (known or not) is a note row.
                val handoff = list.lastOrNull { it.kind == "handoff" }
                handoff?.let { HandoffBlock(it) }
                list.filter { it !== handoff }.forEach { c ->
                    CommentRow(c, onResolve = { scope.launch { api.resolve(c.id).onFailure { error = "Resolve: ${it.message}" }; reload() } })
                }
            }
        }
        Composer(onSend = { body, resolved ->
            scope.launch {
                api.add(ticketId, body, resolved).onFailure { error = "Send: ${it.message}" }
                reload()
            }
        })
    }
}

const val HANDOFF_TAG = "ticket-handoff"

/** AGENT-TASKS-6: the latest handoff note (what the last worker left for the next one). */
@Composable
private fun HandoffBlock(c: TicketComment) {
    Column(Modifier.fillMaxWidth().background(Palette.Panel, RoundedCornerShape(6.dp)).padding(8.dp).testTag(HANDOFF_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Handoff", color = Palette.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(c.time, color = Palette.Muted, fontSize = 10.sp)
        CommentBody(c.body)
    }
}

@Composable
private fun CommentRow(c: TicketComment, onResolve: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(if (c.open) Palette.Panel else Palette.Bg, RoundedCornerShape(6.dp))
            .padding(8.dp).testTag(COMMENT_TAG_PREFIX + c.id),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.author, color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(c.time, color = Palette.Muted, fontSize = 10.sp)
            Chip(c.kind, Palette.Muted)
            Chip(if (c.open) "open" else "resolved", if (c.open) Palette.Accent else Palette.Ok, Modifier.testTag(COMMENT_STATE_TAG_PREFIX + c.id))
            Spacer(Modifier.weight(1f))
            if (c.open) TextButton(onClick = onResolve, modifier = Modifier.testTag(COMMENT_RESOLVE_TAG_PREFIX + c.id)) { Text("Resolve", fontSize = 11.sp) }
        }
        CommentBody(c.body)
    }
}

@Composable
private fun Composer(onSend: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var resolved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicTextField(
            value = text, onValueChange = { text = it },
            textStyle = TextStyle(color = Palette.Text, fontSize = 12.sp), cursorBrush = SolidColor(Palette.Accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).background(Palette.Panel, RoundedCornerShape(6.dp)).padding(8.dp).testTag(COMMENT_INPUT_TAG),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = resolved, onCheckedChange = { resolved = it }, modifier = Modifier.testTag(COMMENT_RESOLVED_TOGGLE_TAG))
            // The label toggles too: tapping the words (not just the small box) was a dead click.
            Text("Post as resolved", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.clickable { resolved = !resolved })
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onSend(text.trim(), resolved); text = ""; resolved = false },
                enabled = text.isNotBlank(), modifier = Modifier.testTag(COMMENT_SEND_TAG),
            ) { Text("Send", fontSize = 12.sp) }
        }
    }
}

/** "Created by" and the notify picker (multi-select over user-created agents only; Save writes it with the daemon's ticket update). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotifyBlock(
    ticketId: String, meta: TicketCommentMeta?, candidates: List<NotifyAgent>?, api: TicketCommentsApi,
    onError: (String) -> Unit, onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var picked by remember(ticketId, meta?.notify) { mutableStateOf((meta?.notify ?: emptyList()).toSet()) }
    Column(Modifier.fillMaxWidth().background(Palette.Panel, RoundedCornerShape(6.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val creator = notifyCreatorLabel(meta?.createdBy)
        if (creator != null) Text("Created by $creator", color = Palette.Text, fontSize = 11.sp, modifier = Modifier.testTag(COMMENT_CREATED_BY_TAG))
        var editing by remember(ticketId) { mutableStateOf(false) }
        var menu by remember(ticketId) { mutableStateOf(false) }
        fun nameOf(id: String) = candidates?.firstOrNull { it.id == id }?.name?.ifBlank { id } ?: id
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Notify: " + (if (picked.isEmpty()) "nobody" else picked.joinToString(", ") { nameOf(it) }),
                color = Palette.Muted, fontSize = 11.sp, maxLines = 2, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { editing = !editing }) { Text(if (editing) "Done" else "Edit", fontSize = 11.sp) }
        }
        if (editing) when {
            candidates == null -> Text("Loading agents…", color = Palette.Muted, fontSize = 11.sp)
            candidates.isEmpty() -> Text("No user-created agents to notify.", color = Palette.Muted, fontSize = 11.sp)
            else -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    picked.forEach { id -> TextButton(onClick = { picked = picked - id }) { Text("${nameOf(id)} ×", fontSize = 11.sp) } }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("+ Add", fontSize = 11.sp) }
                        InfantryDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            candidates.filter { it.id !in picked }.forEach { a ->
                                DropdownMenuItem(
                                    text = { Text(a.name.ifBlank { a.id }, fontSize = 11.sp) },
                                    onClick = { picked = picked + a.id; menu = false },
                                    modifier = Modifier.testTag(COMMENT_NOTIFY_TAG_PREFIX + a.id),
                                )
                            }
                        }
                    }
                }
            }
        }
        TextButton(
            onClick = { scope.launch { api.saveNotify(ticketId, picked.toList()).onFailure { onError("Notify: ${it.message}") }; onSaved() } },
            enabled = meta != null && picked != meta.notify.toSet(), modifier = Modifier.testTag(COMMENT_NOTIFY_SAVE_TAG),
        ) { Text("Save notify list", fontSize = 11.sp) }
    }
}

/** The creator to show, or null (line hidden) when the ticket predates the feature or the meta has not loaded. */
internal fun notifyCreatorLabel(createdBy: String?): String? =
    createdBy?.trim()?.takeIf { it.isNotEmpty() && !it.equals("unknown", true) }?.removePrefix("role:")

/** CARD-4: a comment body parses and draws through the window's shared chat engine and renderers (plugin cards, overrides). */
@Composable
private fun CommentBody(body: String) {
    val md = LocalChatMarkdown.current
    parseChatMarkdownSafely(md.engine, body).forEach { md.renderers.rendererFor(it).Render(it) }
}
