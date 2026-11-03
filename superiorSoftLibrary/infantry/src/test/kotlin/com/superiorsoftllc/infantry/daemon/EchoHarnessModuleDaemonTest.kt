package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.harness.HarnessRegistry
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.ProviderInfo
import com.superiorsoftllc.infantry.harness.NoHarnessProvider
import com.superiorsoftllc.infantry.runner.PipedProcessRunner
import com.superiorsoftllc.infantry.session.SessionState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HARNESS-PLUGIN-3 end to end: a daemon with NO built-in harness and ONLY the example module plugins/examples/Acme_EchoHarness
 * (its BUILT harness jar, as a community module granted in modules.json) lists the `echo` harness, starts a session on it
 * through the real child process and finishes the run. With the module removed, the same state dir starts with no harness and
 * a spawn of `echo` is refused as not installed. Without the grant, the module is never loaded.
 */
class EchoHarnessModuleDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val module = "Acme_EchoHarness"
    private val granted = """{"$module":{"enabled":true,"allow":["harness.code","harness.setting:echo.prefix","harness.cli","harness.cli.install"]}}"""

    private fun root(modulesJson: String) = TestDaemonPlugins.root("examples/$module", folder = "community", tier = "community", modulesJson = modulesJson)

    private fun launch(state: File, pluginsRoot: File) = DaemonFixture.launch(
        stateDir = state,
        runner = PipedProcessRunner(),
        defaultProvider = NoHarnessProvider,
        harnesses = HarnessRegistry(),
        pluginsRoot = pluginsRoot,
        pluginTrust = TestDaemonPlugins.trustAll,
    )

    private fun providerIds(fixture: DaemonFixture): List<String> {
        val from = fixture.client.mark()
        fixture.send(Envelope(Envelope.PROVIDERS))
        return json.decodeFromString(
            ListSerializer(ProviderInfo.serializer()),
            fixture.client.awaitAfter(from, "providers") { it.type == Envelope.PROVIDERS }.data,
        ).map { it.id }
    }

    /** HARNESS-PLUGIN-4 H5a: the module's `GET /plugins` entry. */
    private fun pluginEntry(fixture: DaemonFixture): kotlinx.serialization.json.JsonObject {
        val body = java.net.http.HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://${fixture.connectHost}:${fixture.port}/plugins")).GET().build(),
            java.net.http.HttpResponse.BodyHandlers.ofString(),
        ).body()
        return json.parseToJsonElement(body).jsonObject["modules"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == module }
    }

    private fun assertNotInstalled(fixture: DaemonFixture) {
        val before = fixture.daemon.manager.list().map { it.id }.toSet()
        val from = fixture.client.mark()
        fixture.send(Envelope(Envelope.NEW, """{"provider":"echo"}"""))
        val refusal = fixture.client.awaitAfter(from, "the refusal of echo") { it.type == Envelope.ERROR }
        assertTrue("harness 'echo' is not installed" in refusal.data, refusal.data)
        // A session restored from the first daemon's state may remain listed; the refused spawn must not add one.
        assertEquals(before, fixture.daemon.manager.list().map { it.id }.toSet(), "no session on an absent harness")
    }

    @Test
    fun `only the echo module - it is listed, a session runs on it and finishes, removing it leaves a clean not installed`() {
        val state = Files.createTempDirectory("infantry-echo-harness").toFile()
        val pluginsRoot = root(granted)
        val first = launch(state, pluginsRoot)
        try {
            first.client.await(Envelope.READY)
            assertEquals(listOf("echo"), providerIds(first), "only the module's harness is installed")
            // H5a: GET /plugins shows the harness state and the ids it registered.
            val entry = pluginEntry(first)
            assertEquals("loaded", entry["harness"]!!.jsonPrimitive.content, "entry: $entry")
            assertEquals(listOf("echo"), entry["harnessIds"]!!.jsonArray.map { it.jsonPrimitive.content })

            first.send(Envelope(Envelope.NEW, """{"provider":"echo"}"""))
            first.send(Envelope(Envelope.PROMPT, "ping from the daemon"))
            first.client.awaitWhere("the echoed prompt on stdout", timeoutMs = 60_000) {
                it.type == Envelope.STDOUT && it.data.contains("echo: ping from the daemon")
            }
            first.await(timeoutMs = 60_000, what = "the provider session id from the plugin's translator") {
                first.daemon.manager.list().singleOrNull()?.providerSessionId?.startsWith("echo-") == true
            }
            first.await(timeoutMs = 60_000, what = "the run to finish") {
                first.daemon.manager.list().single().state.let { it != SessionState.BUSY && it != SessionState.STARTING }
            }
            assertEquals("echo", first.daemon.manager.list().single().provider)
            assertTrue(first.client.of(Envelope.ERROR).isEmpty(), "errors: ${first.client.of(Envelope.ERROR)}")
            assertTrue(first.client.of(Envelope.STDOUT).none { it.data.contains("context limit:") }, "no limit: nothing reaches the plugin")

            // CONTEXT-LIMIT-1: the plugin's declared capability is listed, and a spawn's limit reaches its translator (API 2.1).
            val from = first.client.mark()
            first.send(Envelope(Envelope.PROVIDERS))
            val echo = json.decodeFromString(
                ListSerializer(ProviderInfo.serializer()),
                first.client.awaitAfter(from, "providers") { it.type == Envelope.PROVIDERS }.data,
            ).single()
            assertEquals("supported", echo.contextLimit)
            assertTrue(echo.contextLimitHow.orEmpty().contains("ECHO_CONTEXT_LIMIT"), "how: ${echo.contextLimitHow}")
            first.send(Envelope(Envelope.NEW, """{"provider":"echo","contextLimit":200000}"""))
            first.send(Envelope(Envelope.PROMPT, "ping with a limit"))
            first.client.awaitWhere("the limit echoed by the plugin", timeoutMs = 60_000) {
                it.type == Envelope.STDOUT && it.data.contains("context limit: 200000")
            }
            assertEquals(200_000, first.daemon.manager.list().single { it.contextLimit != null }.contextLimit)

            // EFFORT-1: the plugin's declared effort capability is listed (API 2.2), and a spawn's effort reaches it.
            assertEquals("supported", echo.effort)
            assertTrue(echo.effortHow.orEmpty().contains("ECHO_EFFORT"), "how: ${echo.effortHow}")
            first.send(Envelope(Envelope.NEW, """{"provider":"echo","effort":"high"}"""))
            first.send(Envelope(Envelope.PROMPT, "ping with an effort"))
            first.client.awaitWhere("the effort echoed by the plugin", timeoutMs = 60_000) {
                it.type == Envelope.STDOUT && it.data.contains("effort: high")
            }
            assertEquals("high", first.daemon.manager.list().single { it.effort == "high" }.effort)
        } finally {
            first.stop()
        }

        // Remove the module folder (the class loader was closed with the daemon, so the jar is not held open).
        assertTrue(File(pluginsRoot, "community/$module").deleteRecursively(), "the module folder can be removed")
        val second = launch(state, pluginsRoot)
        try {
            second.client.await(Envelope.READY)
            assertEquals(emptyList(), providerIds(second), "the harness vanished with its module")
            assertNotInstalled(second)
        } finally {
            second.stop()
            second.deleteState()
        }
    }

    @Test
    fun `a community harness module without the harness code grant is never loaded`() {
        val fixture = launch(Files.createTempDirectory("infantry-echo-ungranted").toFile(), root("""{"$module":{"enabled":true}}"""))
        try {
            fixture.client.await(Envelope.READY)
            assertEquals(emptyList(), providerIds(fixture))
            assertNotInstalled(fixture)
            val entry = pluginEntry(fixture)
            assertEquals("refused: community harness code needs the 'harness.code' grant in modules.json", entry["harness"]!!.jsonPrimitive.content, "entry: $entry")
            assertTrue(entry["harnessIds"]!!.jsonArray.isEmpty())
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
