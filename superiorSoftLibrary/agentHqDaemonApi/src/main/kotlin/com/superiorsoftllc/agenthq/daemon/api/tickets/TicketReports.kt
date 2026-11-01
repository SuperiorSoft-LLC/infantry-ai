package com.superiorsoftllc.agenthq.daemon.api.tickets

/*
 * TICKET-COMMENTS-4 (DaemonApi 1.24.0, additive): the ticket comment port core needs. The board plugin publishes it; core's idle watch
 * finds it (`services.find(TicketReports::class)`), so core holds no board code.
 */
interface TicketReports {
    /** True when a comment by [sessionId] (any kind) exists on [ticketId] at or after [sinceMs]. */
    fun commentedSince(ticketId: String, sessionId: String, sinceMs: Long): Boolean

    /** Posts an OPEN `issue` comment by the daemon on [ticketId] (notifying its notify list); false when it could not be posted. */
    fun postDaemonIssue(ticketId: String, body: String): Boolean
}
