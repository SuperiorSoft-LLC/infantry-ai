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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AGENT-TASKS-4: `TicketBoard.assign` and `switch`: start on an existing agent, refusals, queue mode, and the A-to-B switch.
 */
class AgentAssignSwitchTest {
    private val dir: File = Files.createTempDirectory("infantry-agentassign").toFile()
    private val stores = mutableListOf<SqlInfantryStore>()
    private val boards = mutableListOf<TicketBoard>()

    @AfterTest
    fun cleanUp() {
        boards.forEach { runCatching { it.close() } }
        stores.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private class Rig(val board: TicketBoard, val manager: SessionManager, val runner: ScriptedRunner, val profiles: AgentProfiles) {
        val x = profiles.save(AgentProfile(name = "Xavier", role = "backend"))
        val xId: String get() = x.id!!
        fun ticket(title: String, agent: String? = null): String = board.save(TicketSave(title = title, agentId = agent)).ticket.id
        fun queued(): List<String> = board.queue(xId).tickets.map { it.ticketId }
    }

    private fun rig(max: Long = 5): Rig {
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
            queueMax = { max },
        ).also { boards += it }
        val seam = com.superiorsoftllc.infantry.daemon.BoardSeam<com.superiorsoftllc.infantry.daemon.plugins.LocalBoardHost, SessionEvent>({ h, e -> h.publish(e) })
        seam.attach(board.localHost!!)
        manager.eventSink = { seam.emit(it) }
        return Rig(board, manager, runner, profiles)
    }

    private fun await(what: String, check: () -> Boolean) {
        val until = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < until) {
            if (check()) return
            Thread.sleep(25)
        }
        fail("timed out waiting for $what")
    }

    @Test
    fun assignToAnAgentWithNoSessionStartsTheTicketForThatProfile() {
        val rig = rig()
        val a = rig.ticket("a")
        val answer = rig.board.assign(a, "Xavier")
        assertTrue(answer.ticket.start?.ok == true, "start: ${answer.ticket.start}")
        val t = rig.board.get(a)
        assertEquals(TicketStatus.IN_PROGRESS, t.status)
        assertEquals(rig.xId, t.agentId)
        assertTrue(t.branch != null || t.sessionId != null)
    }

    @Test
    fun assignRefusesAnAgentOnAnotherTicketAndAForeignOrUnknownRole() {
        val rig = rig()
        val a = rig.ticket("a"); val b = rig.ticket("b")
        rig.board.assign(a, rig.xId)
        val msg = assertFailsWith<IllegalArgumentException> { rig.board.assign(b, rig.xId) }.message!!
        assertTrue(msg.contains("use switch, or assign --queue"), msg)
        assertEquals(TicketStatus.TODO, rig.board.get(b).status)
        assertTrue(assertFailsWith<IllegalArgumentException> { rig.board.assign(a, rig.xId, AssignMode.QUEUE) }.message!!.contains("only a todo"))
        val foreign = rig.profiles.save(AgentProfile(name = "Zed", role = "backend", projectId = "other-project"))
        assertTrue(assertFailsWith<IllegalArgumentException> { rig.board.assign(b, foreign.id!!) }.message!!.contains("another project"))
    }

    @Test
    fun assignInQueueModeAddsToTheAgentQueueAtThePlace() {
        val rig = rig()
        val a = rig.ticket("a"); val b = rig.ticket("b")
        rig.board.assign(a, rig.xId, AssignMode.QUEUE)
        rig.board.assign(b, rig.xId, AssignMode.QUEUE, at = 1)
        assertEquals(listOf(b, a), rig.queued())
        assertEquals(rig.xId, rig.board.get(b).agentId)
        assertEquals(TicketStatus.TODO, rig.board.get(b).status)
    }

    @Test
    fun switchPutsAInTodoWithItsBranchAndStartsBOnTheSameAgentWithNeverTwoInProgress() {
        val rig = rig()
        val a = rig.ticket("a"); val b = rig.ticket("b")
        rig.board.assign(a, rig.xId)
        val branchA = rig.board.get(a).branch
        val answer = rig.board.switch(rig.xId, b, now = true)
        assertEquals("deferred", answer.ticket.start?.reason)
        val mid = rig.board.get(a)
        assertEquals(TicketStatus.TODO, mid.status)
        assertEquals(branchA, mid.branch, "A keeps its branch")
        assertTrue(rig.board.list().count { it.status == TicketStatus.IN_PROGRESS && it.agentId == rig.xId } <= 1)
        rig.runner.processes.last().exit(0)
        await("B to start") { rig.board.get(b).status == TicketStatus.IN_PROGRESS }
        assertEquals(rig.xId, rig.board.get(b).agentId)
        assertEquals(TicketStatus.TODO, rig.board.get(a).status)
        assertTrue(rig.board.list().count { it.status == TicketStatus.IN_PROGRESS && it.agentId == rig.xId } == 1)
    }

    @Test
    fun switchWithNoCurrentTicketIsAnAssign() {
        val rig = rig()
        val a = rig.ticket("a")
        rig.board.switch(rig.xId, a)
        assertEquals(TicketStatus.IN_PROGRESS, rig.board.get(a).status)
    }
}
