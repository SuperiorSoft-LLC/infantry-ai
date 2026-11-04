// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chatfeed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.superiorsoftllc.infantry.ui.model.ChatItem

/** CHAT-SCROLL-1: the `chat.pageSize` setting's default: how many rows a chat opens with, and how many each scroll-up adds. */
const val CHAT_PAGE_SIZE_DEFAULT = 80

/** CHAT-SCROLL-1: the setting key (node `infantry.chatfeed`) for the page size. */
const val CHAT_PAGE_SIZE_KEY = "chat.pageSize"

/** CHAT-SCROLL-1: the smallest page size honoured, so a mistyped setting never leaves the chat a row or two tall. */
const val CHAT_PAGE_SIZE_MIN = 20

/** CHAT-SCROLL-1: older rows load once the first visible row is within this many rows of the window's top. */
const val CHAT_LOAD_OLDER_WITHIN = 5

/**
 * CHAT-SCROLL-1: which slice of a long transcript the chat composes, and whether it follows new rows.
 *
 * The transcript ([ChatItem] rows, oldest first) is append-mostly. The chat shows rows `start until total`: it opens on the
 * newest [pageSize] rows and [loadOlder] widens the slice by a page when the user nears its top. New rows extend the slice at
 * the end, so the rows already on screen never move. [follow] is true while the user sits at the bottom: new rows are then
 * scrolled to (and the slice is trimmed back to two pages so it stays small); scrolled up, they only count as [unread].
 */
class ChatWindow(pageSize: Int, total: Int) {
    val pageSize: Int = pageSize.coerceAtLeast(CHAT_PAGE_SIZE_MIN)

    /** Absolute index of the first composed row. */
    var start by mutableIntStateOf((total - this.pageSize).coerceAtLeast(0))
        private set

    /** The user is at the bottom: new rows are followed. */
    var follow by mutableStateOf(true)

    /** How many rows the user had seen when they last were at the bottom; rows past it are [unread]. */
    var seen by mutableIntStateOf(total)
        private set

    /** A row (absolute index) to scroll to once the slice holds it; [JUMP_TO_END] = the bottom. Null = none pending. */
    var pendingJump by mutableStateOf<Int?>(null)

    /** The start to use for a transcript of [total] rows (a transcript that shrank under the slice restarts at its newest page). */
    fun startFor(total: Int): Int = if (start > total) (total - pageSize).coerceAtLeast(0) else start

    /** New rows arrived (or the transcript changed) and it now holds [total]. Following: mark them seen and trim the slice. */
    fun onTotal(total: Int) {
        if (start > total) start = (total - pageSize).coerceAtLeast(0)
        if (seen > total) seen = total
        if (follow) {
            seen = total
            if (total - start > pageSize * 2) start = total - pageSize
        }
    }

    /** New rows since the user scrolled up. Zero while following. Never capped. */
    fun unread(total: Int): Int = if (follow) 0 else (total - seen).coerceAtLeast(0)

    /** Widen the slice by a page towards the oldest row; false when it already starts at the first row. */
    fun loadOlder(): Boolean {
        if (start == 0) return false
        start = (start - pageSize).coerceAtLeast(0)
        return true
    }

    /** Make [index] part of the slice (loading its page, with a little context above it) and ask to scroll to it. */
    fun reveal(index: Int) {
        if (index < start) start = (index - pageSize / 4).coerceAtLeast(0)
        follow = false
        pendingJump = index
    }

    /** Back to the newest rows, following again (the jump-to-latest button, and the owner's own new message). */
    fun jumpToLatest(total: Int) {
        follow = true
        seen = total
        start = (total - pageSize).coerceAtLeast(0)
        pendingJump = JUMP_TO_END
    }

    companion object {
        const val JUMP_TO_END = -1
    }
}

/** CHAT-SCROLL-1: the text a row is searched by. */
fun chatItemText(item: ChatItem): String = when (item) {
    is ChatItem.Line -> item.line.text
    is ChatItem.Reasoning -> item.lines.joinToString("\n") { it.text }
    is ChatItem.Contribution -> item.item.text.ifBlank { item.item.label } + "\n" + item.item.details.joinToString("\n")
}

/** CHAT-SCROLL-1: the absolute indices of the rows whose text holds [query] (case-insensitive), oldest first; none for a blank query. */
fun chatSearchHits(rows: List<ChatItem>, query: String): List<Int> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return rows.indices.filter { chatItemText(rows[it]).contains(q, ignoreCase = true) }
}
