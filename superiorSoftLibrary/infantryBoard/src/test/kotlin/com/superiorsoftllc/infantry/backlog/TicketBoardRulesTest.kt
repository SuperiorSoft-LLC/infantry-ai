// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.persistence.TicketLinkRow
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.protocol.TicketLink
import com.superiorsoftllc.infantry.protocol.TicketLinkRequest
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketReorder
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.protocol.TicketStatus.DONE
import com.superiorsoftllc.infantry.protocol.TicketStatus.IN_PROGRESS
import com.superiorsoftllc.infantry.protocol.TicketStatus.PENDING
import com.superiorsoftllc.infantry.protocol.TicketStatus.TODO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DP-5f-5: the backlog's rules (moved from infantry's TicketBoardTest), run on the board-side [InMemoryBoardHost]. */
class TicketBoardRulesTest {

    private lateinit var host: InMemoryBoardHost

    /** The links as the fake store holds them (what the SQL tests read from the real one). */
    private fun storedLinks() = host.storage.tables.tickets.links().map { TicketLinkRow(it.ticketId, it.relatedId, it.type, it.createdAt) }

    /** A clock that ticks once per operation, and ids `a`, `b`, `c`... in creation order. */
    private fun board(): Pair<TicketBoard, MutableList<BoardEvent>> {
        val ids = ('a'..'z').map { it.toString() }.iterator()
        var tick = 0L
        val events = mutableListOf<BoardEvent>()
        val board = TicketBoard(InMemoryBoardHost { tick }.also { host = it }, clock = { ++tick }, idFactory = { ids.next() })
        board.eventSink = { events += it }
        return board to events
    }

    private fun TicketBoard.new(title: String, type: String? = null): Ticket =
        save(TicketSave(title = title, ticketType = type)).ticket

    private fun TicketBoard.link(a: String, type: String, b: String) = link(TicketLinkRequest(a, b, type))
    private fun TicketBoard.move(id: String, status: String? = null, position: Int? = null, wontFix: Boolean? = null) =
        move(TicketMove(id, status, position, wontFix))

    private fun TicketBoard.column(status: String): List<String> = list().filter { it.status == status }.map { it.id }

    /** Positions run 0, 1, 2... in every column, and the list is in board order. */
    private fun assertContiguous(board: TicketBoard) {
        val tickets = board.list()
        for ((status, column) in tickets.groupBy { it.status }) {
            assertEquals((0 until column.size).toList(), column.map { it.position }, "column $status is not contiguous")
        }
        val order = listOf(TODO, IN_PROGRESS, PENDING, DONE)
        assertEquals(tickets.sortedBy { order.indexOf(it.status) }.map { it.id }, tickets.map { it.id }, "board order")
    }

    // --- creating and editing ------------------------------------------------------------------------

    @Test
    fun aNewTicketGoesToTheBottomOfTodoAndIsStored() {
        val (board, _) = board()
        val a = board.new("  Add login  ", type = "Enhancement")
        board.new("Fix the build")
        board.new("Write the docs")

        assertEquals("a", a.id)
        assertEquals("Add login", a.title, "trimmed")
        assertEquals("enhancement", a.ticketType, "normalised to the wire's lowercase")
        assertEquals("", a.description)
        assertEquals(TODO, a.status)
        assertEquals(a.createdAt, a.updatedAt)
        assertFalse(a.wontFix)
        assertEquals(listOf("a", "b", "c"), board.column(TODO))
        assertContiguous(board)
    }

    @Test
    fun aRefusedSaveSaysWhyAndStoresNothing() {
        val (board, events) = board()
        fun refused(request: TicketSave) = assertFailsWith<IllegalArgumentException> { board.save(request) }.message!!

        assertTrue(refused(TicketSave(description = "no title")).contains("title is required"))
        assertTrue(refused(TicketSave(title = "   ")).contains("title is blank"))
        assertTrue(refused(TicketSave(title = "x".repeat(201))).contains("longer than 200"))
        assertTrue(refused(TicketSave(title = "two\nlines")).contains("control character"))
        assertTrue(refused(TicketSave(title = "t", description = "d".repeat(100_001))).contains("longer than 100000"))
        val badType = refused(TicketSave(title = "t", ticketType = "epic"))
        assertTrue(badType.contains("'epic' is not a ticket type") && badType.contains("hardening"), badType)
        val unknown = refused(TicketSave(id = "zz", title = "edit of a deleted ticket"))
        assertTrue(unknown.startsWith("no ticket 'zz'"), unknown)

        assertTrue(board.list().isEmpty())
        assertTrue(events.isEmpty(), "no push for a refusal")
    }

