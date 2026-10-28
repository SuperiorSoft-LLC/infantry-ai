package com.superiorsoftllc.agenthq.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.NodeConfigEntry
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.ModuleStatus
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
import com.superiorsoftllc.agenthq.plugins.core.InfantryGit
import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.model.ConnectionInfo
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.net.DaemonClient
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.REGISTRY_CHILD_LOCKED_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.REGISTRY_CHILD_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.REGISTRY_HOST_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.REGISTRY_LOCKED_TAG
import com.superiorsoftllc.agenthq.ui.plugin.REGISTRY_MODULE_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.REGISTRY_SOURCE_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.REGISTRY_STATE_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.REGISTRY_TIER_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.REGISTRY_DOWN_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.REGISTRY_REFUSAL_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.REGISTRY_TOGGLE_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.REGISTRY_UP_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.config.NodeConfigApi
import com.superiorsoftllc.agenthq.ui.plugin.config.NodeConfigLoad
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P1 (f60f4d97): Configuration > Plugins > Registered, in the real window: a fake community plugin registered in the
 * centre tab strip shows up in the strip's list with its source and tier; a locked node shows as not editable. P1 step 2:
 * a tab switched off leaves the strip, a move reorders it.
 */
@OptIn(ExperimentalTestApi::class)
class RegistrySectionUiTest {
    private val acme = AgentHqModule(
        ModuleManifest("Acme_Tabs", "2.1.0", author = "Acme", license = "NOASSERTION", tabs = listOf(TabDeclaration("acme.tabs", TabContainers.CENTER))),
        tabs = listOf(TabProvider("acme.tabs", TabContainers.CENTER, 500, { "Acme" }, InfantryGit.tabs.single().icon, "center-tab-ACME_TABS") { Text("acme") }),
        tier = Tier.COMMUNITY,
    )

