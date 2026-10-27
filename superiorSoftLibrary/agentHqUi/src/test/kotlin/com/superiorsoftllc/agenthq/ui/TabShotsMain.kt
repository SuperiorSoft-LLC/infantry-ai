package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.superiorsoftllc.agenthq.plugin.api.ModuleSetting
import com.superiorsoftllc.agenthq.plugin.api.ModulesConfig
import com.superiorsoftllc.agenthq.plugin.host.PluginFolders
import com.superiorsoftllc.agenthq.plugin.host.PluginJson
import com.superiorsoftllc.agenthq.ui.plugin.DesktopPlugins
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.plugin.MODULE_ROW_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.MODULE_MESSAGE_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.MODULE_TOGGLE_TAG_PREFIX
import androidx.compose.ui.test.ComposeUiTest
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
import java.util.concurrent.atomic.AtomicInteger
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
        """{"name":"Acme_Needy","version":"1.0.0","author":"Acme (example)","license":"NOASSERTION","dependencies":[{"id":"Acme_Missing","version":">=1.0"}]}""",
    )
    // PLUGINS-2 fixtures, off: Acme_Wants needs Acme_Hello >=2 (out of range), Acme_Layer needs Acme_Base (off).
    fun fixture(name: String, needs: String) = File(root, "community/$name").apply { mkdirs() }.resolve("module.json").writeText(
        """{"name":"$name","version":"1.0.0","author":"Acme (example)","license":"NOASSERTION","dependencies":[$needs]}""",
    )
    fixture("Acme_Wants", """{"id":"Acme_Hello","version":">=2"}""")
    fixture("Acme_Layer", """{"id":"Acme_Base","version":"^1.0"}""")
    fixture("Acme_Base", "")
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
    val sessionCount = AtomicInteger()
    val daemon = AgentHqDaemon.build(
        port = 18797, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
        sessionStore = JsonSessionStore(File(dir, "sessions.json")), mailStore = JsonMailStore(File(dir, "mailbox.json")),
        store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath), mailBaseUrl = "http://127.0.0.1:1", token = null,
        // TAB-SHOTS-DIFF: fixed session ids (the id shows in the header, the agent row and the composer), so two renders
        // of the same code are pixel-identical.
        sessionIds = { "5e55a0%02d-0000-4000-8000-000000000000".format(sessionCount.incrementAndGet()) },
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
        // TAB-SHOTS-DIFF: a pane still showing its loading placeholder ("Loading skills...", the Stats pane's "Reading this
        // session's record...") is shot once it has loaded, so a slow host does not change the picture. One that never
        // loads is shot as it is (the diff then shows it).
        fun shot(name: String) = snap(out, name)
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
        // The example module's daemon access: a declared route goes through, an undeclared one is refused.
        if (onAllNodesWithTag("center-tab-ACME_HELLO").fetchSemanticsNodes().isNotEmpty()) {
            onAllNodesWithTag("center-tab-ACME_HELLO")[0].performClick()
            for ((button, name) in listOf("acme-hello-sessions" to "route-refused", "acme-hello-health" to "route-declared")) {
                onNodeWithTag(button).performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("acme-hello-reply").fetchSemanticsNodes().isNotEmpty() }
                Thread.sleep(500)
                shot("acme-hello-$name")
            }
        }
        // Settings > Plugins > Modules: every module and why it did or did not load.
        onNodeWithContentDescription("Configuration").performClick()
        onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.PLUGINS.name).performClick()
        shot("settings-plugins")
        for (name in listOf("Acme_Hello", "Acme_Broken", "Acme_Needy")) {
            val row = onAllNodesWithTag(MODULE_ROW_TAG_PREFIX + name)
            if (row.fetchSemanticsNodes().isNotEmpty()) { row[0].performScrollTo(); shot("settings-plugins-$name") }
        }
        // PLUGINS-2: the dependency rules' refusals, as Settings shows them under the row.
        for ((name, shotName) in listOf("Infantry_Cli" to "refused-disable", "Acme_Wants" to "refused-range", "Acme_Layer" to "refused-enable-first")) {
            val toggle = onAllNodesWithTag(MODULE_TOGGLE_TAG_PREFIX + name)
            if (toggle.fetchSemanticsNodes().isEmpty()) continue
            toggle[0].performScrollTo()
            toggle[0].performClick()
            onAllNodesWithTag(MODULE_ROW_TAG_PREFIX + name)[0].performScrollTo()
            shot("settings-plugins-$shotName")
            onAllNodesWithTag(MODULE_MESSAGE_TAG_PREFIX + name).fetchSemanticsNodes().firstOrNull()
                ?.let { println("message $name: ${it.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString()}") }
        }
        // LAYOUT-1 P0: the same window with the title bar's window controls (header + footer visible), then compact widths and
        // the header's overflow menu, so a later shell change can prove the header and footer unchanged.
    }
    for ((name, size) in listOf("chrome-desktop-1400" to (1400 to 860), "chrome-compact-800" to (800 to 860), "chrome-compact-360" to (360 to 860))) {
        runDesktopComposeUiTest(size.first, size.second) {
            setContent { ChromeApp(controller, tabs) }
            waitForIdle()
            snap(out, name)
            if (size.first < 840) {
                onNodeWithTag(TITLE_BAR_OVERFLOW_TAG).performClick()
                waitForIdle()
                snap(out, "$name-overflow")
            }
        }
    }
    daemon.close()
    exitProcess(0)
}

private object ShotChrome : WindowChrome {
    override val isMaximized: Boolean = false
    override fun minimize() {}
    override fun toggleMaximize() {}
    override fun close() {}
}

@androidx.compose.runtime.Composable
private fun ChromeApp(controller: Controller, tabs: TabFactory) {
    androidx.compose.runtime.CompositionLocalProvider(LocalWindowChrome provides ShotChrome) {
        App(controller, "ws://127.0.0.1:18797/ws", null, tabs = tabs)
    }
}

/** Waits out loading placeholders, then writes the topmost root (the window, or a dialog over it) as `<name>.png`. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.snap(out: File, name: String) {
    // TAB-SHOTS-DIFF: a pane still showing its loading placeholder ("Loading skills...", the Stats pane's "Reading this
    // session's record...") is shot once it has loaded, so a slow host does not change the picture. One that never
    // loads is shot as it is (the diff then shows it).
    val loading = SemanticsMatcher("a loading placeholder") { n ->
        n.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty()
            .any { it.text.startsWith("Loading") || it.text.startsWith("Reading this session") }
    }
    waitForIdle()
    runCatching { waitUntil(timeoutMillis = 10_000) { onAllNodes(loading).fetchSemanticsNodes().isEmpty() } }
    Thread.sleep(400)
    waitForIdle()
    val roots = onAllNodes(isRoot())
    ImageIO.write(roots[roots.fetchSemanticsNodes().size - 1].captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    println("shot ${File(out, "$name.png").path}")
}
