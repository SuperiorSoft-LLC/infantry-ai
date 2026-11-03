// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.protocol.Ticket

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

/**
 * Matches [prefix] against the ids of [tickets], in tiers so the most specific reading wins:
 * 1. the exact full id;
 * 2. a HEAD prefix of the random tail, of any length, typed with or without the `ticket-` scheme
 *    (`63df`, `63df1aba`, `ticket-63df`): what the board and `tickets` print is that 8-char tail;
 * 3. a SUFFIX of the id (the historical short-id rule; a full id is a suffix of itself).
 * The first tier with any match decides: one match is [TicketPrefixMatch.Unique], several are
 * [TicketPrefixMatch.Ambiguous] so the caller can list them.
 */
internal fun matchTicketPrefix(prefix: String, tickets: List<Ticket>): TicketPrefixMatch =
    // RUNTIME-LOAD-4: the one rule, shared with the board plugin's tools (daemon.api.board.TicketRefs).
    outcome(com.superiorsoftllc.infantry.daemon.api.board.TicketRefs.match(prefix, tickets) { it.id })

/** The constant scheme every ticket id starts with; the random tail after it is what identifies a ticket. */
internal const val TICKET_ID_SCHEME = com.superiorsoftllc.infantry.daemon.api.board.TicketRefs.SCHEME

private fun outcome(matches: List<Ticket>): TicketPrefixMatch = when (matches.size) {
    0 -> TicketPrefixMatch.None
    1 -> TicketPrefixMatch.Unique(matches.single())
    else -> TicketPrefixMatch.Ambiguous(matches)
}
