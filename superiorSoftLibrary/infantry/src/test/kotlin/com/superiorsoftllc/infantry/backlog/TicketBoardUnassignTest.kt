// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.context.ComposedContext
import com.superiorsoftllc.infantry.context.ContextInput
import com.superiorsoftllc.infantry.context.TicketContext
import com.superiorsoftllc.infantry.mailbox.FakeAgentProvider
import com.superiorsoftllc.infantry.mailbox.ScriptedRunner
import com.superiorsoftllc.infantry.mailbox.tempSessionStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.protocol.EffectiveContext
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.protocol.TicketStatus
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionManager
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AGENT-TASKS-2: `TicketBoard.unassign`. The ticket is in todo at the top at once with its worker detached; the worker is then ended
 * outside the board lock (an idle one immediately, a busy one when its turn ends or after the wait limit, `now` without waiting),
 * and exactly one handoff note is written.
 */
class TicketBoardUnassignTest {
    private val dir: File = Files.createTempDirectory("infantry-unassign").toFile()
    private val stores = mutableListOf<SqlInfantryStore>()
    private val boards = mutableListOf<TicketBoard>()

    @AfterTest
    fun cleanUp() {
        boards.forEach { runCatching { it.close() } }
        stores.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private class Rig(val board: TicketBoard, val manager: SessionManager, val runner: ScriptedRunner, val profiles: AgentProfiles) {
        val notes = CopyOnWriteArrayList<String>()
        fun ticket(title: String): String {
            val worker = profiles.save(AgentProfile(name = "Worker $title", role = "backend"))
            return board.save(TicketSave(title = title, agentId = worker.id)).ticket.id
        }
    }

    private fun rig(wait: Long = 120, mode: String = "auto"): Rig {
        val store = SqlInfantryStore.open(File(dir, "infantry-${stores.size}.db").absolutePath).also { stores += it }
        val runner = ScriptedRunner(autoExit = false)
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir, bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy())
        val profiles = AgentProfiles(store.agents, manager)
        val prompting = object : TicketContext {
            override fun compose(input: ContextInput) = ComposedContext("Ship ${input.title}. ${input.description}", emptyList())
            override fun effective(override: Boolean?, skillsOverride: Boolean?): EffectiveContext? = null
        }
        var n = 0
        val board = TicketBoard(
            store, idFactory = { "t${n++}" }, context = prompting, agents = profiles, sessions = manager,
            handoffMode = { mode }, unassignWaitSeconds = { wait },
        ).also { boards += it }
        val rig = Rig(board, manager, runner, profiles)
        val seam = com.superiorsoftllc.infantry.daemon.BoardSeam<com.superiorsoftllc.infantry.daemon.plugins.LocalBoardHost, SessionEvent>({ h, e -> h.publish(e) })
        seam.attach(board.localHost!!)
        manager.eventSink = { seam.emit(it) }
        board.handoffSink = { _, _, body -> rig.notes += body }
        board.latestHandoff = { rig.notes.lastOrNull() }
        return rig
    }

    private fun await(what: String, check: () -> Boolean) {
        val until = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < until) {
            if (check()) return
            Thread.sleep(25)
        }
        fail("timed out waiting for $what")
    }

    private fun start(rig: Rig, id: String): String {
        assertEquals("started", rig.board.move(TicketMove(id, TicketStatus.IN_PROGRESS)).ticket.start?.reason)
        return rig.board.get(id).sessionId!!
    }

    @Test
    fun anIdleWorkerIsEndedAtOnceAndTheTicketIsBackAtTheTopOfTodo() {
        val rig = rig()
        val other = rig.ticket("other")
        val id = rig.ticket("mine")
        val session = start(rig, id)
        rig.runner.processes.last().exit(0) // the turn ended: the worker is idle
        await("the worker to be idle") { rig.board.localHost!!.sessions.detail(session)?.busy == false }
        val answer = rig.board.unassign(id, note = "pick up from the tests")
        assertEquals(TicketStatus.TODO, answer.ticket.status)
        assertNull(answer.ticket.sessionId)
        assertEquals(0, answer.ticket.position, "top of todo")
        assertEquals(listOf(id, other), rig.board.list().filter { it.status == TicketStatus.TODO }.map { it.id })
        await("the idle worker to be gone") { rig.manager.get(session) == null }
        assertEquals(1, rig.notes.size, "exactly one handoff note")
        assertTrue(rig.notes.single().contains("pick up from the tests"), rig.notes.single())
    }

    @Test
    fun aBusyWorkerIsEndedOnlyAfterItsTurnEnds() {
        val rig = rig()
        val id = rig.ticket("busy")
        val session = start(rig, id)
        val answer = rig.board.unassign(id)
        assertEquals(TicketStatus.TODO, answer.ticket.status, "in todo at once")
        Thread.sleep(300)
        assertNotNull(rig.manager.get(session), "the busy worker is still there while its turn runs")
        assertEquals(0, rig.notes.size, "no note before the worker ends")
        rig.runner.processes.last().exit(0)
        await("the worker to be ended after its turn") { rig.manager.get(session) == null }
        await("the handoff note") { rig.notes.size == 1 }
    }

    @Test
    fun aBusyWorkerIsForcedAfterTheWaitLimit() {
        val rig = rig(wait = 1)
        val id = rig.ticket("stuck")
        val session = start(rig, id)
        rig.board.unassign(id)
        await("the forced end") { rig.manager.get(session) == null }
        assertEquals(1, rig.notes.size)
    }

    @Test
    fun nowEndsABusyWorkerWithoutWaiting() {
        val rig = rig()
        val id = rig.ticket("now")
        val session = start(rig, id)
        rig.board.unassign(id, now = true)
        await("the worker to be gone") { rig.manager.get(session) == null }
        assertEquals(1, rig.notes.size)
    }

    @Test
    fun offWritesNoNoteAndOnlyAnInProgressTicketCanBeUnassigned() {
        val off = rig(mode = "off")
        val id = off.ticket("quiet")
        start(off, id)
        off.board.unassign(id, now = true)
        Thread.sleep(300)
        assertEquals(0, off.notes.size)
        val refused = assertFailsWith<IllegalArgumentException> { off.board.unassign(id) }
        assertTrue(refused.message!!.contains("not in_progress"), refused.message)
    }

    @Test
    fun theNextStartBriefCarriesTheLatestHandoff() {
        val rig = rig()
        val id = rig.ticket("again")
        val session = start(rig, id)
        rig.board.unassign(id, now = true, note = "first note")
        await("the first worker to be gone") { rig.manager.get(session) == null }
        val again = start(rig, id)
        val brief = rig.board.brief(again)
        assertNotNull(brief)
        assertTrue(brief.contains("first note"), "the latest handoff is in the brief: $brief")
    }
}
