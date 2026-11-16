// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.board.BoardResult
import com.superiorsoftllc.infantry.daemon.api.board.TicketQuery
import com.superiorsoftllc.infantry.daemon.api.board.TicketEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SCRIPT-5: the board's typed ticket events (a ticket entering a column) published as the named event `ticket.moved` on the SCRIPT-3 bus,
 * like `ticket.commented` (SCRIPT-3b). Payload: `ticketId`, `from` (null when created), `to` (the lane entered), `kind` (created, moved,
 * started, done) and `seq`. Scripts hang `on lane enter <lane>` / `lane exit <lane>` hooks (every committed move emits, the daemon's own included) on it; the hook runner adds branch, tip and worker session itself.
 * A failed publish never disturbs the board.
 */
object TicketMovedEvent {
    const val NAME = "ticket.moved"

    fun payload(e: TicketEvent, agentQueueNext: String? = null, projectId: String? = null): String = buildJsonObject {
        put("ticketId", e.ticketId)
        // HOOK-LANE-DONE-1: the event names its own project, so a hook never depends on a second ticket read (which can fail or time out
        // exactly when the daemon moves a ticket itself) to learn whose event it is.
        projectId?.let { put("projectId", it) }
        put("kind", e.kind)
        e.from?.let { put("from", it) }
        e.to?.let { put("to", it) }
        put("seq", e.seq)
        agentQueueNext?.let { put("agentQueueNext", it) }
    }.toString()

    /** AGENT-TASKS-3: the head of the queue of the agent that owned the done ticket (a ticket id), or null when it has none. */
    fun agentQueueNext(api: DaemonApi, ticketId: String): String? = runCatching {
        val by = Caller(owner = true)
        val done = (api.tickets.get(ticketId, by) as? BoardResult.Ok)?.value ?: return null
        val agent = done.agentId?.takeUnless { it.startsWith("role:") } ?: return null
        val page = (api.tickets.list(by, TicketQuery(projectId = done.projectId, status = setOf("todo"))) as? BoardResult.Ok)?.value ?: return null
        page.items.filter { it.queuedAgent == agent }.minByOrNull { it.queueRank ?: Int.MAX_VALUE }?.id
    }.getOrNull()

    /** The ticket's project, read in-process from the board; null when it cannot be read (the hook runner then falls back to its own read). */
    fun projectOf(api: DaemonApi, ticketId: String): String? = runCatching {
        ((api.tickets.get(ticketId, Caller(owner = true)) as? BoardResult.Ok)?.value)?.projectId
    }.getOrNull()

    /** Subscribes to the ticket events; the returned handle ends the subscription. */
    fun attach(api: DaemonApi): AutoCloseable {
        // HOOK-DUP-1: the log redelivers an event whose listener call failed or timed out after the publish went out; one seq publishes once.
        var lastSeq = 0L
        return api.events.onTicket("script-ticket-moved") { e ->
            if (e.isTransition && e.seq > lastSeq) {
                lastSeq = e.seq
                val project = projectOf(api, e.ticketId)
                runCatching { api.events.publish(NAME, payload(e, if (e.to == "done") agentQueueNext(api, e.ticketId) else null, project)) }
                    .onFailure { System.err.println("[infantry] ticket.moved publish failed for ${e.ticketId} seq ${e.seq}: $it") }
            }
        }
    }
}
