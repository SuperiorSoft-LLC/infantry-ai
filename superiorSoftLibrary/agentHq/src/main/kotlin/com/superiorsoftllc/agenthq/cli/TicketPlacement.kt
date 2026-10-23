package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.protocol.Ticket

/**
 * Where in its column a `ticket_move` puts a ticket: the ONE place the CLI (`ticket move --top|--bottom|--before|--after|
 * --position`) and the MCP `agenthq_ticket_move` turn those words into the `position` the daemon takes. The daemon's
 * order field is `Ticket.position` (0 = top), the same one a drag on the board writes.
 */
internal class TicketPlacement(
    val top: Boolean = false,
    val bottom: Boolean = false,
    val before: String? = null,
    val after: String? = null,
    val position: Int? = null,
) {
    /** How many placements were named; more than one is a usage error. */
    val named: Int get() = listOf(top, bottom, before != null, after != null, position != null).count { it }

    /**
     * The index to send, or the reason it cannot be had. The daemon counts in the column AFTER the ticket is lifted
     * out, so a ticket moving down inside its own column lands one place above the target's current index.
     * [status] is the column asked for (null = stay).
     */
    fun resolve(ticket: Ticket, status: String?, board: List<Ticket>): Result<Int> {
        if (named > 1) return fail("name only one of top, bottom, before, after, position")
        if (top) return Result.success(0)
        if (bottom) return Result.success(Int.MAX_VALUE)
        if (position != null) {
            return if (position < 0) fail("position must be 0 or more, was $position") else Result.success(position)
        }
        val ref = before ?: after ?: return fail("no placement given")
        val word = if (before != null) "before" else "after"
        val target = when (val match = matchTicketPrefix(ref.trim(), board)) {
            is TicketPrefixMatch.Unique -> match.ticket
            TicketPrefixMatch.None -> return fail("no ticket $ref on this board (the $word target)")
            is TicketPrefixMatch.Ambiguous -> return fail("ticket id '$ref' is ambiguous; use a longer id or the full id")
        }
        if (target.id == ticket.id) return fail("a ticket cannot be moved $word itself")
        val column = status?.lowercase() ?: ticket.status
        if (target.status != column) {
            return fail("${target.id} is in ${target.status}, not $column: name the column or pick a ticket in it")
        }
        val lifted = if (ticket.status == column && ticket.position < target.position) 1 else 0
        return Result.success(target.position - lifted + if (after != null) 1 else 0)
    }

    private fun fail(reason: String): Result<Int> = Result.failure(IllegalArgumentException(reason))
}
