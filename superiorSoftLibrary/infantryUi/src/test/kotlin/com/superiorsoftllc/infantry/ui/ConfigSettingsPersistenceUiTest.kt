package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import com.superiorsoftllc.infantry.plugin.api.ConfigChangedFrame
import com.superiorsoftllc.infantry.plugin.api.ConfigSetFrame
import com.superiorsoftllc.infantry.ui.plugin.config.GENERATED_CARD_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.config.GENERATED_DAEMON_ERROR_TAG
import com.superiorsoftllc.infantry.ui.plugin.config.GENERATED_FIELD_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.config.GENERATED_READONLY_TAG_PREFIX
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigManifest
import com.superiorsoftllc.infantry.plugin.api.ConfigSchemaFrame
import com.superiorsoftllc.infantry.plugin.api.ConfigValuesFrame
import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.plugin.host.ModuleStatus
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.model.CHAT_MARKDOWN_PREF_KEY
import com.superiorsoftllc.infantry.ui.model.ThemeAccent
import com.superiorsoftllc.infantry.ui.model.ThemeSettings
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.plugin.config.DeviceScope
import com.superiorsoftllc.infantry.ui.plugin.config.NODE_FIELD_CHECK_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.config.NODE_FIELD_OPTION_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.config.NodeConfigApi
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.shell.uiPrefsOverride
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
            com.superiorsoftllc.infantry.plugins.official.header.InfantryHeader,
            com.superiorsoftllc.infantry.plugins.official.InfantryAgents,
            com.superiorsoftllc.infantry.plugins.official.chat.InfantryChat, com.superiorsoftllc.infantry.plugins.official.chatfeed.InfantryChatFeed,
            com.superiorsoftllc.infantry.plugins.official.approvals.InfantryApprovals,
            com.superiorsoftllc.infantry.plugins.official.appearance.InfantryAppearance,
            com.superiorsoftllc.infantry.plugins.official.assistant.InfantryAssistant,
            com.superiorsoftllc.infantry.plugins.official.alerts.InfantryAlerts,
            com.superiorsoftllc.infantry.plugins.official.processes.InfantryProcesses,
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
        7, listOf(ConfigDeclaration("daemon.Acme_Hook", "Hook", listOf(ConfigFieldDeclaration("Acme_Hook.timeoutSec", "number", label = "Timeout", default = JsonPrimitive(5), min = 1.0, max = 60.0, scope = "daemon")), tab = "General")),
    )
    private val wire = Json { encodeDefaults = true }
    private val cardTag = GENERATED_CARD_TAG_PREFIX + "daemon.Acme_Hook/Hook"
    private val fieldTag = GENERATED_FIELD_TAG_PREFIX + "daemon.Acme_Hook/Acme_Hook.timeoutSec"

    private fun daemonFrames(rev: Long = 7, value: Int = 9, schema: ConfigSchemaFrame = daemonSchema) = listOf(
        Envelope(Envelope.CONFIG_SCHEMA, wire.encodeToString(ConfigSchemaFrame.serializer(), schema)),
        Envelope(Envelope.CONFIG_VALUES, wire.encodeToString(ConfigValuesFrame.serializer(), ConfigValuesFrame(rev, listOf(NodeConfigEntry("daemon.Acme_Hook", "Acme_Hook.timeoutSec", JsonPrimitive(value), "user"))))),
    )

    @Test fun aClientOnAnOlderDaemonContractKeepsTheHandWrittenDaemonSectionsAndIgnoresConfigFrames() {
        val tabs = factory()
        // The same frames a 1.142.0 daemon would send: below the contract they must change nothing in the dialog.
        host(tabs, contract = "1.141.0", frames = daemonFrames()) { controller ->
            assertFalse(controller.state.value.connection.servesConfigSchema, "1.141.0 does not serve config_schema")
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.GENERAL.name).performClick()
            waitForIdle()
            onNodeWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.LIFECYCLE.name).performScrollTo().assertExists()
            onNodeWithTag(cardTag).assertDoesNotExist()
            assertTrue(tabs.config.daemonDecls.isEmpty(), "the gate keeps the daemon's sections out of the dialog")
        }
    }

    @Test fun aClientOnTheNewContractAsksForConfigAndTheDialogShowsTheDaemonSectionFromTheFrames() {
        val tabs = factory()
        withConfigDialogLink(tabs, frames = daemonFrames()) { controller, link ->
            assertTrue(controller.state.value.connection.servesConfigSchema)
            assertTrue(link.sent.any { it.type == Envelope.CONFIG_GET }, "config_get is requested on connect: ${link.sent.map { it.type }}")
            waitUntil(timeoutMillis = 10_000) { controller.state.value.daemonConfigValues != null }
            assertEquals(daemonSchema, controller.state.value.daemonConfigSchema)
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.GENERAL.name).performClick()
            waitForIdle()
            onNodeWithTag(cardTag).performScrollTo().assertExists()
            onNodeWithTag(fieldTag).performScrollTo().assertExists()
            onNodeWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.LIFECYCLE.name).performScrollTo().assertExists()
            assertEquals(JsonPrimitive(9), tabs.resolvedConfig.json("daemon.Acme_Hook", "Acme_Hook.timeoutSec"))
            assertNotNull(tabs.config.snapshot)
        }
    }

    @Test fun editingADaemonFieldSendsConfigSetWithTheRevShowsTheRefusalAndFollowsConfigChanged() {
        val tabs = factory()
        withConfigDialogLink(tabs, frames = daemonFrames()) { controller, link ->
            waitUntil(timeoutMillis = 10_000) { controller.state.value.daemonConfigValues != null }
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.GENERAL.name).performClick()
            waitForIdle()
            runBlocking { assertTrue(tabs.config.set("daemon.Acme_Hook", "Acme_Hook.timeoutSec", JsonPrimitive(12))) }
            val sent = wire.decodeFromString(ConfigSetFrame.serializer(), link.sent.last { it.type == Envelope.CONFIG_SET }.data)
            assertEquals(ConfigSetFrame("daemon.Acme_Hook", "Acme_Hook.timeoutSec", JsonPrimitive(12), 7), sent)
            // The daemon's refusal text shows inline on the card, and the old value stays.
            link.push(Envelope(Envelope.ERROR, "config_set refused: stale config rev 7 (now 8)"))
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(GENERATED_DAEMON_ERROR_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(GENERATED_DAEMON_ERROR_TAG).performScrollTo().assertTextContains("stale config rev 7 (now 8)")
            assertEquals(JsonPrimitive(9), tabs.resolvedConfig.json("daemon.Acme_Hook", "Acme_Hook.timeoutSec"))
            // Another client's write arrives as config_changed: the field follows live and the refusal clears.
            link.push(Envelope(Envelope.CONFIG_CHANGED, wire.encodeToString(ConfigChangedFrame.serializer(), ConfigChangedFrame("daemon.Acme_Hook", "Acme_Hook.timeoutSec", JsonPrimitive(30), "other", 8))))
            waitUntil(timeoutMillis = 10_000) { tabs.resolvedConfig.json("daemon.Acme_Hook", "Acme_Hook.timeoutSec") == JsonPrimitive(30) }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(GENERATED_DAEMON_ERROR_TAG).fetchSemanticsNodes().isEmpty() }
            // The next write carries the rev the push moved to.
            runBlocking { tabs.config.set("daemon.Acme_Hook", "Acme_Hook.timeoutSec", null) }
            val reset = wire.decodeFromString(ConfigSetFrame.serializer(), link.sent.last { it.type == Envelope.CONFIG_SET }.data)
            assertEquals(ConfigSetFrame("daemon.Acme_Hook", "Acme_Hook.timeoutSec", null, 8), reset)
        }
    }

    @Test fun aDaemonSectionFromAnUnsignedPluginIsReadOnlyAndNeverShowsASecretField() {
        val tabs = factory()
        val schema = ConfigSchemaFrame(7, listOf(ConfigDeclaration("daemon.Acme_Hook", "Hook", listOf(
            ConfigFieldDeclaration("Acme_Hook.timeoutSec", "number", label = "Timeout", scope = "daemon"),
            ConfigFieldDeclaration("Acme_Hook.token", "secret", label = "Token", scope = "daemon"),
        ), tab = "General", readOnly = true)))
        withConfigDialogLink(tabs, frames = daemonFrames(schema = schema)) { controller, _ ->
            waitUntil(timeoutMillis = 10_000) { controller.state.value.daemonConfigValues != null }
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.GENERAL.name).performClick()
            waitForIdle()
            onNodeWithTag(GENERATED_READONLY_TAG_PREFIX + "daemon.Acme_Hook").performScrollTo().assertExists()
            onNodeWithTag(GENERATED_FIELD_TAG_PREFIX + "daemon.Acme_Hook/Acme_Hook.token").assertDoesNotExist()
        }
    }
}
