// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session.host

import com.superiorsoftllc.infantry.harness.RunRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CLI-PTY-4: a turn the live process begins by itself (an interactive CLI answering a background task that ended) is tracked like a sent
 * one: it is reported once, its end settles it, and a turn sent around it is never ended by the wrong end.
 */
class SessionHostOwnTurnTest {
    private val adapter = FakeHarnessAdapter()
    private val provider = FakeHarnessProvider(adapter)
    private val events = RecordingEvents()
    private val runner = CountingRunner()
    private val host = SessionHost(
        runnerFor = { runner }, lineRunner = runner, policy = { LifecyclePolicy.DEFAULT }, events = events, killGraceMs = 50, abortGraceMs = 50,
    )

    private fun prompt(text: String) = host.runTurn("s1", provider, RunRequest(text, model = "m1"), mapOf("FP" to "a"))

    @Test
    fun anOwnTurnWhileIdle_isReported_andEndsLikeAnyTurn() {
        prompt("one")
        adapter.sink.turnEnded(TurnEnd.COMPLETED, 0)
        adapter.sink.turnStarted("a background task ended")
        assertEquals(listOf("a background task ended"), events.of("start"))
        adapter.sink.turnEnded(TurnEnd.COMPLETED, 0)
        assertEquals(2, events.of("end").size, "the own turn's end is reported")
        adapter.sink.turnEnded(TurnEnd.COMPLETED, 0)
        assertEquals(2, events.of("end").size, "an end with no turn open ends nothing")
    }

    @Test
    fun aTurnSentWhileAnOwnTurnRuns_isEndedByItsOwnEndOnly() {
        prompt("one")
        adapter.sink.turnEnded(TurnEnd.COMPLETED, 0)
        adapter.sink.turnStarted("a background task ended")
        prompt("two")
        assertEquals(1, adapter.launches.size)
        adapter.sink.turnEnded(TurnEnd.COMPLETED, 0) // the own turn
        assertEquals(1, events.of("end").size, "the own turn's end does not end the sent turn")
        adapter.sink.turnEnded(TurnEnd.FAILED, null, "x") // the sent turn
        assertEquals(listOf("COMPLETED code=0 processExited=false", "FAILED code=null processExited=false"), events.of("end"))
    }

    @Test
    fun anOwnTurnBegunAheadOfASentTurn_endsFirst_andIsNoNewRun() {
        prompt("one") // sent, but the process took up its own notice first
        adapter.sink.turnStarted("a background task ended")
        assertEquals(emptyList(), events.of("start"), "the session already has a turn: no second run")
        assertTrue(events.of("activity").any { "began a turn of its own" in it })
        adapter.sink.turnEnded(TurnEnd.COMPLETED, 0) // the own turn
        assertEquals(emptyList(), events.of("end"))
        adapter.sink.turnEnded(TurnEnd.COMPLETED, 0) // the sent turn
        assertEquals(1, events.of("end").size)
    }

    @Test
    fun aTurnBegunAfterTheProcessExited_isEndedAtOnce_soTheSessionIsNotLeftBusy() {
        // STALE-BUSY-1: the exit found no turn open; a late "own turn" from the dead process must not stay open forever.
        prompt("one")
        adapter.sink.turnEnded(TurnEnd.COMPLETED, 0)
        adapter.current.die(1)
        adapter.sink.turnStarted("late notice")
        assertEquals(2, events.of("end").size, "the late turn is ended: ${events.log}")
    }
}
