package com.superiorsoftllc.agenthq.session.host

import com.superiorsoftllc.agenthq.provider.RunRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BUG-M2-11b-1 (docs/AGENTHQ-SESSION-HOST-PLAN.md section 11): the host's lifecycle rules, over a fake persistent
 * harness with id `fake-harness`, deliberately not opencode. What holds here holds for every harness with an adapter.
 */
class SessionHostTest {
    private val adapter = FakeHarnessAdapter()
    private val provider = FakeHarnessProvider(adapter)
    private val events = RecordingEvents()
    private val oneShotRunner = CountingRunner()
    private var keepAlive = LifecyclePolicy.DEFAULT

    private val host = SessionHost(
        runnerFor = { oneShotRunner },
        lineRunner = oneShotRunner,
        policy = { keepAlive },
        events = events,
        killGraceMs = 50,
        abortGraceMs = 50,
    )

    private fun prompt(text: String, fp: String = "a", resume: String? = null) =
        host.runTurn("s1", provider, RunRequest(text, model = "m1", resumeSessionId = resume), mapOf("FP" to fp))

    private fun completeTurn() = adapter.sink.turnEnded(TurnEnd.COMPLETED, 0)

    @Test
    fun twoTurns_oneLaunch_oneBootLine() {
        prompt("one")
        completeTurn()
        prompt("two")
        completeTurn()

        assertEquals(1, adapter.launches.size, "a kept process takes the second turn")
        assertEquals(listOf("one", "two"), adapter.turns.map { it.prompt })
        assertEquals(listOf("↻ session started (fake-harness · m1)"), events.of("chat"))
        assertEquals(listOf("COMPLETED code=0 processExited=false", "COMPLETED code=0 processExited=false"), events.of("end"))
    }

    @Test
    fun fingerprintChange_closesTheOldProcess_restartsAndResumes() {
        prompt("one", fp = "a")
        completeTurn()
        val first = adapter.current
        prompt("two", fp = "b", resume = "prov-1")

        assertEquals(2, adapter.launches.size)
        assertFalse(first.isAlive, "the old process was stopped before the new launch")
        assertEquals(1, adapter.closes)
        assertEquals("prov-1", adapter.launches.last().resumeSessionId, "the relaunch resumes the provider session")
        assertEquals(listOf("↻ session started (fake-harness · m1)", "↻ session restarted (fake-harness · m1)"), events.of("chat"))
        assertEquals(emptyList(), events.of("exit"), "a restart the host asked for is not a crash")
    }

    @Test
    fun deathBetweenTurns_sendsExit_andTheNextPromptRelaunchesOnceWithTheProviderId() {
        prompt("one")
        completeTurn()
        adapter.current.die(7)

        assertEquals(listOf("7"), events.of("exit"), "a kept process that died unasked is an `exit`")
        assertEquals(1, adapter.launches.size, "no relaunch without a prompt")

        prompt("two", resume = "prov-1")
        assertEquals(2, adapter.launches.size)
        assertEquals("prov-1", adapter.launches.last().resumeSessionId)
        assertEquals("↻ session restarted after a crash (fake-harness · m1)", events.of("chat").last())
    }

    @Test
    fun deathMidTurn_failsTheTurn_andNeverRetriesIt() {
        prompt("one")
        adapter.current.die(9)

        assertEquals(listOf("FAILED code=9 processExited=true"), events.of("end"))
        assertTrue(events.of("chat").contains("the fake-harness process exited (code 9) during the turn; the next message restarts it"))
        assertEquals(1, adapter.launches.size, "no relaunch")
        assertEquals(1, adapter.turns.size, "the turn is never sent again")
    }

    @Test
    fun launchFailure_failsTheTurnWithTheStderrTail_andTheNextPromptLaunchesAgain() {
        adapter.failNextLaunch = "boom: port in use"
        prompt("one")

        assertEquals(listOf("FAILED code=null processExited=false"), events.of("end"))
        assertEquals(listOf("the fake-harness process did not start: boom: port in use"), events.of("chat"))
        assertEquals(0, adapter.turns.size)

        prompt("two")
        assertEquals(2, adapter.launches.size)
        assertEquals(listOf("two"), adapter.turns.map { it.prompt })
    }

    @Test
    fun interrupt_abortsTheTurn_keepsTheProcess() {
        prompt("one")
        host.abortTurn("s1")

        assertEquals(1, adapter.aborts)
        assertEquals(listOf("INTERRUPTED code=null processExited=false"), events.of("end"), "the harness's own word for it is ignored")
        assertTrue(adapter.current.isAlive, "an accepted abort keeps the process")

        prompt("two")
        assertEquals(1, adapter.launches.size)
    }

    @Test
    fun interrupt_whenTheHarnessCannotAbort_kills() {
        adapter.abortAccepted = false
        prompt("one")
        host.abortTurn("s1")

        assertTrue(adapter.current.killed)
        assertEquals(listOf("INTERRUPTED code=null processExited=false"), events.of("end"))
        assertEquals(emptyList(), events.of("exit"), "the host's own kill is not a crash")
    }

    @Test
    fun close_closesThenKillsAfterTheGrace_forceKillsAtOnce() {
        adapter.exitOnClose = false
        prompt("one")
        completeTurn()
        var forced = false
        host.stop("s1", onForce = { forced = true })
        assertEquals(1, adapter.closes)
        assertTrue(adapter.current.killed && forced, "a process that ignores close is killed after the grace")

        prompt("two")
        completeTurn()
        host.stop("s1", force = true)
        assertEquals(1, adapter.closes, "force does not ask")
        assertTrue(adapter.current.killed)

        host.stop("s1")
        assertEquals(1, adapter.closes, "stop is idempotent")
        assertEquals(emptyList(), events.of("exit"))
    }

    @Test
    fun close_ofAGracefulHarness_needsNoKill() {
        prompt("one")
        completeTurn()
        host.stop("s1")
        assertFalse(adapter.current.isAlive)
        assertFalse(adapter.current.killed)
    }

    @Test
    fun shutdown_leavesNothingAlive() {
        prompt("one")
        completeTurn()
        host.runTurn("s2", provider, RunRequest("busy"), mapOf("FP" to "a"))
        host.shutdown()

        assertTrue(adapter.processes.none { it.isAlive }, "nothing survives a daemon stop")
        assertTrue(events.of("end").last().startsWith("FAILED "), "the busy turn ends: ${events.log}")
        assertEquals(emptyList(), events.of("exit"))
    }

    @Test
    fun keepAliveZero_runsTheSameHarnessOneShot() {
        keepAlive = LifecyclePolicy(0)
        prompt("one")
        prompt("two")

        assertEquals(0, adapter.launches.size, "keep-alive off never uses the persistent mode")
        assertEquals(2, oneShotRunner.starts.get(), "one child per turn")
        assertEquals(listOf("↻ session started (fake-harness · m1)"), events.of("chat"), "and still ONE chat line")
        assertEquals(listOf("COMPLETED code=0 processExited=false", "COMPLETED code=0 processExited=false"), events.of("end"))
    }

    @Test
    fun noAdapter_runsOneShot() {
        val plain = object : com.superiorsoftllc.agenthq.provider.AgentProvider by provider {
            override fun sessionAdapter(): HarnessSessionAdapter? = null
        }
        host.runTurn("s1", plain, RunRequest("one"), emptyMap())
        host.runTurn("s1", plain, RunRequest("two"), emptyMap())
        assertEquals(0, adapter.launches.size)
        assertEquals(2, oneShotRunner.starts.get())
        assertEquals(1, events.of("chat").size)
    }
}
