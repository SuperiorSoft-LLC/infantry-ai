package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runner.StubScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Q0 fidelity: the text a user types must reach the provider EXACTLY, not CLI-wrapped.
 *
 * The real daemon (Ktor, WebSocket route, SessionManager, provider seam) is booted in-process by
 * [DaemonFixture], and the prompt is sent exactly as the desktop Controller sends it: a `new` frame,
 * then a `prompt` envelope whose data is the typed text. The stub is opted into ECHOING the stdin it
 * received ([StubScript.echoPrompt]), so the transcript a client renders shows what the provider
 * actually got. The only thing faked is the provider's own reply, which is what the stub is for.
 */
class PromptFidelityDaemonTest {

    /** The Q0 probe text: quotes, braces, non-ASCII letters and a division sign, plus a marker. */
    private val typed = "Q0 fidelity \"quoted\" {braces} ünï ÷ MARKER-7731"

    @Test
    fun theProviderReceivesTheTypedPromptExactlyNotCliWrapped() {
        val fixture = DaemonFixture.launch(script = StubScript(echoPrompt = true))
        try {
            val client = fixture.client
            client.await(Envelope.READY)
            client.send(Envelope(Envelope.NEW))
            client.send(Envelope(Envelope.PROMPT, typed))
            client.await(Envelope.FINISHED)

            val rendered = client.of(Envelope.TEXT_DELTA).map { it.data }
            assertTrue(
                rendered.any { it == typed },
                "the provider must receive the typed prompt byte-for-byte; rendered text was $rendered",
            )
            assertEquals(
                1,
                rendered.count { it.contains("MARKER-7731") },
                "the prompt must reach the provider once, not wrapped or duplicated; rendered text was $rendered",
            )
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
