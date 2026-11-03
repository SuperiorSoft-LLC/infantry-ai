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
import com.superiorsoftllc.infantry.protocol.TicketStart
import com.superiorsoftllc.infantry.protocol.TicketStatus.IN_PROGRESS
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionState
import com.superiorsoftllc.infantry.session.TicketWorkerHealth
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GAP-006b (ticket 006b778b): a ticket in progress has a worker that received its brief, or it is started again / surfaced.
 *
 * The phantom it fixes: `infantry_ticket_start` answered started, yet the worker sat IDLE with an empty transcript, one run
 * and no provider session id; prompting it was refused "not resumable", and a second start answered already_in_progress
 * and ran nothing. Here the scripted harness reproduces it by never revealing a provider session id and ending the turn.
 */
class TicketWorkerInvariantTest {

    private val dir: File = Files.createTempDirectory("infantry-worker-invariant").toFile()
    private val opened = mutableListOf<SqlInfantryStore>()
    private val boards = mutableListOf<TicketBoard>()

    @AfterTest
    fun cleanUp() {
        boards.forEach { runCatching { it.close() } }
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private class Rig(val board: TicketBoard, val manager: SessionManager, val runner: ScriptedRunner, val turns: MutableList<SessionEvent.Turn>, val ticketId: String)

    /** A board over a scripted harness; [briefed] false = the harness never reveals a provider session id. */
    private fun rig(briefed: Boolean, clock: () -> Long = System::currentTimeMillis): Rig {
        val store = SqlInfantryStore.open(File(dir, "infantry-${opened.size}.db").absolutePath).also { opened += it }
        val runner = ScriptedRunner(autoExit = false, emitSessionId = briefed)
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir, bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy())
        val profiles = AgentProfiles(store.agents, manager)
        val worker = profiles.save(AgentProfile(name = "Backend", role = "backend"))
        val prompting = object : TicketContext {
            override fun compose(input: ContextInput) = ComposedContext("Ship ${input.title}.", emptyList())
            override fun effective(override: Boolean?, skillsOverride: Boolean?): EffectiveContext? = null
        }
        var n = 0
        val board = TicketBoard(
            store, clock = clock, idFactory = { "t${n++}" }, context = prompting, agents = profiles, sessions = manager,
            exitBehavior = { "leave" },
        ).also { boards += it }
        val turns = CopyOnWriteArrayList<SessionEvent.Turn>()
        manager.eventSink = { event ->
            if (event is SessionEvent.Turn) turns += event
            board.localHost?.publish(event)
        }
        manager.addFirstTurnProvider { sessionId, _ -> board.brief(sessionId) }
        val ticket = board.save(TicketSave(title = "Add login", agentId = worker.id)).ticket
        return Rig(board, manager, runner, turns, ticket.id)
    }

    private fun Rig.start() = board.move(TicketMove(ticketId, IN_PROGRESS))

    private fun Rig.worker() = board.get(ticketId).sessionId?.let(manager::get)

    /** Ends the current turn with no output at all, as the phantom's did, and waits (bounded) for it to settle at rest. */
    private fun Rig.endSilently(exitCode: Int = 0) {
        runner.processes.last().exit(exitCode)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (worker()?.live == true && System.nanoTime() < deadline) Thread.sleep(10)
    }

    @Test
    fun aWorkerWhoseFirstTurnEndedSilentlyIsThePhantom() {
        val rig = rig(briefed = false)
        assertEquals("started", rig.start().ticket.start?.reason)
        rig.endSilently()

        val phantom = assertNotNull(rig.worker())
        assertEquals(SessionState.IDLE, phantom.state)
        assertEquals(1, phantom.runCount)
        assertNull(phantom.providerSessionId)
        assertEquals(IN_PROGRESS, rig.board.get(rig.ticketId).status)
        val problem = assertNotNull(TicketWorkerHealth.problem(phantom), "the phantom must read as unable to carry its ticket")
        assertTrue(problem.contains("never received its brief"), problem)
    }

    @Test
    fun aStartOfAnInProgressTicketWithAPhantomWorkerStartsItAgainWithItsBrief() {
        val rig = rig(briefed = false)
        rig.start()
        rig.endSilently()
        val phantom = rig.worker()!!.id

        val again = rig.start().ticket

        assertEquals("started", again.start?.reason, "a start of a phantom's ticket must restart it, not answer already_in_progress")
        assertTrue(again.start?.detail.orEmpty().startsWith(TicketStart.RESTARTED), "${again.start?.detail}")
        assertEquals(IN_PROGRESS, again.status)
        val fresh = assertNotNull(again.sessionId)
        assertNotEquals(phantom, fresh)
        assertNull(rig.manager.get(phantom), "the phantom is removed, so the ticket never has two instances")
        assertEquals(1, rig.manager.list().count { it.ticketId == rig.ticketId }, "one worker for the ticket")
        assertTrue(rig.manager.get(fresh)!!.live, "the new worker runs")
        assertEquals("Ship Add login.", rig.turns.last { it.sessionId == fresh }.prompt, "the new worker's first turn is the ticket's brief")
    }

