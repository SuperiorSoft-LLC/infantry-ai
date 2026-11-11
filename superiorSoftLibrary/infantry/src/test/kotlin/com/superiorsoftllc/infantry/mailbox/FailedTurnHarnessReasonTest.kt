// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionState
import com.superiorsoftllc.infantry.session.host.CountingRunner
import com.superiorsoftllc.infantry.session.host.FakeHarnessAdapter
import com.superiorsoftllc.infantry.session.host.FakeHarnessProvider
import com.superiorsoftllc.infantry.session.host.TurnEnd
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * HARNESS-ALL-1: a turn that FAILS with an exit code names the harness's own error (e.g. opencode's "Google Generative AI API key is
 * missing"), not only "the process exited with code 1", in the record's `lastError` (the failure card in the open session) and the
 * `Exit` reason (the `finished` detail). Over the fake harness, so every harness that prints its error as a stream `error` event is
 * covered.
 */
class FailedTurnHarnessReasonTest {
    private val adapter = FakeHarnessAdapter(autoComplete = false)
    private val events: MutableList<SessionEvent> = Collections.synchronizedList(mutableListOf())
    private val runner = CountingRunner()
    private val manager = SessionManager(
        FakeHarnessProvider(adapter), runner, tempSessionStore(), killGraceMs = 50, lineRunner = runner,
        bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy(),
    ).also { m -> m.eventSink = { e -> events += e } }

    private fun exitReason() = synchronized(events) { events.filterIsInstance<SessionEvent.Exit>().single().reason }

    @Test
    fun aFailedTurnWithAnExitCode_namesTheErrorTheHarnessPrinted() {
        val s = manager.startNew("work")
        adapter.sink.output("""{"type":"error","error":{"message":"Google Generative AI API key is missing."}}""")
        adapter.sink.turnEnded(TurnEnd.FAILED, 1)

        val r = assertNotNull(manager.get(s.id))
        assertEquals(SessionState.FAILED, r.state)
        assertEquals("Google Generative AI API key is missing. (the process exited with code 1)", r.lastError)
        assertEquals(r.lastError, exitReason())
    }

    @Test
    fun aReasonTheHarnessGaveWithTheEnd_winsOverTheExitCode() {
        val s = manager.startNew("work")
        adapter.sink.turnEnded(TurnEnd.FAILED, 2, "Invalid API key")

        assertEquals("Invalid API key (the process exited with code 2)", manager.get(s.id)!!.lastError)
    }

    @Test
    fun withNoHarnessError_theExitCodeIsStillTheReason() {
        val s = manager.startNew("work")
        adapter.sink.output("""{"reply":"no error mentioned here"}""")
        adapter.sink.turnEnded(TurnEnd.FAILED, 1)

        assertEquals("the process exited with code 1", manager.get(s.id)!!.lastError)
    }
}
