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
import com.superiorsoftllc.infantry.protocol.TicketStatus.DONE
import com.superiorsoftllc.infantry.protocol.TicketStatus.IN_PROGRESS
import com.superiorsoftllc.infantry.protocol.TicketStatus.TODO
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionState
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AUTOSTART-SAME-AGENT-1 (ticket ca8c0955): auto-start put three tickets on ONE backend agent while its worker rested between
 * turns on another in-progress ticket. The extra sessions could never run beside it (one agent works one ticket in one session)
 * and sat "idle, out never" for hours. An agent whose session works another in-progress ticket is taken, even at rest: a
 * second ticket for it queues (a move) or is refused (start-next, a trigger), and starts when the first ticket leaves the column.
 */
class TicketStartSameAgentTest {

    private val dir: File = Files.createTempDirectory("infantry-same-agent").toFile()
    private val opened = mutableListOf<SqlInfantryStore>()
    private val boards = mutableListOf<TicketBoard>()

    @AfterTest
    fun cleanUp() {
        boards.forEach { runCatching { it.close() } }
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private class Rig(val board: TicketBoard, val manager: SessionManager, val runner: ScriptedRunner, val agentId: String)

    private fun rig(): Rig {
        val store = SqlInfantryStore.open(File(dir, "infantry-${opened.size}.db").absolutePath).also { opened += it }
        val runner = ScriptedRunner(autoExit = false, emitSessionId = true)
        val manager = SessionManager(
            FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir,
            bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy(),
        )
        val profiles = AgentProfiles(store.agents, manager)
        val worker = profiles.save(AgentProfile(name = "Backend", role = "backend"))
        val prompting = object : TicketContext {
            override fun compose(input: ContextInput) = ComposedContext("Ship ${input.title}.", emptyList())
            override fun effective(override: Boolean?, skillsOverride: Boolean?): EffectiveContext? = null
        }
        var n = 0
        val board = TicketBoard(
            store, idFactory = { "t${n++}" }, context = prompting, agents = profiles, sessions = manager,
            exitBehavior = { "leave" },
        ).also { boards += it }
        manager.eventSink = { board.localHost?.publish(it) }
        manager.addFirstTurnProvider { sessionId, _ -> board.brief(sessionId) }
        return Rig(board, manager, runner, worker.id!!)
    }

    /** Starts [ticketId], then ends the worker's turn so it rests between turns (IDLE, briefed), its ticket still in progress. */
    private fun Rig.startAndRest(ticketId: String): String {
        assertEquals("started", board.move(TicketMove(ticketId, IN_PROGRESS)).ticket.start?.reason)
        val session = board.get(ticketId).sessionId!!
        runner.processes.last().exit(0)
        waitFor { manager.get(session)?.state == SessionState.IDLE && board.get(ticketId).lastRun != null }
        assertEquals(IN_PROGRESS, board.get(ticketId).status)
        return session
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
    }

    private fun Rig.sessionsOfAgent() = manager.list().filter { it.agentId == agentId }

    @Test
    fun aSecondTicketForAnAgentRestingOnAnInProgressTicketQueuesInsteadOfASecondSession() {
        val rig = rig()
        val first = rig.board.save(TicketSave(title = "First", agentId = rig.agentId)).ticket.id
        rig.startAndRest(first)
        val second = rig.board.save(TicketSave(title = "Second", agentId = rig.agentId)).ticket.id

        val answer = rig.board.move(TicketMove(second, IN_PROGRESS)).ticket

        assertEquals("queued", answer.start?.reason, "the agent is on $first: ${answer.start}")
        assertEquals(TODO, answer.status)
        assertEquals(1, rig.sessionsOfAgent().size, "one agent, one session: ${rig.sessionsOfAgent().map { it.id }}")
    }

    @Test
    fun startNextRefusesATicketWhoseAgentRestsOnAnotherInProgressTicket() {
        val rig = rig()
        val first = rig.board.save(TicketSave(title = "First", agentId = rig.agentId)).ticket.id
        rig.startAndRest(first)
        rig.board.save(TicketSave(title = "Second", agentId = rig.agentId))

        val refusal = assertFailsWith<IllegalArgumentException> { rig.board.startNext() }

        assertTrue(refusal.message.orEmpty().contains("at capacity"), refusal.message)
        assertEquals(1, rig.sessionsOfAgent().size)
    }

    @Test
    fun aRoleTicketIsNotHandedToTheRolesAgentWhileItRestsOnAnotherTicket() {
        val rig = rig()
        val first = rig.board.save(TicketSave(title = "First", agentId = "role:backend")).ticket.id
        rig.startAndRest(first)
        val second = rig.board.save(TicketSave(title = "Second", agentId = "role:backend")).ticket.id

        val answer = rig.board.move(TicketMove(second, IN_PROGRESS)).ticket

        // The lane cap is 1 here, so no extra agent is added: the ticket waits in the role's lane.
        assertEquals("queued", answer.start?.reason, "${answer.start}")
        assertEquals(1, rig.manager.list().size, "no second session on the busy agent")
    }

    @Test
    fun theQueuedTicketStartsWhenTheAgentsTicketLeavesInProgressWithItsWorkerAtRest() {
        val rig = rig()
        val first = rig.board.save(TicketSave(title = "First", agentId = rig.agentId)).ticket.id
        rig.startAndRest(first)
        val second = rig.board.save(TicketSave(title = "Second", agentId = rig.agentId)).ticket.id
        assertEquals("queued", rig.board.move(TicketMove(second, IN_PROGRESS)).ticket.start?.reason)

        rig.board.move(TicketMove(first, DONE))
        waitFor { rig.board.get(second).status == IN_PROGRESS }

        val started = rig.board.get(second)
        assertEquals(IN_PROGRESS, started.status, "the drain starts the queued ticket once the agent is free")
        val worker = assertNotNull(started.sessionId)
        assertEquals(rig.agentId, rig.manager.get(worker)?.agentId)
    }

    @Test
    fun theSweepFlagsAWorkerAtRestThatNeverProducedOutputUnderTheLeaveExitBehavior() {
        val rig = rig()
        val ticket = rig.board.save(TicketSave(title = "First", agentId = rig.agentId)).ticket.id
        rig.startAndRest(ticket)
        val flags = mutableListOf<String>()

        assertEquals(emptyList(), rig.board.sweepWorkers(graceMs = 0) { _, _, d -> flags += d }, "a briefed worker is flagged, never restarted")

        assertTrue(flags.single().contains("produced no output at all"), "$flags")
    }
}
