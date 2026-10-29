package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.superiorsoftllc.agenthq.plugin.api.ConfigDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ConfigManifest
import com.superiorsoftllc.agenthq.plugin.api.ConfigSchemaFrame
import com.superiorsoftllc.agenthq.plugin.api.ConfigValuesFrame
import com.superiorsoftllc.agenthq.plugin.api.NodeConfigEntry
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.ModuleStatus
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
import com.superiorsoftllc.agenthq.ui.model.CHAT_MARKDOWN_PREF_KEY
import com.superiorsoftllc.agenthq.ui.model.ThemeAccent
import com.superiorsoftllc.agenthq.ui.model.ThemeSettings
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.plugin.config.DeviceScope
import com.superiorsoftllc.agenthq.ui.plugin.config.NODE_FIELD_CHECK_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.config.NODE_FIELD_OPTION_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.config.NodeConfigApi
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.shell.uiPrefsOverride
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val THEME_NODE = "infantry.shell.theme"

/**
 * QA-CONFIG-3: what QA-CONFIG-2 could not drive on screen. The real official plugin set (the same Kotlin objects the
 * packaged app loads) in the real window, over an in-memory ui-prefs store (never java.util.prefs) and a fake daemon link:
 * the generated Settings dialog, device-scope values persisting across a host restart without any daemon row, the
 * migration marker running once, and a client meeting an older and a newer daemon contract.
 */
@OptIn(ExperimentalTestApi::class)
class ConfigSettingsPersistenceUiTest {
    private var savedOverride: MutableMap<String, String>? = null
    private lateinit var prefs: MutableMap<String, String>

    @BeforeTest fun pinPrefs() {
        savedOverride = uiPrefsOverride
        prefs = mutableMapOf()
        uiPrefsOverride = prefs
    }

    @AfterTest fun restore() {
        uiPrefsOverride = savedOverride
        Palette.theme = ThemeSettings()
    }

    /** The daemon's node_config rows; a device field must never reach it. */
    private val daemonRows = LinkedHashMap<Pair<String, String>, NodeConfigEntry>()
    private val puts = mutableListOf<NodeConfigEntry>()
    private var manifest: ConfigManifest? = null
    private val api = object : NodeConfigApi {
        override suspend fun seed(entries: List<NodeConfigEntry>): Result<List<NodeConfigEntry>> = Result.success(daemonRows.values.toList())
        override suspend fun put(entry: NodeConfigEntry): Result<List<NodeConfigEntry>> {
            puts += entry
            if (entry.value == null) daemonRows.remove(entry.nodeId to entry.key) else daemonRows[entry.nodeId to entry.key] = entry
            return Result.success(daemonRows.values.toList())
        }
        override suspend fun manifest(host: String) = Result.success(manifest)
        override suspend fun putManifest(manifest: ConfigManifest): Result<ConfigManifest?> { this@ConfigSettingsPersistenceUiTest.manifest = manifest; return Result.success(manifest) }
    }

    /** The official plugin set, as the packaged app loads it (loaded, no jar: the objects themselves). */
    private fun factory(): TabFactory {
        val all = CoreModules.all + listOf(
            com.superiorsoftllc.agenthq.plugins.official.header.InfantryHeader,
            com.superiorsoftllc.agenthq.plugins.official.InfantryAgents,
            com.superiorsoftllc.agenthq.plugins.official.chat.InfantryChat,
            com.superiorsoftllc.agenthq.plugins.official.approvals.InfantryApprovals,
            com.superiorsoftllc.agenthq.plugins.official.appearance.InfantryAppearance,
            com.superiorsoftllc.agenthq.plugins.official.assistant.InfantryAssistant,
            com.superiorsoftllc.agenthq.plugins.official.alerts.InfantryAlerts,
            com.superiorsoftllc.agenthq.plugins.official.processes.InfantryProcesses,
        )
        return TabFactory(all, statuses = all.map { ModuleStatus(it.manifest.name, it.tier, ModuleState.LOADED, manifest = it.manifest) }, configApi = api)
    }

    private fun host(tabs: TabFactory, contract: String = "1.142.0", frames: List<Envelope> = emptyList(), body: ComposeUiTest.(Controller) -> Unit) =
        withConfigDialog(tabs, contract, frames, body)

