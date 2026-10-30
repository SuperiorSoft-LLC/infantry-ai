package com.superiorsoftllc.agenthq.daemon.api.board

/**
 * RUNTIME-LOAD-4 (BOARD_API_VERSION 1.3.0, additive): the board's two pure rules every client of it shares, here so the CLI
 * (`ticket move`, every `<ticketId>` argument) and a board plugin's tools cannot drift: which ticket a short id names
 * ([TicketRefs]) and where in its column a move puts a ticket ([TicketPlacement]). No daemon class: a ticket is seen as a
 * [PlacedTicket] (its id, column and order).
 */
data class PlacedTicket(val id: String, val status: String, val position: Int)

object TicketRefs {
    /** The constant scheme every ticket id starts with; the random tail after it is what identifies a ticket. */
    const val SCHEME = "ticket-"

    /**
     * The tickets of [items] that [prefix] names, by the first tier that matches any: 1. the exact id; 2. a PREFIX of the id
     * after [SCHEME] (`63df`, `63df1aba`, `ticket-63df`), what the board prints is that 8-char tail; 3. a SUFFIX of the id.
     * Empty = none; more than one = ambiguous.
     */
    fun <T> match(prefix: String, items: List<T>, id: (T) -> String): List<T> {
        val wanted = prefix.trim()
        if (wanted.isEmpty()) return emptyList()
        items.filter { id(it) == wanted }.takeIf { it.isNotEmpty() }?.let { return it }
        val head = wanted.removePrefix(SCHEME)
        if (head.isNotEmpty()) items.filter { id(it).removePrefix(SCHEME).startsWith(head) }.takeIf { it.isNotEmpty() }?.let { return it }
        return items.filter { id(it).endsWith(wanted) }
    }
}

/**
 * Where in its column a `ticket_move` puts a ticket: ONE of [top], [bottom], [before] / [after] (just above / below that ticket)
 * or [position] (0 = top), turned into the `position` the daemon takes. The daemon's order field is the ticket's position
 * (0 = top), the same one a drag on the board writes.
 */
class TicketPlacement(
    val top: Boolean = false,
    val bottom: Boolean = false,
    val before: String? = null,
    val after: String? = null,
    val position: Int? = null,
) {
    /** How many placements were named; more than one is a usage error. */
    val named: Int get() = listOf(top, bottom, before != null, after != null, position != null).count { it }

    /**
     * The index to send, or the reason it cannot be had. The daemon counts in the column AFTER the ticket is lifted out, so a
     * ticket moving down inside its own column lands one place above the target's current index. [status] is the column asked
     * for (null = stay); [board] is where `before` / `after` find their target.
     */
    fun resolve(ticket: PlacedTicket, status: String?, board: List<PlacedTicket>): Result<Int> {
        if (named > 1) return fail("name only one of top, bottom, before, after, position")
        if (top) return Result.success(0)
        if (bottom) return Result.success(Int.MAX_VALUE)
        if (position != null) {
            return if (position < 0) fail("position must be 0 or more, was $position") else Result.success(position)
        }
        val ref = before ?: after ?: return fail("no placement given")
        val word = if (before != null) "before" else "after"
        val matches = TicketRefs.match(ref.trim(), board) { it.id }
        val target = when (matches.size) {
            1 -> matches.single()
            0 -> return fail("no ticket $ref on this board (the $word target)")
            else -> return fail("ticket id '$ref' is ambiguous; use a longer id or the full id")
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
