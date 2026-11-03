package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.plugin.api.ConfigChangedFrame
import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigSchemaFrame
import com.superiorsoftllc.infantry.plugin.api.ConfigSetFrame
import com.superiorsoftllc.infantry.plugin.api.ConfigValuesFrame
import com.superiorsoftllc.infantry.plugin.api.DaemonCapabilities
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.plugin.host.ModuleStatus
import com.superiorsoftllc.infantry.plugin.host.Resolution
import com.superiorsoftllc.infantry.settings.SettingsService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** CONFIG-8: the daemon's compiled config manifest, its frames, the stale-write guard and the validator fallback of `SettingsService.set`. */
class DaemonConfigTest {
    private val dir: File = Files.createTempDirectory("infantry-daemon-config").toFile()
    private val store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath)
    private val settings = SettingsService(store)
    private val config = DaemonConfig(settings, store)

    @AfterTest
    fun tearDown() { store.close(); dir.deleteRecursively() }

    private fun module(name: String, tier: Tier, vararg fields: ConfigFieldDeclaration, node: String = "daemon.$name") = ModuleStatus(
        name, tier, ModuleState.LOADED, null,
        ModuleManifest(name = name, version = "1.0.0", daemon = DaemonCapabilities(config = listOf(ConfigDeclaration(node, "Agents", fields.toList(), tab = "Agents")))),
    )

    private val timeout = ConfigFieldDeclaration("Acme_Hook.timeoutSec", "number", default = JsonPrimitive(5), min = 0.0, max = 60.0, scope = "daemon")
    private val mode = ConfigFieldDeclaration("Acme_Hook.mode", "radio", default = JsonPrimitive("fast"), options = listOf("fast", "slow"))

    private fun load(vararg modules: ModuleStatus) = config.load(Resolution(modules.toList(), emptyList(), emptyList()))

    @Test
    fun `the schema holds only daemon fields and frames round-trip through json`() {
        load(module("Acme_Hook", Tier.OFFICIAL, timeout, mode, ConfigFieldDeclaration("Acme_Hook.ui", "text", scope = "device")))
        val schema = config.schema()
        assertEquals(listOf("Acme_Hook.timeoutSec", "Acme_Hook.mode"), schema.declarations.single().fields.map { it.key })
        assertTrue(schema.declarations.single().fields.all { it.scope == "daemon" })
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(schema, json.decodeFromString(ConfigSchemaFrame.serializer(), json.encodeToString(ConfigSchemaFrame.serializer(), schema)))
        val set = ConfigSetFrame("daemon.Acme_Hook", "Acme_Hook.timeoutSec", JsonPrimitive(9), schema.rev)
        assertEquals(set, json.decodeFromString(ConfigSetFrame.serializer(), json.encodeToString(ConfigSetFrame.serializer(), set)))
        val values = config.set(set)
        assertEquals(ConfigValuesFrame::class, values::class)
        assertEquals(JsonPrimitive(9), values.rows.single().value)
        assertEquals(values, json.decodeFromString(ConfigValuesFrame.serializer(), json.encodeToString(ConfigValuesFrame.serializer(), values)))
    }

    @Test
    fun `a stale expectRev is refused and changes nothing, a fresh one is accepted and pushed`() {
        load(module("Acme_Hook", Tier.OFFICIAL, timeout))
        val pushed = mutableListOf<ConfigChangedFrame>()
        config.onChanged = { pushed += it }
        val rev = config.rev
        config.set(ConfigSetFrame("daemon.Acme_Hook", "Acme_Hook.timeoutSec", JsonPrimitive(7), rev), by = "device-a")
        assertFailsWith<IllegalArgumentException> { config.set(ConfigSetFrame("daemon.Acme_Hook", "Acme_Hook.timeoutSec", JsonPrimitive(8), rev)) }
        assertEquals(JsonPrimitive(7), config.values().rows.single().value)
        assertEquals(listOf(ConfigChangedFrame("daemon.Acme_Hook", "Acme_Hook.timeoutSec", JsonPrimitive(7), "device-a", config.rev)), pushed)
        // A null value resets; no expectRev = last write wins.
        assertEquals(emptyList(), config.set(ConfigSetFrame("daemon.Acme_Hook", "Acme_Hook.timeoutSec", null)).rows)
    }

    @Test
    fun `settings set falls back to the declared validator for a plugin key and still refuses unknown ones`() {
        load(module("Acme_Hook", Tier.OFFICIAL, timeout, mode))
        settings.set("Acme_Hook.timeoutSec", JsonPrimitive(10))
        assertFailsWith<IllegalArgumentException> { settings.set("Acme_Hook.timeoutSec", JsonPrimitive(61)) }
        assertFailsWith<IllegalArgumentException> { settings.set("Acme_Hook.mode", JsonPrimitive("medium")) }
        assertFailsWith<IllegalArgumentException> { settings.set("Acme_Hook.mode", JsonArray(emptyList())) }
        assertFailsWith<IllegalArgumentException> { settings.set("nobody.declared", JsonPrimitive(1)) }
        assertEquals(JsonPrimitive(30), settings.set("session.keepAliveMinutes", JsonPrimitive(30))["session.keepAliveMinutes"], "a built-in key keeps its own validator")
        assertFailsWith<IllegalArgumentException> { settings.set("session.keepAliveMinutes", JsonPrimitive(-1)) }
    }

    @Test
    fun `a community plugin's field is read-only and a field the daemon already owns is dropped`() {
        val dropped = load(
            module("Acme_Hook", Tier.COMMUNITY, timeout),
            module("Infantry_Dup", Tier.OFFICIAL, ConfigFieldDeclaration("session.keepAliveMinutes", "number", scope = "daemon"), ConfigFieldDeclaration("Acme_Hook.timeoutSec", "number", scope = "daemon")),
        )
        assertEquals(2, dropped.size)
        assertTrue(config.schema().declarations.single().readOnly)
        assertFailsWith<IllegalArgumentException> { config.set(ConfigSetFrame("daemon.Acme_Hook", "Acme_Hook.timeoutSec", JsonPrimitive(1))) }
        assertEquals(emptyList(), config.values().rows)
    }

    @Test
    fun `a daemon with no daemon config serves an empty schema and the old settings are untouched`() {
        load(ModuleStatus("Infantry_Plain", Tier.OFFICIAL, ModuleState.LOADED, null, ModuleManifest(name = "Infantry_Plain", version = "1")))
        assertEquals(emptyList(), config.schema().declarations)
        assertEquals(JsonPrimitive(30), settings.all()["session.keepAliveMinutes"])
    }
}
