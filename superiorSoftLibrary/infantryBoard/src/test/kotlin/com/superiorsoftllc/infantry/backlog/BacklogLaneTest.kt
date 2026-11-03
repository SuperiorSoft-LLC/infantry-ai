// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.daemon.api.board.BoardAgent
import com.superiorsoftllc.infantry.protocol.TicketLinkRequest
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.protocol.TicketStatus
import com.superiorsoftllc.infantry.protocol.TicketStatus.BACKLOG
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

/**
 * BACKLOG (contract 1.131.0, ticket-39eab724): `backlog` is limbo before `todo` (the sprint). Over the board-side fake host (DP-5f-6)
 * and a board that CAN start tickets (a profile with a role and a session manager), so every "not started" below is
 * the lane's doing: the same ticket starts once it is promoted to todo.
 */
class BacklogLaneTest {

    private class Rig(val board: TicketBoard, val workerId: String)

    private fun rig(): Rig {
        val host = InMemoryBoardHost()
        val worker = host.agents.save(BoardAgent(name = "Backend", role = "backend"))
        val ids = ('a'..'z').map { it.toString() }.iterator()
        val board = TicketBoard(host, idFactory = { ids.next() }, profilesAvailable = true)
        return Rig(board, worker.id!!)
    }

    private fun Rig.new(title: String, status: String? = null) =
        board.save(TicketSave(title = title, agentId = workerId, status = status)).ticket

    private fun Rig.status(id: String) = board.list().single { it.id == id }.status

    @Test
    fun backlogIsTheFirstColumnAndACreateCanTargetIt() {
        val r = rig()
        val sprint = r.new("Sprint work")
        val parked = r.new("Maybe later", status = BACKLOG)
        val parkedUpper = r.new("Maybe never", status = "  Backlog ")

        assertEquals(BACKLOG, TicketStatus.ALL.first(), "backlog is ordered before todo")
        assertEquals(TODO, sprint.status, "a create with no status still enters the sprint")
        assertEquals(BACKLOG, parked.status)
        assertEquals(BACKLOG, parkedUpper.status, "the status is trimmed and lowercased")
        assertEquals(listOf(parked.id, parkedUpper.id, sprint.id), r.board.list().map { it.id }, "board order lists backlog first")
        assertEquals(listOf(0, 1), r.board.list().filter { it.status == BACKLOG }.map { it.position })

        for (bad in listOf(IN_PROGRESS, PENDING, DONE, "limbo")) {
            val e = assertFailsWith<IllegalArgumentException> { r.board.save(TicketSave(title = "x", status = bad)) }
            assertTrue(e.message!!.contains("todo or backlog"), "a create into '$bad' is refused naming the lanes: ${e.message}")
        }
        val edit = assertFailsWith<IllegalArgumentException> { r.board.save(TicketSave(id = sprint.id, status = BACKLOG)) }
        assertTrue(edit.message!!.contains("ticket_move"), "an edit never changes the column: ${edit.message}")
        assertEquals(3, r.board.list().size, "a refused save stores nothing")
    }

    @Test
    fun aBacklogTicketRefusesEveryStartNamingTheLaneAndStartsOncePromoted() {
        val r = rig()
        val t = r.new("Parked", status = BACKLOG)

        val moved = r.board.move(TicketMove(t.id, IN_PROGRESS)).ticket
        assertFalse(moved.start!!.ok)
        assertEquals("start_refused", moved.start!!.reason)
        assertEquals(TicketStatus.BACKLOG_START_REFUSAL, moved.start!!.detail)
        assertTrue(moved.start!!.detail!!.contains("Backlog"), "the refusal names the lane")
        assertEquals(BACKLOG, r.status(t.id), "a refused start leaves it in backlog")
        assertNull(r.board.list().single().sessionId)

        // Pickers: start next (person) and a trigger's start next never pick it.
        val next = assertFailsWith<IllegalArgumentException> { r.board.startNext() }
        assertEquals("nothing startable", next.message, "a backlog ticket is not a start-next candidate")
        assertNull(r.board.startNextUnattended(), "a trigger's start next never picks a backlog ticket")
        assertNull(r.board.startNextUnattended("role:backend"))
        assertEquals(BACKLOG, r.status(t.id))

        // Promoted by hand to the sprint, the same ticket on the same board starts.
        r.board.move(TicketMove(t.id, TODO))
        assertEquals(TODO, r.status(t.id))
        assertEquals("started", r.board.startNextUnattended("role:backend")?.ticket?.start?.reason)
        assertEquals(IN_PROGRESS, r.status(t.id))
    }

    @Test
    fun movesToAndFromBacklogAreByHandAndTheDaemonNeverSettlesABacklogTicket() {
        val r = rig()
        val prereq = r.new("Prerequisite")
        val parked = r.new("Parked and blocked", status = BACKLOG)
        val sprint = r.new("Sprint and blocked")

        r.board.link(TicketLinkRequest(parked.id, prereq.id, "blocked_by"))
        r.board.link(TicketLinkRequest(sprint.id, prereq.id, "blocked_by"))
        assertEquals(BACKLOG, r.status(parked.id), "a held backlog ticket stays in backlog: settle moves todo only")
        assertEquals(PENDING, r.status(sprint.id), "a held todo ticket still goes to pending")

        // A held ticket may be parked from pending to backlog, and stays held there.
        r.board.move(TicketMove(sprint.id, BACKLOG))
        assertEquals(BACKLOG, r.status(sprint.id))
        assertTrue(r.board.list().single { it.id == sprint.id }.heldBy.isNotEmpty(), "still held in backlog")
        // But never to another column by hand.
        r.board.move(TicketMove(sprint.id, TODO))
        assertEquals(PENDING, r.status(sprint.id), "promoted while held, it settles into pending again")
        assertFailsWith<IllegalArgumentException> { r.board.move(TicketMove(sprint.id, DONE)) }

        // Releasing the prerequisite releases pending to todo, but never pulls a backlog ticket into the sprint.
        r.board.move(TicketMove(prereq.id, DONE))
        assertEquals(TODO, r.status(sprint.id))
        assertEquals(BACKLOG, r.status(parked.id), "the daemon never moves a ticket out of backlog")

        // Todo -> backlog -> done -> backlog: every hand move is allowed.
        r.board.move(TicketMove(sprint.id, BACKLOG))
        assertEquals(BACKLOG, r.status(sprint.id))
        r.board.move(TicketMove(sprint.id, DONE))
        r.board.move(TicketMove(sprint.id, BACKLOG))
        assertEquals(BACKLOG, r.status(sprint.id))
    }
}
