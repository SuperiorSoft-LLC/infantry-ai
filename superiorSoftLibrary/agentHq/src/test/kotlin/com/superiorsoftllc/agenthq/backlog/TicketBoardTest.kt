package com.superiorsoftllc.agenthq.backlog

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.mailbox.FakeAgentProvider
import com.superiorsoftllc.agenthq.mailbox.ScriptedRunner
import com.superiorsoftllc.agenthq.mailbox.tempSessionStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.persistence.TicketLinkRow
import com.superiorsoftllc.agenthq.mvp.ProjectBaseCheck
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.Project
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketLink
import com.superiorsoftllc.agenthq.protocol.TicketLinkRequest
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketReorder
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.protocol.TicketStatus.DONE
import com.superiorsoftllc.agenthq.protocol.TicketStatus.IN_PROGRESS
import com.superiorsoftllc.agenthq.protocol.TicketStatus.PENDING
import com.superiorsoftllc.agenthq.protocol.TicketStatus.TODO
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.usage.GateDecision
import com.superiorsoftllc.agenthq.worktree.TicketBaseRef
import com.superiorsoftllc.agenthq.worktree.TicketBranches
import com.superiorsoftllc.agenthq.worktree.newGitRepo
import com.superiorsoftllc.agenthq.worktree.testGit
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract 1.5.0 (story 9.2): the backlog's rules, over the real SQLite store.
 *
 * Columns and positions are the daemon's (contiguous, 0 at the top, new and daemon-moved tickets at the
 * bottom); `pending` is derived from `blocked_by` links and never set by hand; won't fix does not release;
 * cycles and self-links are refused; every committed change reaches [TicketBoard.eventSink], and a refusal
 * writes nothing.
 */
class TicketBoardTest {

    private val dir: File = Files.createTempDirectory("agenthq-backlog").toFile()
    private val dbPath = File(dir, "agenthq.db").absolutePath
    private val opened = mutableListOf<SqlAgentHqStore>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private fun openStore(): SqlAgentHqStore = SqlAgentHqStore.open(dbPath).also { opened += it }

