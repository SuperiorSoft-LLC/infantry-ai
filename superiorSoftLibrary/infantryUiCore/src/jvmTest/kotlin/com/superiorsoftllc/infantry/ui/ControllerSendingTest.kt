// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.SendState
import com.superiorsoftllc.infantry.ui.model.pendingSendsFor
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** A link that records every frame the controller sends and lets the test push the daemon's answers. */
private class SendLink(scope: CoroutineScope) : DaemonClient(scope) {
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    val sent = CopyOnWriteArrayList<Envelope>()
    override val frames: SharedFlow<Envelope> get() = incoming
    override suspend fun send(envelope: Envelope): Boolean { sent += envelope; return true }
    suspend fun push(e: Envelope) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 10_000.milliseconds
        while (incoming.subscriptionCount.value == 0 && deadline.hasNotPassedNow()) delay(10)
        check(incoming.tryEmit(e)) { "frame buffer full" }
    }
}

/**
 * CHAT-SENDING-1, through the Controller: a sent message shows at once with a client message id; no answer in
 * `limits.chat.sendAckSeconds` marks it "not sent"; the retry resends the SAME id (so the daemon can drop a duplicate); the ack and
 * the turn echo then leave exactly one line in the transcript.
 */
class ControllerSendingTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun waitFor(what: String, check: () -> Boolean) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 10_000.milliseconds
        while (!check() && deadline.hasNotPassedNow()) runBlocking { delay(10) }
        assertTrue(check(), what)
    }

    @Test
    fun `optimistic bubble, timeout, retry with the same id, then ack and echo leave one line`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = SendLink(scope)
            val controller = Controller(scope, link)
            val rec = SessionRecord("a", "stub", "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "p")
            runBlocking {
                link.push(Envelope(Envelope.READY, "contract=1.151.0 provider=stub instance=i1"))
                link.push(Envelope(Envelope.SETTINGS, """{"limits.chat.sendAckSeconds":1}"""))
                link.push(Envelope(Envelope.SESSIONS, json.encodeToString(ListSerializer(SessionRecord.serializer()), listOf(rec))))
            }
            waitFor("session listed and the wait read") { controller.state.value.agents["a"] != null && controller.state.value.sendAckSeconds == 1L }

            val r = runBlocking { controller.prompt("a", "hello") }
            assertEquals(CommandResult.Sent, r)
            val first = controller.state.value.pendingSendsFor("a").single()
            assertEquals(SendState.SENDING, first.state, "shown the moment Send is pressed")
            val prompt = link.sent.single { it.type == Envelope.PROMPT }
            assertEquals(first.clientId, prompt.requestId, "the prompt carries the client message id")
            assertTrue(prompt.requestId!!.startsWith(Envelope.CLIENT_SEND_PREFIX))

            waitFor("no answer in 1s: not sent") { controller.state.value.pendingSends[first.clientId]?.state == SendState.FAILED }

            runBlocking { controller.retrySend(first.clientId) }
            val prompts = link.sent.filter { it.type == Envelope.PROMPT }
            assertEquals(2, prompts.size)
            assertEquals(prompts[0].requestId, prompts[1].requestId, "a resend reuses the id, so the daemon never runs it twice")
            assertEquals(SendState.SENDING, controller.state.value.pendingSends[first.clientId]?.state)

            runBlocking {
                link.push(Envelope(Envelope.PROMPT_ACK, "duplicate", "a", requestId = first.clientId))
                link.push(Envelope(Envelope.TURN, "hello", "a", sequence = 1))
            }
            waitFor("echo replaces the bubble") { controller.state.value.pendingSendsFor("a").isEmpty() }
            assertEquals(1, controller.state.value.agents["a"]!!.entries.count { it.kind == LineKind.USER && it.text == "hello" }, "one line, never two")
        } finally {
            scope.cancel()
        }
    }
}