    @Test
    fun anEditChangesOnlyWhatItCarries() {
        val (board, _) = board()
        val a = board.save(TicketSave(title = "Add login", description = "OAuth first", ticketType = "enhancement")).ticket
        board.new("Other")

        val retitled = board.save(TicketSave(id = "a", title = "Add login and logout")).ticket
        assertEquals("OAuth first", retitled.description, "a null field is left as it is")
        assertEquals("enhancement", retitled.ticketType)
        assertEquals(a.createdAt, retitled.createdAt)
        assertTrue(retitled.updatedAt > a.updatedAt)

        val cleared = board.save(TicketSave(id = "a", description = "", ticketType = "")).ticket
        assertEquals("", cleared.description, "an empty description clears it")
        assertNull(cleared.ticketType, "an empty ticketType clears it")
        assertEquals("Add login and logout", cleared.title)
        assertEquals(TODO to 0, cleared.status to cleared.position, "an edit never moves a ticket")
    }

    // --- columns and positions -------------------------------------------------------------------------

    @Test
    fun movesPlaceTicketsAndKeepEveryColumnContiguous() {
        val (board, _) = board()
        listOf("A", "B", "C", "D").forEach { board.new(it) }

        assertEquals(DONE to 0, board.move("b", DONE).ticket.let { it.status to it.position }, "the bottom of done")
        assertEquals(listOf("a", "c", "d"), board.column(TODO))
        assertContiguous(board)

        board.move("c", DONE, position = 0)
        assertEquals(listOf("c", "b"), board.column(DONE), "a position places it")

        board.move("d", position = 0)
        assertEquals(listOf("d", "a"), board.column(TODO), "a position alone moves it inside its column")

        board.move("b", TODO, position = 99)
        assertEquals(listOf("d", "a", "b"), board.column(TODO), "a position past the end is the bottom")
        assertContiguous(board)

        val negative = assertFailsWith<IllegalArgumentException> { board.move("a", position = -1) }
        assertTrue(negative.message!!.contains("0 or more"))
        val badColumn = assertFailsWith<IllegalArgumentException> { board.move("a", "in_review") }
        assertTrue(badColumn.message!!.contains("'in_review' is not a column"), badColumn.message)
        assertTrue(assertFailsWith<IllegalArgumentException> { board.move("zz", DONE) }.message!!.startsWith("no ticket 'zz'"))
    }

    // `startingIsRefusedUntilStory93` was REMOVED by story 9.3: starting is no longer refused, and the
    // internal TicketBoard/handOut split must not be pinned. Starting is proven at the DAEMON level
    // instead (TicketStartDaemonTest / TicketStartQueueDaemonTest), over the real wire.

    // --- the derived pending column ------------------------------------------------------------------

    @Test
    fun aBlockedByLinkHoldsATodoTicketAndClosingThePrerequisiteReleasesIt() {
        val (board, _) = board()
        listOf("A", "B", "C").forEach { board.new(it) }

        val held = board.link("a", "blocked_by", "b").ticket
        assertEquals(PENDING, held.status, "a todo ticket with an open prerequisite is pending")
        assertEquals(listOf("b"), held.heldBy)
        assertEquals("waiting for b", held.holdReason)
        assertEquals(listOf(TicketLink("blocked_by", "b")), held.links)
        assertEquals(listOf(TicketLink("blocks", "a")), board.get("b").links, "the other side reads it in reverse")
        assertEquals(listOf("b", "c"), board.column(TODO))
        assertContiguous(board)

        board.move("b", DONE)
        val released = board.get("a")
        assertEquals(TODO, released.status, "closing the last prerequisite releases it in the same change")
        assertEquals(listOf("c", "a"), board.column(TODO), "a ticket the daemon moves goes to the bottom")
        assertTrue(released.heldBy.isEmpty())
        assertNull(released.holdReason)
        assertContiguous(board)
    }

