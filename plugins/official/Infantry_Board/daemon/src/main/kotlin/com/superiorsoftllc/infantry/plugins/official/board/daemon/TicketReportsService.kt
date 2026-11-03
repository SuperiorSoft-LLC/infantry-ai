// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.board.BoardResult
import com.superiorsoftllc.infantry.daemon.api.tickets.TicketReports

/** TICKET-COMMENTS-4: the comment port core's idle watch uses (open `issue` comments authored by the daemon). */
internal class TicketReportsService(private val api: DaemonApi, private val comments: TicketComments) : TicketReports {
    override fun commentedSince(ticketId: String, sessionId: String, sinceMs: Long) = comments.commentedSince(ticketId, sessionId, sinceMs)

    override fun postDaemonIssue(ticketId: String, body: String): Boolean {
        val ticket = (api.tickets.get(ticketId, Caller(owner = true)) as? BoardResult.Ok)?.value ?: return false
        comments.add(ticket.id, ticket.projectId, ticket.title, TicketComments.Author.daemon, "issue", TicketComments.OPEN, body)
        return true
    }
}

/**
 * TICKET-COMMENTS-4: the one plain instruction line a new ticket carries. Its text is the setting [KEY] (project / daemon data);
 * unset = [DEFAULT]; set to blank = no line. A description that already holds the line is left as it is.
 */
internal object TicketReportLine {
    const val KEY = "board.ticket.reportLine"
    const val DEFAULT = "Update this ticket (comment) after completion or if you have issues."

    fun apply(description: String?, setting: String?): String? {
        val line = (setting ?: DEFAULT).trim()
        if (line.isEmpty()) return description
        if (description != null && description.contains(line)) return description
        return if (description.isNullOrBlank()) line else description.trimEnd() + "\n\n" + line
    }
}