    @Test
    fun aStartInterruptedBeforeTheHarnessAnsweredIsRestartedByTheNextStart() {
        val rig = rig(briefed = false)
        rig.start()
        val interrupted = rig.worker()!!.id
        rig.manager.interrupt(interrupted)
        assertEquals(SessionState.IDLE, rig.manager.get(interrupted)?.state)

        val again = rig.start().ticket

        assertEquals("started", again.start?.reason)
        assertNotEquals(interrupted, again.sessionId)
        assertTrue(rig.manager.get(again.sessionId!!)!!.live)
    }

    @Test
    fun aStartOfAnInProgressTicketWithAWorkingWorkerStartsNothingAndSaysSo() {
        val rig = rig(briefed = true)
        rig.start()
        val worker = rig.worker()!!.id

        val again = rig.start().ticket

        assertEquals(TicketStart.ALREADY_IN_PROGRESS, again.start?.reason)
        assertEquals(false, again.start?.ok)
        assertEquals(worker, again.sessionId)
        assertEquals(1, rig.runner.specs.size, "nothing new ran")
    }

    @Test
    fun aWorkerAtRestThatTookItsBriefIsLeftForResume() {
        val rig = rig(briefed = true)
        rig.start()
        rig.endSilently()

        assertNull(TicketWorkerHealth.problem(rig.worker()), "a briefed worker at rest resumes; it is not restarted")
        assertEquals(TicketStart.ALREADY_IN_PROGRESS, rig.start().ticket.start?.reason)
    }

    @Test
    fun aPromptToAPhantomWorkerRunsAFirstTurnCarryingTheTicketBrief() {
        val rig = rig(briefed = false)
        rig.start()
        rig.endSilently()
        val phantom = rig.worker()!!.id

        rig.manager.resume(phantom, "hello")

        val turn = rig.turns.last { it.sessionId == phantom }.prompt
        assertTrue(turn.startsWith("Ship Add login.") && turn.endsWith("hello"), turn)
        assertEquals(listOf("fake", "FRESH"), rig.runner.specs.last().command, "a first turn, never a resume of nothing")
    }

    @Test
    fun theSweepRestartsAPhantomOnceThenSurfacesTheNext() {
        val rig = rig(briefed = false)
        rig.start()
        rig.endSilently()
        val flags = mutableListOf<String>()

        assertEquals(emptyList(), rig.board.sweepWorkers(graceMs = TimeUnit.HOURS.toMillis(1)) { _, _, d -> flags += d }, "inside the grace nothing happens")
        assertEquals(listOf(rig.ticketId), rig.board.sweepWorkers(graceMs = 0) { _, _, d -> flags += d })
        assertTrue(flags.single().startsWith("Restarted"), "$flags")
        assertTrue(rig.worker()!!.live)

        rig.endSilently()
        assertEquals(emptyList(), rig.board.sweepWorkers(graceMs = 0) { _, _, d -> flags += d }, "a second phantom is not restarted again")
        assertTrue(flags.last().contains("already restarted once"), "$flags")
        rig.board.sweepWorkers(graceMs = 0) { _, _, d -> flags += d }
        assertEquals(2, flags.size, "one problem is surfaced once")
    }

    @Test
    fun theSweepNeverStartsAnInProgressTicketWithNoWorkerButSurfacesIt() {
        val rig = rig(briefed = false)
        // The deprecated story_move shim files a ticket in progress with no worker (someone works it off the board).
        rig.board.move(TicketMove(rig.ticketId, IN_PROGRESS), startOnInProgress = false)
        val flags = mutableListOf<String>()

        assertEquals(emptyList(), rig.board.sweepWorkers(graceMs = 0) { _, _, d -> flags += d })

        assertTrue(rig.runner.specs.isEmpty(), "the sweep starts nothing for it")
        assertTrue(flags.single().contains("no session - start it"), "$flags")
        // An explicit start does start it.
        assertEquals("started", rig.start().ticket.start?.reason)
    }

    @Test
    fun theSweepFlagsABusyWorkerThatOpenedNoProviderSessionButNeverEndsIt() {
        val rig = rig(briefed = false)
        rig.start()
        val flags = mutableListOf<String>()

        rig.board.sweepWorkers(graceMs = 0) { _, _, d -> flags += d }

        assertTrue(rig.worker()!!.live, "a slow first boot is flagged, never ended")
        assertTrue(flags.single().contains("opened no provider session"), "$flags")
    }
}
