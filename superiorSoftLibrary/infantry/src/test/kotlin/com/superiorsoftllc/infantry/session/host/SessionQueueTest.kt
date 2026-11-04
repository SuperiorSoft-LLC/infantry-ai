// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session.host

import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionState
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CHAT-QUEUE-1: the daemon holds the messages typed while a turn runs. Removed ones are never delivered; a forced one goes INTO the
 * running turn (no abort, no new turn) when the harness declares mid-turn input, and is refused (it stays queued) when it does not;
 * the rest go out one per turn end, in order. Over the fake harness, so no harness is named.
 */
class SessionQueueTest {
    private val adapter = FakeHarnessAdapter(autoComplete = false)
    private val events: MutableList<SessionEvent> = Collections.synchronizedList(mutableListOf())
    private val runner = CountingRunner()

    private fun manager(midTurn: Boolean): SessionManager {
        val base = FakeHarnessProvider(adapter)
        val provider: AgentProvider = if (!midTurn) base else object : AgentProvider by base {
            override fun midTurnInput(): Boolean = true
        }
        return SessionManager(
            provider, runner,
            JsonSessionStore(File.createTempFile("infantry-queue", ".json").also { it.delete() }),
            killGraceMs = 50, lineRunner = runner,
        ).also { it.eventSink = { e -> events += e } }
    }

    private fun completeTurn() = adapter.sink.turnEnded(TurnEnd.COMPLETED, 0)

    private fun prompts() = synchronized(adapter.turns) { adapter.turns.map { it.prompt } }

    /** The drain runs off the settling thread: wait (bounded) for [what]. */
    private fun until(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what; turns=${prompts()}")
            Thread.sleep(10)
        }
    }

    @Test
    fun aRemovedMessageIsNeverDelivered_theRestGoOutOnePerTurnEnd() {
        val m = manager(midTurn = false)
        val s = m.startNew("one")
        val drop = assertNotNull(m.queueAdd(s.id, "drop me", maxQueued = 20))
        val keep = assertNotNull(m.queueAdd(s.id, "keep me", maxQueued = 20))
        assertEquals(listOf("drop me", "keep me"), m.queued(s.id).map { it.text }, "held in order, on the record")
        assertEquals(listOf("drop me", "keep me"), m.get(s.id)!!.queued.map { it.text }, "on the session record clients get")

        assertEquals(drop.id, m.queueRemove(s.id, drop.id).id)
        assertEquals(listOf(keep.id), m.queued(s.id).map { it.id })
        assertFailsWith<IllegalArgumentException> { m.queueRemove(s.id, drop.id) }

        completeTurn()
        until("the kept message sent as the next turn") { prompts() == listOf("one", "keep me") }
        assertTrue(m.queued(s.id).isEmpty())
        completeTurn()
        Thread.sleep(100)
        assertEquals(listOf("one", "keep me"), prompts(), "the removed message never went out")
    }

    @Test
    fun forceDeliversIntoTheRunningTurn_withoutStoppingIt() {
        val m = manager(midTurn = true)
        val s = m.startNew("long task")
        val item = assertNotNull(m.queueAdd(s.id, "also check the logs", maxQueued = 20))

        assertEquals(SessionManager.ForceOutcome.INJECTED, m.queueForce(s.id, item.id))
        assertEquals(listOf("also check the logs"), adapter.injected, "typed into the live process")
        assertEquals(0, adapter.aborts, "the turn is not stopped")
        assertEquals(listOf("long task"), prompts(), "no new turn was sent")
        assertTrue(m.queued(s.id).isEmpty(), "no longer queued")
        val turn = synchronized(events) { events.filterIsInstance<SessionEvent.Turn>().last() }
        assertEquals("also check the logs", turn.prompt)
        assertTrue(turn.midTurn, "the transcript shows it where it went in, flagged mid-turn")

        completeTurn()
        Thread.sleep(100)
        assertEquals(listOf("long task"), prompts(), "nothing is sent again at the turn end")
    }

    @Test
    fun forceIsRefusedWhenTheHarnessCannotTakeInputMidTurn_theMessageStaysQueued() {
        val m = manager(midTurn = false)
        val s = m.startNew("one")
        val item = assertNotNull(m.queueAdd(s.id, "later", maxQueued = 20))
        val e = assertFailsWith<IllegalStateException> { m.queueForce(s.id, item.id) }
        assertTrue(e.message!!.contains("cannot take a message mid-turn"), e.message)
        assertEquals(listOf(item.id), m.queued(s.id).map { it.id })
        assertTrue(adapter.injected.isEmpty())
    }

    @Test
    fun forceIsRefusedWhenTheProcessDoesNotTakeIt_theMessageStaysQueued() {
        adapter.injectAccepted = false
        val m = manager(midTurn = true)
        val s = m.startNew("one")
        val item = assertNotNull(m.queueAdd(s.id, "later", maxQueued = 20))
        assertFailsWith<IllegalStateException> { m.queueForce(s.id, item.id) }
        assertEquals(listOf(item.id), m.queued(s.id).map { it.id })
    }

    @Test
    fun aSessionAtRestTakesAQueuedOrForcedMessageAsATurnNow() {
        val m = manager(midTurn = false)
        val s = m.startNew("one")
        completeTurn()
        until("at rest") { m.get(s.id)!!.state == SessionState.IDLE }
        assertNull(m.queueAdd(s.id, "now please", maxQueued = 20), "nothing held: sent as a turn")
        assertEquals(listOf("one", "now please"), prompts())
    }

    @Test
    fun theQueueRefusesPastItsLimit() {
        val m = manager(midTurn = false)
        val s = m.startNew("one")
        m.queueAdd(s.id, "a", maxQueued = 2)
        m.queueAdd(s.id, "b", maxQueued = 2)
        assertFailsWith<IllegalArgumentException> { m.queueAdd(s.id, "c", maxQueued = 2) }
        assertEquals(listOf("a", "b"), m.queued(s.id).map { it.text })
    }
}
