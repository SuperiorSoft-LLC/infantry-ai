// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import com.superiorsoftllc.infantry.ui.protocol.Ticket

/**
 * GAP-006b: what the chat pane offers for the session it shows when that session has nothing on screen.
 *
 * The rule is the daemon's (TicketWorkerHealth, SessionManager.resumeHeld): a session at rest WITH a provider session id
 * took its first turn, so it RESUMES with its context; one with none never did, so it STARTS -- the same session, on the
 * settings it already has (a ticket worker restarts its ticket, which keeps the ticket's role, executor and branch). A
 * pane never offers the generic New agent box for a session it shows: that cut a second, unrelated instance.
 */
sealed interface SessionPaneAction {
    /** No first turn yet: start what is configured, after the owner applies [plan]. */
    data class Start(val plan: StartPlan) : SessionPaneAction

    /** A conversation exists: reattach and continue it. */
    data class Resume(val sessionId: String) : SessionPaneAction
}

/** GAP-006b: the settings a Start will run, shown on the start card before Apply. */
data class StartPlan(
    val sessionId: String,
    /** The ticket the session works on; a Start of a ticket worker is a start of that ticket. */
    val ticketId: String?,
    val ticketTitle: String?,
    val role: String?,
    val provider: String,
    val model: String?,
    val branch: String?,
    val worktree: String,
)

/** The action for [session] at rest, or null while a turn is in flight or when there is nothing to start or resume. */
fun sessionPaneAction(session: SessionRecord, tickets: List<Ticket>): SessionPaneAction? {
    if (turnInFlight(session)) return null
    if (session.providerSessionId != null) return SessionPaneAction.Resume(session.id)
    val ticket = session.ticketId?.let { id -> tickets.firstOrNull { it.id == id } }
    // A plain session that ran and FAILED before its harness answered stays refused by the daemon: nothing to offer.
    if (ticket == null && session.state != SessionState.IDLE) return null
    return SessionPaneAction.Start(
        StartPlan(
            sessionId = session.id,
            ticketId = ticket?.id ?: session.ticketId,
            ticketTitle = ticket?.title,
            role = session.role,
            provider = session.provider,
            model = session.model ?: ticket?.model,
            branch = session.worktreeBranch ?: ticket?.branch,
            worktree = session.worktreePath ?: session.workingDir,
        ),
    )
}

/** The first turn a Start of a plain (non-ticket) session sends when the owner typed nothing. */
const val START_DEFAULT_PROMPT = "Start."

/**
 * END-SESSION-1: an ended session (End session, exit, orphaned) that can be resumed shows the Resume splash at once,
 * even while its transcript is on screen; it is not a live chat any more.
 * SESSION-FAIL-VISIBLE-1: a FAILED one never does: it keeps its transcript, with the failure card ([sessionFailure]) on top.
 */
fun showsResumeSplash(session: SessionRecord, action: SessionPaneAction?): Boolean =
    action is SessionPaneAction.Resume && session.state in setOf(SessionState.EXITED, SessionState.ORPHANED)

/** SESSION-FAIL-VISIBLE-1: the text a failed session's card shows when the daemon recorded no reason. */
const val SESSION_FAILED_NO_REASON = "The session failed and the daemon recorded no reason; see the lines below."

/**
 * SESSION-FAIL-VISIBLE-1: the exact error of a session whose start or last turn FAILED (the daemon's `lastError`, e.g.
 * "the claude process did not start: Claude Code is not signed in. ..."), or null when it did not fail. Every client
 * shows it first, above the transcript, with Retry in place.
 */
fun sessionFailure(session: SessionRecord): String? =
    if (session.state != SessionState.FAILED) null else session.lastError?.trim()?.takeIf { it.isNotEmpty() } ?: SESSION_FAILED_NO_REASON
