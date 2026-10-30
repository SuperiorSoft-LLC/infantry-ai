package com.superiorsoftllc.agenthq.daemon.api.board

import com.superiorsoftllc.agenthq.daemon.api.progress.ProgressText
import com.superiorsoftllc.agenthq.daemon.api.sessions.SessionText
import com.superiorsoftllc.agenthq.daemon.api.sessions.SessionView

/**
 * RUNTIME-LOAD-5: the whole board in a few lines (moved unchanged from core's `BoardSummary`, which delegates here), so
 * `agenthq board` and the `agenthq_board` tool print the same thing. Tickets grouped by column, one line each with its worker
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
    data class Row(val id: String, val title: String, val status: String, val position: Int, val sessionId: String?, val branch: String?)

    fun row(t: TicketInfo) = Row(t.id, t.title, t.status, t.position, t.sessionId, t.branch)

    fun shortId(id: String): String = id.takeLast(SHORT_ID_LEN)

    fun render(tickets: List<Row>, sessions: List<SessionView>, now: Long, commitOf: (Row) -> String?): List<String> {
        if (tickets.isEmpty()) return listOf("board is empty: no tickets")
        val bySession = sessions.associateBy { it.id }
        val lines = mutableListOf<String>()
        val known = COLUMNS.toSet()
        val groups = COLUMNS.associateWith { column -> tickets.filter { it.status == column }.sortedBy { it.position } } +
            tickets.filter { it.status !in known }.groupBy { it.status }
        for ((column, group) in groups) {
            if (group.isEmpty()) continue
            lines += "$column (${group.size})"
            val shown = if (column == "done") group.takeLast(DONE_SHOWN) else group
            if (shown.size < group.size) lines += "  ... ${group.size - shown.size} older done not shown"
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
        } else if (t.sessionId != null) parts += "worker ${t.sessionId.take(8)} gone"
        t.branch?.let { b -> parts += "$b@${commitOf(t) ?: "no commit"}" }
        return parts.joinToString(" | ")
    }

    /** [title] on one line, at most [TITLE_WIDTH] characters, ending in [ELLIPSIS] only when it was cut. */
    fun cutTitle(title: String): String {
        val flat = title.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= TITLE_WIDTH) flat else flat.take(TITLE_WIDTH - ELLIPSIS.length).trimEnd() + ELLIPSIS
    }
}
