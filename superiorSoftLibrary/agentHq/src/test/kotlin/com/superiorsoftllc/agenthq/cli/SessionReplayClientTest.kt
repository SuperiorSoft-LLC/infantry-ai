package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import com.superiorsoftllc.agenthq.protocol.SessionReplayGap
import com.superiorsoftllc.agenthq.protocol.SessionReplayRequest
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionReplayClientTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun aCursorIssuedByOneInstanceIsNotResumedByADifferentInstance() {
        val cursor = SessionReplayCursor()
        cursor.track("session-1")
        cursor.observeReady("instance-A", 1_000)
        assertEquals("instance-A", cursor.instance(), "the first ready establishes the instance")
        listOf(1L, 2L, 3L).forEach { seq ->
            assertTrue(cursor.shouldDeliver(Envelope(Envelope.STDOUT, "a$seq", "session-1", seq)))
        }
        assertEquals(3L, cursor.lastSequence("session-1"))

        // The daemon restarted: a new process, a new instance id, the session stream numbering from 1 again.
        assertTrue(cursor.observeReady("instance-B", 2_000), "a different instance resets every cursor")
        val request = cursor.replayRequests().single()
        assertEquals(
            0L,
            json.decodeFromString(SessionReplayRequest.serializer(), request.data).lastSequence,
            "a new instance is asked for the whole session, not resumed from the dead instance's cursor",
        )
        // ...and the new process's first frame is delivered, not dropped as a duplicate of the old instance's.
        assertTrue(cursor.shouldDeliver(Envelope(Envelope.STDOUT, "b1", "session-1", 1)))
    }

    @Test
    fun aCursorIsHonouredWhenTheDaemonComesBackAsTheSameInstance() {
        val cursor = SessionReplayCursor()
        cursor.track("session-1")
        cursor.observeReady("instance-A", 1_000)
        listOf(1L, 2L, 3L).forEach { seq ->
            cursor.shouldDeliver(Envelope(Envelope.STDOUT, "a$seq", "session-1", seq))
        }

        assertFalse(cursor.observeReady("instance-A", 1_000), "the same instance keeps the cursor")
        val request = cursor.replayRequests().single()
        assertEquals(
            3L,
            json.decodeFromString(SessionReplayRequest.serializer(), request.data).lastSequence,
            "a reconnect to the same process resumes where it stopped",
        )
        assertFalse(cursor.shouldDeliver(Envelope(Envelope.STDOUT, "dup", "session-1", 3)), "already delivered")
    }

    @Test
    fun aReadyWithoutAnInstanceKeepsTheCursor() {
        // A pre-1.11 daemon names no instance, so it cannot be told apart from a reconnect: keep the cursor.
        val cursor = SessionReplayCursor()
        cursor.track("session-1")
        assertFalse(cursor.observeReady(null, null), "no identity is not a restart")
        cursor.shouldDeliver(Envelope(Envelope.STDOUT, "a1", "session-1", 1))
        assertFalse(cursor.observeReady(null, null))
        assertEquals(1L, cursor.lastSequence("session-1"))
    }

    @Test
    fun replayUsesTheLastDeliveredSequenceAndSuppressesReplayedDuplicates() {
        val cursor = SessionReplayCursor()
        cursor.track("session-1")

        val initialRequest = cursor.replayRequests().single()
        assertEquals("replay_request", initialRequest.type)
        assertEquals("session-1", initialRequest.sessionId)
        assertEquals(0L, json.decodeFromString(SessionReplayRequest.serializer(), initialRequest.data).lastSequence)

        val frames = listOf(
            Envelope(Envelope.STDOUT, "one", "session-1", 1),
            Envelope(Envelope.STDOUT, "two", "session-1", 2),
            Envelope(Envelope.STDOUT, "two again", "session-1", 2),
            Envelope(Envelope.FINISHED, "done", "session-1", 3),
        )
        assertEquals(listOf(true, true, false, true), frames.map(cursor::shouldDeliver))
        assertEquals(3L, cursor.lastSequence("session-1"))

        val reconnectRequest = cursor.replayRequests().single()
        assertEquals(3L, json.decodeFromString(SessionReplayRequest.serializer(), reconnectRequest.data).lastSequence)
    }

    @Test
    fun replayCursorsAreIndependentAndUnsequencedControlFramesPassThrough() {
        val cursor = SessionReplayCursor()
        cursor.track("session-a")
        cursor.track("session-b")

        assertTrue(cursor.shouldDeliver(Envelope(Envelope.STDOUT, "a1", "session-a", 1)))
        assertTrue(cursor.shouldDeliver(Envelope(Envelope.STDOUT, "b7", "session-b", 7)))
        assertFalse(cursor.shouldDeliver(Envelope(Envelope.STDOUT, "a-old", "session-a", 1)))
        assertEquals(1L, cursor.lastSequence("session-a"))
        assertEquals(7L, cursor.lastSequence("session-b"))
        assertTrue(cursor.shouldDeliver(Envelope(Envelope.READY, "ready", sequence = 99)))
    }

    @Test
    fun replayGapMessageIncludesTheMissingRangeAndEvictionCount() {
        val gap = SessionReplayGap(
            requestedAfterSequence = 1,
            oldestAvailableSequence = 5,
            latestAvailableSequence = 8,
            evictedCount = 4,
        )
        val frame = Envelope(
            type = SessionReplayCursor.GAP_TYPE,
            data = json.encodeToString(SessionReplayGap.serializer(), gap),
            sessionId = "session-1234",
        )

        val message = SessionReplayCursor().gapMessage(frame)

        assertTrue(message.contains("session-"), message)
        assertTrue(message.contains("requested after sequence 1"), message)
        assertTrue(message.contains("oldest retained 5"), message)
        assertTrue(message.contains("latest retained 8"), message)
        assertTrue(message.contains("4 frames evicted"), message)
        assertTrue(message.contains("Earlier output may be missing"), message)
    }

    @Test
    fun logsReplaysAfterReconnectDropsDuplicatesAndShowsReplayGaps() {
        val sessionId = "client-replay-session"
        val firstConnection = AtomicBoolean(true)
        val requests = LinkedBlockingQueue<Envelope>()
        val serverJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                webSocket("/ws") {
                    suspend fun sendEnvelope(envelope: Envelope) {
                        send(Frame.Text(serverJson.encodeToString(Envelope.serializer(), envelope)))
                    }

                    sendEnvelope(Envelope(Envelope.READY, EventVocabulary.VERSION))
                    if (firstConnection.getAndSet(false)) {
                        sendEnvelope(Envelope(Envelope.STDOUT, "seen-before-disconnect", sessionId, 1))
                        close(CloseReason(CloseReason.Codes.GOING_AWAY, "test reconnect"))
                    } else {
                        var request: Envelope? = null
                        while (request == null) {
                            val frame = incoming.receive()
                            if (frame is Frame.Text) {
                                val received = serverJson.decodeFromString(Envelope.serializer(), frame.readText())
                                if (received.type == "replay_request") request = received
                            }
                        }
                        requests.put(request)
                        sendEnvelope(
                            Envelope(
                                type = SessionReplayCursor.GAP_TYPE,
                                data = serverJson.encodeToString(
                                    SessionReplayGap.serializer(),
                                    SessionReplayGap(
                                        requestedAfterSequence = 1,
                                        oldestAvailableSequence = 3,
                                        latestAvailableSequence = 4,
                                        evictedCount = 2,
                                    ),
                                ),
                                sessionId = sessionId,
                            ),
                        )
                        sendEnvelope(Envelope(Envelope.STDOUT, "duplicate", sessionId, 1))
                        sendEnvelope(Envelope(Envelope.STDOUT, "retained-after-gap", sessionId, 3))
                        sendEnvelope(Envelope(Envelope.FINISHED, "done", sessionId, 4))
                    }
                }
            }
        }.start(wait = false)

        try {
            val port = runBlocking {
                val deadline = System.currentTimeMillis() + 5_000
                var resolvedPort = 0
                while (resolvedPort == 0 && System.currentTimeMillis() < deadline) {
                    resolvedPort = server.engine.resolvedConnectors().firstOrNull()?.port ?: 0
                    if (resolvedPort == 0) delay(20)
                }
                resolvedPort
            }
            check(port > 0) { "fake replay daemon did not bind" }

            val out = StringBuilder()
            val err = StringBuilder()
            val exit = Cli.run(arrayOf("logs", sessionId, "--url", "ws://127.0.0.1:$port/ws"), out, err, CliTestEnv.getenv)

            assertEquals(0, exit, err.toString())
            assertTrue(out.toString().contains("seen-before-disconnect"), out.toString())
            assertTrue(out.toString().contains("retained-after-gap"), out.toString())
            assertFalse(out.toString().contains("duplicate"), out.toString())
            assertTrue(err.toString().contains("replay gap"), err.toString())
            assertTrue(err.toString().contains("oldest retained 3"), err.toString())

            val request = requests.poll(1, TimeUnit.SECONDS)
                ?: error("client did not send a replay request after reconnect")
            assertEquals(sessionId, request.sessionId)
            assertEquals(1L, json.decodeFromString(SessionReplayRequest.serializer(), request.data).lastSequence)
        } finally {
            server.stop(1, 500)
        }
    }

    /**
     * BUG-UX-8: a reconnect to a daemon that came back as a NEW process (a new `instance`) must not resume
     * the dead process's cursor. Before the fix the CLI sent `lastSequence=1` and the new process's frames
     * 1..n were dropped as duplicates, so the user saw stale/empty output.
     */
    @Test
    fun logsDoesNotResumeACursorFromADaemonThatRestartedAsAnotherInstance() {
        val sessionId = "instance-change-session"
        val firstConnection = AtomicBoolean(true)
        val requests = LinkedBlockingQueue<Envelope>()
        val serverJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                webSocket("/ws") {
                    suspend fun sendEnvelope(envelope: Envelope) {
                        send(Frame.Text(serverJson.encodeToString(Envelope.serializer(), envelope)))
                    }

                    if (firstConnection.getAndSet(false)) {
                        sendEnvelope(
                            Envelope(
                                Envelope.READY,
                                "provider=stub contract=${EventVocabulary.VERSION} instance=instance-A since=1000",
                            ),
                        )
                        sendEnvelope(Envelope(Envelope.STDOUT, "seen-before-restart", sessionId, 1))
                        close(CloseReason(CloseReason.Codes.GOING_AWAY, "daemon restart"))
                    } else {
                        // A NEW daemon process: a different instance, its session stream numbering from 1 again.
                        sendEnvelope(
                            Envelope(
                                Envelope.READY,
                                "provider=stub contract=${EventVocabulary.VERSION} instance=instance-B since=2000",
                            ),
                        )
                        var request: Envelope? = null
                        while (request == null) {
                            val frame = incoming.receive()
                            if (frame is Frame.Text) {
                                val received = serverJson.decodeFromString(Envelope.serializer(), frame.readText())
                                if (received.type == "replay_request") request = received
                            }
                        }
                        requests.put(request)
                        sendEnvelope(Envelope(Envelope.STDOUT, "replayed-full-history", sessionId, 1))
                        sendEnvelope(Envelope(Envelope.FINISHED, "done", sessionId, 2))
                    }
                }
            }
        }.start(wait = false)

        try {
            val port = runBlocking {
                val deadline = System.currentTimeMillis() + 5_000
                var resolvedPort = 0
                while (resolvedPort == 0 && System.currentTimeMillis() < deadline) {
                    resolvedPort = server.engine.resolvedConnectors().firstOrNull()?.port ?: 0
                    if (resolvedPort == 0) delay(20)
                }
                resolvedPort
            }
            check(port > 0) { "fake replay daemon did not bind" }

            val out = StringBuilder()
            val err = StringBuilder()
            val exit = Cli.run(arrayOf("logs", sessionId, "--url", "ws://127.0.0.1:$port/ws"), out, err, CliTestEnv.getenv)

            assertEquals(0, exit, err.toString())
            assertTrue(out.toString().contains("seen-before-restart"), out.toString())
            assertTrue(
                out.toString().contains("replayed-full-history"),
                "the new process's frames must not be dropped as the old instance's duplicates: ${out.toString()}",
            )

            val request = requests.poll(1, TimeUnit.SECONDS)
                ?: error("client did not send a replay request after the daemon restarted")
            assertEquals(sessionId, request.sessionId)
            assertEquals(
                0L,
                json.decodeFromString(SessionReplayRequest.serializer(), request.data).lastSequence,
                "a new instance must be asked for the whole session, not resumed from the dead instance's cursor",
            )
        } finally {
            server.stop(1, 500)
        }
    }
}
