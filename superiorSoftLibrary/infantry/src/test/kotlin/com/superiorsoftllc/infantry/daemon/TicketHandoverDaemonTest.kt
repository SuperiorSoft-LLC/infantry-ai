package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.AgentSwapped
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.SwapSession
import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TICKET-HANDOVER over the real daemon: switching a ticket worker's harness gives the old agent a last turn to write a
 * handoff note, THEN moves the session, and the new harness's first run carries the full ticket brief and that note.
 * A handoff that does not finish in time still moves the session, and the first run still carries the brief.
 */
class TicketHandoverDaemonTest {

    /** Every run's stdin, in the order the runs started, across both harnesses: "<harness>: <input>". */
    private class Log {
        val runs = CopyOnWriteArrayList<String>()
    }

    /**
     * A harness whose reply to [SessionHandover.HANDOFF_PROMPT] is [NOTE] (or nothing at all, ever, when [hangOnHandoff]:
     * a stuck agent), and a short acknowledgement to anything else.
     */
    private class HandoffRunner(private val name: String, private val log: Log, private val hangOnHandoff: Boolean = false) : ProcessRunner {
        override val id: String = "handoff-$name"
        val inputs = CopyOnWriteArrayList<String>()

        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess = object : RunningProcess {
            private val input = StringBuilder()
            private val alive = AtomicBoolean(true)
            private val done = CountDownLatch(1)
            override val isAlive: Boolean get() = alive.get()
            override fun writeInput(text: String) {
                input.append(text)
            }

            override fun closeInput() {
                val text = input.toString()
                inputs += text
                log.runs += "$name: $text"
                Thread({
                    try {
                        val handoff = text.startsWith("HANDOFF:")
                        if (handoff && hangOnHandoff) {
                            done.await(60, TimeUnit.SECONDS)
                            return@Thread
                        }
                        listener.onStdout("""{"type":"step_start","sessionID":"$name-session"}""")
                        listener.onStdout(textLine(if (handoff) NOTE else "ack from $name"))
                        listener.onStdout("""{"type":"step_finish"}""")
                        listener.onExit(0)
                    } finally {
                        alive.set(false)
                        done.countDown()
                    }
                }, "handoff-run").apply { isDaemon = true }.start()
            }

            override fun killTree() {
                alive.set(false)
                done.countDown()
            }

            override fun awaitExit(): Int {
                done.await(60, TimeUnit.SECONDS)
                return 0
            }
        }
    }

    private class Second(val runner: ProcessRunner) : AgentProvider by StubProvider(delayMs = 0) {
        override val id = "second"
        override fun transport(): ProcessRunner = runner
    }

    private class Rig(val f: DaemonFixture, val old: HandoffRunner, val second: HandoffRunner, val log: Log)

    private fun withDaemon(hangOnHandoff: Boolean = false, timeoutMs: Long = 30_000, block: (Rig) -> Unit) {
        val log = Log()
        val old = HandoffRunner("old", log, hangOnHandoff)
        val second = HandoffRunner("second", log)
        val f = DaemonFixture.launch(runner = old, additionalProviders = listOf(Second(second)), handoffTimeoutMs = timeoutMs)
        try {
            f.client.await(Envelope.READY)
            block(Rig(f, old, second, log))
        } finally {
            f.stop()
            f.deleteState()
        }
    }

    /** Start a ticket for the `backend` agent; returns (ticketId, sessionId) once its first turn is at rest. */
    private fun Rig.startTicket(): Pair<String, String> {
        f.client.saveProfile("""{"id":"backend","name":"Backend","role":"backend"}""")
        val ticket = f.client.saveTicket("""{"title":"$TITLE","description":"$DESCRIPTION","agentId":"backend","useWorktree":false}""")
        val started = f.client.moveTicket(ticket.id, "in_progress")
        assertEquals("started", started.start?.reason, "the ticket starts: ${started.start}")
        val sessionId = started.sessionId!!
        f.await(what = "the ticket's first turn at rest") { f.daemon.manager.get(sessionId)!!.let { !it.live && it.runCount >= 1 } }
        assertEquals(1, old.inputs.size)
        assertTrue(old.inputs.single().contains(TITLE), "the first turn is the ticket brief")
        return ticket.id to sessionId
    }

    private fun Rig.swapToSecond(sessionId: String) {
        val swaps = f.client.of(Envelope.AGENT_SWAPPED).size
        f.send(Envelope(Envelope.AGENT_SWAP, """{"agentId":"backend","provider":"second"}"""))
        val swapped = JSON.decodeFromString(AgentSwapped.serializer(), f.client.await(Envelope.AGENT_SWAPPED, swaps).data)
        assertEquals(SwapSession(SwapSession.KEPT, sessionId), swapped.session, "the worker keeps its session")
    }