    @Test
    fun pendingIsNeitherEnteredNorLeftByHand() {
        val (board, _) = board()
        listOf("A", "B", "C").forEach { board.new(it) }

        val noPrerequisite = assertFailsWith<IllegalArgumentException> { board.move("c", PENDING) }
        assertTrue(noPrerequisite.message!!.contains("no open prerequisite"), noPrerequisite.message)

        board.link("a", "blocked_by", "b")
        board.link("c", "blocked_by", "b")
        for (target in listOf(TODO, DONE)) {
            val refusal = assertFailsWith<IllegalArgumentException> { board.move("a", target) }
            assertTrue(refusal.message!!.contains("is held (waiting for b)"), refusal.message)
        }
        assertFailsWith<IllegalArgumentException> { board.move("a", DONE, wontFix = true) }
        assertEquals(PENDING, board.get("a").status)

        // Reordering inside pending is fine: the ticket stays held where it is.
        board.move("c", position = 0)
        assertEquals(listOf("c", "a"), board.column(PENDING))
    }

    @Test
    fun reopeningAPrerequisiteReholdsTodoDependentsAndLeavesStartedOnesAlone() {
        val (board, _) = board()
        listOf("A", "B", "C").forEach { board.new(it) }
        board.link("a", "blocked_by", "b")
        board.link("c", "blocked_by", "b")
        board.move("b", DONE)
        board.move("c", DONE) // c is finished (in 9.3 it could be in_progress: equally started)

        board.move("b", TODO)
        assertEquals(PENDING, board.get("a").status, "a todo dependent is held again")
        val finished = board.get("c")
        assertEquals(DONE, finished.status, "a started dependent is never moved by the daemon")
        assertEquals(listOf("b"), finished.heldBy, "but it says what reopened under it")

        // A held ticket moved back to todo by hand lands where the rule puts it: pending.
        board.move("c", TODO)
        assertEquals(PENDING, board.get("c").status)
        assertEquals(listOf("a", "c"), board.column(PENDING))
    }

    @Test
    fun aPrerequisiteClosedAsWontFixKeepsItsDependentsHeld() {
        val (board, _) = board()
        listOf("A", "B", "C").forEach { board.new(it) }
        board.link("a", "blocked_by", "b")
        board.link("a", "blocked_by", "c")

        val noDone = assertFailsWith<IllegalArgumentException> { board.move("b", wontFix = true) }
        assertTrue(noDone.message!!.contains("status done"), noDone.message)

        val declined = board.move("b", DONE, wontFix = true).ticket
        assertTrue(declined.wontFix)
        board.move("c", DONE)
        val held = board.get("a")
        assertEquals(PENDING, held.status, "won't fix does not release")
        assertEquals(listOf("b"), held.heldBy)
        assertEquals("b was closed as won't fix", held.holdReason)

        // Closed as plain done after all: released.
        board.move("b", wontFix = false)
        assertEquals(TODO, board.get("a").status)

        // Declining a prerequisite that was already done holds a todo dependent again.
        board.move("b", wontFix = true)
        assertEquals(PENDING, board.get("a").status)

        // Reopening clears the flag.
        val reopened = board.move("b", TODO).ticket
        assertFalse(reopened.wontFix)
        assertEquals("waiting for b", board.get("a").holdReason)
    }

    // --- links ------------------------------------------------------------------------------------------

