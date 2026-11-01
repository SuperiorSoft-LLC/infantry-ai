package com.superiorsoftllc.agenthq.plugins.official.board.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.board.TicketEvent
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

    fun payload(e: TicketEvent): String = buildJsonObject {
        put("ticketId", e.ticketId)
        put("kind", e.kind)
        e.from?.let { put("from", it) }
        e.to?.let { put("to", it) }
        put("seq", e.seq)
    }.toString()

    /** Subscribes to the ticket events; the returned handle ends the subscription. */
    fun attach(api: DaemonApi): AutoCloseable =
        api.events.onTicket("script-ticket-moved") { e ->
            if (e.isTransition) runCatching { api.events.publish(NAME, payload(e)) }
        }
}
