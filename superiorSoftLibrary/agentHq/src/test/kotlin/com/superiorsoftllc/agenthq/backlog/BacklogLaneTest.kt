package com.superiorsoftllc.agenthq.backlog

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.mailbox.FakeAgentProvider
import com.superiorsoftllc.agenthq.mailbox.ScriptedRunner
import com.superiorsoftllc.agenthq.mailbox.tempSessionStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.TicketLinkRequest
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.protocol.TicketStatus.BACKLOG
import com.superiorsoftllc.agenthq.protocol.TicketStatus.DONE
import com.superiorsoftllc.agenthq.protocol.TicketStatus.IN_PROGRESS
import com.superiorsoftllc.agenthq.protocol.TicketStatus.PENDING
import com.superiorsoftllc.agenthq.protocol.TicketStatus.TODO
import com.superiorsoftllc.agenthq.session.SessionManager
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BACKLOG (contract 1.131.0, ticket-39eab724): `backlog` is limbo before `todo` (the sprint). Over the real SQLite store
 * and a board that CAN start tickets (a profile with a role and a session manager), so every "not started" below is
 * the lane's doing: the same ticket starts once it is promoted to todo.
 */
class BacklogLaneTest {

    private val dir: File = Files.createTempDirectory("agenthq-backlog-lane").toFile()
    private val opened = mutableListOf<AutoCloseable>()

    @AfterTest
    fun cleanUp() {
        opened.asReversed().forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private class Rig(val board: TicketBoard, val workerId: String)

    private fun rig(): Rig {
        val store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath).also { opened += it }
        val manager = SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = false), tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir)
        val profiles = AgentProfiles(store.agents, manager)
        val worker = profiles.save(AgentProfile(name = "Backend", role = "backend"))
        val ids = ('a'..'z').map { it.toString() }.iterator()
        val board = TicketBoard(store, idFactory = { ids.next() }, agents = profiles, sessions = manager).also { b -> opened += AutoCloseable { b.close() } }
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
