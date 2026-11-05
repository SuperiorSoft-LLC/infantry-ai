// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.persistence.TicketRow
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
import kotlin.test.assertTrue

/**
 * HARNESS-GAP G9 (ticket b908f8fa): a harness turn that ends FAILED with no exit code (an API error, an error stop, a model that
 * answered nothing) goes down the same road as any failed turn: an `Exit` event, so the ticket's starter gets the failure notice with
 * the harness's reason, and the queue drain, so a message held for the session goes out instead of staying stranded. Over the fake
 * harness, so every harness that reports errors this way is covered.
 */
class FailedTurnNoExitCodeTest {
    private val adapter = FakeHarnessAdapter(autoComplete = false)
    private val events: MutableList<SessionEvent> = Collections.synchronizedList(mutableListOf())
    private val runner = CountingRunner()
    private val manager = SessionManager(
        FakeHarnessProvider(adapter), runner, tempSessionStore(), killGraceMs = 50, lineRunner = runner,
        bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy(),
    )
    private val mailbox = Mailbox(MemMailStore(), MailLimits())
    private val sent: MutableList<MailSendRequest> = Collections.synchronizedList(mutableListOf())
    private var owner = ""
    private val monitor = FinishNoticeMonitor(
        manager, mailbox, send = { r -> sent += r; mailbox.send(r) },
        ticketOf = { TicketRow(id = "t1", title = "T", status = "in_progress", position = 0, createdAt = 1, updatedAt = 1, sessionId = owner, startedBy = "pm") },
        commitOf = { null },
    )

    init {
        manager.eventSink = { e -> events += e; monitor.onSessionEvent(e) }
    }

    private fun prompts() = synchronized(adapter.turns) { adapter.turns.map { it.prompt } }

    private fun until(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what; turns=${prompts()}")
            Thread.sleep(10)
        }
    }

    @Test
    fun aFailedTurnWithNoExitCode_noticesTheStarter_andDrainsTheQueue() {
        val s = manager.startNew("work", ticketId = "t1")
        owner = s.id
        // The harness opened its conversation (a provider session id), then the turn failed with no exit code.
        adapter.sink.output("""{"reply":"working"}""")
        val held = assertNotNull(manager.queueAdd(s.id, "and then this", maxQueued = 20))
        adapter.sink.turnEnded(TurnEnd.FAILED, null, "API error: overloaded")

        val exit = synchronized(events) { events.filterIsInstance<SessionEvent.Exit>().singleOrNull() }
        assertNotNull(exit, "a failed turn with no exit code emits an Exit; events=$events")
        assertEquals(null, exit.code)

        val notice = synchronized(sent) { sent.singleOrNull() }
        assertNotNull(notice, "the starter gets the failure notice")
        assertEquals("pm", notice.to)
        assertTrue(notice.subject.orEmpty().contains("AUTOMATIC FAILURE"), notice.subject)
        assertTrue(notice.body.contains("API error: overloaded"), notice.body)

        until("the held message sent as the next turn") { prompts() == listOf("work", "and then this") }
        assertTrue(manager.queued(s.id).none { it.id == held.id }, "no longer queued")
    }

    @Test
    fun aFailedSessionWithNoConversation_keepsItsQueue() {
        val s = manager.startNew("work", ticketId = "t1")
        owner = s.id
        manager.queueAdd(s.id, "later", maxQueued = 20)
        adapter.sink.turnEnded(TurnEnd.FAILED, null, "no reply")

        assertEquals(SessionState.FAILED, manager.get(s.id)!!.state)
        assertEquals(1, synchronized(sent) { sent.size }, "the failure is still noticed")
        Thread.sleep(150)
        assertEquals(listOf("work"), prompts(), "resume refuses a session that never opened a conversation, so nothing is sent")
        assertEquals(listOf("later"), manager.queued(s.id).map { it.text }, "the message stays held")
    }
}
