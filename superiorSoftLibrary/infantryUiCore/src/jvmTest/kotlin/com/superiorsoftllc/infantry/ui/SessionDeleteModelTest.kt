// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** A daemon link with no network: records every frame the controller sends, and lets the test push frames in. */
private class RecordingDaemonClient(scope: CoroutineScope) : DaemonClient(scope) {
    private val lock = Mutex()
    private val _sent = mutableListOf<Envelope>()
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    override val frames: SharedFlow<Envelope> get() = incoming
    override suspend fun send(envelope: Envelope): Boolean {
        lock.withLock { _sent += envelope }
        return true
    }

    suspend fun sentOf(type: String): List<Envelope> = lock.withLock { _sent.filter { it.type == type }.toList() }

    suspend fun sent(): List<Envelope> = lock.withLock { _sent.toList() }

    /** A shared flow drops what nobody collects yet, so wait for the controller's collector before pushing. */
    suspend fun push(e: Envelope) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 10_000.milliseconds
        while (incoming.subscriptionCount.value == 0 && deadline.hasNotPassedNow()) delay(10)
        check(incoming.tryEmit(e)) { "frame buffer full" }
    }
}

/**
 * UI-AGENT-DEL (Remove button on sidebar agents): removing a session goes through the daemon's
 * `session_delete`, and the row leaves the sidebar when the daemon pushes the refreshed `sessions`
 * list that no longer names it.
 */
class SessionDeleteModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun rec(id: String, state: SessionState = SessionState.IDLE, heartbeat: Long = 1) =
        SessionRecord(id, "stub", "/w", spawnedAt = 1, lastHeartbeat = heartbeat, state = state, providerSessionId = "p")

    private fun sessions(vararg r: SessionRecord) =
        Envelope(Envelope.SESSIONS, json.encodeToString(ListSerializer(SessionRecord.serializer()), r.toList()))

    private fun feed(vararg e: Envelope) = e.fold(AppState()) { s, f -> reduce(s, f, 100) }

    private data class Watch(val deadline: kotlin.time.TimeSource.Monotonic.ValueTimeMark = kotlin.time.TimeSource.Monotonic.markNow() + 10_000.milliseconds) {
        val passed: Boolean get() = deadline.hasNotPassedNow()
    }

    @Test
    fun `a refreshed sessions list drops the rows the daemon no longer lists`() {
        val s = feed(sessions(rec("a"), rec("b")))
        assertEquals(setOf("a", "b"), s.agents.keys)

        // The daemon deleted "b" and pushed the refreshed full list without it.
        val refreshed = reduce(s, sessions(rec("a")), 200)
        assertFalse(refreshed.agents.containsKey("b"), "the deleted session must leave the sidebar list")
        assertEquals(setOf("a"), refreshed.agents.keys)
        assertEquals(listOf("a"), refreshed.orderedAgents.map { it.id })
    }

    @Test
    fun `deleteSession sends session_delete and the refreshed sessions list removes the row`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = RecordingDaemonClient(scope)
            val controller = Controller(scope, link)
            runBlocking {
                link.push(Envelope(Envelope.READY, "contract=1.28.0 provider=stub instance=i1"))
                link.push(sessions(rec("a"), rec("b")))
            }
            val known = Watch()
            while (controller.state.value.agents.size < 2 && known.passed) runBlocking { delay(10) }

            val result = runBlocking { controller.deleteSession("b") }
            assertTrue(result is CommandResult.Sent, "deleteSession must report sent, was $result")
            assertTrue(runBlocking { link.sentOf(Envelope.SESSION_DELETE) }.any { it.data == "b" }, "session_delete must name the deleted session")

            // The daemon answers every client with the refreshed full list.
            runBlocking { link.push(sessions(rec("a"))) }
            val gone = Watch()
            while (controller.state.value.agents.containsKey("b") && gone.passed) runBlocking { delay(10) }
            assertFalse(controller.state.value.agents.containsKey("b"), "after the daemon's refresh the row is gone")
            assertEquals(setOf("a"), controller.state.value.agents.keys)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `removeSession kills a running session first, then only an idle one is deleted directly`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = RecordingDaemonClient(scope)
            val controller = Controller(scope, link)
            runBlocking {
                link.push(Envelope(Envelope.READY, "contract=1.28.0 provider=stub instance=i1"))
                link.push(sessions(rec("busy", SessionState.BUSY), rec("idle", SessionState.IDLE)))
            }
            val known = Watch()
            while (controller.state.value.agents.size < 2 && known.passed) runBlocking { delay(10) }

            assertTrue(runBlocking { controller.removeSession("busy") } is CommandResult.Sent)
            val killFirst = runBlocking { link.sentOf(Envelope.KILL) }.any { it.data == "busy force" }
            assertTrue(killFirst, "a running session must be killed (forced: the user confirmed) before it can be deleted")
            val order = runBlocking {
                val all = link.sent()
                all.indexOfFirst { it.type == Envelope.KILL && it.data == "busy force" } <
                    all.indexOfFirst { it.type == Envelope.SESSION_DELETE && it.data == "busy" }
            }
            assertTrue(order, "kill must be sent before session_delete for the running session")

            assertTrue(runBlocking { controller.removeSession("idle") } is CommandResult.Sent)
            val idleKills = runBlocking { link.sentOf(Envelope.KILL) }.count { it.data == "idle" }
            assertEquals(0, idleKills, "an idle session is deleted directly, never killed")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `removing a ghost row the daemon does not hold drops it client-side`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = RecordingDaemonClient(scope)
            val controller = Controller(scope, link)
            runBlocking {
                link.push(Envelope(Envelope.READY, "contract=1.28.0 provider=stub instance=i1"))
                link.push(sessions(rec("live", SessionState.BUSY), rec("ghost")))
            }
            val known = Watch()
            while (controller.state.value.agents.size < 2 && known.passed) runBlocking { delay(10) }
            // The daemon never answers a delete for "ghost" with a list change: the row must still go.
            assertTrue(runBlocking { controller.removeSession("ghost") } is CommandResult.Sent)
            assertEquals(setOf("live"), controller.state.value.agents.keys)
            // After a reconnect the daemon's full list is authoritative: a row it omits is dropped.
            runBlocking { link.push(sessions(rec("live", SessionState.BUSY))) }
            assertEquals(setOf("live"), controller.state.value.agents.keys)
        } finally {
            scope.cancel()
        }
    }
}