    /** Loaded at run time (statuses given), as on the desktop: only then does Configuration have a Plugins tab. */
    /** P1 step 2: the daemon's node_config, kept in memory. */
    private val stored = LinkedHashMap<Pair<String, String>, NodeConfigEntry>()
    private val api = object : NodeConfigApi {
        override suspend fun seed(entries: List<NodeConfigEntry>): Result<List<NodeConfigEntry>> {
            for (e in entries) stored.putIfAbsent(e.nodeId to e.key, e)
            return Result.success(stored.values.toList())
        }
        override suspend fun put(entry: NodeConfigEntry): Result<List<NodeConfigEntry>> {
            if (entry.value == null) stored.remove(entry.nodeId to entry.key) else stored[entry.nodeId to entry.key] = entry
            return Result.success(stored.values.toList())
        }
    }
    private val tabs = (CoreModules.all + acme).let { all ->
        TabFactory(all, statuses = all.map { ModuleStatus(it.manifest.name, it.tier, ModuleState.LOADED, manifest = it.manifest) }, configApi = api)
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

    /** Configuration open on Plugins, in the real window over a fake daemon link. */
    private fun withPlugins(body: ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = NoDialLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 860) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null, tabs = tabs) }
                link.push(Envelope(Envelope.READY, "contract=1.94.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                waitForIdle()
                fun shown(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
                waitUntil(timeoutMillis = 10_000) {
                    shown(CONFIG_TAB_TAG_PREFIX + ConfigTab.PLUGINS.name) || run { onNodeWithContentDescription("Configuration").performClick(); false }
                }
                onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.PLUGINS.name).performClick()
                waitForIdle()
                body()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun aCommunityTabInTheCentreStripIsListedWithItsModuleSourceAndTier() = withPlugins {
        // The plugin really is in the strip.
        onAllNodesWithTag("center-tab-ACME_TABS")[0].assertExists()
        onNodeWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.REGISTRY.name).performScrollTo()
        onNodeWithTag(REGISTRY_HOST_TAG_PREFIX + "infantry.shell.center.chat.tabs").assertIsSelected()
        onNodeWithTag(REGISTRY_CHILD_TAG_PREFIX + "acme.tabs").performScrollTo()
        onNodeWithTag(REGISTRY_MODULE_TAG_PREFIX + "acme.tabs", useUnmergedTree = true).assertTextEquals("Acme_Tabs 2.1.0")
        onNodeWithTag(REGISTRY_TIER_TAG_PREFIX + "acme.tabs", useUnmergedTree = true).assertTextEquals("Community")
        onNodeWithTag(REGISTRY_SOURCE_TAG_PREFIX + "acme.tabs", useUnmergedTree = true).assertTextEquals("Tab")
        onNodeWithTag(REGISTRY_STATE_TAG_PREFIX + "acme.tabs", useUnmergedTree = true).assertTextEquals("Enabled")
        // A core tab next to it draws a core tab tree.
        onNodeWithTag(REGISTRY_TIER_TAG_PREFIX + "infantry.git", useUnmergedTree = true).assertTextEquals("Core")
        onNodeWithTag(REGISTRY_SOURCE_TAG_PREFIX + "infantry.git", useUnmergedTree = true).assertTextEquals("CoreShell.tabs")
        // The strip is not locked.
        onNodeWithTag(REGISTRY_LOCKED_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun switchingATabOffTakesItOutOfTheStripAndBackOnRestoresIt() = withPlugins {
        val git = InfantryGit.tabs.single()
        waitUntil(timeoutMillis = 10_000) { tabs.config.load == NodeConfigLoad.LOADED }
        onAllNodesWithTag(git.testTag)[0].assertExists()
        val toggle = REGISTRY_TOGGLE_TAG_PREFIX + git.id
        onNodeWithTag(toggle).performScrollTo()
        onNodeWithTag(toggle).assertIsOn()
        onNodeWithTag(toggle).performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(git.testTag).fetchSemanticsNodes().isEmpty() }
        onNodeWithTag(REGISTRY_STATE_TAG_PREFIX + git.id, useUnmergedTree = true).assertTextEquals("Disabled")
        assertEquals(true, stored.keys.any { it.first == "infantry.shell.center.chat.tabs" && it.second == "children.disabled" })
        onNodeWithTag(toggle).assertIsOff()
        onNodeWithTag(toggle).performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(git.testTag).fetchSemanticsNodes().isNotEmpty() }
        // The home tab and a community tab with no grant say why they cannot be switched off.
        val home = tabs.home(TabContainers.CENTER)!!
        onNodeWithTag(REGISTRY_REFUSAL_TAG_PREFIX + home, useUnmergedTree = true).assertTextEquals("Not editable: Home tab")
        onNodeWithTag(REGISTRY_REFUSAL_TAG_PREFIX + "acme.tabs", useUnmergedTree = true).assertTextEquals("Not editable: Not granted")
    }

    @Test fun movingATabUpReordersTheStrip() = withPlugins {
        waitUntil(timeoutMillis = 10_000) { tabs.config.load == NodeConfigLoad.LOADED }
        val before = tabs.tabs(TabContainers.CENTER).map { it.id }
        val last = before.last()
        onNodeWithTag(REGISTRY_UP_TAG_PREFIX + last).performScrollTo()
        onNodeWithTag(REGISTRY_DOWN_TAG_PREFIX + last).assertIsNotEnabled()
        onNodeWithTag(REGISTRY_UP_TAG_PREFIX + last).performClick()
        waitUntil(timeoutMillis = 10_000) { tabs.tabs(TabContainers.CENTER).map { it.id } != before }
        assertEquals(before.toMutableList().apply { add(lastIndex - 1, removeAt(lastIndex)) }, tabs.tabs(TabContainers.CENTER).map { it.id })
    }

    @Test fun aLockedNodeShowsLockedAndOffersNothingToChange() = withPlugins {
        val config = REGISTRY_HOST_TAG_PREFIX + "infantry.shell.config"
        onNodeWithTag(config).performScrollTo()
        onNodeWithTag(config).performClick()
        waitForIdle()
        onNodeWithTag(config).assertIsSelected()
        onNodeWithTag(REGISTRY_LOCKED_TAG, useUnmergedTree = true).assertExists()
        // Its trust sections are locked (this view among them), its open settings group is not.
        onNodeWithTag(REGISTRY_CHILD_LOCKED_TAG_PREFIX + "infantry.shell.config.registry", useUnmergedTree = true).assertExists()
        onNodeWithTag(REGISTRY_CHILD_LOCKED_TAG_PREFIX + "infantry.shell.config.secrets", useUnmergedTree = true).assertExists()
        onNodeWithTag(REGISTRY_CHILD_LOCKED_TAG_PREFIX + "infantry.shell.config.settings", useUnmergedTree = true).assertDoesNotExist()
        // Read-only: no row has anything to click.
        val clickable = onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(REGISTRY_CHILD_TAG_PREFIX + "infantry.shell.config.registry"))).fetchSemanticsNodes()
        assertEquals(0, clickable.size)
    }
}
