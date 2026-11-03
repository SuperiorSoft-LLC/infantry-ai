package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.api.board.BoardLines
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionText
import com.superiorsoftllc.infantry.daemon.plugins.SessionViews
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.session.SessionRecord
import java.util.concurrent.TimeUnit

/**
 * PM visibility: the whole board in a few lines. RUNTIME-LOAD-5: the text lives in the daemon API ([BoardLines], [SessionText]) so
 * `infantry board` and the `infantry_board` tool (Infantry_Board's daemon half) print the same thing; this object maps core's
 * records onto it and keeps the git lookup the CLI runs on its own machine.
 */
object BoardSummary {
    val COLUMNS = com.superiorsoftllc.infantry.protocol.TicketStatus.ALL

    const val DONE_SHOWN = BoardLines.DONE_SHOWN

    fun liveState(session: SessionRecord): String = SessionText.liveState(SessionViews.of(session))

    fun ago(at: Long?, now: Long): String = SessionText.ago(at, now)

    fun activity(session: SessionRecord, now: Long): String = SessionText.activity(SessionViews.of(session), now)

    fun render(tickets: List<Ticket>, sessions: List<SessionRecord>, now: Long, options: BoardLines.Options = BoardLines.Options.FULL, commitOf: (Ticket) -> String?): List<String> {
        val byId = tickets.associateBy { it.id }
        return BoardLines.render(tickets.map(::row), sessions.map(SessionViews::of), now, { r -> byId[r.id]?.let(commitOf) }, options)
    }

    private fun row(t: Ticket) = BoardLines.Row(t.id, t.title, t.status, t.position, t.sessionId, t.branch)

    const val TITLE_WIDTH = BoardLines.TITLE_WIDTH
    const val ELLIPSIS = BoardLines.ELLIPSIS

    fun cutTitle(title: String): String = BoardLines.cutTitle(title)

    /** The branch tip of [ticket], via git in the worker's checkout or repo; null when it cannot be read. */
    fun gitCommitOf(sessions: List<SessionRecord>): (Ticket) -> String? = { t ->
        val dir = t.sessionId?.let { id -> sessions.firstOrNull { it.id == id } }?.let { it.repoRoot ?: it.worktreePath }
        gitCommit(t.branch, dir)
    }

    /** `git rev-parse --short <branch>` in [dir]; null when either is missing or git cannot answer. */
    fun gitCommit(branch: String?, dir: String?): String? =
        if (branch == null || dir == null) null
        else runCatching {
            val p = ProcessBuilder("git", "-C", dir, "rev-parse", "--short", branch).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) out.lines().firstOrNull()?.takeIf { it.isNotBlank() } else null
        }.getOrNull()
}
