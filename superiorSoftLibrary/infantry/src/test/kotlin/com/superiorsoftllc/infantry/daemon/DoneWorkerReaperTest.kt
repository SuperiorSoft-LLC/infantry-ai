// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.mailbox.FakeAgentProvider
import com.superiorsoftllc.infantry.mailbox.ScriptedRunner
import com.superiorsoftllc.infantry.mailbox.tempSessionStore
import com.superiorsoftllc.infantry.protocol.TicketStatus
import com.superiorsoftllc.infantry.session.SessionManager
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BASICS-KILL-1: [DoneWorkerReaper] removes a done ticket's worker, never mid-turn, only when its project says so. */
class DoneWorkerReaperTest {

    private val direct = Executor { it.run() }

    private class Rig(val manager: SessionManager, val runner: ScriptedRunner, val reaper: DoneWorkerReaper, val removed: MutableList<String>)

    private fun rig(enabled: Boolean, autoExit: Boolean, tickets: MutableMap<String, Pair<String?, String?>>): Rig {
        val runner = ScriptedRunner(autoExit = autoExit)
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50, bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy())
        val removed = mutableListOf<String>()
        val reaper = DoneWorkerReaper(manager, { enabled }, { tickets[it] }, direct) { removed += it }
        manager.eventSink = { reaper.onSessionEvent(it) }
        return Rig(manager, runner, reaper, removed)
    }

    @Test
    fun anAtRestWorkerOfADoneTicketIsRemovedAtOnce() {
        val tickets = mutableMapOf<String, Pair<String?, String?>>()
        val r = rig(enabled = true, autoExit = true, tickets)
        val worker = r.manager.startNew("brief", ticketId = "t1", projectId = "p")
        tickets["t1"] = worker.id to "p"

        r.reaper.onTicketTransition("t1", TicketStatus.DONE)

        assertNull(r.manager.get(worker.id))
        assertEquals(listOf(worker.id), r.removed)
    }

    @Test
    fun aWorkerMidTurnIsRemovedWhenItsTurnEndsNotBefore() {
        val tickets = mutableMapOf<String, Pair<String?, String?>>()
        val r = rig(enabled = true, autoExit = false, tickets)
        val worker = r.manager.startNew("brief", ticketId = "t1", projectId = "p")
        tickets["t1"] = worker.id to "p"

        r.reaper.onTicketTransition("t1", TicketStatus.DONE)
        assertNotNull(r.manager.get(worker.id), "a worker mid-turn is left to finish (it mails its starter after moving the ticket)")
        assertEquals(setOf(worker.id), r.reaper.pendingSessions())

        r.runner.current!!.exit(0)
        assertNull(r.manager.get(worker.id))
        assertTrue(r.reaper.pendingSessions().isEmpty())
    }

    @Test
    fun nothingHappensWhenTheProjectHasTheSettingOffOrTheMoveIsNotDone() {
        val tickets = mutableMapOf<String, Pair<String?, String?>>()
        val off = rig(enabled = false, autoExit = true, tickets)
        val worker = off.manager.startNew("brief", ticketId = "t1", projectId = "p")
        tickets["t1"] = worker.id to "p"
        off.reaper.onTicketTransition("t1", TicketStatus.DONE)
        assertNotNull(off.manager.get(worker.id))

        val on = rig(enabled = true, autoExit = true, tickets)
        val other = on.manager.startNew("brief", ticketId = "t1", projectId = "p")
        tickets["t1"] = other.id to "p"
        on.reaper.onTicketTransition("t1", TicketStatus.TODO)
        assertNotNull(on.manager.get(other.id), "only a move into done removes the worker")
    }
}