    /** A clock that ticks once per operation, and ids `a`, `b`, `c`... in creation order. */
    private fun board(store: SqlAgentHqStore = openStore()): Pair<TicketBoard, MutableList<BoardEvent>> {
        val ids = ('a'..'z').map { it.toString() }.iterator()
        var tick = 0L
        val events = mutableListOf<BoardEvent>()
        val board = TicketBoard(store, clock = { ++tick }, idFactory = { ids.next() })
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

        // The board is the database's: a fresh store over the same file sees the same tickets.
        val (reopened, _) = board(openStore())
        assertEquals(board.list(), reopened.list())
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
            opened.first().tickets.links().map { it.copy(createdAt = 0) }.toSet(),
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
        assertEquals(4, opened.first().tickets.links().size, "only the duplicates link was added")
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
        assertTrue(opened.first().tickets.links().isEmpty(), "the database cascaded the links")
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

    // --- hand-out and session events -----------------------------------------------------------------

    /**
     * H1, lock order: the board lock is taken before the session manager's, never after. A session event is
     * emitted while the session manager holds its own lock, so it must not wait for the board, even when it
     * lands in the middle of a ticket hand-out (which holds the board lock and then needs the session
     * manager's). The latches hold each side at that point, so the interleaving is the same on every run.
     */
    @Test
    fun aHandOutCompletesWhileAnotherSessionsEventArrives() {
        val store = openStore()
        val manager = SessionManager(
            FakeAgentProvider(),
            ScriptedRunner(autoExit = false),
            tempSessionStore(),
            killGraceMs = 50,
            defaultWorkingDir = dir,
        )
        val profiles = AgentProfiles(store.agents, manager)
        val worker = profiles.save(AgentProfile(name = "Backend", role = "backend"))
        val ids = ('a'..'z').map { it.toString() }.iterator()
        val board = TicketBoard(store, idFactory = { ids.next() }, agents = profiles, sessions = manager)
        val ticket = board.save(TicketSave(title = "Add login", agentId = worker.id)).ticket

        // The hand-out reaches the launch gate inside the board lock; the other session's event is emitted
        // inside the session manager's lock. Each side waits until the other one holds its lock.
        val inHandOut = CountDownLatch(1)
        val eventEmitted = CountDownLatch(1)
        manager.launchGate = { _, _, _ ->
            inHandOut.countDown()
            eventEmitted.await(2, TimeUnit.SECONDS)
            GateDecision.Allow
        }
        manager.eventSink = { event ->
            eventEmitted.countDown()
            board.onSessionEvent(event) // the daemon's fourth arm
        }

        var answer: TicketAnswer? = null
        val handOut = Thread { answer = board.move(ticket.id, IN_PROGRESS) }.apply { isDaemon = true }
        val otherSession = Thread {
            inHandOut.await(2, TimeUnit.SECONDS)
            manager.startNew("another session's work")
        }.apply { isDaemon = true }
        handOut.start()
        otherSession.start()

        handOut.join(2_000)
        assertEquals(0L, eventEmitted.count, "the other session's event never reached the session manager's sink")
        assertFalse(
            handOut.isAlive,
            "handOut did not complete within 2 s (handOut ${handOut.state}, other session ${otherSession.state})",
        )
        val started = checkNotNull(answer) { "handOut returned no answer" }
        assertEquals(IN_PROGRESS, started.ticket.status)
        assertEquals("started", started.ticket.start?.reason)
    }

    /**
     * BUG-TriggerRunIds: the hand-out spawns the session BEFORE the commit that records it (the commit needs the
     * session id), so a commit that throws -- SQLITE_BUSY_SNAPSHOT, a full disk -- must not leave that session
     * running against a ticket the board still files as todo. The session is killed and the failure rethrown.
     */
    @Test
    fun aHandOutWhoseCommitThrowsLeavesNoLiveSession() {
        val real = openStore()
        var failTransactions = false
        val store = object : com.superiorsoftllc.agenthq.persistence.AgentHqStore by real {
            override fun <T> transaction(block: () -> T): T {
                if (failTransactions) throw IllegalStateException("forced commit failure")
                return real.transaction(block)
            }
            // The commit is a write-first transaction (BUG-BoardCommitImmediate); it must fail the same way.
            override fun <T> writeTransaction(block: () -> T): T {
                if (failTransactions) throw IllegalStateException("forced commit failure")
                return real.writeTransaction(block)
            }
        }
        val manager = SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = false), tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir)
        val profiles = AgentProfiles(real.agents, manager)
        val worker = profiles.save(AgentProfile(name = "Backend", role = "backend"))
        val ids = ('a'..'z').map { it.toString() }.iterator()
        // A non-blank prompt, so startNew starts a run (a blank one leaves the session IDLE, never live).
        val prompting = object : com.superiorsoftllc.agenthq.context.TicketContext {
            override fun compose(input: com.superiorsoftllc.agenthq.context.ContextInput) =
                com.superiorsoftllc.agenthq.context.ComposedContext("Ship ${input.title}.", emptyList())
            override fun effective(override: Boolean?, skillsOverride: Boolean?): com.superiorsoftllc.agenthq.protocol.EffectiveContext? = null
        }
        val board = TicketBoard(store, idFactory = { ids.next() }, context = prompting, agents = profiles, sessions = manager)
        val ticket = board.save(TicketSave(title = "Add login", agentId = worker.id)).ticket
        // The launch gate runs inside startNew, after every read the hand-out makes: the next transaction is its commit.
        manager.launchGate = { _, _, _ -> failTransactions = true; GateDecision.Allow }

        val thrown = runCatching { board.move(ticket.id, IN_PROGRESS) }.exceptionOrNull()

