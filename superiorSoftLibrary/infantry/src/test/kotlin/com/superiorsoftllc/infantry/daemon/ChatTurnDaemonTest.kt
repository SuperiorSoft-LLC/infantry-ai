package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.EventVocabulary
import com.superiorsoftllc.infantry.protocol.SequencedSessionFrame
import com.superiorsoftllc.infantry.protocol.SessionReplayContract
import com.superiorsoftllc.infantry.protocol.SessionReplayRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.runner.ScriptedRunner
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.SessionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contract 1.11.0: talking to an agent is ONE session a client continues turn after turn, and both
 * sides of that conversation are on the session's own stream.
 *
 * Before 1.11.0 only the agent's side was on the wire (`text_delta`): what the human asked existed
 * only as a local echo in whichever window sent it, so a second window, a reconnect or a replay showed
 * answers to questions nobody could see. The `turn` frame puts the prompt each run was started with on
 * the sequenced stream, in front of the reply it caused, so replay reconstructs the whole conversation.
 *
 * `ready` also names the daemon process (`instance`) and the moment its replay history begins
 * (`since`): sequence numbers restart with the daemon, so a client must be able to tell a reconnect to
 * the same process (keep its cursors) from a restarted one (its cursors mean nothing any more).
 */
class ChatTurnDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun aPromptIsEchoedIntoItsSessionStreamAsASequencedTurnBeforeTheReply() = withDaemon { fixture, _ ->
        val client = fixture.client
        client.await(Envelope.READY)
        client.send(Envelope(Envelope.PROMPT, "hello agent"))

        val turn = client.awaitWhere("the first turn", 10_000) { it.type == Envelope.TURN }
        val sessionId = assertNotNull(turn.sessionId, "a turn belongs to the session it started")
        assertEquals("hello agent", turn.data, "the turn carries the prompt exactly as the provider received it")
        val turnSequence = assertNotNull(turn.sequence, "a turn is part of the replayable session stream")