    private fun Rig.awaitFirstRunOnSecond(sessionId: String) {
        f.await(what = "the first run on the new harness, at rest") {
            second.inputs.isNotEmpty() && f.daemon.manager.get(sessionId)!!.let { !it.live && it.provider == "second" }
        }
    }

    private fun assertBrief(input: String, ticketId: String) {
        assertTrue(input.contains("You are running inside Infantry AI. Ticket: $ticketId."), "the brief's Infantry AI line: ${input.take(600)}")
        assertTrue(input.contains(TITLE) && input.contains(DESCRIPTION), "the ticket title and description: ${input.take(600)}")
        assertTrue(input.contains("infantry ticket move $ticketId done"), "the ticket rules")
    }

    @Test
    fun aSwitchedTicketWorkerHandsOffThenResumesWithTheBriefAndTheNote() = withDaemon { r ->
        val (ticketId, sessionId) = r.startTicket()
        r.swapToSecond(sessionId)
        r.awaitFirstRunOnSecond(sessionId)

        // (1) the old agent got the handoff prompt, on its old harness, BEFORE the new harness ran anything.
        assertEquals(SessionHandover.HANDOFF_PROMPT, r.old.inputs.last(), "the old agent's last turn is the handoff prompt")
        val handoffAt = r.log.runs.indexOfFirst { it == "old: ${SessionHandover.HANDOFF_PROMPT}" }
        val firstNewAt = r.log.runs.indexOfFirst { it.startsWith("second: ") }
        assertTrue(handoffAt in 0 until firstNewAt, "handoff before the new session: ${r.log.runs.map { it.take(40) }}")

        // (2) the new harness's first prompt: the ticket brief, then the note as "you are resuming".
        val first = r.second.inputs.single()
        assertBrief(first, ticketId)
        assertTrue(first.contains(SessionHandover.RESUMING_HEADING), "resuming heading: ${first.takeLast(600)}")
        assertTrue(first.contains(NOTE), "the handoff note: ${first.takeLast(600)}")
        assertTrue(first.indexOf(TITLE) < first.indexOf(NOTE), "brief first, then the note")
        assertFalse(first.contains("[Our conversation so far]"), "the note replaces the replayed conversation")

        // The session keeps its ticket binding; the ticket still names it.
        val after = r.f.daemon.manager.get(sessionId)!!
        assertEquals(ticketId, after.ticketId)
        assertEquals("second", after.provider)
        assertEquals(sessionId, r.f.client.board().single { it.id == ticketId }.sessionId)
        assertFalse(r.f.daemon.manager.isHeld(sessionId), "the hold ends with the switch")
    }

    @Test
    fun aHandoffThatTimesOutStillStartsTheNewSessionWithTheBrief() = withDaemon(hangOnHandoff = true, timeoutMs = 1_500) { r ->
        val (ticketId, sessionId) = r.startTicket()
        r.swapToSecond(sessionId)
        r.awaitFirstRunOnSecond(sessionId)

        assertEquals(SessionHandover.HANDOFF_PROMPT, r.old.inputs.last(), "the old agent was asked for a handoff")
        val first = r.second.inputs.single()
        assertBrief(first, ticketId)
        assertTrue(first.contains(SessionHandover.NO_HANDOFF_HEADING), "says there is no handoff: ${first.takeLast(600)}")
        assertTrue(first.contains("did not finish its handoff"), "and why: ${first.takeLast(600)}")
        assertFalse(first.contains(SessionHandover.RESUMING_HEADING))
        assertEquals(ticketId, r.f.daemon.manager.get(sessionId)!!.ticketId)
    }

    @Test
    fun theResumePromptPutsTheBriefFirst() {
        val withNote = SessionHandover.resumePrompt("BRIEF", "did A; next B", null)
        assertTrue(withNote.startsWith("BRIEF\n\n" + SessionHandover.RESUMING_HEADING))
        assertTrue(withNote.contains("did A; next B"))
        val without = SessionHandover.resumePrompt("BRIEF", null, "it crashed")
        assertTrue(without.startsWith("BRIEF\n\n" + SessionHandover.NO_HANDOFF_HEADING + " (it crashed)"))
    }

    private companion object {
        const val TITLE = "Ship the billing API"
        const val DESCRIPTION = "Add the refunds endpoint and its tests."
        const val NOTE = "HANDOFF NOTE: refunds endpoint done in Billing.kt; tests pending; branch ticket/x at abc1234."

        fun textLine(text: String): String = JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))).toString()
    }
}
