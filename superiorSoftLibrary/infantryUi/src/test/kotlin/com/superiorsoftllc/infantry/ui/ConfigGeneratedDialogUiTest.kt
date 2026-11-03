// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigManifest
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.plugin.host.ModuleStatus
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.plugin.NoPluginServices
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.config.FieldType
import com.superiorsoftllc.infantry.ui.plugin.config.NodeConfigApi
import com.superiorsoftllc.infantry.ui.plugin.config.NodeConfigLoad
import com.superiorsoftllc.infantry.ui.plugin.registerModule
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

private const val RATING_NODE = "acme.rating"
private const val STARS_GOOD_TAG = "acme-stars-good"
private const val STARS_BAD_TAG = "acme-stars-bad"

/**
 * CONFIG-5 (docs/CONFIG-MANIFEST-DESIGN.md section 4): with the daemon's compiled manifest the whole Settings dialog is
 * generated: a plugin's own tab after core's, its custom field type drawn and validated, its keywords searchable, the
 * trust sections declared as locked block cards; without a snapshot the dialog is the hand-written one.
 */
@OptIn(ExperimentalTestApi::class)
class ConfigGeneratedDialogUiTest {
    private val stored = LinkedHashMap<Pair<String, String>, NodeConfigEntry>()
    private var manifest: ConfigManifest? = null
    private var daemonHasManifest = true
    private val api = object : NodeConfigApi {
        override suspend fun seed(entries: List<NodeConfigEntry>): Result<List<NodeConfigEntry>> = Result.success(stored.values.toList())
        override suspend fun put(entry: NodeConfigEntry): Result<List<NodeConfigEntry>> {
            if (entry.value == null) stored.remove(entry.nodeId to entry.key) else stored[entry.nodeId to entry.key] = entry
            return Result.success(stored.values.toList())
        }
        override suspend fun manifest(host: String) = if (daemonHasManifest) Result.success(manifest) else Result.failure(IllegalStateException("older daemon"))
        override suspend fun putManifest(manifest: ConfigManifest): Result<ConfigManifest?> =
            if (daemonHasManifest) { this@ConfigGeneratedDialogUiTest.manifest = manifest; Result.success(manifest) } else Result.failure(IllegalStateException("older daemon"))
    }

    /** An editor with two buttons: one writes 4 (valid), one writes 9 (refused by the type). */
    private class Stars : FieldType {
        override fun validate(field: ConfigFieldDeclaration, value: JsonElement?): String? =
            if (((value as? JsonPrimitive)?.content?.toIntOrNull() ?: 0) in 1..5) null else "1 to 5 stars"

        @Composable
        override fun Editor(field: ConfigFieldDeclaration, value: JsonElement?, enabled: Boolean, onChange: (JsonElement?) -> Unit) {
            Text("stars=${(value as? JsonPrimitive)?.content}", modifier = Modifier.testTag("acme-stars"))
            Button(onClick = { onChange(JsonPrimitive(4)) }, enabled = enabled, modifier = Modifier.testTag(STARS_GOOD_TAG)) { Text("4") }
            Button(onClick = { onChange(JsonPrimitive(9)) }, enabled = enabled, modifier = Modifier.testTag(STARS_BAD_TAG)) { Text("9") }
        }
    }

    private val acme = registerModule(
        ModuleManifest(
            "Acme_Rating", "1.0.0", author = "Acme", license = "NOASSERTION", fieldTypes = listOf("acme.stars"),
            config = listOf(
                ConfigDeclaration(
                    RATING_NODE, "Rating", tab = "Acme", title = "Rating", applies = "Applies to: nothing real.", keywords = listOf("zebra"),
                    fields = listOf(ConfigFieldDeclaration("stars", "acme.stars", label = "Stars", description = "Pick a grade", default = JsonPrimitive(3))),
                ),
            ),
        ),
        Tier.CORE, NoPluginServices,
        object : UiPlugin { override fun register(registrar: UiRegistrar) = registrar.fieldType("acme.stars", Stars()) },
    )

