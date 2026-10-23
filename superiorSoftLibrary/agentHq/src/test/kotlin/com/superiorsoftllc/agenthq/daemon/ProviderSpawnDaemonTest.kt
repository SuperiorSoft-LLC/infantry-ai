package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import com.superiorsoftllc.agenthq.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.provider.AgentProvider
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.SessionState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract 1.3.0 (story 9.0), over the REAL daemon and a real WebSocket: one daemon serves two
 * providers, a `new` frame chooses which one (and which model) the connection's next sessions run on,
 * and a choice the daemon cannot honour is refused out loud -- never replaced by the default.
 *
 * The second provider is the stub's whole machinery under another id and default model, so nothing
 * here needs a CLI, and nothing provider-specific is asserted beyond the id and model a client sees.
 */
class ProviderSpawnDaemonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** A second scripted provider: the stub's transport and stream, another id and default model. */
    private class SecondStub(script: StubScript = StubScript.default()) :
        AgentProvider by StubProvider(script = script, delayMs = 0) {
        override val id = "second"
        override fun defaultModel() = "second-default"
    }

    private fun withDaemon(second: AgentProvider = SecondStub(), block: (DaemonFixture) -> Unit) {
        val fixture = DaemonFixture.launch(additionalProviders = listOf(second))
        try {
            fixture.client.await(Envelope.READY)
            block(fixture)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    // --- what a picker reads -----------------------------------------------------------------------

    @Test
    fun providersListsEveryProviderTheDefaultFirst() = withDaemon { fixture ->
        fixture.send(Envelope(Envelope.PROVIDERS))
        val answer = fixture.client.await(Envelope.PROVIDERS)

        assertEquals(
            listOf(
                ProviderInfo(id = "stub", default = true, defaultModel = null),
                ProviderInfo(id = "second", default = false, defaultModel = "second-default"),
            ),
            json.decodeFromString(ListSerializer(ProviderInfo.serializer()), answer.data),
        )
        // The literal bytes a UI parses: every field present, nulls included. AGENT-UX-10 adds `models`
        // (null here: the stub has no model catalogue) and `modelsStale`; CLIPATH-1 (1.73.0) adds `available`
        // and `unavailableReason` (the stub providers need no CLI, so both are available).
        assertEquals(
            """[{"id":"stub","default":true,"defaultModel":null,"models":null,"modelsStale":false,""" +
                """"available":true,"unavailableReason":null},""" +
                """{"id":"second","default":false,"defaultModel":"second-default","models":null,"modelsStale":false,""" +
                """"available":true,"unavailableReason":null}]""",
            answer.data,
        )
    }

    @Test
    fun healthListsTheProviders() = withDaemon { fixture ->
        val health = fixture.health()
        assertEquals(listOf("stub", "second"), health["providers"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("stub", health["provider"]!!.jsonPrimitive.content, "`provider` still names the default")
        // The literal version is pinned once, in StubDaemonContractTest; here it only has to be the served one.
        assertEquals(EventVocabulary.VERSION, health["contract"]!!.jsonPrimitive.content)
    }

    // --- choosing --------------------------------------------------------------------------------

    @Test
    fun aNewFrameChoosesTheProviderAndModelOfTheNextSession() = withDaemon { fixture ->
        fixture.send(Envelope(Envelope.NEW, """{"provider":"second","model":"m-1"}"""))
        fixture.send(Envelope(Envelope.PROMPT, "hello"))
        fixture.client.await(Envelope.FINISHED)

        val session = fixture.daemon.manager.list().single()
        assertEquals("second", session.provider)
        assertEquals("m-1", session.model)
        assertTrue(fixture.client.of(Envelope.ERROR).isEmpty(), "errors: ${fixture.client.of(Envelope.ERROR)}")
        // The `session` frame a client renders carries both, so a UI can label the tab.
        fixture.client.awaitWhere("a session frame naming provider and model") {
            it.type == Envelope.SESSION && it.data.contains("\"provider\":\"second\"") && it.data.contains("\"model\":\"m-1\"")
        }
    }

    @Test
    fun aBlankNewKeepsItsPre130Meaning() = withDaemon { fixture ->
        fixture.send(Envelope(Envelope.NEW, """{"provider":"second"}"""))
        fixture.send(Envelope(Envelope.NEW)) // clears the choice
        fixture.send(Envelope(Envelope.PROMPT, "hello"))
        fixture.client.await(Envelope.FINISHED)

        val session = fixture.daemon.manager.list().single()
        assertEquals("stub", session.provider, "an empty new means the default provider, as before 1.3.0")
        assertNull(session.model)
    }

    @Test
    fun theChoiceHoldsForEverySessionTheConnectionStartsUntilTheNextNew() =
        withDaemon(second = SecondStub(StubScript.failing())) { fixture ->
            fixture.send(Envelope(Envelope.NEW, """{"provider":"second"}"""))
            fixture.send(Envelope(Envelope.PROMPT, "one"))
            fixture.client.await(Envelope.FINISHED)
            fixture.await(what = "the first session failed") {
                fixture.daemon.manager.list().singleOrNull()?.state == SessionState.FAILED
            }

            // A failed session is terminal, so the next prompt starts another session...
            fixture.send(Envelope(Envelope.PROMPT, "two"))
            fixture.client.await(Envelope.FINISHED, occurrence = 1)

            val sessions = fixture.daemon.manager.list()
            assertEquals(2, sessions.size)
            // ...and on the provider the connection chose, not on the default.
            assertTrue(sessions.all { it.provider == "second" }, "providers: ${sessions.map { it.provider }}")
        }

    // --- refusing --------------------------------------------------------------------------------

    @Test
    fun anUnknownProviderIsRefusedAndTheNextPromptStartsNothing() = withDaemon { fixture ->
        fixture.send(Envelope(Envelope.NEW, """{"provider":"gemini"}"""))
        val refusal = fixture.client.await(Envelope.ERROR)
        assertTrue(refusal.data.startsWith("new refused:"), refusal.data)
        assertTrue(refusal.data.contains("gemini") && refusal.data.contains("stub, second"), refusal.data)

        fixture.send(Envelope(Envelope.PROMPT, "hello"))
        val blocked = fixture.client.await(Envelope.ERROR, occurrence = 1)
        assertTrue(blocked.data.startsWith("no session started"), blocked.data)
        assertTrue(fixture.daemon.manager.list().isEmpty(), "no session on the default provider either")

        // A valid `new` recovers the connection.
        fixture.send(Envelope(Envelope.NEW, """{"provider":"stub"}"""))
        fixture.send(Envelope(Envelope.PROMPT, "hello again"))
        fixture.client.await(Envelope.FINISHED)
        assertEquals("stub", fixture.daemon.manager.list().single().provider)
    }

    @Test
    fun aMisspeltOrNewerOptionIsRefusedNotIgnored() = withDaemon { fixture ->
        fixture.send(Envelope(Envelope.NEW, """{"provder":"second"}"""))
        val refusal = fixture.client.await(Envelope.ERROR)
        assertTrue(refusal.data.startsWith("new refused: malformed new payload"), refusal.data)
        assertTrue(refusal.data.contains("provder"), "the refusal names the key it did not know: ${refusal.data}")
    }

    @Test
    fun aModelThatCouldChangeTheCommandLineIsRefused() = withDaemon { fixture ->
        fixture.send(Envelope(Envelope.NEW, """{"provider":"second","model":"--dangerously-skip-permissions"}"""))
        val refusal = fixture.client.await(Envelope.ERROR)
        assertTrue(refusal.data.contains("refused") && refusal.data.contains("flag"), refusal.data)

        fixture.send(Envelope(Envelope.PROMPT, "hello"))
        fixture.client.await(Envelope.ERROR, occurrence = 1)
        assertTrue(fixture.daemon.manager.list().isEmpty())
    }
}
