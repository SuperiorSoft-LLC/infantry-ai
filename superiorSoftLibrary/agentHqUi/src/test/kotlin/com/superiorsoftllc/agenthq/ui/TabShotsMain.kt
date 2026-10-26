package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.superiorsoftllc.agenthq.plugin.api.ModuleSetting
import com.superiorsoftllc.agenthq.plugin.api.ModulesConfig
import com.superiorsoftllc.agenthq.plugin.host.PluginFolders
import com.superiorsoftllc.agenthq.plugin.host.PluginJson
import com.superiorsoftllc.agenthq.ui.plugin.DesktopPlugins
import com.superiorsoftllc.agenthq.ui.plugin.MODULE_ROW_TAG_PREFIX
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * PLUGINS-1 dev tool: the whole window against an in-process stub daemon (temp state), one PNG per tab of every tab
 * strip, so a change to how tabs are built can be compared shot by shot, then Settings > Plugins. Args: output folder
 * (default build/tab-shots), then `demo-lock` to load under the demo lock.
 *
 * The modules come from a temp copy of the plugins root (`agenthq.plugins.root`, the repository's plugins/ in the Gradle
 * task) plus two community fixtures the loader must refuse: Acme_Broken (invalid module.json) and Acme_Needy (needs
 * Acme_Missing). Both are enabled in the copy's modules.json, so they are refused by the rules, not left off.
 */
@OptIn(ExperimentalTestApi::class)
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/tab-shots").apply { mkdirs() }
    val dir = Files.createTempDirectory("agenthq-tab-shots").toFile()
    val root = File(dir, "plugins")
    System.getProperty(PluginFolders.ROOT_PROPERTY)?.let { File(it).copyRecursively(root) }
    File(root, "community/Acme_Broken").apply { mkdirs() }.resolve("module.json").writeText("""{ "name": "Acme_Broken", "version": """)
    File(root, "community/Acme_Needy").apply { mkdirs() }.resolve("module.json").writeText(
        """{"name":"Acme_Needy","version":"1.0.0","author":"Acme (example)","license":"NOASSERTION","sequence":["Acme_Missing"]}""",
    )
    val modulesJson = File(root, "modules.json")
    val config = runCatching { PluginJson.decodeFromString(ModulesConfig.serializer(), modulesJson.readText()) }.getOrDefault(ModulesConfig())
    modulesJson.writeText(
        PluginJson.encodeToString(
            ModulesConfig.serializer(),
            config.copy(modules = config.modules + listOf("Acme_Broken", "Acme_Needy").associateWith { ModuleSetting(enabled = true) }),
        ),
    )
    val lock = args.getOrNull(1) == "demo-lock"
    val env = mapOf(PluginFolders.ROOT_ENV to root.absolutePath) + (if (lock) mapOf(PluginFolders.DEMO_LOCK_ENV to "1") else emptyMap())
    val tabs = DesktopPlugins.load(dir, env = env::get, property = { null }).factory
    val provider = StubProvider(StubScript.default(), delayMs = 0)
    val daemon = AgentHqDaemon.build(
        port = 18797, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
        sessionStore = JsonSessionStore(File(dir, "sessions.json")), mailStore = JsonMailStore(File(dir, "mailbox.json")),
        store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath), mailBaseUrl = "http://127.0.0.1:1", token = null,
    )
    daemon.start()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = Controller(scope)
    runDesktopComposeUiTest(1400, 860) {
        setContent { App(controller, "ws://127.0.0.1:18797/ws", null, tabs = tabs) }
        waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive }
        scope.launch { controller.spawn("say hello") }
        waitUntil(timeoutMillis = 20_000) { controller.state.value.agents.isNotEmpty() }
        waitForIdle()
        fun shot(name: String) {
            waitForIdle()
            Thread.sleep(400)
            waitForIdle()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
            println("shot ${File(out, "$name.png").path}")
        }
        // Every node tagged center-tab-* / right-tab-* is one tab; click each in strip order.
        for (prefix in listOf("center-tab-", "right-tab-")) {
            val tagged = SemanticsMatcher("tag starts with $prefix") { n ->
                n.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith(prefix) == true
            }
            val tags = onAllNodes(tagged).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }.filter { it != CENTER_TAB_BAR_TAG }.distinct()
            for (tag in tags) {
                onAllNodesWithTag(tag)[0].performClick()
                shot(tag)
            }
        }
        // Settings > Plugins > Modules: every module and why it did or did not load.
        onNodeWithContentDescription("Configuration").performClick()
        onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.PLUGINS.name).performClick()
        shot("settings-plugins")
        for (name in listOf("Acme_Hello", "Acme_Broken")) {
            val row = onAllNodesWithTag(MODULE_ROW_TAG_PREFIX + name)
            if (row.fetchSemanticsNodes().isNotEmpty()) { row[0].performScrollTo(); shot("settings-plugins-$name") }
        }
    }
    daemon.close()
    exitProcess(0)
}