    @Test fun accentDarkAndBlurPersistAcrossAHostRestartWithoutADaemonRowAndTheMigrationRunsOnce() {
        // Legacy pref the old build wrote: the first boot migrates it, once.
        prefs[BLUR_PREF_KEY] = "on"
        val blurKey = DeviceScope.prefKey(THEME_NODE, "blur")
        val accentKey = DeviceScope.prefKey(THEME_NODE, "accent")
        val darkKey = DeviceScope.prefKey(THEME_NODE, "dark")

        val first = factory()
        host(first) {
            assertEquals("1", prefs[DeviceScope.MIGRATED_MARKER], "the marker is set by the first boot")
            assertEquals("true", prefs[blurKey], "the legacy blur pref was copied")
            val dark = NODE_FIELD_CHECK_TAG_PREFIX + "$THEME_NODE/dark"
            val blur = NODE_FIELD_CHECK_TAG_PREFIX + "$THEME_NODE/blur"
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.APPEARANCE.name).performClick()
            waitForIdle()
            onNodeWithTag(NODE_FIELD_OPTION_TAG_PREFIX + "$THEME_NODE/accent/TEAL").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { Palette.theme.accent == ThemeAccent.TEAL }
            onNodeWithTag(dark).performScrollTo().assertIsOn()
            onNodeWithTag(dark).performClick()
            waitUntil(timeoutMillis = 10_000) { !Palette.theme.dark }
            onNodeWithTag(blur).performScrollTo().assertIsOn()
            onNodeWithTag(blur).performClick()
            waitForIdle()
            waitUntil(timeoutMillis = 10_000) { prefs[blurKey] == "false" }
            onNodeWithTag(blur).assertIsOff()
        }
        // The host is gone. The values are in the device store and nowhere on the daemon.
        assertEquals("\"TEAL\"", prefs[accentKey])
        assertEquals("false", prefs[darkKey])
        assertEquals("false", prefs[blurKey])
        assertTrue(puts.none { it.nodeId == THEME_NODE && it.key in setOf("accent", "dark", "blur") }, "a device field wrote a daemon row: $puts")
        assertTrue(daemonRows.keys.none { it.first == THEME_NODE && it.second in setOf("accent", "dark", "blur") }, "daemon rows: ${daemonRows.keys}")

        // A second boot over the same prefs and a fresh daemon store: values come back, nothing is copied again.
        Palette.theme = ThemeSettings()
        val markdownKey = DeviceScope.prefKey("infantry.shell.chat", "markdown")
        assertNull(prefs[markdownKey], "no legacy markdown pref existed at the first boot, so nothing was copied")
        prefs[CHAT_MARKDOWN_PREF_KEY] = "table" // a second migration run would copy this
        prefs[BLUR_PREF_KEY] = "on" // and would flip blur back to true
        val second = factory()
        host(second) {
            assertEquals("1", prefs[DeviceScope.MIGRATED_MARKER])
            assertNull(prefs[markdownKey], "the migration did not run a second time")
            assertEquals("false", prefs[blurKey], "a second boot does not re-copy the legacy blur pref over the stored value")
            assertEquals(JsonPrimitive(false), second.config.device[THEME_NODE]?.get("dark"))
            assertEquals(JsonPrimitive(false), second.config.device[THEME_NODE]?.get("blur"))
            assertFalse(Palette.theme.dark, "dark survived the restart")
            assertEquals(ThemeAccent.TEAL, Palette.theme.accent, "accent survived the restart")
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.APPEARANCE.name).performClick()
            waitForIdle()
            onNodeWithTag(NODE_FIELD_CHECK_TAG_PREFIX + "$THEME_NODE/dark").performScrollTo().assertIsOff()
            onNodeWithTag(NODE_FIELD_CHECK_TAG_PREFIX + "$THEME_NODE/blur").performScrollTo().assertIsOff()
        }
        assertTrue(puts.none { it.nodeId == THEME_NODE && it.key in setOf("accent", "dark", "blur") })
    }

    private val daemonSchema = ConfigSchemaFrame(
        7, listOf(ConfigDeclaration("daemon.Acme_Hook", "Hook", listOf(ConfigFieldDeclaration("Acme_Hook.timeoutSec", "number", default = JsonPrimitive(5), scope = "daemon")))),
    )
    private val wire = Json { encodeDefaults = true }

    @Test fun aClientOnAnOlderDaemonContractKeepsTheHandWrittenDaemonSections() {
        val tabs = factory()
        host(tabs, contract = "1.141.0") { controller ->
            assertFalse(controller.state.value.connection.servesConfigSchema, "1.141.0 does not serve config_schema")
            assertNull(controller.state.value.daemonConfigSchema)
            assertNull(controller.state.value.daemonConfigValues)
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.GENERAL.name).performClick()
            waitForIdle()
            onNodeWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.LIFECYCLE.name).performScrollTo().assertExists()
        }
    }

    @Test fun aClientOnTheNewContractTakesTheDaemonConfigFramesAndTheDialogStillOpens() {
        val tabs = factory()
        val values = ConfigValuesFrame(7, listOf(NodeConfigEntry("daemon.Acme_Hook", "Acme_Hook.timeoutSec", JsonPrimitive(9), "user")))
        val frames = listOf(
            Envelope(Envelope.CONFIG_SCHEMA, wire.encodeToString(ConfigSchemaFrame.serializer(), daemonSchema)),
            Envelope(Envelope.CONFIG_VALUES, wire.encodeToString(ConfigValuesFrame.serializer(), values)),
        )
        host(tabs, frames = frames) { controller ->
            assertTrue(controller.state.value.connection.servesConfigSchema)
            waitUntil(timeoutMillis = 10_000) { controller.state.value.daemonConfigValues != null }
            assertEquals(daemonSchema, controller.state.value.daemonConfigSchema)
            assertEquals(JsonPrimitive(9), controller.state.value.daemonConfigValues!!.rows.single().value)
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.GENERAL.name).performClick()
            waitForIdle()
            onNodeWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.LIFECYCLE.name).performScrollTo().assertExists()
            assertNotNull(tabs.config.snapshot)
        }
    }
}
