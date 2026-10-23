package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.protocol.Ticket

/**
 * The outcome of matching a typed ticket prefix against the board: exactly one ticket, none at all,
 * or several that share the prefix. Only [Unique] is ever acted on; the other two are reported.
 */
internal sealed interface TicketPrefixMatch {
    data class Unique(val ticket: Ticket) : TicketPrefixMatch
    data object None : TicketPrefixMatch
    data class Ambiguous(val tickets: List<Ticket>) : TicketPrefixMatch
}

/**
 * Matches [prefix] against the ids of [tickets]. What `tickets` prints is the LAST 8 characters of an id
 * ([shortTicketId]): ids start with the constant `ticket-` plus a random tail, so the head carries almost
 * no entropy. A full id is a suffix of itself, so pasting the whole id matches its own ticket too.
 */
/** Width of the short ticket/epic/story id the CLI prints and accepts. Other id schemes keep their own. */
internal const val TICKET_SHORT_ID_LEN = 8

/** The printed short form of a ticket-scheme id: its last [TICKET_SHORT_ID_LEN] characters. */
internal fun shortTicketId(id: String): String = id.takeLast(TICKET_SHORT_ID_LEN)

internal fun matchTicketPrefix(prefix: String, tickets: List<Ticket>): TicketPrefixMatch {
    val matches = tickets.filter { it.id.endsWith(prefix) }
    return when (matches.size) {
        0 -> TicketPrefixMatch.None
        1 -> TicketPrefixMatch.Unique(matches.single())
        else -> TicketPrefixMatch.Ambiguous(matches)
    }
}
