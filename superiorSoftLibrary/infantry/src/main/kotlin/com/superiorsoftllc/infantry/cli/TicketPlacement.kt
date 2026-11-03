// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.api.board.PlacedTicket
import com.superiorsoftllc.infantry.protocol.Ticket

/**
 * Where in its column a `ticket_move` puts a ticket (`ticket move --top|--bottom|--before|--after|--position`). RUNTIME-LOAD-4:
 * the rule itself is the API's [com.superiorsoftllc.infantry.daemon.api.board.TicketPlacement], which the board plugin's
 * `infantry_ticket_move` uses too; this only sees the CLI's wire tickets as [PlacedTicket]s.
 */
internal class TicketPlacement(
    top: Boolean = false,
    bottom: Boolean = false,
    before: String? = null,
    after: String? = null,
    position: Int? = null,
) {
    private val rule = com.superiorsoftllc.infantry.daemon.api.board.TicketPlacement(top, bottom, before, after, position)

    val before: String? get() = rule.before
    val after: String? get() = rule.after

    /** How many placements were named; more than one is a usage error. */
    val named: Int get() = rule.named

    /** The index to send, or the reason it cannot be had ([status] = the column asked for, null = stay). */
    fun resolve(ticket: Ticket, status: String?, board: List<Ticket>): Result<Int> = rule.resolve(ticket.placed(), status, board.map { it.placed() })

    private fun Ticket.placed() = PlacedTicket(id, status, position)
}
