// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session.host

import com.superiorsoftllc.infantry.harness.RunRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BUG-M2-11f (docs/INFANTRY-SESSION-HOST-PLAN.md sections 5, 8 and 11): the idle stop and the live keep-alive policy, on
 * a FAKE host clock, over the same not-opencode `fake-harness` the 11b host tests use. No test here sleeps for a minute:
 * time moves when [now] does.
 */
class SessionHostIdleTest {
    private val adapter = FakeHarnessAdapter()
    private val provider = FakeHarnessProvider(adapter)
    private val events = RecordingEvents()
    private val oneShotRunner = CountingRunner()
    private val table = FakeProcessTable(1000L to 777L)
    private var keepAlive = LifecyclePolicy(30)
    @Volatile private var now = 1_000_000L

    private val host = SessionHost(
        runnerFor = { oneShotRunner },
        lineRunner = oneShotRunner,
        policy = { keepAlive },
        events = events,
        killGraceMs = 50,
        abortGraceMs = 50,
        clock = { now },
        // The test drives the sweep (stopIdle) or the live path (policyChanged); no background timer races the clock.
        idleSweepMs = 0,
        processTable = table,
    )

    private fun prompt(session: String = "s1", text: String = "one", resume: String? = null) =
        host.runTurn(session, provider, RunRequest(text, model = "m1", resumeSessionId = resume), mapOf("FP" to "a"))

    private fun completeTurn() = adapter.sink.turnEnded(TurnEnd.COMPLETED, 0)

    private fun minutes(n: Long) { now += n * 60_000L }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for: $what; events ${events.log}" }
            Thread.sleep(5)
        }
    }

    @Test
    fun idleForKeepAliveMinutes_stopsWithNoExitAndNoChatLine_andTheNextPromptShowsResumed() {
        prompt()
        completeTurn()
        val kept = adapter.current

        minutes(29)
        assertEquals(emptyList(), host.stopIdle(), "29 idle minutes is under 30")
        assertTrue(kept.isAlive)

        minutes(1)
        assertEquals(listOf("s1"), host.stopIdle(), "30 idle minutes stops it")
        assertFalse(kept.isAlive, "the idle process is stopped")
        assertFalse(host.hasProcess("s1"), "isProcessAlive: false")
        assertEquals(emptyList(), events.of("exit"), "an idle stop is the host's own decision: NO `exit` frame")
        assertEquals(listOf("* session started (fake-harness · m1)"), events.of("chat"), "and NO chat line")
        assertTrue("stopped the fake-harness process after 30 idle minutes" in events.of("activity"), "${events.log}")
        assertEquals(listOf("COMPLETED code=0 processExited=false"), events.of("end"), "no turn was touched")

        prompt(text = "two", resume = "prov-1")
        assertEquals(2, adapter.launches.size, "the next prompt launches")
        assertEquals("prov-1", adapter.launches.last().resumeSessionId, "and resumes the provider session")
        assertEquals("* session resumed (fake-harness · m1)", events.of("chat").last())
    }

    @Test
    fun aSessionTypeThatNeverIdlesOut_outlivesTheGlobalKeepAlive() {
        // CLI-LIVE-1: a live CLI (harness.claude.liveIdleMinutes = 0, mapped to Int.MAX_VALUE) is never idle-stopped.
        adapter.idleLimit = Int.MAX_VALUE
        prompt()
        completeTurn()
        val kept = adapter.current
        minutes(24 * 60)
        assertEquals(emptyList(), host.stopIdle(), "a day at rest does not stop a never-idle session type")
        assertTrue(kept.isAlive)
        prompt(text = "two")
        assertEquals(1, adapter.launches.size, "the next wake goes into the same process")
    }

    @Test
    fun aSessionTypesOwnIdleLimit_replacesTheGlobalOne() {
        adapter.idleLimit = 5
        prompt()
        completeTurn()
        minutes(4)
        assertEquals(emptyList(), host.stopIdle())
        minutes(1)
        assertEquals(listOf("s1"), host.stopIdle(), "5 idle minutes stops it although the global keep-alive is 30")
    }

    @Test
    fun aBusyProcess_isNeverIdleStopped_andIdleTimeCountsFromTheEndOfTheLastTurn() {
        prompt()
        minutes(120)
        assertEquals(emptyList(), host.stopIdle(), "a turn in flight is not idle, however long it runs")
        assertTrue(adapter.current.isAlive)

        completeTurn()
        minutes(29)
        assertEquals(emptyList(), host.stopIdle(), "idle since the turn ended, not since the launch")
        prompt(text = "two")
        completeTurn()
        minutes(29)
        assertEquals(emptyList(), host.stopIdle(), "a reused turn restarts the idle time")
        minutes(1)
        assertEquals(listOf("s1"), host.stopIdle())
    }

    @Test
    fun loweringN_stopsTheProcessesAlreadyOverIt_atOnce_andLeavesTheOthers() {
        prompt("s1")
        completeTurn()
        val s1 = adapter.current
        minutes(9)
        prompt("s2")
        completeTurn()
        val s2 = adapter.current
        minutes(3)

        keepAlive = LifecyclePolicy(10)
        host.policyChanged()

        awaitTrue("s1, idle 12 minutes, stops under N=10") { !s1.isAlive }
        assertTrue(s2.isAlive, "s2, idle 3 minutes, is kept")
        assertEquals(emptyList(), events.of("exit"))
        assertEquals(2, events.of("chat").size, "no chat line for the stop")
    }

    @Test
    fun zero_stopsEveryKeptProcess_evenOnesIdleForNoTimeAtAll() {
        prompt("s1")
        completeTurn()
        prompt("s2")
        completeTurn()
        val all = adapter.processes.toList()

        keepAlive = LifecyclePolicy(0)
        host.policyChanged()

        awaitTrue("0 stops every kept process") { all.none { it.isAlive } }
        assertEquals(2, events.of("activity").count { it == "stopped the fake-harness process: keep-alive is off" }, "${events.log}")
        assertEquals(emptyList(), events.of("exit"))
    }

    @Test
    fun zero_stopsABusySession_atTheEndOfItsTurn_notBefore() {
        prompt()
        val busy = adapter.current
        keepAlive = LifecyclePolicy(0)
        host.policyChanged()
        Thread.sleep(50)
        assertTrue(busy.isAlive, "a busy session keeps its process until its turn ends")

        completeTurn()
        awaitTrue("the end of the turn picks up 0") { !busy.isAlive }
        assertEquals(listOf("COMPLETED code=0 processExited=false"), events.of("end"), "the turn itself completed")
        assertEquals(emptyList(), events.of("exit"))
    }

    @Test
    fun aOneShotHandle_holdsNoProcess_soNothingIsStoppedAndThereIsStillOneChatLine() {
        keepAlive = LifecyclePolicy(0)
        prompt()
        minutes(60)
        assertEquals(emptyList(), host.stopIdle(), "a one-shot handle between turns has no OS process to stop")
        prompt(text = "two")
        assertEquals(listOf("* session started (fake-harness · m1)"), events.of("chat"), "no relaunch line per turn")
        assertEquals(emptyList(), events.of("kept"), "and nothing to persist for reaping")
    }

    @Test
    fun theKeptProcess_isReportedAtLaunchWithItsStartInstant_andClearedWhenItEnds() {
        prompt()
        completeTurn()
        assertEquals(listOf("s1 pid=1000 startedAt=777 fake-harness"), events.of("kept"))

        minutes(30)
        host.stopIdle()
        assertEquals(listOf("s1 pid=1000"), events.of("keptEnded"), "an idle stop clears the record")
    }
}