        failTransactions = false
        assertEquals("forced commit failure", thrown?.message, "the commit failure must surface to the caller")
        val spawned = manager.list().filter { it.ticketId == ticket.id }
        assertEquals(1, spawned.size, "the hand-out must have spawned exactly one session before its commit")
        assertFalse(spawned.single().live, "the commit failed, yet the session it spawned is still live: ${spawned.single().state}")
        assertEquals(TODO, board.list().single { it.id == ticket.id }.status)
    }

    /**
     * P9.9, the H1 lock order under repetition. A hand-out holds the board monitor and then takes the
     * session manager's (its `startNew`), while a session exit is emitted from inside the session
     * manager's monitor (its `finish`). The exit must therefore never wait for the board: the daemon's
     * fourth arm queues it to the board's own session-events thread when the emitting thread holds the
     * manager lock. Without that defer the two threads deadlock every time the interleaving lands --
     * the hand-out holds the board and wants the manager, the exit holds the manager and wants the
     * board -- so this runs the race fifty times, each iteration bounded to 5 s so a hang is a failure
     * and not a stuck suite.
     */
    @Test
    fun aHandOutRacingAnotherSessionsExitCompletesFiftyTimes() {
        var uid = 0
        repeat(50) { iteration ->
            // One store per iteration: the queued exit bookkeeping runs on the board's own thread and
            // writes runs, so iterations sharing one SQLite file would race each other's transactions.
            val store = SqlAgentHqStore.open(File(dir, "race-$iteration.db").absolutePath).also { opened += it }
            val runner = ScriptedRunner(autoExit = false)
            val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir)
            val profiles = AgentProfiles(store.agents, manager)
            val worker = profiles.save(AgentProfile(name = "Worker-$iteration", role = "backend"))
            val otherWorker = profiles.save(AgentProfile(name = "Other-$iteration", role = "backend"))
            val board = TicketBoard(store, idFactory = { "t${uid++}" }, agents = profiles, sessions = manager)
            val ticket = board.save(TicketSave(title = "Ticket $iteration", agentId = worker.id)).ticket
            val otherTicket = board.save(TicketSave(title = "Other $iteration", agentId = otherWorker.id)).ticket

            // The other session: another ticket's stub run, kept BUSY until the race ends it.
            val otherSession = manager.startNew(
                prompt = "another session's work",
                agentId = otherWorker.id,
                role = otherWorker.role,
                ticketId = otherTicket.id,
            )
            val otherProcess = runner.processes.single()

            // The hand-out reaches the launch gate inside the board lock; the exit is emitted inside
            // the session manager's lock. Each side waits until the other one holds its lock.
            val inHandOut = CountDownLatch(1)
            val exitFired = CountDownLatch(1)
            val done = CountDownLatch(2)
            manager.launchGate = { _, _, _ ->
                inHandOut.countDown()
                exitFired.await(5, TimeUnit.SECONDS)
                GateDecision.Allow
            }
            manager.eventSink = { event ->
                if (event is SessionEvent.Exit) exitFired.countDown()
                board.onSessionEvent(event) // the daemon's fourth arm
            }

            var answer: TicketAnswer? = null
            val handOut = Thread({
                runCatching { answer = board.move(ticket.id, IN_PROGRESS) }
                done.countDown()
            }, "hand-out-$iteration").apply { isDaemon = true }
            val otherExit = Thread({
                inHandOut.await(5, TimeUnit.SECONDS)
                runCatching { otherProcess.exit(0) }
                done.countDown()
            }, "session-exit-$iteration").apply { isDaemon = true }
            handOut.start()
            otherExit.start()

            assertTrue(
                done.await(5, TimeUnit.SECONDS),
                "iteration $iteration: hand-out and session exit did not both complete within 5 s " +
                    "(handOut ${handOut.state}, otherExit ${otherExit.state})",
            )
            assertEquals(0L, exitFired.count, "iteration $iteration: the exit event never reached the sink")
            // The queued exit books the other ticket's run row on the board's own thread. Waiting for
            // that finished row (bounded) both proves the deferred work landed instead of disappearing
            // into the queue, and keeps its writes from leaking into the next iteration's transactions.
            val finishedRunRow = {
                store.ticketRuns.byTicket(otherTicket.id).any { it.sessionId == otherSession.id && it.endedAt != null }
            }
            val bookDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!finishedRunRow() && System.nanoTime() < bookDeadline) Thread.sleep(10)
            assertTrue(finishedRunRow(), "iteration $iteration: the exited session's run row was never finished")
            val started = checkNotNull(answer) { "iteration $iteration: the hand-out returned no answer" }
            assertEquals(IN_PROGRESS, started.ticket.status, "iteration $iteration")
            assertEquals("started", started.ticket.start?.reason, "iteration $iteration")
        }
    }

    /**
     * BUG-BoardCommitImmediate: a board commit reads the whole board and then writes. Run as a DEFERRED transaction
     * that read takes only a snapshot, so a writer on another connection that commits between the read and the write
     * makes the snapshot impossible to upgrade: SQLITE_BUSY_SNAPSHOT, immediately, and busy_timeout does not apply.
     * The commit must take the write lock FIRST, so the competing writer WAITS (busy_timeout) instead of winning and
     * aborting the commit. Forced, not hoped for: the competing write is fired from inside the commit's load.
     */
    @Test
    fun aConcurrentWriterBetweenTheCommitsLoadAndItsWriteDoesNotAbortIt() {
        val real = openStore()
        val other = openStore() // another connection on the same file: the "another trigger / usage write" writer
        var armed = false
        var competing: Thread? = null
        var competingFinishedInsideLoad = false
        var competingFailure: Throwable? = null
        val store = object : com.superiorsoftllc.agenthq.persistence.AgentHqStore by real {
            override val tickets = object : com.superiorsoftllc.agenthq.persistence.TicketRepository by real.tickets {
                override fun all(): List<com.superiorsoftllc.agenthq.persistence.TicketRow> {
                    val rows = real.tickets.all()
                    if (armed) {
                        armed = false
                        val writer = Thread {
                            try {
                                other.settings.put(com.superiorsoftllc.agenthq.persistence.SettingRow("probe", "\"x\"", 1L))
                            } catch (t: Throwable) {
                                competingFailure = t
                            }
                        }
                        competing = writer
                        writer.start()
                        // Give it the whole window to commit between this read and the commit's write.
                        writer.join(750)
                        competingFinishedInsideLoad = !writer.isAlive
                    }
                    return rows
                }
            }
        }
        val ids = ('a'..'z').map { it.toString() }.iterator()
        val board = TicketBoard(store, clock = { 1L }, idFactory = { ids.next() })
        board.new("First")

        armed = true
        val saved = runCatching { board.new("Second") }

        competing!!.join(10_000)
        assertNull(saved.exceptionOrNull(), "a concurrent writer between load and write must not abort the board commit: ${saved.exceptionOrNull()}")
        assertFalse(competingFinishedInsideLoad, "the competing writer committed inside the commit's read: the commit did not hold the write lock")
        assertNull(competingFailure, "the competing writer waits for the lock and then succeeds: $competingFailure")
        assertEquals(listOf("First", "Second"), board.list().map { it.title })
        assertEquals("\"x\"", real.settings.get("probe")?.value)
    }

    @Test
    fun aRoleTriggerStartsATodoTicketAssignedToAProfileOfThatRole() {
        val store = openStore()
        val runner = ScriptedRunner(autoExit = false)
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir)
        val profiles = AgentProfiles(store.agents, manager)
        val worker = profiles.save(AgentProfile(name = "Backend", role = "backend"))
        val ids = ('a'..'z').map { it.toString() }.iterator()
        val prompting = object : com.superiorsoftllc.agenthq.context.TicketContext {
            override fun compose(input: com.superiorsoftllc.agenthq.context.ContextInput) =
                com.superiorsoftllc.agenthq.context.ComposedContext("Ship ${input.title}.", emptyList())
            override fun effective(override: Boolean?, skillsOverride: Boolean?): com.superiorsoftllc.agenthq.protocol.EffectiveContext? = null
        }
        val board = TicketBoard(store, idFactory = { ids.next() }, context = prompting, agents = profiles, sessions = manager)
        board.save(TicketSave(title = "Add login", agentId = worker.id))

        assertNull(board.startNextUnattended("role:frontend"), "another role's trigger must skip it")
        assertEquals("started", board.startNextUnattended("role:backend")?.ticket?.start?.reason)
        board.close()
    }

    // --- BUG-E2E-2: a finished run decides the ticket's column ------------------------------------------

    /** One assistant text line, the work a briefed worker shows. */
    private val assistantText = "{\"type\":\"text\",\"text\":\"Done: login shipped.\"}"

    /**
     * Starts one ticket on a scripted session that prints [output] (after the provider session id, when [providerSession]),
     * ends that session with [exitCode], and answers the ticket afterwards; [sweepFlags] gets what a worker sweep then flags.
     */
    private fun ticketAfterRunExits(
        exitCode: Int,
        exitBehavior: String = "done",
        output: List<String> = listOf(assistantText),
        providerSession: Boolean = true,
        minOutputs: Long = 1,
        sweepFlags: MutableList<String>? = null,
    ): Ticket {
        val store = openStore()
        val runner = ScriptedRunner(autoExit = false, emitSessionId = providerSession)
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir)
        val profiles = AgentProfiles(store.agents, manager)
        val worker = profiles.save(AgentProfile(name = "Backend", role = "backend"))
        val ids = ('a'..'z').map { it.toString() }.iterator()
        // A non-blank prompt, so startNew starts a run (a blank one leaves the session IDLE, never live).
        val prompting = object : com.superiorsoftllc.agenthq.context.TicketContext {
            override fun compose(input: com.superiorsoftllc.agenthq.context.ContextInput) =
                com.superiorsoftllc.agenthq.context.ComposedContext("Ship ${input.title}.", emptyList())
            override fun effective(override: Boolean?, skillsOverride: Boolean?): com.superiorsoftllc.agenthq.protocol.EffectiveContext? = null
        }
        val board = TicketBoard(store, idFactory = { ids.next() }, context = prompting, agents = profiles, sessions = manager,
            exitBehavior = { exitBehavior }, autoDoneMinOutputs = { minOutputs },
        )
        manager.eventSink = { board.onSessionEvent(it) } // the daemon's fourth arm
        val ticket = board.save(TicketSave(title = "Add login", agentId = worker.id)).ticket
        assertEquals("started", board.move(ticket.id, IN_PROGRESS).ticket.start?.reason)

        val process = runner.processes.single()
        output.forEach(process::say)
        process.exit(exitCode)

        // The exit is applied on the board's own session-events thread: wait (bounded) for the finished run.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (board.get(ticket.id).lastRun == null && System.nanoTime() < deadline) Thread.sleep(10)
        board.close()
        sweepFlags?.let { flags -> board.sweepWorkers(graceMs = 0) { _, _, detail -> flags += detail } }
        return board.get(ticket.id)
    }

    @Test
    fun aSucceededRunMovesItsStillInProgressTicketToDone() {
        val after = ticketAfterRunExits(0)

        assertEquals("SUCCEEDED", after.lastRun?.outcome)
        assertEquals(DONE, after.status, "a SUCCEEDED run must not leave its ticket in_progress forever")
        assertTrue(
            after.lastRun?.detail.orEmpty().contains("moved to done"),
            "the ticket must say why the daemon moved it: '${after.lastRun?.detail}'",
        )
    }

    // --- GAP 855db590: a silent clean exit is not work done -----------------------------------------------

    @Test
    fun aSilentFirstTurnThatExitsCleanlyLeavesItsTicketInProgressAndIsFlagged() {
        val flags = mutableListOf<String>()
        val after = ticketAfterRunExits(0, output = emptyList(), sweepFlags = flags)

        assertEquals("SUCCEEDED", after.lastRun?.outcome)
        assertEquals(IN_PROGRESS, after.status, "exit 0 with no output is not work done: the ticket must stay in_progress")
        assertTrue(after.lastRun?.detail.orEmpty().startsWith(TicketBoard.NO_WORK_DETAIL), "lastRun says why: '${after.lastRun?.detail}'")
        assertTrue(after.lastRun?.detail.orEmpty().contains("0 assistant output(s)"), "'${after.lastRun?.detail}'")
        assertEquals(1, flags.size, "the sweep flags the briefed worker that did no work: $flags")
        assertTrue(flags.single().contains("done no work"), flags.single())
    }

    @Test
    fun aCleanExitThatNeverOpenedAProviderSessionLeavesItsTicketInProgress() {
        val after = ticketAfterRunExits(0, output = emptyList(), providerSession = false)

        assertEquals("SUCCEEDED", after.lastRun?.outcome)
        assertEquals(IN_PROGRESS, after.status, "an unbriefed worker (no provider session id) never moves its ticket to done")
        assertTrue(after.lastRun?.detail.orEmpty().contains("never opened a provider session"), "'${after.lastRun?.detail}'")
    }

    @Test
    fun outputWithoutAProviderSessionIsStillNotWorkDone() {
        val after = ticketAfterRunExits(0, providerSession = false)

        assertEquals(IN_PROGRESS, after.status, "output alone, with no provider session, does not count")
    }

    @Test
    fun theWorkThresholdIsConfigurable() {
        val belowThree = ticketAfterRunExits(0, output = listOf(assistantText, assistantText), minOutputs = 3)
        assertEquals(IN_PROGRESS, belowThree.status, "2 outputs are below a threshold of 3")
        assertTrue(belowThree.lastRun?.detail.orEmpty().contains("2 assistant output(s)"), "'${belowThree.lastRun?.detail}'")

        val toolCall = "{\"type\":\"tool_use\",\"name\":\"Bash\"}"
        assertEquals(DONE, ticketAfterRunExits(0, output = listOf(assistantText, toolCall, assistantText), minOutputs = 3).status, "a tool call counts as work")

        assertEquals(DONE, ticketAfterRunExits(0, output = emptyList(), minOutputs = 0).status, "0 = any clean exit counts (the old rule)")
    }

    @Test
    fun aSucceededRunLeavesItsTicketInProgressUnderTheDefaultExitBehavior() {
        val after = ticketAfterRunExits(0, exitBehavior = "leave")

        assertEquals("SUCCEEDED", after.lastRun?.outcome)
        assertEquals(IN_PROGRESS, after.status, "tickets.exitBehavior=leave keeps a clean exit in_progress")
    }

    @Test
    fun aFailedRunLeavesItsTicketInProgressWithTheFailureOnLastRun() {
        val after = ticketAfterRunExits(1)

        assertEquals("FAILED", after.lastRun?.outcome)
        assertEquals(IN_PROGRESS, after.status, "a FAILED run stays in_progress for a human to look at")
    }

    // --- BUG-WT-1: a worker never runs in the project's own git checkout ---------------------------------

    private fun noRepoBoard(projectFolder: File, useWorktree: Boolean? = null): Triple<TicketBoard, SessionManager, String> {
        val store = openStore()
        val manager = SessionManager(
            FakeAgentProvider(),
            ScriptedRunner(autoExit = false),
            tempSessionStore(),
            killGraceMs = 50,
            defaultWorkingDir = projectFolder,
        )
        val profiles = AgentProfiles(store.agents, manager)
        val worker = profiles.save(AgentProfile(name = "Backend", role = "backend"))
        val ids = ('a'..'z').map { it.toString() }.iterator()
        val board = TicketBoard(store, idFactory = { ids.next() }, agents = profiles, sessions = manager)
        val ticket = board.save(TicketSave(title = "Add login", agentId = worker.id, useWorktree = useWorktree)).ticket
        return Triple(board, manager, ticket.id)
    }

    @Test
    fun noRepoConfiguredAndAGitProjectFolderPutsTheWorkerOnItsTicketBranch() {
        val checkout = newGitRepo("agenthq-bugwt1")
        val checkoutBranch = { testGit().run(checkout, listOf("branch", "--show-current")).stdout.trim() }
        val branchBefore = checkoutBranch()
        try {
            val (board, manager, id) = noRepoBoard(checkout)
            val answer = board.move(id, IN_PROGRESS).ticket
            assertTrue(
                manager.list().none { File(it.workingDir).absoluteFile == checkout.absoluteFile },
                "no worker runs in the project's own checkout: ${manager.list().map { it.workingDir }} (${answer.start})",
            )
            assertEquals("started", answer.start?.reason, "the start succeeds: ${answer.start}")
            val session = manager.get(answer.sessionId!!)!!
            val workingDir = File(session.workingDir).absoluteFile
            assertEquals(File(session.worktreePath!!).absoluteFile, workingDir, "the worker runs in its worktree")
            assertTrue(
                workingDir.startsWith(File(checkout, ".worktrees").absoluteFile),
                "the worktree is cut under the project folder's repo: $workingDir",
            )
            // The branch is named after the task by the one rule the configured-repo path uses.
            val expected = TicketBranches.branchFor(id, "Add login")
            val current = testGit().run(workingDir, listOf("branch", "--show-current")).stdout.trim()
            assertEquals(expected, current, "the worker's worktree is on the branch named after the task")
            assertEquals(expected, session.worktreeBranch)
            assertEquals(expected, board.get(id).branch, "the ticket records its branch")
            assertEquals(branchBefore, checkoutBranch(), "the project checkout's own branch is unchanged")
        } finally {
            checkout.deleteRecursively()
        }
    }

    /**
     * BUG-REGRESS-1: uncommitted changes in the project checkout never refuse a start. Since BUG-WT-1 a board with no
     * repo configured cuts every ticket's worktree from the git checkout it runs in, and the dirty-base refusal then
     * kept EVERY start of a checkout with a half-finished edit in To Do. The branch is cut from the base ref's commit,
     * so the edit stays in the checkout, and the start answer says what the branch was cut from.
     */
    @Test
    fun noRepoConfiguredAndADirtyGitProjectFolderStillStartsTheTicketOnItsBranch() {
        val checkout = newGitRepo("agenthq-regress1")
        val head = testGit().run(checkout, listOf("rev-parse", "HEAD")).stdout.trim()
        // A tracked change nobody committed: someone's half-finished work in the project checkout.
        File(checkout, "README.md").writeText("half-written\n")
        try {
            val (board, manager, id) = noRepoBoard(checkout)
            val answer = board.move(id, IN_PROGRESS).ticket

            assertEquals("started", answer.start?.reason, "a dirty checkout never refuses a start: ${answer.start}")
            assertEquals(IN_PROGRESS, answer.status, "the started ticket is in progress")
            assertEquals(TicketBranches.branchFor(id, "Add login"), answer.branch, "the ticket is on its own branch")
            assertEquals(head, answer.base, "the branch is cut from the base ref's commit")
            val detail = answer.start?.detail.orEmpty()
            assertTrue(detail.contains("cut from HEAD ${head.take(8)}"), "the start answer names what it was cut from: $detail")
            val worktree = File(manager.get(answer.sessionId!!)!!.worktreePath!!)
            assertEquals("hello", File(worktree, "README.md").readText().trim(), "only committed work reaches the ticket branch")
            assertEquals("half-written", File(checkout, "README.md").readText().trim(), "the checkout keeps its own edit")
        } finally {
            checkout.deleteRecursively()
        }
    }

    @Test
    fun anExplicitUseWorktreeFalseOverAGitProjectFolderIsRefused() {
        val checkout = newGitRepo("agenthq-bugwt1-false")
        try {
            val (board, manager, id) = noRepoBoard(checkout, useWorktree = false)
            val answer = board.move(id, IN_PROGRESS).ticket
            assertEquals("start_refused", answer.start?.reason)
            assertTrue(answer.start!!.detail!!.contains("git repo"), "the reason names the git repo: ${answer.start}")
            assertEquals(TODO, answer.status, "a refused start leaves the ticket where it was")
            assertEquals(0, manager.list().size)
        } finally {
            checkout.deleteRecursively()
        }
    }

    @Test
    fun noRepoConfiguredAndANonGitProjectFolderStillStartsInIt() {
        val folder = Files.createTempDirectory("agenthq-bugwt1-plain").toFile()
        try {
            val (board, manager, id) = noRepoBoard(folder)
            val answer = board.move(id, IN_PROGRESS).ticket
            assertEquals("started", answer.start?.reason)
            assertEquals(folder.absolutePath, manager.get(answer.sessionId!!)!!.workingDir)
        } finally {
            folder.deleteRecursively()
        }
    }

    // --- NOGIT-1: a project folder that is not a git repo runs in place, whatever checkout the daemon is in -----

    private fun projectDirBoard(
        daemonDir: File,
        projectDir: File,
        useWorktree: Boolean? = null,
        baseRef: TicketBaseRef = TicketBaseRef.fixed(TicketBaseRef.HEAD),
    ): Triple<TicketBoard, SessionManager, String> {
        val store = openStore()
        val manager = SessionManager(
            FakeAgentProvider(),
            ScriptedRunner(autoExit = false),
            tempSessionStore(),
            killGraceMs = 50,
            defaultWorkingDir = daemonDir,
        )
        val profiles = AgentProfiles(store.agents, manager)
        val worker = profiles.save(AgentProfile(name = "Backend", role = "backend"))
        val ids = ('a'..'z').map { it.toString() }.iterator()
        val board = TicketBoard(
            store, idFactory = { ids.next() }, agents = profiles, sessions = manager, baseRef = baseRef,
            projectDirectory = { projectDir.path },
        )
        val ticket = board.save(
            TicketSave(title = "Add login", agentId = worker.id, projectId = "project-x", useWorktree = useWorktree),
        ).ticket
        return Triple(board, manager, ticket.id)
    }

    @Test
    fun aNonGitProjectDirectoryRunsInPlaceEvenWhenTheDaemonIsInAGitCheckout() {
        val daemonRepo = newGitRepo("agenthq-nogit-daemon")
        val folder = Files.createTempDirectory("agenthq-nogit-plain").toFile()
        try {
            val (board, manager, id) = projectDirBoard(daemonRepo, folder)
            val answer = board.move(id, IN_PROGRESS).ticket
            assertEquals("started", answer.start?.reason, "the start succeeds: ${answer.start}")
            assertNull(answer.branch, "no branch is cut for a folder with no git repo")
            val session = manager.get(answer.sessionId!!)!!
            assertEquals(folder.absolutePath, File(session.workingDir).absolutePath, "the worker runs in the project folder")
            assertNull(session.worktreePath, "no worktree")
            assertFalse(File(daemonRepo, ".worktrees").exists(), "nothing is cut in the daemon's checkout")
            val branchList = testGit().run(daemonRepo, listOf("branch", "--list")).stdout.lines().count { it.isNotBlank() }
            assertEquals(1, branchList, "the daemon's repo gained no branch")
        } finally {
            daemonRepo.deleteRecursively()
            folder.deleteRecursively()
        }
    }

    @Test
    fun useWorktreeTrueOnANonGitProjectFolderNamesTheNonGitFolder() {
        val folder = Files.createTempDirectory("agenthq-nogit-wt").toFile()
        try {
            val (board, _, id) = projectDirBoard(folder, folder, useWorktree = true)
            val answer = board.move(id, IN_PROGRESS).ticket
            assertEquals("start_refused", answer.start?.reason)
            assertTrue(answer.start!!.detail!!.contains("is not a git repository"), "one clear reason: ${answer.start}")
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun aGitRepoWithNoCommitsIsRefusedWithAHaveNoCommitsReason() {
        val repo = Files.createTempDirectory("agenthq-nogit-empty").toFile()
        try {
            testGit().run(repo, listOf("init"))
            val (board, _, id) = projectDirBoard(repo, repo, baseRef = TicketBaseRef.fixed(TicketBaseRef.NONE))
            val answer = board.move(id, IN_PROGRESS).ticket
            assertEquals("start_refused", answer.start?.reason)
            assertTrue(answer.start!!.detail!!.contains("no commits yet"), "names the cause: ${answer.start}")
            val check = ProjectBaseCheck(TicketBranches()) { repo }
            val failure = assertFailsWith<IllegalArgumentException> {
                check.check(Project(id = "project-x", name = "x", directory = repo.path), "main")
            }
            assertTrue(failure.message!!.contains("no commits yet"), failure.message)
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun aBaseBranchOnANonGitProjectSaysBaseBranchesDoNotApply() {
        val folder = Files.createTempDirectory("agenthq-nogit-base").toFile()
        try {
            val check = ProjectBaseCheck(TicketBranches()) { null }
            val failure = assertFailsWith<IllegalArgumentException> {
                check.check(Project(id = "project-x", name = "x", directory = folder.path), "main")
            }
            assertTrue(failure.message!!.contains("is not a git repository"), failure.message)
            assertFalse(failure.message!!.contains("AGENTHQ_REPO"), "no misleading advice: ${failure.message}")
        } finally {
            folder.deleteRecursively()
        }
    }
}
