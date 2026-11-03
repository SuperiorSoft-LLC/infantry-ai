package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigManifest
import com.superiorsoftllc.infantry.plugin.api.DaemonCapabilities
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.plugin.host.ModuleStatus
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.stories.JsonStoryStore
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.plugin.config.GENERATED_FIELD_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.config.NODE_FIELD_INPUT_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.config.NODE_FIELD_SAVE_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.config.NodeConfigApi
import com.superiorsoftllc.infantry.ui.shell.uiPrefsOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CONFIG-11: the daemon-scoped config round trip, end to end. A real in-process daemon (temp state dir, ephemeral port, a
 * temp plugins root holding an official module that declares a `scope:"daemon"` field) and the real [App] over the real
 * WebSocket: the Settings dialog shows the daemon's section, a value typed there is written through `config_set`, a second
 * client sees `config_changed`, and the value is served again after the daemon restarts over the same state dir.
 */
@OptIn(ExperimentalTestApi::class)
class ConfigDaemonScopeRealDaemonUiTest {
    private val waitMs = System.getenv("INFANTRY_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closers = mutableListOf<() -> Unit>()
    private val state: File = Files.createTempDirectory("infantry-ui-config11").toFile()
    private val pluginsBefore: String? = System.getProperty(PluginFolders.ROOT_PROPERTY)
    private var savedPrefs: MutableMap<String, String>? = null

    private val node = "daemon.Acme_Hook"
    private val key = "Acme_Hook.timeoutSec"

    init {
        val manifest = ModuleManifest(
            name = "Acme_Hook", version = "1.0.0", author = "Acme", license = "NOASSERTION",
            daemon = DaemonCapabilities(config = listOf(ConfigDeclaration(node, "Hook", listOf(
                ConfigFieldDeclaration(key, "number", label = "Timeout", default = JsonPrimitive(5), min = 0.0, max = 60.0, scope = "daemon"),
            ), tab = "General"))),
        )
        File(state, "plugins/official/Acme_Hook").apply { mkdirs() }.resolve("module.json").writeText(json.encodeToString(ModuleManifest.serializer(), manifest))
        System.setProperty(PluginFolders.ROOT_PROPERTY, File(state, "plugins").absolutePath)
        savedPrefs = uiPrefsOverride
        uiPrefsOverride = mutableMapOf()
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        closers.reversed().forEach { runCatching { it() } }
        if (pluginsBefore == null) System.clearProperty(PluginFolders.ROOT_PROPERTY) else System.setProperty(PluginFolders.ROOT_PROPERTY, pluginsBefore)
        uiPrefsOverride = savedPrefs
        state.deleteRecursively()
    }

    /** One daemon over [state]; the returned stop closes it (the files stay, so the next boot is a restart). */
    private fun boot(): Pair<Int, () -> Unit> {
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = JsonSessionStore(File(state, "sessions.json")),
            mailStore = JsonMailStore(File(state, "mailbox.json")),
            store = SqlInfantryStore.open(File(state, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(state, "stories.json")),
            modelCatalogs = com.superiorsoftllc.infantry.provider.HarnessModelCatalogs.EMPTY,
            workingDir = state, pluginsRoot = File(state, "plugins"),
            // POLICY-1 gap 9: never the defaults, which read the process's state dir; this test's own temp one only.
            ownerToken = com.superiorsoftllc.infantry.daemon.OwnerCredential.ensure(state),
            policies = com.superiorsoftllc.infantry.policy.PolicyRuntime(emptyList(), { null }, null, state),
        )
        daemon.server.start(wait = false)
        val stop = { daemon.server.stop(0, 200); daemon.close() }
        closers += { runCatching(stop) }
        val port = runBlocking {
            repeat(250) {
                daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                delay(20)
            }
            error("no port")
        }
        return port to stop
    }

    /** The node_config rows are not under test: a store-less fake keeps the manifest compile local to the window. */
    private val api = object : NodeConfigApi {
        private var held: ConfigManifest? = null
        override suspend fun seed(entries: List<NodeConfigEntry>) = Result.success(emptyList<NodeConfigEntry>())
        override suspend fun put(entry: NodeConfigEntry) = Result.success(emptyList<NodeConfigEntry>())
        override suspend fun manifest(host: String) = Result.success(held)
        override suspend fun putManifest(manifest: ConfigManifest): Result<ConfigManifest?> { held = manifest; return Result.success(manifest) }
    }

    private fun tabs(): TabFactory {
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

    private fun valueOf(c: Controller) = c.state.value.daemonConfigValues?.rows?.firstOrNull { it.nodeId == node && it.key == key }?.value

    @Test fun `a daemon field changed in the dialog reaches a second client and survives a daemon restart`() {
        val (port, stop) = boot()
        val url = "ws://127.0.0.1:$port/ws"
        val second = Controller(scope).also { it.connect(url, null) }
        val c = Controller(scope)
        val tabs = tabs()
        runDesktopComposeUiTest(1400, 900) {
            setContent { App(c, url, null, tabs = tabs) }
            waitUntil(timeoutMillis = waitMs) { c.state.value.connection.state == ConnState.CONNECTED && c.state.value.daemonConfigSchema != null }
            waitUntil(timeoutMillis = waitMs) { second.state.value.connection.state == ConnState.CONNECTED && second.state.value.daemonConfigSchema != null }
            onNodeWithContentDescriptionWhenReady()
            waitUntil(timeoutMillis = waitMs) { tabs.config.snapshot != null }
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.GENERAL.name).performClick()
            val field = "$node/$key"
            waitUntil(timeoutMillis = waitMs) { onAllNodesWithTag(GENERATED_FIELD_TAG_PREFIX + field).fetchSemanticsNodes().isNotEmpty() }

            // Typed in the generated dialog, saved with the field's own Save button: a config_set with the rev the window holds.
            onNodeWithTag(NODE_FIELD_INPUT_TAG_PREFIX + field).performScrollTo().performTextReplacement("9")
            onNodeWithTag(NODE_FIELD_SAVE_TAG_PREFIX + field).performScrollTo().performClick()
            waitUntil(timeoutMillis = waitMs) { valueOf(c)?.let { it is JsonPrimitive && it.content == "9" } == true }
            // The second client is told (config_changed / config_values) without asking.
            waitUntil(timeoutMillis = waitMs) { valueOf(second)?.let { it is JsonPrimitive && it.content == "9" } == true }
            // The dialog follows the controller state on its next recomposition, so wait for it rather than race it.
            waitUntil(timeoutMillis = waitMs) { tabs.resolvedConfig.json(node, key) == JsonPrimitive(9) }
        }

        // Restart over the same state dir: a fresh client is served the stored value again.
        stop()
        val (port2, _) = boot()
        val third = Controller(scope).also { it.connect("ws://127.0.0.1:$port2/ws", null) }
        val end = System.currentTimeMillis() + waitMs
        while (valueOf(third) == null && System.currentTimeMillis() < end) Thread.sleep(25)
        assertEquals(JsonPrimitive(9).content, (valueOf(third) as? JsonPrimitive)?.content, "the value survived the daemon restart")
    }

    /** Opens the Configuration dialog once the footer cog is composed (it appears after the shell's first frames). */
    private fun androidx.compose.ui.test.ComposeUiTest.onNodeWithContentDescriptionWhenReady() {
        waitUntil(timeoutMillis = waitMs) {
            onAllNodesWithTag(CONFIG_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() ||
                run {
                    val cog = onAllNodesWithContentDescription("Configuration")
                    if (cog.fetchSemanticsNodes().isNotEmpty()) cog[0].performClick()
                    false
                }
        }
    }
}
