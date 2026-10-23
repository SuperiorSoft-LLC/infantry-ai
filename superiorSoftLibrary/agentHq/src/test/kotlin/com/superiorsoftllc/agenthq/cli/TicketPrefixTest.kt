package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.protocol.Ticket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Deterministic coverage of [matchTicketPrefix] with PINNED ids. The acceptance test
 * (`CliAcceptanceTest.ticketShowRefusesAnAmbiguousPrefixAndListsEveryMatch`) reaches the same branch
 * through a real daemon, but there the colliding pair is whichever random ids happen to share a prefix.
 * Here the two ids are chosen so they collide on purpose, so the ambiguity contract never depends on luck.
 */
class TicketPrefixTest {

    private fun ticket(id: String, title: String) = Ticket(
        id = id,
        title = title,
        status = "todo",
        position = 0,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private val collidingA = ticket("ticket-aaaa1a2b", "First colliding ticket")
    private val collidingB = ticket("ticket-bbbb1f2b", "Second colliding ticket")
    private val other = ticket("ticket-cccc2b00", "Unrelated ticket")
    private val board = listOf(collidingA, collidingB, other)

    @Test
    fun aSharedShortPrefixIsAmbiguousAndListsEveryMatch() {
        // "2b" is a suffix both colliding ids share, with different heads.
        val match = matchTicketPrefix("2b", board)
        val ambiguous = assertIs<TicketPrefixMatch.Ambiguous>(match)
        assertEquals(listOf(collidingA, collidingB), ambiguous.tickets, "every ticket sharing the prefix must be listed")
    }

    @Test
    fun aFullIdMatchesOnlyItsOwnTicketEvenWhenItsShortPrefixIsShared() {
        assertEquals(
            TicketPrefixMatch.Unique(collidingA),
            matchTicketPrefix(collidingA.id, board),
            "a full id is a prefix of itself, and must resolve to exactly its own ticket",
        )
        assertEquals(TicketPrefixMatch.Unique(collidingB), matchTicketPrefix(collidingB.id, board))
    }

    @Test
    fun aLongerPrefixDisambiguatesACollidingPair() {
        // "a2b" narrows the shared "2b" down to the first ticket alone.
        assertEquals(TicketPrefixMatch.Unique(collidingA), matchTicketPrefix("a2b", board))
    }

    @Test
    fun aPrefixThatMatchesNothingIsNone() {
        assertEquals(TicketPrefixMatch.None, matchTicketPrefix("nosuchtk", board))
    }

    @Test
    fun anEmptyBoardMatchesNothing() {
        assertTrue(matchTicketPrefix("2b", emptyList()) == TicketPrefixMatch.None)
    }

    @Test
    fun twoIdsSharingTheOldEightCharPrefixPrintDistinctShortIdsAndResolveUniquely() {
        // The flake: both ids start "ticket-e", so the old head-8 short id was identical for them.
        val x = ticket("ticket-e1111111", "X")
        val y = ticket("ticket-e2222222", "Y")
        assertEquals(x.id.take(8), y.id.take(8), "fixture must collide under the old scheme")
        assertEquals("e1111111", shortTicketId(x.id))
        assertEquals("e2222222", shortTicketId(y.id))
        assertEquals(TicketPrefixMatch.Unique(x), matchTicketPrefix(shortTicketId(x.id), listOf(x, y)))
        assertEquals(TicketPrefixMatch.Unique(y), matchTicketPrefix(shortTicketId(y.id), listOf(x, y)))
    }
}
