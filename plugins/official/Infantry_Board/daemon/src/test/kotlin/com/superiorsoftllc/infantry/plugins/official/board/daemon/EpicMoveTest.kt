// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.board.BoardResult
import com.superiorsoftllc.infantry.daemon.api.board.EpicInfo
import com.superiorsoftllc.infantry.daemon.api.board.Epics
import com.superiorsoftllc.infantry.daemon.api.board.NoBoard
import com.superiorsoftllc.infantry.daemon.api.board.TicketInfo
import com.superiorsoftllc.infantry.daemon.api.board.TicketPatch
import com.superiorsoftllc.infantry.daemon.api.board.Tickets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** EPIC-MOVE-1: single and bulk moves, create on the fly, clearing, and the in-progress base guard. */
class EpicMoveTest {
    private val by = Caller(owner = true)

    private class Board : Tickets by NoBoard.tickets {
        val tickets = LinkedHashMap<String, TicketInfo>()
        val epics = LinkedHashMap<String, EpicInfo>()
        var updates = 0

        fun ticket(id: String, epic: String? = null, status: String = "todo", position: Int = 0) {
            tickets[id] = TicketInfo(id = id, title = id, projectId = "p1", epicId = epic, status = status, position = position, createdAt = 0, updatedAt = 0, branch = "ticket/$id")
        }

        override fun get(id: String, by: Caller): BoardResult<TicketInfo> =
            tickets[id]?.let { BoardResult.Ok(it) } ?: BoardResult.Refused("no ticket '$id'")

        override fun update(id: String, patch: TicketPatch, by: Caller): BoardResult<TicketInfo> {
            updates++
            val t = tickets.getValue(id)
            // ticket_save: "" clears, null keeps; nothing else changes.
            val next = t.copy(epicId = when (patch.epicId) { null -> t.epicId; "" -> null; else -> patch.epicId })
            tickets[id] = next
            return BoardResult.Ok(next)
        }
    }

    private fun epicsOf(b: Board): Epics = object : Epics by NoBoard.epics {
        override fun get(id: String, by: Caller): BoardResult<EpicInfo> = b.epics[id]?.let { BoardResult.Ok(it) } ?: BoardResult.Refused("no epic")
        override fun list(projectId: String?, by: Caller): BoardResult<List<EpicInfo>> = BoardResult.Ok(b.epics.values.filter { it.projectId == projectId })
        override fun create(projectId: String, name: String, goal: String, by: Caller): BoardResult<EpicInfo> {
            val e = EpicInfo(id = "epic-%08x".format(b.epics.size + 1), projectId = projectId, name = name)
            b.epics[e.id] = e
            return BoardResult.Ok(e)
        }
    }

    private fun board() = Board().apply {
        epics["epic-aaaaaaaa"] = EpicInfo("epic-aaaaaaaa", "p1", "Launch")
        epics["epic-bbbbbbbb"] = EpicInfo("epic-bbbbbbbb", "p1", "Based", baseBranch = "release")
    }

    @Test fun `a single ticket moves to an epic named by id and keeps status and order`() {
        val b = board(); b.ticket("t1", status = "backlog", position = 3)
        val o = EpicMove.run(b, epicsOf(b), by, listOf("t1"), "epic-aaaaaaaa", force = false)
        assertEquals(listOf("t1"), o.moved.map { it.id })
        assertEquals("epic-aaaaaaaa", b.tickets.getValue("t1").epicId)
        assertEquals("backlog", b.tickets.getValue("t1").status)
        assertEquals(3, b.tickets.getValue("t1").position)
    }

    @Test fun `a bulk move by name creates the epic once and moves every ticket`() {
        val b = board(); b.ticket("t1", "epic-aaaaaaaa"); b.ticket("t2"); b.ticket("t3", status = "done")
        val o = EpicMove.run(b, epicsOf(b), by, listOf("t1", "t2", "t3"), "Post-launch", force = false)
        assertTrue(o.created)
        assertEquals(3, b.epics.size)
        val id = o.epic!!.id
        assertEquals(setOf(id), b.tickets.values.map { it.epicId }.toSet())
        // A second call finds the epic by name (case-insensitive) and changes nothing.
        val again = EpicMove.run(b, epicsOf(b), by, listOf("t1", "t2"), "post-LAUNCH", force = false)
        assertEquals(false, again.created)
        assertEquals(listOf("t1", "t2"), again.unchanged)
        assertEquals(3, b.epics.size)
    }

    @Test fun `none clears the epic`() {
        val b = board(); b.ticket("t1", "epic-aaaaaaaa")
        EpicMove.run(b, epicsOf(b), by, listOf("t1"), "none", force = false)
        assertNull(b.tickets.getValue("t1").epicId)
    }

    @Test fun `an in-progress ticket is refused when the new epic cuts from another base, and moved with force`() {
        val b = board(); b.ticket("t1", "epic-aaaaaaaa", status = "in_progress"); b.ticket("t2")
        val o = EpicMove.run(b, epicsOf(b), by, listOf("t1", "t2"), "Based", force = false)
        assertEquals(listOf("t2"), o.moved.map { it.id })
        assertEquals("t1", o.refused.single().ticketId)
        assertTrue("'release'" in o.refused.single().reason, o.refused.single().reason)
        assertEquals("epic-aaaaaaaa", b.tickets.getValue("t1").epicId)
        val forced = EpicMove.run(b, epicsOf(b), by, listOf("t1"), "Based", force = true)
        assertEquals(listOf("t1"), forced.moved.map { it.id })
        assertTrue(forced.warnings.single().contains("keeps its old base"))
    }

    @Test fun `an in-progress ticket moves freely between epics on the same base`() {
        val b = board(); b.ticket("t1", status = "in_progress")
        val o = EpicMove.run(b, epicsOf(b), by, listOf("t1"), "Launch", force = false)
        assertEquals(listOf("t1"), o.moved.map { it.id })
    }

    @Test fun `an unknown ticket or epic id refuses the whole call and writes nothing`() {
        val b = board(); b.ticket("t1")
        assertFailsWith<EpicMove.Refused> { EpicMove.run(b, epicsOf(b), by, listOf("t1", "nope"), "Launch", force = false) }
        assertFailsWith<EpicMove.Refused> { EpicMove.run(b, epicsOf(b), by, listOf("t1"), "epic-cccccccc", force = false) }
        assertEquals(0, b.updates)
        assertEquals(2, b.epics.size)
    }

    @Test fun `a new epic is not created when every ticket is refused`() {
        val b = board(); b.ticket("t1", "epic-bbbbbbbb", status = "in_progress")
        val o = EpicMove.run(b, epicsOf(b), by, listOf("t1"), "Fresh", force = false)
        assertEquals(false, o.created)
        assertEquals(2, b.epics.size)
        assertEquals(1, o.refused.size)
    }
}