    @Test
    fun theEightLinkTypesAreStoredInFourDirections() {
        val (board, _) = board()
        listOf("A", "B", "C", "D", "E").forEach { board.new(it) }
        board.move("b", DONE)
        board.move("c", DONE)

        board.link("b", "blocks", "a")          // stored: a blocked_by b
        board.link("a", "depends_on", "c")      // stored: a blocked_by c
        board.link("d", "parent_of", "a")       // stored: a child_of d
        board.link("e", "duplicated_by", "d")   // stored: d duplicates e
        board.link("e", "relates_to", "a")      // stored: a relates_to e (smaller id first)
        board.link("a", "relates_to", "e")      // the same link: nothing new

        assertEquals(
            setOf(
                TicketLinkRow("a", "b", "blocked_by"),
                TicketLinkRow("a", "c", "blocked_by"),
                TicketLinkRow("a", "d", "child_of"),
                TicketLinkRow("d", "e", "duplicates"),
                TicketLinkRow("a", "e", "relates_to"),
            ),
            storedLinks().map { it.copy(createdAt = 0) }.toSet(),
        )
        assertEquals(
            listOf(
                TicketLink("blocked_by", "b"),
                TicketLink("blocked_by", "c"),
                TicketLink("child_of", "d"),
                TicketLink("relates_to", "e"),
            ),
            board.get("a").links,
            "oldest first, from a's side",
        )
        assertEquals(listOf(TicketLink("parent_of", "a"), TicketLink("duplicates", "e")), board.get("d").links)
        assertEquals(listOf(TicketLink("duplicated_by", "d"), TicketLink("relates_to", "a")), board.get("e").links)

        val badType = assertFailsWith<IllegalArgumentException> { board.link("a", "mentions", "b") }
        assertTrue(badType.message!!.contains("'mentions' is not a link type"), badType.message)
    }

    @Test
    fun childOfIsStructureAndNeverHolds() {
        val (board, _) = board()
        listOf("Epic", "Story").forEach { board.new(it) }
        val child = board.link("b", "child_of", "a").ticket
        assertEquals(TODO, child.status)
        assertTrue(child.heldBy.isEmpty())
    }

    @Test
    fun cyclesAndSelfLinksAreRefusedAndWriteNothing() {
        val (board, events) = board()
        listOf("A", "B", "C").forEach { board.new(it) }
        board.link("a", "blocked_by", "b")
        board.link("b", "blocked_by", "c")
        board.link("a", "child_of", "b")
        val before = board.list()
        events.clear()

        val cycle = assertFailsWith<IllegalArgumentException> { board.link("c", "blocked_by", "a") }
        assertEquals("c blocked_by a would close a cycle: a blocked_by b blocked_by c already", cycle.message)
        // The same edge asked for the other way round is the same cycle.
        assertFailsWith<IllegalArgumentException> { board.link("a", "blocks", "c") }
        assertFailsWith<IllegalArgumentException> { board.link("b", "child_of", "a") }
        val self = assertFailsWith<IllegalArgumentException> { board.link("a", "relates_to", "a") }
        assertTrue(self.message!!.contains("itself"))
        // A cycle of another type is not one: relates_to and duplicates order nothing.
        board.link("c", "duplicates", "a")

        assertEquals(before.map { it.id to it.status }, board.list().map { it.id to it.status })
        assertEquals(4, storedLinks().size, "only the duplicates link was added")
    }

    @Test
    fun unlinkingReleasesAndAMissingLinkIsRefused() {
        val (board, _) = board()
        listOf("A", "B").forEach { board.new(it) }
        board.link("a", "blocked_by", "b")

        val released = board.unlink(TicketLinkRequest("b", "a", "blocks")).ticket
        assertEquals("b", released.id, "answered with the ticket the request named first")
        assertEquals(TODO, board.get("a").status)
        assertTrue(board.get("a").links.isEmpty())

        val missing = assertFailsWith<IllegalArgumentException> { board.unlink(TicketLinkRequest("a", "b", "blocked_by")) }
        assertEquals("no link 'a blocked_by b'", missing.message)
    }

    // --- delete and reorder ---------------------------------------------------------------------------

    @Test
    fun deletingATicketRemovesItsLinksAndReleasesWhatItHeld() {
        val (board, _) = board()
        listOf("A", "B", "C", "D").forEach { board.new(it) }
        board.link("a", "blocked_by", "b")
        board.link("c", "relates_to", "b")

        val after = board.delete("b").tickets
        assertEquals(listOf("c", "d", "a"), after.filter { it.status == TODO }.map { it.id }, "a released, at the bottom")
        assertTrue(after.all { it.links.isEmpty() })
        assertTrue(storedLinks().isEmpty(), "the database cascaded the links")
        assertContiguous(board)

        val missing = assertFailsWith<IllegalArgumentException> { board.delete("b") }
        assertTrue(missing.message!!.startsWith("no ticket 'b'"))
    }

