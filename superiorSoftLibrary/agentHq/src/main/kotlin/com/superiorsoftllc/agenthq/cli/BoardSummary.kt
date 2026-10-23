package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import java.util.concurrent.TimeUnit

/**
 * PM visibility: the whole board in a few lines. Tickets grouped by column, one line each with its worker (role and
 * session id), the worker's live state, its branch and the branch's last commit. Pure over the tickets, sessions and a
 * commit lookup, so `agenthq board` and the `agenthq_board` MCP tool print the same thing.
 */
object BoardSummary {
    val COLUMNS = listOf("todo", "in_progress", "pending", "done")

    /** Done tickets listed in full; older ones fold into a count so a long-lived board stays compact. */
    const val DONE_SHOWN = 5

    /** The live-state words, shared with the background-work card: busy / idle / running background process. */
    fun liveState(session: SessionRecord): String = when {
        session.backgroundWork != null -> "running background process (${session.backgroundWork!!.label.take(40)})"
        session.state == SessionState.BUSY || session.state == SessionState.STARTING -> "busy"
        session.state == SessionState.WAITING_INPUT -> "waiting for input"
        session.state == SessionState.IDLE -> "idle"
        else -> session.state.name.lowercase()
    }

    /** "3m ago" style age of [at] against [now]; "never" when [at] is null. */
    fun ago(at: Long?, now: Long): String {
        if (at == null) return "never"
        val s = TimeUnit.MILLISECONDS.toSeconds((now - at).coerceAtLeast(0))
        return when {
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60}m ago"
            s < 86400 -> "${s / 3600}h ago"
            else -> "${s / 86400}d ago"
        }
    }

    /** Session activity in one phrase: "idle; last action 'Bash: ls' (last output 2m ago)". */
    fun activity(session: SessionRecord, now: Long): String =
        liveState(session) + (session.lastAction?.let { "; last action '$it'" } ?: "") +
            " (last output ${ago(session.lastOutputAt, now)}, last event ${ago(session.lastEventAt, now)})"

    fun render(tickets: List<Ticket>, sessions: List<SessionRecord>, now: Long, commitOf: (Ticket) -> String?): List<String> {
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

    private fun ticketLine(t: Ticket, worker: SessionRecord?, now: Long, commitOf: (Ticket) -> String?): String {
        val parts = mutableListOf(t.id.take(8), t.title.take(60))
        if (worker != null) {
            parts += "${worker.role ?: "?"} ${worker.id.take(8)} ${liveState(worker)}"
            worker.lastAction?.let { parts += "doing '$it'" }
            parts += "out ${ago(worker.lastOutputAt, now)}"
        } else if (t.sessionId != null) parts += "worker ${t.sessionId!!.take(8)} gone"
        t.branch?.let { b -> parts += "$b@${commitOf(t) ?: "no commit"}" }
        return parts.joinToString(" | ")
    }

    /** The branch tip of [ticket], via git in the worker's checkout or repo; null when it cannot be read. */
    fun gitCommitOf(sessions: List<SessionRecord>): (Ticket) -> String? = { t ->
        val branch = t.branch
        val dir = t.sessionId?.let { id -> sessions.firstOrNull { it.id == id } }?.let { it.repoRoot ?: it.worktreePath }
        if (branch == null || dir == null) null
        else runCatching {
            val p = ProcessBuilder("git", "-C", dir, "rev-parse", "--short", branch).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) out.lines().firstOrNull()?.takeIf { it.isNotBlank() } else null
        }.getOrNull()
    }
}
