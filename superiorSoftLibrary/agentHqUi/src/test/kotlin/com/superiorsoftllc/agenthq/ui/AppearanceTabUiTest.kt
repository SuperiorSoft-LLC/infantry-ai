package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.plugin.api.ConfigManifest
import com.superiorsoftllc.agenthq.plugin.api.NodeConfigEntry
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.ModuleStatus
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
import com.superiorsoftllc.agenthq.plugins.official.appearance.InfantryAppearance
import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.model.ConnectionInfo
import com.superiorsoftllc.agenthq.ui.model.ThemeAccent
import com.superiorsoftllc.agenthq.ui.model.ThemeSettings
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.net.DaemonClient
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.plugin.config.ConfigManifests
import com.superiorsoftllc.agenthq.ui.plugin.config.GENERATED_CARD_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.config.NODE_FIELD_CHECK_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.config.NODE_FIELD_OPTION_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.config.NodeConfigApi
import com.superiorsoftllc.agenthq.ui.plugin.config.NodeConfigLoad
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.shell.uiPrefsOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val THEME_NODE = "infantry.shell.theme"

/**
 * CONFIG-3 (docs/CONFIG-MANIFEST-DESIGN.md section 4): with Infantry_Appearance loaded, Configuration > Appearance builds its
 * Theme and Blur cards from the compiled manifest (the daemon's snapshot here), and a change made in a card reaches the
 * ThemeBlock, which re-themes the window. Without the plugin those two cards are simply absent.
 */
@OptIn(ExperimentalTestApi::class)
class AppearanceTabUiTest {
    private var savedOverride: MutableMap<String, String>? = null

    /** QA-CONFIG-3: Appearance is scope=device, so its values live in the host prefs: pin an empty in-memory store, never the machine's. */
    @BeforeTest fun pinPrefs() {
        savedOverride = uiPrefsOverride
        uiPrefsOverride = mutableMapOf()
    }

    @AfterTest fun restore() {
        uiPrefsOverride = savedOverride
        Palette.theme = ThemeSettings()
    }

    private val stored = LinkedHashMap<Pair<String, String>, NodeConfigEntry>()
    private var manifest: ConfigManifest? = null
    private val api = object : NodeConfigApi {
        override suspend fun seed(entries: List<NodeConfigEntry>): Result<List<NodeConfigEntry>> = Result.success(stored.values.toList())
        override suspend fun put(entry: NodeConfigEntry): Result<List<NodeConfigEntry>> {
            if (entry.value == null) stored.remove(entry.nodeId to entry.key) else stored[entry.nodeId to entry.key] = entry
            return Result.success(stored.values.toList())
        }
        override suspend fun manifest(host: String) = Result.success(manifest)
        override suspend fun putManifest(manifest: ConfigManifest): Result<ConfigManifest?> { this@AppearanceTabUiTest.manifest = manifest; return Result.success(manifest) }
    }

    private fun factory(withAppearance: Boolean): TabFactory {
        val all = CoreModules.all + com.superiorsoftllc.agenthq.plugins.official.header.InfantryHeader + com.superiorsoftllc.agenthq.plugins.official.InfantryAgents +
            com.superiorsoftllc.agenthq.plugins.official.chat.InfantryChat + (if (withAppearance) listOf(InfantryAppearance) else emptyList())
        return TabFactory(all, statuses = all.map { ModuleStatus(it.manifest.name, it.tier, ModuleState.LOADED, manifest = it.manifest) }, configApi = api)
    }

    /** A fake link that never dials (frontend/ui-test-flake-causes). */
    private class NoDialLink(scope: CoroutineScope) : DaemonClient(scope) {
        private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
        override val frames: SharedFlow<Envelope> get() = incoming
        override val info: StateFlow<ConnectionInfo> = MutableStateFlow(ConnectionInfo(state = ConnState.CONNECTED))
        override fun connect(url: String, token: String?) {}
        override fun disconnect() {}
        override suspend fun send(envelope: Envelope): Boolean = true
        fun push(e: Envelope) {
            val deadline = System.currentTimeMillis() + 10_000
            while (incoming.subscriptionCount.value == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            check(incoming.tryEmit(e)) { "frame buffer full" }
        }
    }

    /** Configuration open on Appearance, in the real window over a fake daemon link. */
    private fun withAppearance(tabs: TabFactory, body: ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = NoDialLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 860) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null, tabs = tabs) }
                link.push(Envelope(Envelope.READY, "contract=1.94.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                waitForIdle()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag(CONFIG_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() || run { onNodeWithContentDescription("Configuration").performClick(); false }
                }
                waitUntil(timeoutMillis = 10_000) { tabs.config.load == NodeConfigLoad.LOADED }
                onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.APPEARANCE.name).performClick()
                waitForIdle()
                body()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun theAppearanceCardsAreGeneratedFromTheSnapshotAndAChangeRethemesTheBlock() {
        val tabs = factory(withAppearance = true)
        withAppearance(tabs) {
            // The daemon holds the compiled manifest, and the dialog reads its snapshot.
            waitUntil(timeoutMillis = 10_000) { tabs.config.snapshot != null }
            assertEquals(manifest!!.hash, ConfigManifests.compile(tabs.configTree, "desktop", emptyList()).hash)
            // The cards carry the old sections' tags, and the hand-written switches are not drawn beside them.
            onNodeWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.THEME.name).assertExists()
            onNodeWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.BLUR.name).assertExists()
            onNodeWithTag(THEME_DARK_SWITCH_TAG).assertDoesNotExist()
            onNodeWithTag(BLUR_SWITCH_TAG).assertDoesNotExist()
            // CONFIG-6: the markdown section is Infantry_Chat's block card in the same tab, the portrait is Appearance's own.
            onNodeWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.MARKDOWN_ENGINE.name).assertExists()

            val dark = NODE_FIELD_CHECK_TAG_PREFIX + "$THEME_NODE/dark"
            onNodeWithTag(dark).performScrollTo().assertIsOn()
            onNodeWithTag(NODE_FIELD_OPTION_TAG_PREFIX + "$THEME_NODE/accent/TEAL").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { Palette.theme.accent == ThemeAccent.TEAL }
            assertEquals(true, Palette.theme.dark)
            onNodeWithTag(dark).performClick()
            waitUntil(timeoutMillis = 10_000) { !Palette.theme.dark }
            onNodeWithTag(dark).assertIsOff()
            // The fields are scope=device (QA-CONFIG-3): they persist on this device and never become a daemon row.
            assertEquals(emptyList(), stored.keys.filter { it.first == THEME_NODE })
        }
    }

    @Test fun withoutThePluginThereAreNoThemeOrBlurCards() {
        // CONFIG-5/6: the dialog is generated from the declarations, so Theme and Blur exist only while Infantry_Appearance is loaded;
        // the tab itself stays for the cards other plugins declare there (Infantry_Chat's markdown engine).
        withAppearance(factory(withAppearance = false)) {
            onNodeWithTag(THEME_DARK_SWITCH_TAG).assertDoesNotExist()
            onNodeWithTag(BLUR_SWITCH_TAG).assertDoesNotExist()
            onNodeWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.THEME.name).assertDoesNotExist()
            onNodeWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.BLUR.name).assertDoesNotExist()
            onNodeWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.MARKDOWN_ENGINE.name).assertExists()
            onAllNodesWithTag(GENERATED_CARD_TAG_PREFIX + THEME_NODE + "/Theme").assertCountEquals(0)
        }
    }
}
