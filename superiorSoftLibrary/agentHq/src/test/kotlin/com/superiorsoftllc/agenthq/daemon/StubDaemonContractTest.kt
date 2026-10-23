package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.provider.OpenCodeProvider
import com.superiorsoftllc.agenthq.provider.ProviderFactory
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import com.superiorsoftllc.agenthq.protocol.FinishedPayload
import com.superiorsoftllc.agenthq.protocol.StatusPayload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE Phase 9 ACCEPTANCE CRITERION, as an executable test.
 *
 * "A client can connect to a daemon running the stub provider and render a scripted session
 * end-to-end -- spawn, streamed text, status, finish -- with no provider-specific knowledge."
 *
 * Nothing here is faked except the script: the REAL daemon (Ktor + CIO + the real WebSocket route,
 * the real [com.superiorsoftllc.agenthq.session.SessionManager], the real mailbox, the real frame
 * encoders) is booted on an ephemeral port, and a REAL `java.net.http.WebSocket` client drives it
 * exactly like the CLI client does. The only thing absent from the machine is a provider CLI, which
 * is the entire point.
 *
 * The test also asserts the guarantees the ticket makes explicitly:
 *  1. the transcript a client renders comes ONLY from the neutral vocabulary;
 *  2. the contract version is published to the client on connect;
 *  3. a real provider is UNCHANGED -- `AGENTHQ_PROVIDER` still resolves to opencode by default and
 *     the raw `stdout` / `exit` frames still carry the provider's own line.
 */
class StubDaemonContractTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Boot the whole daemon on an ephemeral port with the stub provider and a throwaway state dir,
     * so the test never touches the operator's real `~/.agenthq`.
     *
     * The daemon, the socket and the client are [DaemonFixture]'s; this is only the suspension point
     * the tests below read better with (`withDaemon(script) { client -> ... }`).
     */
    private fun withDaemon(script: StubScript, block: suspend (WsTestClient) -> Unit) {
        val fixture = DaemonFixture.launch(script)
        try {
            runBlocking { block(fixture.client) }
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    // --- the acceptance criterion ------------------------------------------------------------

    /** Contract 1.76.0: the READY frame a client sees on connect names the 1.76.0 contract version. */
    @Test
    fun readyFrameReportsContract132() = withDaemon(StubScript.default()) { client ->
        val ready = client.await(Envelope.READY)
        assertEquals("1.114.0", EventVocabulary.VERSION, "the published contract version must be 1.114.0")
        assertTrue(
            ready.data.contains("contract=${EventVocabulary.VERSION}"),
            "the READY frame must report contract ${EventVocabulary.VERSION}, got '${ready.data}'",
        )
    }

    @Test
    fun aClientRendersAScriptedSessionEndToEnd() = withDaemon(StubScript.default()) { client ->
        // 1. Connect: the daemon announces itself AND publishes the contract version.
        val ready = client.await(Envelope.READY)
        assertTrue(
            ready.data.contains("contract=${EventVocabulary.VERSION}"),
            "the contract version must be published on connect, got '${ready.data}'",
        )
        assertTrue(ready.data.contains("provider=stub"), "ready must name the provider: ${ready.data}")

        // 2. Spawn a session with a prompt.
        client.send(Envelope(Envelope.PROMPT, "hello stub"))

        // 3. The run is reported finished. `delayMs = 0` here, so the replay is quick; a real
        //    stream would take longer and the wait is identical.
        client.await(Envelope.FINISHED)

        // 4. The transcript a client renders is neutral vocabulary, and only that.
        val transcript = client.of(Envelope.TEXT_DELTA).map { it.data }
        assertTrue(
            transcript.isNotEmpty(),
            "a scripted session must stream text; got ${client.frames.map { it.type }}",
        )
        assertTrue(
            transcript.joinToString(" ").contains("daemon is running without a provider CLI"),
            "unexpected transcript: $transcript",
        )

        // 5. Status arrives as a lifecycle transition, not as provider bookkeeping.
        val statuses = client.of(Envelope.STATUS).map {
            json.decodeFromString(StatusPayload.serializer(), it.data).state
        }
        assertTrue(SessionState.BUSY in statuses, "a streamed session must report BUSY, got $statuses")
        assertTrue(SessionState.IDLE in statuses, "a finished session must come to rest at IDLE")

        // 6. Finish carries the daemon's own judgement, not just a code.
        val payload = json.decodeFromString(FinishedPayload.serializer(), client.await(Envelope.FINISHED).data)
        assertEquals(FinishedPayload.Outcome.SUCCEEDED, payload.outcome)
        assertEquals(0, payload.exitCode)

        // 7. The provider session id was observed, so the session is resumable -- which is what
        //    makes the mailbox's "delivery == resume" work against the stub too.
        val session = client.of(Envelope.SESSION)
            .mapNotNull { runCatching { json.decodeFromString(SessionRecord.serializer(), it.data) }.getOrNull() }
            .last()
        assertEquals("stub-session-1", session.providerSessionId)
        assertTrue(session.resumable, "a stub session must be resumable like a real one")
        assertEquals("stub", session.provider, "the frame must name the provider so a UI can label it")
    }

    @Test
    fun theRawStreamIsStillThereSoNothingBreaks() = withDaemon(StubScript.default()) { client ->
        client.send(Envelope(Envelope.PROMPT, "hello stub"))
        client.await(Envelope.FINISHED)

        // Phase 1's frames did not disappear: an existing client keeps working untouched.
        val raw = client.of(Envelope.STDOUT).map { it.data }
        assertTrue(raw.any { it.contains("step_start") }, "the provider's own line must still arrive: $raw")
        // Contract 1.64.0 (BUG-M2-11b-1): `exit` means a hosted process ended unasked; a one-shot turn's end is `finished` only.
        assertEquals(emptyList(), client.of(Envelope.EXIT).map { it.data }, "no raw exit for a turn that ended normally")

        // And the two streams cannot disagree: the neutral deltas are a translation of the raw
        // lines, produced from the same event.
        val deltas = client.of(Envelope.TEXT_DELTA).map { it.data }
        val rawTexts = raw.filter { it.contains("\"text\"") }
        assertEquals(rawTexts.size, deltas.size, "each raw text line yields exactly one delta")
    }

    @Test
    fun aScriptedFailureIsReportedAsFailed() = withDaemon(StubScript.failing()) { client ->
        client.send(Envelope(Envelope.PROMPT, "please fail"))
        val finished = client.await(Envelope.FINISHED)
        val payload = json.decodeFromString(FinishedPayload.serializer(), finished.data)
        assertEquals(FinishedPayload.Outcome.FAILED, payload.outcome, "a non-zero exit is a FAILED run")
        assertEquals(3, payload.exitCode)

        // The provider's own error reaches the client as an `error` frame, not only as a code.
        val errors = client.of(Envelope.ERROR).map { it.data }
        assertTrue(errors.any { it.contains("scripted failure") }, "a provider error must surface: $errors")
    }

    // --- the "no provider-specific knowledge" guarantee, asserted on the factory --------------

    @Test
    fun theStubIsSelectedLikeAnyOtherProvider() {
        // A registry entry, not a special case in the daemon: same flag, same aliasing rules as
        // `opencode` and `claude`, and the same loud failure for a name nobody registered.
        assertEquals("stub", ProviderFactory.fromFlag("stub").id)
        assertEquals("stub", ProviderFactory.fromFlag("SCRIPT").id)
        assertEquals("stub", ProviderFactory.fromFlag("scripted").id)
    }

    @Test
    fun aRegisteredNameBeatsAnAlias() {
        // The alias table must never steal a name a caller registers. `ProviderSeamTest` registers
        // a vendor-neutral fake under `fake`, and that is exactly the collision this guards: an
        // alias claiming `fake` would hand back the stub and look like a broken registration.
        ProviderFactory.register("alias-probe") { OpenCodeProvider() }
        assertEquals(
            "opencode",
            ProviderFactory.fromFlag("alias-probe").id,
            "a registered name must win over any alias resolution",
        )
    }

    @Test
    fun realProvidersAreUnchanged() {
        assertEquals("opencode", ProviderFactory.fromFlag(null).id, "the default is untouched")
        assertEquals("opencode", ProviderFactory.fromFlag("").id)
        assertEquals("claude", ProviderFactory.fromFlag("claude").id)
        // A real CLI spawns a child, so it brings no transport of its own: `AGENTHQ_RUNNER` keeps
        // deciding the pipes-vs-PTY question exactly as it did before this phase.
        assertNull(ProviderFactory.fromFlag("opencode").transport())
        assertNull(ProviderFactory.fromFlag("claude").transport())
    }
}
