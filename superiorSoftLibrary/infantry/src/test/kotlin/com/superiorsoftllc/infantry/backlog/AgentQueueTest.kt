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
 * AGENT-TASKS-3: `TicketBoard.queue`, an agent's persistent ticket queue (add, remove, move, clear, the cap, todo only, one queue per
 * ticket), `startNext` skipping queued tickets, and the drain when the agent's worker exits.
 */
class AgentQueueTest {
    private val dir: File = Files.createTempDirectory("infantry-agentqueue").toFile()
    private val stores = mutableListOf<SqlInfantryStore>()
    private val boards = mutableListOf<TicketBoard>()

    /** AGENT-QUEUE-HANG-1: a list never waits for the board monitor (a board write or hook holding it). */
    @Test
    fun listDoesNotBlockOnHeldBoardMonitor() {
        val r = rig()
        r.board.queue(r.xId, "add", r.ticket("a"))
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder = Thread { synchronized(r.board) { held.countDown(); release.await() } }.also { it.start() }
        held.await()
        try {
            val t0 = System.nanoTime()
            repeat(20) { assertEquals(1, r.board.queue(r.xId).tickets.size) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms < 2000, "20 lists took $ms ms while the board monitor was held")
        } finally {
            release.countDown(); holder.join()
        }
    }

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
    fun addRemoveMoveClearKeepPlacesAndAcceptANameOrId() {
        val rig = rig()
        val a = rig.ticket("a"); val b = rig.ticket("b"); val c = rig.ticket("c")
        rig.board.queue("Xavier", "add", a)
        rig.board.queue(rig.xId, "add", b)
        rig.board.queue(rig.xId, "add", c, at = 1)
        assertEquals(listOf(c, a, b), rig.queued())
        assertEquals(1, rig.board.get(c).queueRank)
        assertEquals(rig.xId, rig.board.get(c).queuedAgent)
        rig.board.queue(rig.xId, "move", b, at = 1)
        assertEquals(listOf(b, c, a), rig.queued())
        rig.board.queue(rig.xId, "remove", c)
        assertEquals(listOf(b, a), rig.queued())
        assertEquals(listOf(1, 2), rig.board.queue(rig.xId).tickets.map { it.rank })
        assertNull(rig.board.get(c).queuedAgent)
        rig.board.queue(rig.xId, "clear")
        assertEquals(emptyList(), rig.queued())
        assertNull(rig.board.get(a).queueRank)
    }

    @Test
    fun theCapTheTodoOnlyRuleAndOneQueuePerTicketAreEnforced() {
        val rig = rig(max = 2)
        val other = rig.profiles.save(AgentProfile(name = "Yara", role = "backend"))
        val a = rig.ticket("a"); val b = rig.ticket("b"); val c = rig.ticket("c")
        rig.board.queue(rig.xId, "add", a)
        rig.board.queue(rig.xId, "add", b)
        assertTrue(assertFailsWith<IllegalArgumentException> { rig.board.queue(rig.xId, "add", c) }.message!!.contains("full"))
        assertTrue(assertFailsWith<IllegalArgumentException> { rig.board.queue(other.id!!, "add", a) }.message!!.contains("already queued"))
        rig.board.move(TicketMove(c, TicketStatus.BACKLOG))
        assertTrue(assertFailsWith<IllegalArgumentException> { rig.board.queue(other.id!!, "add", c) }.message!!.contains("only a todo"))
    }

    @Test
    fun startNextNeverPicksAQueuedTicket() {
        val rig = rig()
        val a = rig.ticket("a"); val b = rig.ticket("b", rig.xId)
        rig.board.queue(rig.xId, "add", a)
        assertEquals(b, rig.board.startNext().ticket.id)
        assertEquals(TicketStatus.TODO, rig.board.get(a).status)
        assertEquals(listOf(a), rig.queued())
    }

    @Test
    fun aHandStartPullsTheTicketOutOfTheQueue() {
        val rig = rig()
        val a = rig.ticket("a", rig.xId)
        rig.board.queue(rig.xId, "add", a)
        rig.board.move(TicketMove(a, TicketStatus.IN_PROGRESS))
        assertNull(rig.board.get(a).queuedAgent)
        assertEquals(emptyList(), rig.queued())
    }

    @Test
    fun whenTheDoneTicketsWorkerExitsTheNextQueuedTicketStartsOnThatAgent() {
        val rig = rig()
        val a = rig.ticket("a", rig.xId); val b = rig.ticket("b"); val c = rig.ticket("c")
        rig.board.move(TicketMove(a, TicketStatus.IN_PROGRESS))
        val first = rig.board.get(a).sessionId!!
        rig.board.queue(rig.xId, "add", b)
        rig.board.queue(rig.xId, "add", c)
        rig.board.move(TicketMove(a, TicketStatus.DONE))
        rig.runner.processes.last().exit(0)
        await("B to start") { rig.board.get(b).status == TicketStatus.IN_PROGRESS }
        val started = rig.board.get(b)
        assertEquals(rig.xId, started.agentId, "B runs on X")
        assertTrue(started.sessionId != null && started.sessionId != first)
        assertNull(started.queuedAgent)
        assertEquals(TicketStatus.TODO, rig.board.get(c).status)
        assertEquals(listOf(c), rig.queued())
        assertEquals(1, rig.board.get(c).queueRank, "C is now #1")
    }

    @Test
    fun leavingTodoDropsTheTicketFromTheQueueButAMoveWithinTodoKeepsIt() {
        val rig = rig()
        val a = rig.ticket("a")
        rig.board.queue(rig.xId, "add", a)
        rig.board.move(TicketMove(a, TicketStatus.TODO))
        assertEquals(listOf(a), rig.queued(), "a move within todo keeps it")
        rig.board.move(TicketMove(a, TicketStatus.BACKLOG))
        assertEquals(emptyList(), rig.queued())
    }
}
