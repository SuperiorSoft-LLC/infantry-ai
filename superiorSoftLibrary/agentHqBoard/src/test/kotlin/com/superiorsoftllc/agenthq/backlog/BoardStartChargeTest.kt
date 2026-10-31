package com.superiorsoftllc.agenthq.backlog

import com.superiorsoftllc.agenthq.daemon.api.spawns.SpawnCharge
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** DP-5h-3a2: the board-side charge of a session-originated ticket start refuses on the port's depth / pod-cap answer. */
class BoardStartChargeTest {
    private class RefusingCharge(val sessions: Set<String>) : SpawnCharge {
        val admitted = mutableListOf<Boolean>()
        override fun isSession(sessionId: String) = sessionId in sessions
        override fun <T> admitIf(origin: String?, charged: Boolean, create: (placed: Boolean) -> T): T {
            admitted += charged
            if (charged) throw IllegalStateException("spawn depth limit reached")
            return create(false)
        }
        override fun record(sessionId: String, origin: String?) {}
        override fun setSpawner(sessionId: String, spawnerId: String?) {}
    }

    private fun board(): TicketBoard {
        val ids = ('a'..'z').map { it.toString() }.iterator()
        return TicketBoard(InMemoryBoardHost(), idFactory = { ids.next() })
    }

    @Test
    fun aSessionStartIsRefusedByThePortAndTheTicketStaysInTodo() {
        val board = board()
        val t = board.save(TicketSave(title = "work")).ticket
        val charge = RefusingCharge(setOf("session-1"))
        val e = assertFailsWith<IllegalStateException> { BoardStartCharge(charge, board).move(TicketMove(t.id, TicketStatus.IN_PROGRESS), "session-1") }
        assertEquals("spawn depth limit reached", e.message)
        assertEquals(listOf(true), charge.admitted, "the start was charged")
        assertEquals(TicketStatus.TODO, board.get(t.id).status)
    }

    @Test
    fun aMoveByNobodyOrOneThatRunsNoWorkIsNotCharged() {
        val board = board()
        val t = board.save(TicketSave(title = "work")).ticket
        val charge = RefusingCharge(setOf("session-1"))
        val c = BoardStartCharge(charge, board)
        assertEquals(TicketStatus.DONE, c.move(TicketMove(t.id, TicketStatus.DONE), "session-1").ticket.status)
        assertTrue(charge.admitted.all { !it }, "a move to done runs no worker: ${charge.admitted}")
        val u = board.save(TicketSave(title = "other")).ticket
        c.move(TicketMove(u.id, TicketStatus.TODO), null)
        assertTrue(charge.admitted.none { it })
    }
}