    @Test
    fun aReorderPutsTheListedTicketsOnTopAndKeepsTheRestInOrder() {
        val (board, _) = board()
        listOf("A", "B", "C", "D", "E").forEach { board.new(it) }
        board.move("e", DONE)

        board.reorder(TicketReorder(TODO, listOf("c", "a")))
        assertEquals(listOf("c", "a", "b", "d"), board.column(TODO))

        // Without a status, each ticket is reordered inside its own column.
        board.reorder(TicketReorder(ids = listOf("d", "e")))
        assertEquals(listOf("d", "c", "a", "b"), board.column(TODO))
        assertEquals(listOf("e"), board.column(DONE))
        assertContiguous(board)

        val stray = assertFailsWith<IllegalArgumentException> { board.reorder(TicketReorder(TODO, listOf("a", "e"))) }
        assertTrue(stray.message!!.startsWith("e is not in todo"), stray.message)
        assertFailsWith<IllegalArgumentException> { board.reorder(TicketReorder(TODO, listOf("a", "a"))) }
        assertFailsWith<IllegalArgumentException> { board.reorder(TicketReorder(TODO, emptyList())) }
        assertFailsWith<IllegalArgumentException> { board.reorder(TicketReorder(TODO, listOf("zz"))) }
        assertEquals(listOf("d", "c", "a", "b"), board.column(TODO), "a refused reorder moves nothing")
    }

    // --- pushes and timestamps ---------------------------------------------------------------------------

    @Test
    fun everyCommittedChangeIsPushedOnceAndANoOpIsNot() {
        val (board, events) = board()
        val created = board.save(TicketSave(title = "A"))
        board.new("B")
        assertTrue(created.pushed)
        assertEquals(listOf("a"), (events[0] as BoardEvent.Changed).tickets.map { it.id })

        events.clear()
        val linked = board.link("a", "blocked_by", "b")
        assertTrue(linked.pushed)
        val pushed = (events.single() as BoardEvent.Changed).tickets
        assertEquals(listOf("a", "b"), pushed.map { it.id }, "the ticket the request named first, then the rest")
        assertEquals(linked.ticket, pushed.first(), "the push IS the answer")

        events.clear()
        val again = board.link("a", "blocked_by", "b")
        assertFalse(again.pushed, "an existing link changes nothing")
        assertEquals(PENDING, again.ticket.status)
        assertFalse(board.move("a").pushed, "a move to where it is changes nothing")
        assertTrue(events.isEmpty())

        val reordered = board.reorder(TicketReorder(ids = listOf("b")))
        assertFalse(reordered.pushed, "b is already on top of todo")
        assertTrue(board.delete("b").pushed)
        val replaced = events.single() as BoardEvent.Replaced
        assertEquals(listOf("a"), replaced.tickets.map { it.id })
        assertEquals(TODO, replaced.tickets.single().status, "the push carries the released ticket")
    }

    @Test
    fun updatedAtMovesForATicketsOwnChangesOnly() {
        val (board, _) = board()
        listOf("A", "B", "C").forEach { board.new(it) }
        val b = board.get("b")
        val c = board.get("c")

        val moved = board.move("a", DONE).ticket
        assertTrue(moved.updatedAt > board.get("b").createdAt)
        assertEquals(0, board.get("b").position, "b moved up a place...")
        assertEquals(b.updatedAt, board.get("b").updatedAt, "...without being changed itself")

        board.link("c", "relates_to", "b")
        assertEquals(c.updatedAt, board.get("c").updatedAt, "a link is not an edit of the ticket")

        val repositioned = board.move("c", position = 0).ticket
        assertTrue(repositioned.updatedAt > c.updatedAt, "a place a client asked for is its own change")
        assertEquals(b.updatedAt, board.get("b").updatedAt)
    }
}