    private fun factory(): TabFactory {
        val all = CoreModules.all + com.superiorsoftllc.infantry.plugins.official.header.InfantryHeader + com.superiorsoftllc.infantry.plugins.official.InfantryAgents +
            com.superiorsoftllc.infantry.plugins.official.chat.InfantryChat + com.superiorsoftllc.infantry.plugins.official.chatfeed.InfantryChatFeed + acme
        return TabFactory(all, statuses = all.map { ModuleStatus(it.manifest.name, it.tier, ModuleState.LOADED, manifest = it.manifest) }, configApi = api)
    }

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

    /** Configuration open in the real window over a fake daemon link. */
    private fun withDialog(tabs: TabFactory, body: ComposeUiTest.() -> Unit) {
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
                body()
            }
        } finally {
            scope.cancel()
        }
    }

    private fun ComposeUiTest.tabTags(): List<String> =
        (ConfigTab.entries.map { it.name } + "Acme").filter { onAllNodesWithTag(CONFIG_TAB_TAG_PREFIX + it).fetchSemanticsNodes().isNotEmpty() }

    @Test fun thePluginsTabComesAfterCoresAndItsCustomFieldTypeIsDrawnAndValidated() {
        val tabs = factory()
        withDialog(tabs) {
            waitUntil(timeoutMillis = 10_000) { tabs.config.snapshot != null }
            waitForIdle()
            val shown = tabTags()
            assertEquals("Acme", shown.last())
            assertEquals(shown.dropLast(1), ConfigTab.entries.map { it.name }.filter { it in shown })

            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + "Acme").performClick()
            waitForIdle()
            onNodeWithTag("acme-stars").performScrollTo().assertTextEquals("stars=3")
            onNodeWithTag(STARS_BAD_TAG).performClick()
            waitForIdle()
            // The type refuses 9: nothing is written, and the reason is shown.
            assertEquals(null, stored[RATING_NODE to "stars"]?.takeIf { it.setBy == "user" })
            onNodeWithTag("acme-stars").assertTextEquals("stars=3")
            onNodeWithTag(STARS_GOOD_TAG).performClick()
            waitUntil(timeoutMillis = 10_000) { stored[RATING_NODE to "stars"]?.value == JsonPrimitive(4) }
            onNodeWithTag("acme-stars").assertTextEquals("stars=4")
        }
    }

    @Test fun searchFindsAPluginCardByItsKeywordAndOpensItsTab() {
        val tabs = factory()
        withDialog(tabs) {
            waitUntil(timeoutMillis = 10_000) { tabs.config.snapshot != null }
            waitForIdle()
            onNodeWithTag(CONFIG_SEARCH_TAG).performTextInput("zebra")
            waitForIdle()
            onNodeWithTag(CONFIG_RESULT_TAG_PREFIX + "$RATING_NODE/Rating").performClick()
            waitForIdle()
            onNodeWithTag("acme-stars").assertExists()
        }
    }

    @Test fun theTrustSectionsAreBlockCardsAndNothingEditsThem() {
        val tabs = factory()
        withDialog(tabs) {
            waitUntil(timeoutMillis = 10_000) { tabs.config.snapshot != null }
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.ABOUT.name).performClick()
            waitForIdle()
            // The hand-written section is the card: same tag as before, drawn once.
            onAllNodesWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.ABOUT.name).assertCountEquals(1)
            assertEquals(true, tabs.configTree.declarations.filter { d -> d.declaration.block != null && ConfigEntry.entries.firstOrNull { configSectionNode(it) == d.declaration.block } !in com.superiorsoftllc.infantry.ui.plugin.config.ConfigPlan.MOVED }.all { it.declaration.locked })
        }
    }

    @Test fun withoutASnapshotThePluginsTabIsNotGeneratedAndTheDialogIsTheHandWrittenOne() {
        daemonHasManifest = false
        val tabs = factory()
        withDialog(tabs) {
            waitForIdle()
            assertEquals(null, tabs.config.snapshot)
            onAllNodesWithTag(CONFIG_TAB_TAG_PREFIX + "Acme").assertCountEquals(0)
        }
    }
}