        val reply = client.awaitWhere("the reply", 10_000) { it.type == Envelope.TEXT_DELTA && it.sessionId == sessionId }
        assertTrue(turnSequence < assertNotNull(reply.sequence), "the turn precedes the reply it caused")
        assertEquals(Envelope.TURN, EventVocabulary.TURN)
    }

    @Test
    fun aFollowUpThatNamesTheSessionContinuesTheSameProviderConversation() = withDaemon { fixture, runner ->
        val client = fixture.client
        val sessionId = converse(fixture, "first question", "follow-up question")

        val turns = streamFrames(client, sessionId).filter { it.type == Envelope.TURN }.map { it.data }
        assertEquals(listOf("first question", "follow-up question"), turns, "both turns are on the one session")

        val record = assertNotNull(fixture.daemon.manager.get(sessionId))
        assertEquals(1, fixture.daemon.manager.list().size, "a follow-up never forks a second session")
        assertEquals(2, record.runCount)
        assertEquals(1, record.resumeCount)
        assertEquals(SessionState.IDLE, record.state)

        // The second run was handed the provider's own session id: that is what carries the context.
        assertEquals(2, runner.specs.size, "one run per turn")
        assertFalse("--resume" in runner.specs[0].command, "the first turn starts a fresh provider session")
        val resumed = runner.specs[1].command
        assertEquals("stub-session-1", resumed.getOrNull(resumed.indexOf("--resume") + 1), "spec was ${resumed}")
    }

    @Test
    fun aReopenedConversationReplaysBothSidesInSequenceOrder() = withDaemon { fixture, _ ->
        val sessionId = converse(fixture, "first question", "follow-up question")

        val reopened = fixture.connectClient()
        reopened.send(
            Envelope(
                SessionReplayContract.REQUEST_TYPE,
                json.encodeToString(SessionReplayRequest.serializer(), SessionReplayRequest(lastSequence = 0)),
                sessionId,
            ),
        )
        fixture.await(10_000, "the replayed second finished frame") {
            streamFrames(reopened, sessionId).count { it.type == Envelope.FINISHED } == 2
        }

        val replayed = streamFrames(reopened, sessionId)
        val sequences = replayed.map { assertNotNull(it.sequence) }
        assertEquals(sequences.sorted(), sequences, "replay is in sequence order")
        val conversation = replayed.filter { it.type == Envelope.TURN || it.type == Envelope.FINISHED }.map { it.type to it.data.take(18) }
        assertEquals(
            listOf(Envelope.TURN, Envelope.FINISHED, Envelope.TURN, Envelope.FINISHED),
            conversation.map { it.first },
            "each turn is followed by the run it started: $conversation",
        )
        assertEquals("first question", conversation[0].second)
        assertEquals("follow-up question", conversation[2].second)
    }

    @Test
    fun readyNamesTheDaemonInstanceAndWhereItsReplayHistoryBegins() {
        val first = DaemonFixture.launch(StubScript.default())
        val second = DaemonFixture.launch(StubScript.default())
        try {
            val ready = readyFields(first.client.await(Envelope.READY).data)
            assertEquals(EventVocabulary.VERSION, ready["contract"], "instance/since arrived with contract 1.11.0")
            val instance = assertNotNull(ready["instance"], "ready must name the daemon process: $ready")
            assertTrue(instance.isNotBlank())
            val since = assertNotNull(ready["since"]?.toLongOrNull(), "ready must say where replay history begins: $ready")
            assertTrue(since <= System.currentTimeMillis())

            val again = readyFields(first.connectClient().await(Envelope.READY).data)
            assertEquals(instance, again["instance"], "every connection to one process sees one instance")
            assertEquals(since.toString(), again["since"])

            val other = readyFields(second.client.await(Envelope.READY).data)
            assertNotEquals(instance, other["instance"], "another daemon process is another instance")

            // A session that streams after the daemon started is inside its history window.
            first.client.send(Envelope(Envelope.PROMPT, "inside the window"))
            val sessionId = first.client.awaitWhere("a turn", 10_000) { it.type == Envelope.TURN }.sessionId!!
            assertTrue(first.daemon.manager.get(sessionId)!!.lastHeartbeat >= since)
        } finally {
            listOf(first, second).forEach { it.stop(); it.deleteState() }
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Two turns on one session over [DaemonFixture.client]; returns the session id once both runs ended. */
    private fun converse(fixture: DaemonFixture, first: String, followUp: String): String {
        val client = fixture.client
        client.await(Envelope.READY)
        client.send(Envelope(Envelope.PROMPT, first))
        val sessionId = client.awaitWhere("the first turn", 10_000) { it.type == Envelope.TURN }.sessionId!!
        fixture.await(10_000, "the first run to rest with a provider session id") {
            fixture.daemon.manager.get(sessionId)?.let { it.state == SessionState.IDLE && it.providerSessionId != null } == true
        }
        client.send(Envelope(Envelope.PROMPT, followUp, sessionId))
        fixture.await(10_000, "the second run to finish") {
            client.frames.count { it.type == Envelope.FINISHED && it.sessionId == sessionId } == 2 &&
                fixture.daemon.manager.get(sessionId)?.state == SessionState.IDLE
        }
        return sessionId
    }

    private fun readyFields(data: String): Map<String, String> =
        data.split(' ').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }.associate { it[0] to it[1] }

    private fun streamFrames(client: WsTestClient, sessionId: String): List<SequencedSessionFrame> =
        client.rawFrames.mapNotNull { frame ->
            val type = frame["type"]?.jsonPrimitive?.contentOrNull
            val frameSessionId = frame["sessionId"]?.jsonPrimitive?.contentOrNull
            if (type !in SessionReplayContract.STREAM_FRAME_TYPES || frameSessionId != sessionId) null
            else json.decodeFromJsonElement(SequencedSessionFrame.serializer(), frame)
        }

    /** Records every spec the daemon hands its transport, then runs it on the scripted transport. */
    private class RecordingRunner(private val inner: ProcessRunner) : ProcessRunner {
        val specs: MutableList<ProcessSpec> = CopyOnWriteArrayList()
        override val id: String = "recording-${inner.id}"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            specs += spec
            return inner.start(spec, listener)
        }
    }

    private fun withDaemon(block: (DaemonFixture, RecordingRunner) -> Unit) {
        val script = StubScript.default()
        val runner = RecordingRunner(ScriptedRunner(script, delayMs = 0))
        val fixture = DaemonFixture.launch(script, runner = runner)
        try {
            block(fixture, runner)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
