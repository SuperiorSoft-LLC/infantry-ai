// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.SequencedSessionFrame
import com.superiorsoftllc.infantry.protocol.SessionReplayContract
import com.superiorsoftllc.infantry.protocol.SessionReplayGap
import com.superiorsoftllc.infantry.protocol.SessionReplayRequest
import com.superiorsoftllc.infantry.runner.ScriptedRunner
import com.superiorsoftllc.infantry.runner.StubScript
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F2 replay RED suite. It drives the actual Ktor daemon and real WebSocket clients through the
 * existing fixture; only the agent output is scripted.
 */
class SessionReplayDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun aDisconnectedClientReplaysOnlyMissedFramesInOrder() = withDaemon(scriptWithTextChunks(10), delayMs = 40) { fixture ->
        val firstClient = fixture.client
        val sessionId = startSession(firstClient)
        firstClient.awaitWhere("first streamed text delta", 10_000) {
            it.type == Envelope.TEXT_DELTA && it.sessionId == sessionId
        }
        firstClient.close()

        fixture.await(timeoutMs = 10_000, what = "the scripted session to finish after disconnect") {
            fixture.daemon.manager.get(sessionId)?.state?.name == "IDLE"
        }

        val alreadySeen = streamFrames(firstClient, sessionId)
        assertTrue(alreadySeen.isNotEmpty(), "the first connection must observe part of the session stream")
        val lastSeen = alreadySeen.last().sequence ?: alreadySeen.size.toLong()
        val reconnected = fixture.connectClient()
        requestReplay(reconnected, sessionId, lastSeen)

        fixture.await(timeoutMs = 5_000, what = "the replayed finished frame") {
            streamFrames(reconnected, sessionId).any { it.type == Envelope.FINISHED }
        }

        val replayed = streamFrames(reconnected, sessionId)
        val replaySequences = replayed.mapNotNull { it.sequence }
        assertTrue(replayed.isNotEmpty(), "reconnect must receive frames produced while disconnected")
        assertTrue(replayed.all { (it.sequence ?: Long.MIN_VALUE) > lastSeen }, "replay must exclude the last seen frame")
        assertStrictlyIncreasing(replaySequences, "replayed frames")

