// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session.host

import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CLI-PTY-5 through the daemon's [SessionManager]: after a restart a session with a conversation is DORMANT; "Resume session"
 * ([SessionManager.attach]) starts its CLI on that conversation with no turn, and the next message goes into that process.
 */
class SessionManagerAttachTest {
    private val adapter = FakeHarnessAdapter(autoComplete = true)
    private val events: MutableList<SessionEvent> = Collections.synchronizedList(mutableListOf())
    private val runner = CountingRunner()
    private val storeFile = File.createTempFile("infantry-attach", ".json").also { it.delete() }

    private fun manager() = SessionManager(
        FakeHarnessProvider(adapter), runner, JsonSessionStore(storeFile),
        killGraceMs = 50, lifecyclePolicy = { LifecyclePolicy(30) }, lineRunner = runner, processTable = FakeProcessTable(), idleSweepMs = 0,
    ).also { it.eventSink = { e -> events += e } }

    private fun turns() = synchronized(events) { events.filterIsInstance<SessionEvent.Turn>().map { it.prompt } }

    @Test
    fun aRestartedSession_isDormant_andAttachStartsItsCliWithNoTurn() {
        JsonSessionStore(storeFile).saveAll(listOf(
            SessionRecord(id = "a", provider = FakeHarnessAdapter.ID, workingDir = ".", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.EXITED, providerSessionId = "prov-a"),
            SessionRecord(id = "b", provider = FakeHarnessAdapter.ID, workingDir = ".", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE),
        ))
        val manager = manager()
        manager.loadAndReconcile()
        assertTrue(manager.get("a")!!.dormant, "a session with a conversation is dormant after a restart")
        assertFalse(manager.get("b")!!.dormant, "one with nothing to resume is not")
        assertTrue(adapter.launches.isEmpty(), "no CLI process starts at daemon start")

        val attached = manager.attach("a")
        assertFalse(attached.dormant)
        assertEquals(SessionState.IDLE, attached.state)
        assertEquals(1, adapter.launches.size)
        assertEquals("prov-a", adapter.launches.single().resumeSessionId, "the CLI resumes the same provider conversation")
        assertTrue(adapter.turns.isEmpty(), "no turn is sent")
        assertTrue(turns().isEmpty(), "nothing reaches the chat as a user turn")
        assertFalse(JsonSessionStore(storeFile).load().single { it.id == "a" }.dormant, "persisted")

        manager.resume("a", "hello")
        assertEquals(1, adapter.launches.size, "the message goes into the attached process")
        assertEquals(listOf("hello"), adapter.turns.map { it.prompt })
        assertEquals(listOf("hello"), turns())
    }

    @Test
    fun anyTurn_clearsDormant() {
        JsonSessionStore(storeFile).saveAll(listOf(
            SessionRecord(id = "a", provider = FakeHarnessAdapter.ID, workingDir = ".", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "prov-a"),
        ))
        val manager = manager()
        manager.loadAndReconcile()
        assertTrue(manager.get("a")!!.dormant)
        manager.resume("a", "typed instead")
        assertFalse(manager.get("a")!!.dormant, "typing a message still works and resumes it")
    }
}
