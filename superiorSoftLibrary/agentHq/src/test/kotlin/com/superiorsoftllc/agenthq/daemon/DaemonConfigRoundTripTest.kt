package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.plugin.api.ConfigChangedFrame
import com.superiorsoftllc.agenthq.plugin.api.ConfigDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ConfigSchemaFrame
import com.superiorsoftllc.agenthq.plugin.api.ConfigSetFrame
import com.superiorsoftllc.agenthq.plugin.api.ConfigValuesFrame
import com.superiorsoftllc.agenthq.plugin.api.DaemonCapabilities
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.host.PluginFolders
import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QA-CONFIG-3: a daemon-scoped config field over the real wire. A real daemon (temp state dir, ephemeral port, plugins root
 * pointed at the temp dir) loads an official module that declares `daemon.config`; clients drive `config_set` /
 * `config_changed` and the unchanged `settings` / `settings_set` frames, and a restart over the same state dir keeps the value.
 */
class DaemonConfigRoundTripTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val state: File = Files.createTempDirectory("agenthq-config-roundtrip").toFile()
    private val pluginsRoot = File(state, "plugins")
    private val before: String? = System.getProperty(PluginFolders.ROOT_PROPERTY)
    private val fixtures = mutableListOf<DaemonFixture>()

    private val node = "daemon.Acme_Hook"
    private val key = "Acme_Hook.timeoutSec"

    init {
        val manifest = ModuleManifest(
            name = "Acme_Hook", version = "1.0.0", author = "Acme", license = "NOASSERTION",
            daemon = DaemonCapabilities(config = listOf(ConfigDeclaration(node, "Hook", listOf(
                ConfigFieldDeclaration(key, "number", default = JsonPrimitive(5), min = 0.0, max = 60.0, scope = "daemon"),
            ), tab = "Agents"))),
        )
        File(pluginsRoot, "official/Acme_Hook").apply { mkdirs() }
            .resolve("module.json").writeText(json.encodeToString(ModuleManifest.serializer(), manifest))
        System.setProperty(PluginFolders.ROOT_PROPERTY, pluginsRoot.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        fixtures.asReversed().forEach { runCatching { it.stop() } }
        if (before == null) System.clearProperty(PluginFolders.ROOT_PROPERTY) else System.setProperty(PluginFolders.ROOT_PROPERTY, before)
        state.deleteRecursively()
    }

    private fun boot(): DaemonFixture = DaemonFixture.launch(stateDir = state, pluginsRoot = pluginsRoot).also { fixtures += it }

    private fun set(value: kotlinx.serialization.json.JsonElement?, expectRev: Long?) =
        Envelope(Envelope.CONFIG_SET, json.encodeToString(ConfigSetFrame.serializer(), ConfigSetFrame(node, key, value, expectRev)))

    private fun WsTestClient.schema() = json.decodeFromString(ConfigSchemaFrame.serializer(), await(Envelope.CONFIG_SCHEMA).data)
    private fun WsTestClient.values(occurrence: Int = 0) = json.decodeFromString(ConfigValuesFrame.serializer(), await(Envelope.CONFIG_VALUES, occurrence).data)

    @Test
    fun `a daemon field is set through config_set, a stale rev is refused, the other client is told and the value survives a restart`() {
        val daemon = boot()
        val first = daemon.client
        val schema = first.schema()
        assertEquals(listOf(key), schema.declarations.single().fields.map { it.key }, "the module's field is in the schema")
        assertEquals(emptyList(), first.values().rows, "nothing is stored before the first write")
        val second = daemon.connectClient()
        second.schema()

        daemon.send(set(JsonPrimitive(9), schema.rev))
        val after = first.values(1)
        assertEquals(JsonPrimitive(9), after.rows.single().value)
        val pushed = json.decodeFromString(ConfigChangedFrame.serializer(), second.await(Envelope.CONFIG_CHANGED).data)
        assertEquals(ConfigChangedFrame(node, key, JsonPrimitive(9), "client", after.rev), pushed)

        // The rev moved with the write, so the old one is stale: refused, nothing changes.
        daemon.send(set(JsonPrimitive(20), schema.rev))
        val refusal = first.awaitWhere("stale config_set error") { it.type == Envelope.ERROR && it.data.contains("config_set refused") }
        assertTrue(refusal.data.contains("stale"), refusal.data)
        daemon.send(set(JsonPrimitive(61), after.rev))
        first.awaitWhere("out-of-range config_set error") { it.type == Envelope.ERROR && it.data.contains("config_set refused") && !it.data.contains("stale") }
        assertEquals(1, second.of(Envelope.CONFIG_CHANGED).size, "a refused write pushes nothing")

        // Restart over the same state dir and database: the stored value is still served.
        daemon.stop()
        val reopened = boot()
        assertEquals(JsonPrimitive(9), reopened.client.values().rows.single().value, "the value survived the restart")
        assertNotNull(reopened.client.schema())
    }

    @Test
    fun `a client that never sends the config frames keeps using settings and settings_set unchanged`() {
        val daemon = boot()
        fun setting(k: String, v: Int) = json.encodeToString(JsonObject.serializer(), buildJsonObject { put("key", k); put("value", v) })
        // A built-in key: answered by the same `settings` frame as before the config frames existed.
        daemon.send(Envelope.SETTINGS_SET, setting("session.keepAliveMinutes", 30))
        daemon.client.awaitWhere("settings frame with keepAlive 30") {
            it.type == Envelope.SETTINGS && (json.parseToJsonElement(it.data) as JsonObject)["session.keepAliveMinutes"] == JsonPrimitive(30)
        }
        // A plugin's declared key is still accepted by settings_set (validated by its declaration) and is the same store config_values reads.
        val before = daemon.client.of(Envelope.SETTINGS).size
        daemon.send(Envelope.SETTINGS_SET, setting(key, 12))
        daemon.client.awaitWhere("settings answer for the plugin key") { it.type == Envelope.SETTINGS && daemon.client.of(Envelope.SETTINGS).size > before }
        daemon.send(Envelope.CONFIG_GET, "")
        daemon.client.awaitWhere("config_values holding 12") {
            it.type == Envelope.CONFIG_VALUES && json.decodeFromString(ConfigValuesFrame.serializer(), it.data).rows.any { r -> r.value == JsonPrimitive(12) }
        }
        // And a refused plugin value is an error frame, not a crash.
        daemon.send(Envelope.SETTINGS_SET, setting(key, 99))
        daemon.client.awaitWhere("settings_set refused") { it.type == Envelope.ERROR && it.data.contains("settings_set refused") }
    }
}
