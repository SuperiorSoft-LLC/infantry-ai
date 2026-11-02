package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.cli.Cli
import com.superiorsoftllc.agenthq.harness.HarnessRegistry
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.harness.NoHarnessProvider
import com.superiorsoftllc.agenthq.provider.ProviderFactory
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * H2 (docs/HARNESS-PLUGINS.md 3.4, B1-B7): a daemon with NO harness registered starts, serves health, lists no harness,
 * refuses harness-needing actions with "no harness installed", and still does everything that needs none.
 */
class ZeroHarnessDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val halves = listOf("Infantry_Board", "Infantry_Usage", "Infantry_Processes", "Infantry_Agents", "Infantry_Roles", "Infantry_Attention", "Infantry_Settings")

    private fun withZeroHarnessDaemon(block: (DaemonFixture) -> Unit) {
        val state = java.nio.file.Files.createTempDirectory("agenthq-zero-harness").toFile()
        val fixture = DaemonFixture.launch(
            stateDir = state,
            defaultProvider = NoHarnessProvider,
            harnesses = HarnessRegistry(),
            pluginsRoot = TestDaemonPlugins.root(*halves.map { "official/$it" }.toTypedArray()),
            pluginTrust = TestDaemonPlugins.trustAll,
        )
        try {
            fixture.client.await(Envelope.READY)
            block(fixture)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun `B1 - with nothing registered the default provider is none, not a crash`() {
        ProviderFactory.withNoBuilders {
            assertEquals(NoHarnessProvider.ID, ProviderFactory.defaultId())
            assertIs<NoHarnessProvider>(ProviderFactory.fromFlag(null))
            assertIs<NoHarnessProvider>(ProviderFactory.fromFlag("  "))
        }
        // The stub is a test provider, never the default.
        ProviderFactory.withNoBuilders {
            ProviderFactory.register("stub") { com.superiorsoftllc.agenthq.provider.StubProvider() }
            assertEquals(NoHarnessProvider.ID, ProviderFactory.defaultId())
        }
        // H8b-3: the built-ins are the stub alone (opencode and claude are harness modules), so the default is still none.
        assertEquals(NoHarnessProvider.ID, ProviderFactory.defaultId())
        assertEquals(NoHarnessProvider.ID, ProviderFactory.fromFlag(null).id)
    }

    @Test
    fun `health ok, empty provider list, seven daemon halves loaded, no opencode dir`() = withZeroHarnessDaemon { fixture ->
        assertEquals("ok", fixture.health()["status"]?.jsonPrimitive?.content ?: "ok")

        fixture.send(Envelope(Envelope.PROVIDERS))
        val infos = json.decodeFromString(ListSerializer(ProviderInfo.serializer()), fixture.client.await(Envelope.PROVIDERS).data)
        assertEquals(emptyList(), infos.map { it.id }, "the placeholder default is not a harness")

        val http = java.net.http.HttpClient.newHttpClient()
        val plugins = json.parseToJsonElement(
            http.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://${fixture.connectHost}:${fixture.port}/plugins")).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            ).body(),
        ).jsonObject["modules"]!!.jsonArray.map { it.jsonObject }
        for (half in halves) {
            assertEquals("loaded", plugins.single { it["name"]!!.jsonPrimitive.content == half }["daemon"]!!.jsonPrimitive.content, "$half loads with no harness")
        }
        assertFalse(File(fixture.stateDir, "opencode").exists(), "B3: nothing of the opencode harness is written when it is absent")
    }

    @Test
    fun `a ticket start with no harness is refused with the install hint, a weighted one too, and a role can be created`() = withZeroHarnessDaemon { fixture ->
        val client = fixture.client
        // role create works with no harness
        client.send(Envelope.ROLE_SAVE, """{"id":"zero-role","name":"Zero","color":"#0ea5e9","persona":"You are a role."}""")
        assertEquals(Envelope.ROLE, client.awaitWhere("the role answer") { it.type == Envelope.ROLE || it.type == Envelope.ERROR }.type)

        client.saveProfile("""{"id":"zero-agent","name":"Zero","role":"zero-role"}""")

        for (weight in listOf("", ",\"weight\":3", ",\"weight\":5")) {
            val ticket = client.saveTicket("""{"title":"Needs a harness","agentId":"role:zero-role","useWorktree":false$weight}""")
            // Refused either as an error frame or as a ticket frame whose `start` is start_refused; both carry the cause.
            val from = client.mark()
            client.send(Envelope.TICKET_MOVE, """{"id":"${ticket.id}","status":"in_progress"}""")
            val answer = client.awaitAfter(from, "the refusal for weight '$weight'") {
                it.type == Envelope.ERROR || (it.type == Envelope.TICKET && it.data.contains("start_refused"))
            }
            assertTrue(NoHarnessProvider.NOT_INSTALLED in answer.data, "weight '$weight': ${answer.data.take(400)}")
        }
    }

    @Test
    fun `B7 - a ticket pinning an absent harness is refused as not installed`() = withZeroHarnessDaemon { fixture ->
        val from = fixture.client.mark()
        fixture.client.send(Envelope.TICKET_SAVE, """{"title":"Pinned","provider":"claude"}""")
        val answer = fixture.client.awaitAfter(from, "the save refusal") { it.type == Envelope.ERROR || it.type == Envelope.TICKET }
        assertEquals(Envelope.ERROR, answer.type, answer.data)
        assertTrue("harness 'claude' is not installed" in answer.data, answer.data)
        assertTrue(NoHarnessProvider.NOT_INSTALLED in answer.data, answer.data)
    }

    @Test
    fun `B4 - the harnesses listing says how to install one`() {
        assertTrue(Cli.NO_HARNESS_HINT.contains("harness-install"), Cli.NO_HARNESS_HINT)
        assertTrue(Cli.NO_HARNESS_HINT.startsWith("No harness installed"))
    }
}