        val combined = (alreadySeen + replayed).mapNotNull { it.sequence }
        assertEquals(combined.size, combined.distinct().size, "a sequence must not be delivered twice")
        assertEquals((1L..combined.size.toLong()).toList(), combined.sorted(), "the combined stream must be complete")
    }

    @Test
    fun everySessionStreamFrameHasOneStrictlyIncreasingSequence() = withDaemon(StubScript.default()) { fixture ->
        val sessionId = startSession(fixture.client)
        awaitFinished(fixture.client, sessionId)

        val frames = streamFrames(fixture.client, sessionId)
        assertTrue(frames.isNotEmpty(), "the scripted session must emit stream frames")
        assertTrue(frames.all { it.sequence != null }, "every session stream frame must carry sequence")
        val sequences = frames.map { assertNotNull(it.sequence) }
        assertEquals((1L..sequences.size.toLong()).toList(), sequences, "sequence is assigned once in emission order")
    }

    @Test
    fun aReplayGapLargerThanTheBufferIsReported() = withDaemon(
        scriptWithTextChunks(SessionReplayContract.SESSION_REPLAY_BUFFER_CAPACITY + 20),
    ) { fixture ->
        val sessionId = startSession(fixture.client)
        awaitFinished(fixture.client, sessionId, timeoutMs = 15_000)

        val reconnected = fixture.connectClient()
        requestReplay(reconnected, sessionId, lastSequence = 0)
        val gapFrame = awaitGap(reconnected, sessionId)
        val gap = json.decodeFromString(SessionReplayGap.serializer(), gapFrame["data"]!!.jsonPrimitive.content)

        assertEquals(0L, gap.requestedAfterSequence)
        assertTrue(gap.oldestAvailableSequence > 1L, "the retained range must start after the requested cursor")
        assertTrue(gap.latestAvailableSequence >= gap.oldestAvailableSequence)
        assertTrue(gap.evictedCount > 0L, "the gap report must name the frames evicted from the buffer")
    }

    @Test
    fun sustainedOverflowKeepsTheReplayBufferAtItsNamedCapacity() = withDaemon(
        scriptWithTextChunks(SessionReplayContract.SESSION_REPLAY_BUFFER_CAPACITY * 4),
    ) { fixture ->
        val sessionId = startSession(fixture.client)
        awaitFinished(fixture.client, sessionId, timeoutMs = 20_000)

        val emitted = streamFrames(fixture.client, sessionId)
        assertTrue(emitted.size > SessionReplayContract.SESSION_REPLAY_BUFFER_CAPACITY)
        val reconnected = fixture.connectClient()
        requestReplay(reconnected, sessionId, lastSequence = 0)
        val gapFrame = awaitGap(reconnected, sessionId)
        fixture.await(timeoutMs = 10_000, what = "the retained replay range to include the latest frame") {
            streamFrames(reconnected, sessionId).any { it.type == Envelope.FINISHED }
        }

        val gap = json.decodeFromString(SessionReplayGap.serializer(), gapFrame["data"]!!.jsonPrimitive.content)
        val retained = streamFrames(reconnected, sessionId)
        assertEquals(SessionReplayContract.SESSION_REPLAY_BUFFER_CAPACITY, retained.size)
        assertEquals(emitted.size.toLong() - retained.size, gap.evictedCount)
        assertEquals(gap.oldestAvailableSequence, retained.first().sequence)
        assertEquals(gap.latestAvailableSequence, retained.last().sequence)
        assertTrue(retained.size <= SessionReplayContract.SESSION_REPLAY_BUFFER_CAPACITY)
    }

    @Test
    fun aCursorAtTheLastSeenSequenceDoesNotReplayAnythingTwice() = withDaemon(StubScript.default()) { fixture ->
        val sessionId = startSession(fixture.client)
        awaitFinished(fixture.client, sessionId)
        val alreadySeen = streamFrames(fixture.client, sessionId)
        val lastSeen = alreadySeen.last().sequence ?: alreadySeen.size.toLong()

        val reconnected = fixture.connectClient()
        requestReplay(reconnected, sessionId, lastSeen)
        requestReplay(reconnected, sessionId, lastSeen)
        reconnected.send(Envelope(Envelope.SESSIONS))
        reconnected.await(Envelope.SESSIONS, occurrence = 1)

        val unknownFrameErrors = reconnected.frames.filter { it.type == Envelope.ERROR }
            .map { it.data }.filter { it.contains("unknown frame type '${SessionReplayContract.REQUEST_TYPE}'") }
        assertTrue(unknownFrameErrors.isEmpty(), "replay_request must be a recognized request")
        assertTrue(streamFrames(reconnected, sessionId).isEmpty(), "a cursor at the latest seen sequence must return no stream frame")
    }

    private fun startSession(client: WsTestClient): String {
        client.await(Envelope.READY)
        client.send(Envelope(Envelope.PROMPT, "exercise F2 replay"))
        return client.awaitWhere("the new session snapshot", 10_000) {
            it.type == Envelope.SESSION && it.sessionId != null
        }.sessionId!!
    }

    private fun awaitFinished(client: WsTestClient, sessionId: String, timeoutMs: Long = 10_000) {
        client.awaitWhere("finished for session $sessionId", timeoutMs) {
            it.type == Envelope.FINISHED && it.sessionId == sessionId
        }
    }

    private fun requestReplay(client: WsTestClient, sessionId: String, lastSequence: Long) {
        val request = SessionReplayRequest(lastSequence)
        client.send(
            Envelope(
                type = SessionReplayContract.REQUEST_TYPE,
                data = json.encodeToString(SessionReplayRequest.serializer(), request),
                sessionId = sessionId,
            ),
        )
    }

    private fun awaitGap(client: WsTestClient, sessionId: String): JsonObject {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            client.rawFrames.firstOrNull {
                it["type"]?.jsonPrimitive?.contentOrNull == SessionReplayContract.GAP_TYPE &&
                    it["sessionId"]?.jsonPrimitive?.contentOrNull == sessionId
            }?.let { return it }
            Thread.sleep(20)
        }
        error("no '${SessionReplayContract.GAP_TYPE}' frame for $sessionId within 5000ms; got ${client.frames.map { it.type }}")
    }

    private fun streamFrames(client: WsTestClient, sessionId: String): List<SequencedSessionFrame> =
        client.rawFrames.mapNotNull { frame ->
            val type = frame["type"]?.jsonPrimitive?.contentOrNull
            val frameSessionId = frame["sessionId"]?.jsonPrimitive?.contentOrNull
            if (type !in SessionReplayContract.STREAM_FRAME_TYPES || frameSessionId != sessionId) {
                null
            } else {
                json.decodeFromJsonElement(SequencedSessionFrame.serializer(), frame)
            }
        }

    private fun assertStrictlyIncreasing(sequences: List<Long>, description: String) {
        assertTrue(sequences.isNotEmpty(), "$description must not be empty")
        assertTrue(
            sequences.zipWithNext().all { (left, right) -> left < right },
            "$description must be strictly increasing: $sequences",
        )
    }

    private fun scriptWithTextChunks(count: Int): StubScript = StubScript(
        stdoutLines = buildList {
            add("""{"type":"step_start","sessionID":"stub-f2-replay"}""")
            repeat(count) { index -> add("""{"type":"text","text":"f2-chunk-$index"}""") }
            add("""{"type":"step_finish"}""")
        },
    )

    private fun withDaemon(script: StubScript, delayMs: Long = 0, block: (DaemonFixture) -> Unit) {
        val fixture = DaemonFixture.launch(script, runner = ScriptedRunner(script, delayMs))
        try {
            block(fixture)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
