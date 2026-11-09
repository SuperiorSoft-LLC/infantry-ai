// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.board

import com.superiorsoftllc.infantry.daemon.api.progress.ProgressText
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionText
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionView

/**
 * RUNTIME-LOAD-5: the whole board in a few lines (moved unchanged from core's `BoardSummary`, which delegates here), so
 * `infantry board` and the `infantry_board` tool print the same thing. Tickets grouped by column, one line each with its worker
 * (role and session id), the worker's live state, its branch and the branch's last commit. Pure over the [Row]s, the sessions and
 * a commit lookup.
 */
object BoardLines {
    /** The board's columns in order (the protocol's `TicketStatus.ALL`). */
    val COLUMNS = listOf("backlog", "todo", "in_progress", "pending", "done")

    /** Done tickets listed in full; older ones fold into a count so a long-lived board stays compact. */
    const val DONE_SHOWN = 5

    /** Widest title a board line prints; a longer one is cut and ends in [ELLIPSIS]. */
    const val TITLE_WIDTH = 100
    const val ELLIPSIS = "..."

    /** The short id a board line shows: the id's last 8 characters. */
    const val SHORT_ID_LEN = 8

    /** One ticket as a board line needs it. */
    data class Row(val id: String, val title: String, val status: String, val position: Int, val sessionId: String?, val branch: String?, val workTip: String? = null, val workAhead: Int? = null, val queuedAgent: String? = null, val queuedAgentName: String? = null, val queueRank: Int? = null)

    fun row(t: TicketInfo) = Row(t.id, t.title, t.status, t.position, t.sessionId, t.branch, t.workTip, t.workAhead, t.queuedAgent, t.queuedAgentName, t.queueRank)

    fun shortId(id: String): String = id.takeLast(SHORT_ID_LEN)

    /**
     * TOKEN-1: what to print. [columns] limits the board to those columns (null = all); [limit] caps the lines of every column
     * (null = the compact default: done shows its last [DONE_SHOWN], backlog and todo show [QUEUE_SHOWN] lines and a count of the
     * rest, unless [columns] names them; an explicit [limit] applies to every listed column).
     */
    data class Options(val columns: Set<String>? = null, val limit: Int? = null, val compact: Boolean = false, val queueShown: Int = QUEUE_SHOWN, val doneShown: Int = DONE_SHOWN,
        /** WAITING-FLAG-1: only the tickets whose worker is flagged "active but waiting" (and the flag still counts). */
        val waitingOnly: Boolean = false,
    ) {
        companion object {
            /** The full board, as it always printed (what the golden tests read). */
            val FULL = Options()
        }
    }

    /** Lines of backlog / todo the compact default prints before folding the rest into a count. */
    const val QUEUE_SHOWN = 10

    fun render(tickets: List<Row>, sessions: List<SessionView>, now: Long, commitOf: (Row) -> String?, options: Options = Options.FULL): List<String> {
        if (tickets.isEmpty()) return listOf("board is empty: no tickets")
        val bySession = sessions.associateBy { it.id }
        val lines = mutableListOf<String>()
        val known = COLUMNS.toSet()
        val groups = COLUMNS.associateWith { column -> tickets.filter { it.status == column }.sortedBy { it.position } } +
            tickets.filter { it.status !in known }.groupBy { it.status }
        for ((column, allGroup) in groups) {
            val group = if (options.waitingOnly) allGroup.filter { t -> t.sessionId?.let { bySession[it] }?.let { waitingActive(it, now) } == true } else allGroup
            if (group.isEmpty() || (options.columns != null && column !in options.columns)) continue
            lines += "$column (${group.size})"
            val shown = when {
                options.limit != null -> if (column == "done") group.takeLast(options.limit) else group.take(options.limit)
                column == "done" -> group.takeLast(options.doneShown)
                options.compact && options.columns == null && (column == "backlog" || column == "todo") -> group.take(options.queueShown)
                else -> group
            }
            if (shown.size < group.size) lines += "  ... ${group.size - shown.size} " + (if (column == "done") "older done" else "more") + " not shown"
            for (t in shown) lines += "  " + ticketLine(t, t.sessionId?.let { bySession[it] }, now, commitOf)
        }
        return lines
    }

    private fun ticketLine(t: Row, worker: SessionView?, now: Long, commitOf: (Row) -> String?): String {
        val parts = mutableListOf(shortId(t.id), cutTitle(t.title))
        if (worker != null) {
            parts += "${worker.role ?: "?"} ${worker.id.take(8)} ${SessionText.liveState(worker)}"
            worker.lastAction?.let { parts += "doing '$it'" }
            parts += "out ${SessionText.ago(worker.lastOutputAt, now)}"
            worker.progress?.let { parts += "progress " + ProgressText.line(it, now) }
            waitingText(worker, now)?.let { parts += it }
        } else if (t.sessionId != null) parts += "worker ${t.sessionId.take(8)} gone"
        t.branch?.let { b -> parts += "$b@${commitOf(t) ?: "no commit"}" }
        // AGENT-TASKS-2: a todo ticket whose branch keeps work: `[WORK <tip7> +<ahead>]`.
        if (t.workTip != null && t.workAhead != null) parts += "[WORK ${t.workTip.take(7)} +${t.workAhead}]"
        // AGENT-TASKS-3: a todo ticket in an agent's persistent queue: `[Q:<agent name>#<place>]`.
        if (t.queuedAgent != null && t.queueRank != null) parts += "[Q:${t.queuedAgentName ?: t.queuedAgent.take(SHORT_ID_LEN)}#${t.queueRank}]"
        return parts.joinToString(" | ")
    }

    /** WAITING-FLAG-1: the session's waiting flag still counts at [now]. */
    fun waitingActive(s: SessionView, now: Long): Boolean = s.waitingReason != null && now <= (s.waitingExpiresAt ?: Long.MAX_VALUE)

    /** WAITING-FLAG-1: `WAITING 3m (build): reason`, or `WAITING EXPIRED ...` past its expiry; null when not flagged. */
    fun waitingText(s: SessionView, now: Long): String? {
        val reason = s.waitingReason ?: return null
        val since = s.waitingSince ?: now
        return if (waitingActive(s, now)) "WAITING ${(now - since) / 60_000}m: $reason" else "WAITING EXPIRED ${(now - (s.waitingUntilAt ?: since)) / 60_000}m ago: $reason"
    }

    /** [title] on one line, at most [TITLE_WIDTH] characters, ending in [ELLIPSIS] only when it was cut. */
    fun cutTitle(title: String): String {
        val flat = title.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= TITLE_WIDTH) flat else flat.take(TITLE_WIDTH - ELLIPSIS.length).trimEnd() + ELLIPSIS
    }
}
