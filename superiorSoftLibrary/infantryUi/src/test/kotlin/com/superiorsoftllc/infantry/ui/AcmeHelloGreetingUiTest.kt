package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.acme.hello.AcmeHelloConfig
import com.acme.hello.GREETING_TEXT_TAG
import com.acme.hello.HelloPlugin
import com.superiorsoftllc.infantry.plugin.api.ConfigManifest
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.plugin.host.ModuleStatus
import com.superiorsoftllc.infantry.plugin.host.PluginJson
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.plugin.NoPluginServices
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.plugin.config.GENERATED_CARD_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.config.NODE_FIELD_CHECK_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.config.NODE_FIELD_OPTION_TAG_PREFIX
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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

private const val NODE = AcmeHelloConfig.GREETING_NODE

/**
 * EXAMPLE-1: the Acme_Hello community module, registered from its real module.json, draws its greeting block in the
 * left panel's left.board slot (under the Board button); its Settings > Plugins card changes the block live.
 */
@OptIn(ExperimentalTestApi::class)
class AcmeHelloGreetingUiTest {
    private val stored = LinkedHashMap<Pair<String, String>, NodeConfigEntry>()
    private var manifest: ConfigManifest? = null
    private val api = object : NodeConfigApi {
        override suspend fun seed(entries: List<NodeConfigEntry>): Result<List<NodeConfigEntry>> = Result.success(stored.values.toList())
        override suspend fun put(entry: NodeConfigEntry): Result<List<NodeConfigEntry>> {
            if (entry.value == null) stored.remove(entry.nodeId to entry.key) else stored[entry.nodeId to entry.key] = entry
            return Result.success(stored.values.toList())
        }
        override suspend fun manifest(host: String) = Result.success(manifest)
        override suspend fun putManifest(manifest: ConfigManifest): Result<ConfigManifest?> { this@AcmeHelloGreetingUiTest.manifest = manifest; return Result.success(manifest) }
    }

    private fun acmeModuleJson(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "plugins/community/Acme_Hello/module.json").isFile) dir = dir.parentFile
        return File(checkNotNull(dir) { "plugins/community/Acme_Hello not found above ${File("").absolutePath}" }, "plugins/community/Acme_Hello/module.json")
    }

    private fun factory(): TabFactory {
        val acme = registerModule(PluginJson.decodeFromString(ModuleManifest.serializer(), acmeModuleJson().readText()), Tier.COMMUNITY, NoPluginServices, HelloPlugin())
        val all = CoreModules.all + com.superiorsoftllc.infantry.plugins.official.header.InfantryHeader + com.superiorsoftllc.infantry.plugins.official.InfantryAgents +
            com.superiorsoftllc.infantry.plugins.official.chat.InfantryChat + com.superiorsoftllc.infantry.plugins.official.chatfeed.InfantryChatFeed + acme
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

    private fun inWindow(tabs: TabFactory, body: ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = NoDialLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 860) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null, tabs = tabs) }
                link.push(Envelope(Envelope.READY, "contract=1.94.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                waitUntil(timeoutMillis = 10_000) { tabs.config.load == NodeConfigLoad.LOADED }
                waitForIdle()
                body()
            }
        } finally {
            scope.cancel()
        }
    }

    private fun ComposeUiTest.waitForText(expected: String) = waitUntil(timeoutMillis = 10_000) {
        runCatching { onNodeWithTag(GREETING_TEXT_TAG, useUnmergedTree = true).assertTextEquals(expected) }.isSuccess
    }

    private fun ComposeUiTest.waitForClasses(expected: String) = waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithTag(GREETING_TEXT_TAG, useUnmergedTree = true).fetchSemanticsNodes()
            .any { SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected).matches(it) }
    }

    @Test fun theSettingsCardDrivesTheBlockUnderTheBoardButtonLive() {
        val tabs = factory()
        inWindow(tabs) {
            // The block is in the agents section (its left.board slot), with the default greeting and no classes.
            onNodeWithTag("acme-hello-greeting-block").assertExists()
            waitForText("Hello World")
            waitForClasses("")

            // Configuration > Plugins: the card generated from Acme_Hello's module.json.
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithTag(CONFIG_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() || run { onNodeWithContentDescription("Configuration").performClick(); false }
            }
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.PLUGINS.name).performClick()
            waitForIdle()
            onNodeWithTag("$GENERATED_CARD_TAG_PREFIX$NODE/Hello greeting").performScrollTo()

            // Radio: Hello World -> Hello Nation; the block follows with no restart.
            onNodeWithTag("$NODE_FIELD_OPTION_TAG_PREFIX$NODE/greeting/Hello Nation").performScrollTo().performClick()
            waitForText("Hello Nation")

            // Checkbox: Shout upper-cases it.
            onNodeWithTag("$NODE_FIELD_CHECK_TAG_PREFIX$NODE/shout").performScrollTo().performClick()
            waitForText("HELLO NATION")

            // Classes: big and bold reach the block (the tokens it applies are its state description).
            onNodeWithTag("$NODE_FIELD_OPTION_TAG_PREFIX$NODE/classes/text/big").performScrollTo().performClick()
            waitForClasses("big")
            onNodeWithTag("$NODE_FIELD_OPTION_TAG_PREFIX$NODE/classes/text/bold").performScrollTo().performClick()
            waitForClasses("big bold")
            assertEquals("\"Hello Nation\"", stored[NODE to "greeting"]?.value.toString())
        }
    }
}
