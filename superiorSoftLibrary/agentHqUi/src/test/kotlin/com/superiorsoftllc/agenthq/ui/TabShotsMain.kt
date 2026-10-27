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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
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
import com.superiorsoftllc.agenthq.session.MemorySource
import com.superiorsoftllc.agenthq.session.ProcMem
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
    // Own port per render (concurrent renders must not share a daemon): agenthq.tabshots.port pins one, else a free ephemeral one.
    val port = System.getProperty("agenthq.tabshots.port")?.toIntOrNull() ?: java.net.ServerSocket(0).use { it.localPort }
    val provider = StubProvider(StubScript.default(), delayMs = 0)
    val sessionCount = AtomicInteger()
    val daemon = AgentHqDaemon.build(
        port = port, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
        sessionStore = JsonSessionStore(File(dir, "sessions.json")), mailStore = JsonMailStore(File(dir, "mailbox.json")),
        store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath), mailBaseUrl = "http://127.0.0.1:1", token = null,
        // TAB-SHOTS-DIFF: fixed session ids (the id shows in the header, the agent row and the composer), so two renders
        // of the same code are pixel-identical.
        sessionIds = { "5e55a0%02d-0000-4000-8000-000000000000".format(sessionCount.incrementAndGet()) },
        // TAB-SHOTS-DIFF: a fixed 8 GB free of 16 GB, so a host under the 4 GB floor never pushes the "low memory" notice
        // (a 36 px row over the composer) into a shot.
        memory = object : MemorySource {
            override fun freeMb(): Long = 8192
            override fun totalMb(): Long = 16384
            override fun processes(): List<ProcMem> = emptyList()
        },
    )
    daemon.start()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = Controller(scope)
    runDesktopComposeUiTest(1400, 860) {
        setContent { App(controller, "ws://127.0.0.1:$port/ws", null, tabs = tabs) }
        waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive }
        scope.launch { controller.spawn("say hello") }
        waitUntil(timeoutMillis = 20_000) { controller.state.value.agents.isNotEmpty() }
        waitForIdle()
        // BLOCKS-LAYOUT L10: -PtabShotsExtra=board renders only the open board (no baseline shot opens it), so a board
        // change is diffed against a render of its base (-PtabShotsBaseline). The default run is unchanged.
        if (System.getProperty("agenthq.tabshots.extra") == "board") {
            onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).performClick()
            // Open only: the window without the board is the baseline shots' (its message clocks are masked per shot name).
            snap(out, "board-open")
            daemon.close()
            exitProcess(0)
        }
        // BLOCKS-LAYOUT L12: -PtabShotsExtra=roles renders only the role manager (Configuration > Agents > Manage roles): its
        // list, a new role's form, and a built-in role's form at the top and scrolled to Save. The default run is unchanged.
        if (System.getProperty("agenthq.tabshots.extra") == "roles") {
            waitUntil(timeoutMillis = 20_000) { controller.state.value.roles.isNotEmpty() }
            onNodeWithContentDescription("Configuration").performClick()
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.AGENTS.name).performClick()
            onNodeWithTag(ROLE_MANAGER_OPEN_TAG).performScrollTo().performClick()
            snap(out, "roles-list")
            onNodeWithTag(ROLE_MANAGER_NEW_TAG).performClick()
            snap(out, "roles-new")
            onNodeWithText("Back").performScrollTo().performClick()
            onNodeWithTag(ROLE_MANAGER_ROW_TAG_PREFIX + controller.state.value.roles.first().id).performScrollTo().performClick()
            snap(out, "roles-edit")
            onNodeWithTag(ROLE_MANAGER_SAVE_TAG).performScrollTo()
            snap(out, "roles-edit-end")
            daemon.close()
            exitProcess(0)
        }
        // BLOCKS-LAYOUT L14: -PtabShotsExtra=git renders only the Git tab over a project with a real repository (the default
        // shot has no project): its top, a picked file's diff, and scrolled to the history heading and to its oldest commit.
        // The repository has a fixed path (the directory is drawn) and fixed commit dates (the history is drawn). The default
        // run is unchanged.
        if (System.getProperty("agenthq.tabshots.extra") == "git") {
            val repo = File(System.getProperty("java.io.tmpdir"), "agenthq-tab-shots-git").apply { deleteRecursively(); mkdirs() }
            fun git(vararg a: String) = ProcessBuilder(listOf("git", "-c", "user.name=Shots", "-c", "user.email=shots@test.invalid") + a)
                .directory(repo).redirectErrorStream(true)
                .apply { environment()["GIT_AUTHOR_DATE"] = "2026-01-02T03:04:05Z"; environment()["GIT_COMMITTER_DATE"] = "2026-01-02T03:04:05Z" }
                .start().apply { inputStream.readBytes(); waitFor() }
            git("init", "-b", "main")
            File(repo, "README.md").writeText("hello\nsecond line\n")
            git("add", "README.md")
            git("commit", "-m", "add the readme")
            File(repo, "notes.txt").writeText("one\n")
            git("add", "notes.txt")
            git("commit", "-m", "add the notes")
            git("branch", "feature-shots")
            File(repo, "README.md").writeText("hello from the git shot\nsecond line\n")
            File(repo, "new.txt").writeText("untracked\n")
            kotlinx.coroutines.runBlocking { controller.saveProject("Git shot project") }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.projects.any { it.name == "Git shot project" } }
            val project = controller.state.value.projects.single { it.name == "Git shot project" }
            kotlinx.coroutines.runBlocking { controller.selectProject(project.id); controller.saveProjectDirectory(project.id, repo.absolutePath) }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.activeProject?.directory == repo.absolutePath }
            onAllNodesWithTag("center-tab-GIT")[0].performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("README.md", substring = true).fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("add the notes").fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag(GIT_SWITCH_TAG + "feature-shots").fetchSemanticsNodes().isNotEmpty() }
            snap(out, "git-top")
            onAllNodesWithText("README.md", substring = true)[0].performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.projectGitPatch[project.id]?.path == "README.md" }
            snap(out, "git-diff")
            onNodeWithText("HISTORY").performScrollTo()
            snap(out, "git-history")
            onNodeWithText("add the readme").performScrollTo()
            snap(out, "git-end")
            daemon.close()
            exitProcess(0)
        }
        // TOAST-1: -PtabShotsExtra=toast renders one fixed toast over the window, then the same with the board open (the
        // toast sits under the board's modal), for a before/after diff of the toast block. The default run is unchanged.
        if (System.getProperty("agenthq.tabshots.extra") == "toast") {
            controller.postToast(com.superiorsoftllc.agenthq.ui.model.ToastEvent(
                "shot-toast", Long.MAX_VALUE / 2, com.superiorsoftllc.agenthq.ui.model.ToastKind.SUCCESS,
                "Toast shot", "A visible toast, for the before/after diff", durationMs = 600_000,
            ))
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(TOAST_TAG).fetchSemanticsNodes().isNotEmpty() }
            snap(out, "toast-visible")
            onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).performClick()
            snap(out, "toast-board")
            daemon.close()
            exitProcess(0)
        }
        // BLOCKS-LAYOUT L11: -PtabShotsExtra=assistant,newAgent,diff (any of them, comma-separated) renders only those
        // overlays open, as the board's shot does (the default run is unchanged). The diff comes last: it adds a project.
        val l11 = System.getProperty("agenthq.tabshots.extra")?.split(',')?.map { it.trim() }.orEmpty()
        if (l11.any { it in setOf("assistant", "newAgent", "diff") }) {
            if ("assistant" in l11) {
                onNodeWithTag(TITLE_BAR_ASSISTANT_TAG).performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag(ASSISTANT_STARTING_TAG).fetchSemanticsNodes().isEmpty() }
                snap(out, "assistant-docked")
                onNodeWithTag(ASSISTANT_EXPAND_TAG).performClick()
                snap(out, "assistant-full")
                onNodeWithTag(ASSISTANT_EXPAND_TAG).performClick()
                onNodeWithTag(ASSISTANT_CLOSE_TAG).performClick()
                waitForIdle()
            }
            if ("newAgent" in l11) {
                onNodeWithTag(SIDEBAR_NEW_AGENT_TAG).performClick()
                snap(out, "newAgent-open")
                val role = SemanticsMatcher("a role card") { n ->
                    n.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith(NEW_AGENT_ROLE_TAG_PREFIX) == true
                }
                onAllNodes(role).fetchSemanticsNodes().firstOrNull()?.let { onAllNodes(role)[0].performClick(); snap(out, "newAgent-role") }
                onNodeWithText("Cancel").performClick()
                waitForIdle()
            }
            if ("diff" in l11) {
                // A temp git repository with one modified file, as DiffModalRealDaemonUiTest builds it.
                val repo = File(dir, "diff-repo").apply { mkdirs() }
                fun git(vararg a: String) = ProcessBuilder(listOf("git", "-c", "user.name=Shots", "-c", "user.email=shots@test.invalid") + a)
                    .directory(repo).redirectErrorStream(true).start().apply { inputStream.readBytes(); waitFor() }
                git("init", "-b", "main")
                File(repo, "README.md").writeText("hello\nsecond line\n")
                git("add", "README.md")
                git("commit", "-m", "add the readme")
                File(repo, "README.md").writeText("hello from the diff shot\nsecond line\n")
                kotlinx.coroutines.runBlocking { controller.saveProject("Diff shot project") }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.projects.any { it.name == "Diff shot project" } }
                val project = controller.state.value.projects.single { it.name == "Diff shot project" }
                kotlinx.coroutines.runBlocking { controller.selectProject(project.id); controller.saveProjectDirectory(project.id, repo.absolutePath) }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.activeProject?.directory == repo.absolutePath }
                onAllNodesWithTag("right-tab-CHANGES")[0].performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("README.md", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText("README.md", substring = true)[0].performClick()
                waitUntil(timeoutMillis = 20_000) { controller.state.value.projectGitPatch[project.id]?.path == "README.md" }
                snap(out, "diff-open")
            }
            daemon.close()
            exitProcess(0)
        }
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
            setContent { ChromeApp(controller, tabs, port) }
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
private fun ChromeApp(controller: Controller, tabs: TabFactory, port: Int) {
    androidx.compose.runtime.CompositionLocalProvider(LocalWindowChrome provides ShotChrome) {
        App(controller, "ws://127.0.0.1:$port/ws", null, tabs = tabs)
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
