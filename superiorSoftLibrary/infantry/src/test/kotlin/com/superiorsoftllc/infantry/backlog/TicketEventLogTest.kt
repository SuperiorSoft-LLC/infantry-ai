package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.daemon.api.board.TicketEvent
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.protocol.TicketStatus.DONE
import com.superiorsoftllc.infantry.protocol.TicketStatus.TODO
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BOARD-API-2 (DP-4b): the typed ticket events: written with the change, ordered, at-least-once, replayed after a restart. */
class TicketEventLogTest {
    private val dir: File = Files.createTempDirectory("infantry-ticket-events").toFile()
    private val dbPath = File(dir, "infantry.db").absolutePath
    private val opened = mutableListOf<SqlInfantryStore>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private class Rig(val store: SqlInfantryStore, val board: TicketBoard, val log: TicketEventLog, val errors: MutableList<String>)

    private fun rig(): Rig {
        val store = SqlInfantryStore.open(dbPath).also { opened += it }
        val ids = generateSequence(0) { it + 1 }.map { "t$it" }.iterator()
        var tick = 0L
        val board = TicketBoard(store, clock = { ++tick }, idFactory = { ids.next() })
        val errors = mutableListOf<String>()
        val log = TicketEventLog(store.database, clock = { tick }, log = { errors += it })
        board.events = log
        return Rig(store, board, log, errors)
    }

    @Test
    fun `a create, a move and a done arrive in order with typed kinds`() {
        val r = rig()
        val seen = mutableListOf<TicketEvent>()
        r.log.subscribe("test") { seen += it }
        val t = r.board.save(TicketSave(title = "one")).ticket
        r.board.move(TicketMove(t.id, DONE, null, false))
        assertEquals(listOf(TicketEvent.CREATED, TicketEvent.DONE), seen.map { it.kind })
        assertEquals(listOf(null to TODO, TODO to DONE), seen.map { it.from to it.to })
        assertTrue(seen.zipWithNext().all { (a, b) -> a.seq < b.seq }, "seq is strictly increasing")
        assertTrue(seen.all { it.ticketId == t.id })
    }

    @Test
    fun `a change that keeps the column is updated and not a transition, an unchanged commit is nothing`() {
        val r = rig()
        val seen = mutableListOf<TicketEvent>()
        val t = r.board.save(TicketSave(title = "one")).ticket
        r.log.subscribe("test") { seen += it }
        r.board.save(TicketSave(id = t.id, title = "renamed"))
        r.board.save(TicketSave(id = t.id, title = "renamed"))
        assertEquals(listOf(TicketEvent.UPDATED), seen.map { it.kind })
        assertTrue(!seen.single().isTransition)
    }

    @Test
    fun `a delete is an event`() {
        val r = rig()
        val seen = mutableListOf<TicketEvent>()
        val t = r.board.save(TicketSave(title = "one")).ticket
        r.log.subscribe("test") { seen += it }
        r.board.delete(t.id)
        assertEquals(listOf(TicketEvent.DELETED), seen.map { it.kind })
        assertEquals(TODO, seen.single().from)
    }

    @Test
    fun `two consumers each see every event in the same order`() {
        val r = rig()
        val a = mutableListOf<Long>()
        val b = mutableListOf<Long>()
        r.log.subscribe("a") { a += it.seq }
        r.log.subscribe("b") { b += it.seq }
        repeat(5) { r.board.save(TicketSave(title = "t$it")) }
        assertEquals(5, a.size)
        assertEquals(a, b)
        assertEquals(a.sorted(), a)
    }

    @Test
    fun `events not handled before a restart are replayed in order to a consumer with a cursor, not to others`() {
        val first = rig()
        val seenFirst = mutableListOf<Long>()
        first.log.subscribe("reaper") { seenFirst += it.seq }
        first.log.subscribe("files") { }
        first.board.save(TicketSave(title = "handled"))
        assertEquals(1, seenFirst.size)
        // The daemon stops while "reaper" is down: two more events are stored, nobody delivers them.
        val handled = first.log.head()
        val down = TicketEventLog(first.store.database)
        first.board.events = down
        first.board.save(TicketSave(title = "missed 1"))
        first.board.save(TicketSave(title = "missed 2"))
        first.store.close()
        opened.remove(first.store)

        val second = rig()
        val replayed = mutableListOf<TicketEvent>()
        second.log.subscribe("reaper") { replayed += it }
        assertEquals(listOf(handled + 1, handled + 2), replayed.map { it.seq })
        assertEquals(listOf("missed 1", "missed 2").size, replayed.size)
        // A consumer that never existed starts at the head: no history.
        val fresh = mutableListOf<TicketEvent>()
        second.log.subscribe("brand-new") { fresh += it }
        assertTrue(fresh.isEmpty())
        // Events stored later go to both, after the replayed ones.
        second.board.save(TicketSave(title = "after"))
        assertEquals(handled + 3, replayed.last().seq)
        assertEquals(1, fresh.size)
    }

    @Test
    fun `a listener that throws gets the same event again, then it is skipped after the limit so the stream is not wedged`() {
        val r = rig()
        val attempts = mutableListOf<Long>()
        val ok = mutableListOf<Long>()
        r.log.subscribe("flaky") { e ->
            if (e.ticketId == "t0") { attempts += e.seq; error("boom") } else ok += e.seq
        }
        r.board.save(TicketSave(title = "poison"))   // t0, attempt 1 fails, cursor stays
        r.board.save(TicketSave(title = "next"))     // retry of t0 (2), still before t1
        assertTrue(ok.isEmpty(), "order is kept: nothing after the failing event is delivered yet")
        r.board.save(TicketSave(title = "third"))    // attempt 3 fails, the event is skipped, the rest flows
        assertEquals(TicketEventLog.MAX_ATTEMPTS, attempts.size)
        assertEquals(2, ok.size)
        assertTrue(r.errors.any { it.contains("skipped") })
    }

    @Test
    fun `a rolled back change leaves no event`() {
        val r = rig()
        val seen = mutableListOf<TicketEvent>()
        r.log.subscribe("test") { seen += it }
        runCatching { r.board.move(TicketMove("missing", DONE, null, false)) }
        assertTrue(seen.isEmpty())
        assertEquals(0, r.log.head())
    }
}
