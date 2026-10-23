package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.provider.AgentProvider
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M2-12: the status follows the agent's TURN (thinking, a tool call, a reply), not its process.
 *
 *  - a turn that has been accepted is WORKING ([SessionState.BUSY]) from its first instant, before the provider
 *    has printed anything: booting a process for it is plumbing, never a separate STARTING phase;
 *  - reasoning / tool / reply lines keep it WORKING;
 *  - the end of the turn brings it to IDLE, and the process exiting with it changes nothing more;
 *  - a crash mid-turn is FAILED, never IDLE.
 *
 * The runner is driven by hand so each instant of the turn can be observed.
 */
class SessionStatusFromActivityTest {

    private class FakeProvider : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest) = ProcessSpec(listOf("fake", request.resumeSessionId ?: "FRESH"))
        override fun sessionIdFromLine(line: String) = if (line.contains("session_id")) "prov-1" else null
    }

    private class ManualProcess(private val listener: ProcessListener) : RunningProcess {
        @Volatile private var alive = true
        override val pid = 777L
        override val isAlive: Boolean get() = alive
        override fun writeInput(text: String) = Unit
        override fun closeInput() = Unit
        override fun killTree() { alive = false; listener.onExit(1) }
        override fun awaitExit(): Int = 0
        fun exit(code: Int) { alive = false; listener.onExit(code) }
    }

    /** Starts a process that prints NOTHING until the test says so: the window a STARTING flip used to live in. */
    private class ManualRunner : ProcessRunner {
        override val id = "fake"
        lateinit var listener: ProcessListener
        lateinit var process: ManualProcess
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            this.listener = listener
            return ManualProcess(listener).also { process = it }
        }
    }

    private val records = mutableListOf<SessionRecord>()

    private fun manager(runner: ManualRunner): Pair<SessionManager, MutableList<SessionState>> {
        val file = File.createTempFile("agenthq-m2-12", ".json").also { it.delete() }
        val manager = SessionManager(FakeProvider(), runner, JsonSessionStore(file), killGraceMs = 50)
        val states = mutableListOf<SessionState>()
        manager.eventSink = { e -> if (e is SessionEvent.Status) { states += e.session.state; records += e.session } }
        return manager to states
    }

    @Test
    fun a_turn_is_working_from_its_first_instant_and_idle_once_it_ends_with_no_starting_flip() {
        val runner = ManualRunner()
        val (manager, states) = manager(runner)

        val id = manager.startNew("first").id
        // Spawned, nothing printed yet: the turn is in progress, so the agent is WORKING.
        assertEquals(SessionState.BUSY, manager.get(id)!!.state, "an accepted turn is WORKING before any output")

        runner.listener.onStdout("{\"type\":\"reasoning\",\"session_id\":\"prov-1\"}")
        assertEquals(SessionState.BUSY, manager.get(id)!!.state, "reasoning keeps the turn WORKING")
        // No state change on the first output any more, yet clients still need the provider session id mid-turn.
        assertTrue(
            records.any { it.state == SessionState.BUSY && it.providerSessionId == "prov-1" },
            "the provider session id seen mid-turn must reach clients as a status",
        )
        runner.listener.onStdout("{\"type\":\"tool_use\",\"tool\":\"Read\"}")
        assertEquals(SessionState.BUSY, manager.get(id)!!.state, "a tool call keeps the turn WORKING")

        // The turn ends and its process exits with it.
        runner.process.exit(0)
        assertEquals(SessionState.IDLE, manager.get(id)!!.state, "a finished turn is IDLE whatever the process does")

        // The next prompt boots a process again: that boot must not flash STARTING.
        states.clear()
        manager.resume(id, "second")
        assertEquals(SessionState.BUSY, manager.get(id)!!.state, "the next turn is WORKING before any output")
        assertFalse(SessionState.STARTING in states, "the next prompt's boot flipped to STARTING: $states")
        runner.process.exit(0)
        assertEquals(SessionState.IDLE, manager.get(id)!!.state)
        assertFalse(SessionState.STARTING in states, "no STARTING anywhere in the second turn: $states")
    }

    @Test
    fun a_crash_mid_turn_is_an_error_state_not_idle() {
        val runner = ManualRunner()
        val (manager, _) = manager(runner)

        val id = manager.startNew("work").id
        runner.listener.onStdout("{\"session_id\":\"prov-1\"}")
        runner.process.exit(137)

        assertEquals(SessionState.FAILED, manager.get(id)!!.state)
    }
}
