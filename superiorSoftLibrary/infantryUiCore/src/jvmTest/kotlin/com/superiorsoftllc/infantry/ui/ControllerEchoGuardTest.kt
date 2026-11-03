package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.LineKind
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

private class EchoLink(scope: CoroutineScope) : DaemonClient(scope) {
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    override val frames: SharedFlow<Envelope> get() = incoming
    override suspend fun send(envelope: Envelope): Boolean = true
    suspend fun push(e: Envelope) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 10_000.milliseconds
        while (incoming.subscriptionCount.value == 0 && deadline.hasNotPassedNow()) delay(10)
        check(incoming.tryEmit(e)) { "frame buffer full" }
    }
}

/**
 * GAP-2: the Controller's echo guard. A 1.11.0+ daemon echoes the user's turn on the stream, so the controller
 * must NOT also add a local USER line (it would show twice); a pre-1.11.0 daemon never echoes, so it must.
 */
class ControllerEchoGuardTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun userLines(contract: String, text: String): Int {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = EchoLink(scope)
            val controller = Controller(scope, link)
            val rec = SessionRecord("a", "stub", "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "p")
            runBlocking {
                link.push(Envelope(Envelope.READY, "contract=$contract provider=stub instance=i1"))
                link.push(Envelope(Envelope.SESSIONS, json.encodeToString(ListSerializer(SessionRecord.serializer()), listOf(rec))))
            }
            val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 10_000.milliseconds
            while (controller.state.value.agents["a"] == null && deadline.hasNotPassedNow()) runBlocking { delay(10) }
            val r = runBlocking { controller.prompt("a", text) }
            check(r is CommandResult.Sent) { "prompt must be sent, was $r" }
            return controller.state.value.agents["a"]!!.entries.count { it.kind == LineKind.USER && it.text == text }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a daemon that echoes turns gets no local echo`() {
        assertEquals(0, userLines("1.28.0", "hello"), "echo-capable daemon: the controller must not add its own USER line")
    }

    @Test
    fun `a daemon that does not echo turns gets exactly one local echo`() {
        assertEquals(1, userLines("1.10.0", "hello"), "pre-1.11 daemon: the controller must add the USER line itself")
    }
}
