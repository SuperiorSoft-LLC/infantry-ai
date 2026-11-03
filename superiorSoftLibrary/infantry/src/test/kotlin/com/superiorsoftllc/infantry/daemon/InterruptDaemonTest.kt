package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.mailbox.Message
import com.superiorsoftllc.infantry.mailbox.MailSendRequest
import com.superiorsoftllc.infantry.mailbox.MessageState
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.RUN_IN_FLIGHT
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * c604b48a, the executable slice: a busy agent can be INTERRUPTED (its current turn ends, the session
 * survives at rest with its id, provider conversation and transcript), and a message sent to a busy
 * agent is QUEUED by the same busy-recipient path MailRouter already uses and delivered at rest.
 *
 * Every test runs on the real daemon over its socket. The run is held open by a long scripted turn
 * (no `step_finish`), so the session is genuinely BUSY when the test acts.
 */
class InterruptDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The transcript line SessionManager.settleInterrupted writes; its presence is the visible stop. */
    private val INTERRUPT_MARKER = "turn interrupted by the owner"

    @Test
    fun anInterruptReturnsAMidTurnAgentToRestWithItsSessionAndTranscriptIntact() = withDaemon(longTurn()) { fixture, runner ->
        val client = fixture.client
        client.await(Envelope.READY)
        client.send(Envelope(Envelope.PROMPT, "scan my directory"))
        val sessionId = client.awaitWhere("the first turn", 10_000) { it.type == Envelope.TURN }.sessionId!!
        awaitBusyWithProviderSession(fixture, sessionId)
        // Prove the turn is mid-stream before the stop: some text must already be on the wire, or the
        // "transcript survives" assertion below would hold vacuously.
        fixture.await(10_000, "some text to stream before the interrupt") {
            client.frames.count { it.type == Envelope.TEXT_DELTA && it.sessionId == sessionId } >= 3
        }

        client.send(Envelope(Envelope.INTERRUPT, sessionId))

        fixture.await(10_000, "the interrupted session to come to rest") {
            fixture.daemon.manager.get(sessionId)?.state == SessionState.IDLE
        }
        val rest = assertNotNull(fixture.daemon.manager.get(sessionId))
        assertEquals(1, fixture.daemon.manager.list().size, "an interrupt neither discards nor forks the session")
        assertEquals(sessionId, rest.id, "the session keeps its id")
        assertEquals("stub-session-1", rest.providerSessionId, "the provider conversation is kept for the next turn")
        assertNull(rest.killOutcome, "an interrupt is not a kill: no kill outcome is recorded")
        assertFalse(rest.live, "the agent is at rest, not still running")

        // The transcript survives: a reopened client replays the turn and the text streamed before the stop.
        val reopened = fixture.connectClient()
        reopened.send(replayRequest(sessionId))
        // Replay arrives frame by frame: wait for the LAST kind we assert on, not the first, or the snapshot is torn.
        fixture.await(10_000, "the replayed turn, the text streamed before the stop and the interrupt marker") {
            streamFrames(reopened, sessionId).let { frames ->
                frames.any { it.type == Envelope.TURN } && frames.any { it.type == Envelope.TEXT_DELTA } &&
                    frames.any { it.type == Envelope.STDERR && it.data.contains(INTERRUPT_MARKER) }
            }
        }
        val replayed = streamFrames(reopened, sessionId)
        assertEquals("scan my directory", replayed.first { it.type == Envelope.TURN }.data)
        assertTrue(replayed.any { it.type == Envelope.TEXT_DELTA }, "text streamed before the interrupt is still in the transcript")
        assertTrue(
            replayed.any { it.type == Envelope.STDERR && it.data.contains(INTERRUPT_MARKER) },
            "the interrupted turn is visible in the transcript as a marked stop, not silently dropped",
        )

        // And the session is genuinely usable again: the next turn resumes the same provider conversation.
        client.send(Envelope(Envelope.PROMPT, "still there?", sessionId))
        fixture.await(10_000, "the follow-up run to start") { runner.specs.size == 2 }
        val resumed = runner.specs[1].command
        assertEquals("stub-session-1", resumed.getOrNull(resumed.indexOf("--resume") + 1), "spec was $resumed")
    }

    @Test
    fun aMessageSentToABusyAgentIsQueuedAndDeliveredWhenItRests() = withDaemon(longTurn()) { fixture, runner ->
        val client = fixture.client
        client.await(Envelope.READY)
        client.send(Envelope(Envelope.PROMPT, "scan my directory"))
        val sessionId = client.awaitWhere("the first turn", 10_000) { it.type == Envelope.TURN }.sessionId!!
        awaitBusyWithProviderSession(fixture, sessionId)

        client.send(
            Envelope(
                Envelope.MAIL_SEND,
                json.encodeToString(MailSendRequest.serializer(), MailSendRequest(from = "human", to = sessionId, body = "queued while busy")),
                sessionId,
            ),
        )
        val queued = client.awaitWhere("the queued message", 10_000) { mailBody(it) == "queued while busy" }
        assertEquals(MessageState.QUEUED, mailOf(queued).state, "a busy recipient's message waits, it is not refused or failed")
        assertTrue(client.of(Envelope.MAIL_REFUSED).isEmpty(), "nothing was refused")
        assertEquals(1, runner.specs.size, "the queued message does not start a second run while the agent is busy")

        client.send(Envelope(Envelope.INTERRUPT, sessionId))

        val delivered = client.awaitWhere("the message delivered at rest", 10_000) {
            mailBody(it) == "queued while busy" && mailOf(it).state == MessageState.DELIVERED
        }
        assertEquals(MessageState.DELIVERED, mailOf(delivered).state)
        fixture.await(10_000, "the delivery run to start") { runner.specs.size == 2 }
        assertTrue(
            runner.processes[1].deliveredInput.contains("queued while busy"),
            "the queued message is handed to the agent's console on the run that follows the rest",
        )
    }

    @Test
    fun anInterruptOfAnAgentAtRestIsRefusedAndLeavesItAtRest() {
        val fixture = DaemonFixture.launch(StubScript.default())
        try {
            val client = fixture.client
            client.await(Envelope.READY)
            client.send(Envelope(Envelope.PROMPT, "quick question"))
            val sessionId = client.awaitWhere("the first turn", 10_000) { it.type == Envelope.TURN }.sessionId!!
            fixture.await(10_000, "the quick turn to rest") { fixture.daemon.manager.get(sessionId)?.state == SessionState.IDLE }

            client.send(Envelope(Envelope.INTERRUPT, sessionId))
            val refusal = client.awaitWhere("an error refusing the interrupt", 10_000) { it.type == Envelope.ERROR }
            assertTrue(refusal.data.contains("not busy"), "refusal names the reason: ${refusal.data}")
            val after = assertNotNull(fixture.daemon.manager.get(sessionId))
            assertEquals(SessionState.IDLE, after.state)
            assertEquals("stub-session-1", after.providerSessionId)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** A turn that keeps its run open for about a minute: step_start with the provider id, then a long stream and no step_finish. */
    /**
     * ticket-b5aa01bc (contract 1.96.0): a prompt to a session that already has a run in flight is refused with the
     * TYPED `run_in_flight <sessionId>` error, carrying the prompt's own requestId, and nothing of it reaches the stream.
     */
    @Test
    fun aPromptToABusySessionIsRefusedAsRunInFlightWithItsSessionAndRequestId() = withDaemon(longTurn()) { fixture, _ ->
        val client = fixture.client
        client.await(Envelope.READY)
        client.send(Envelope(Envelope.PROMPT, "scan my directory"))
        val sessionId = client.awaitWhere("the first turn", 10_000) { it.type == Envelope.TURN }.sessionId!!
        awaitBusyWithProviderSession(fixture, sessionId)

        client.send(Envelope(Envelope.PROMPT, "and then this", sessionId, requestId = "queue-1"))

        val refusal = client.awaitWhere("the busy refusal", 10_000) { it.type == Envelope.ERROR }
        assertTrue(refusal.data.startsWith("$RUN_IN_FLIGHT $sessionId: "), refusal.data)
        assertEquals("queue-1", refusal.requestId, "the refusal names the prompt it refuses")
        assertTrue(client.frames.none { it.type == Envelope.TURN && it.data == "and then this" }, "a refused prompt is never echoed")
        assertEquals(1, fixture.daemon.manager.get(sessionId)!!.runCount, "nothing ran")
    }

    private fun longTurn(): StubScript = StubScript(
        name = "long-turn",
        stdoutLines = listOf("""{"type":"step_start","sessionID":"stub-session-1"}""") +
            (1..1000).map { """{"type":"text","text":"scanning entry $it"}""" },
        exitCode = 0,
    )

    private fun awaitBusyWithProviderSession(fixture: DaemonFixture, sessionId: String) {
        fixture.await(10_000, "the run to be busy with a provider session id") {
            fixture.daemon.manager.get(sessionId)?.let { it.state == SessionState.BUSY && it.providerSessionId != null } == true
        }
    }

    private fun mailOf(env: Envelope): Message = json.decodeFromString(Message.serializer(), env.data)

    private fun mailBody(env: Envelope): String? =
        if (env.type == Envelope.MAIL) runCatching { mailOf(env).body }.getOrNull() else null

    private fun replayRequest(sessionId: String): Envelope = Envelope(
        SessionReplayContract.REQUEST_TYPE,
        json.encodeToString(SessionReplayRequest.serializer(), SessionReplayRequest(lastSequence = 0)),
        sessionId,
    )

    private fun streamFrames(client: WsTestClient, sessionId: String): List<SequencedSessionFrame> =
        client.rawFrames.mapNotNull { frame ->
            val type = frame["type"]?.jsonPrimitive?.contentOrNull
            val frameSessionId = frame["sessionId"]?.jsonPrimitive?.contentOrNull
            if (type !in SessionReplayContract.STREAM_FRAME_TYPES || frameSessionId != sessionId) null
            else json.decodeFromJsonElement(SequencedSessionFrame.serializer(), frame)
        }

    /** Records every spec the daemon hands its transport, then runs it on the scripted transport. */
    private class RecordingRunner(private val inner: ScriptedRunner) : ProcessRunner {
        val specs: MutableList<ProcessSpec> = CopyOnWriteArrayList()
        override val id: String = "recording-${inner.id}"
        val processes: List<com.superiorsoftllc.infantry.runner.ScriptedProcess> get() = inner.processes
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            specs += spec
            return inner.start(spec, listener)
        }
    }

    private fun withDaemon(script: StubScript, block: (DaemonFixture, RecordingRunner) -> Unit) {
        val runner = RecordingRunner(ScriptedRunner(script, delayMs = 50))
        val fixture = DaemonFixture.launch(script, runner = runner)
        try {
            block(fixture, runner)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
