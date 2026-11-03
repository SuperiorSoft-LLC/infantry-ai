// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.BackgroundWorkView
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
private class RestartRecordingClient(scope: CoroutineScope) : DaemonClient(scope) {
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

/** RESTART-UI: the Restart session command refuses a mid-turn / background-work session without confirm. */
class RestartSessionModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun rec(id: String, state: SessionState, turn: Boolean? = null, bg: BackgroundWorkView? = null) =
        SessionRecord(id, "stub", "/w", spawnedAt = 1, lastHeartbeat = 1, state = state, providerSessionId = "p", turnActive = turn, backgroundWork = bg)

    private fun sessions(vararg r: SessionRecord) =
        Envelope(Envelope.SESSIONS, json.encodeToString(ListSerializer(SessionRecord.serializer()), r.toList()))

    private fun withController(block: (Controller, RestartRecordingClient) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = RestartRecordingClient(scope)
            val controller = Controller(scope, link)
            runBlocking {
                link.push(Envelope(Envelope.READY, "contract=1.28.0 provider=stub instance=i1"))
                link.push(sessions(
                    rec("idle", SessionState.IDLE),
                    rec("turn", SessionState.BUSY, turn = true),
                    rec("bg", SessionState.BUSY, turn = false, bg = BackgroundWorkView(4242, "gradle daemon", 1)),
                ))
            }
            val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 10_000.milliseconds
            while (controller.state.value.agents.size < 3 && deadline.hasNotPassedNow()) runBlocking { delay(10) }
            block(controller, link)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an idle session restarts without force`() = withController { c, link ->
        assertTrue(runBlocking { c.restartSession("idle") } is CommandResult.Sent)
        val frame = runBlocking { link.sentOf(Envelope.SESSION_RESTART) }.single()
        assertTrue("\"sessionId\":\"idle\"" in frame.data && "force" !in frame.data, frame.data)
    }

    @Test
    fun `a mid-turn session is refused without confirm and sent with force once confirmed`() = withController { c, link ->
        assertTrue(runBlocking { c.restartSession("turn") } is CommandResult.Refused)
        assertEquals(0, runBlocking { link.sentOf(Envelope.SESSION_RESTART) }.size, "nothing is sent without the confirm")
        assertTrue(runBlocking { c.restartSession("turn", confirmed = true) } is CommandResult.Sent)
        val frame = runBlocking { link.sentOf(Envelope.SESSION_RESTART) }.single()
        assertTrue("\"force\":\"true\"" in frame.data, frame.data)
    }

    @Test
    fun `open background work alone also needs the confirm`() = withController { c, link ->
        assertTrue(runBlocking { c.restartSession("bg") } is CommandResult.Refused)
        assertFalse(restartPlan(c.state.value.agents.getValue("bg")).midTurn)
        assertEquals(listOf("gradle daemon (pid 4242)"), restartPlan(c.state.value.agents.getValue("bg")).openWork)
        assertTrue(runBlocking { c.restartSession("bg", confirmed = true) } is CommandResult.Sent)
        assertEquals(1, runBlocking { link.sentOf(Envelope.SESSION_RESTART) }.size)
    }

    @Test
    fun `an unknown session is refused`() = withController { c, _ ->
        assertTrue(runBlocking { c.restartSession("nope", confirmed = true) } is CommandResult.Refused)
    }
}
