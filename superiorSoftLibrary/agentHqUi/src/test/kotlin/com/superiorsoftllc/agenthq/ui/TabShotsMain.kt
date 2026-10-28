package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.board.*

import com.superiorsoftllc.agenthq.plugins.official.roles.*
import com.superiorsoftllc.agenthq.plugins.official.notes.*
import com.superiorsoftllc.agenthq.plugins.official.changes.*

import com.superiorsoftllc.agenthq.plugins.official.run.*

import com.superiorsoftllc.agenthq.plugins.official.git.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import com.superiorsoftllc.agenthq.ui.plugin.REGISTRY_CHILD_TAG_PREFIX
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.shell.blocks.CONFIG_ENTRY_TAG
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.superiorsoftllc.agenthq.ui.plugin.DesktopDropIn
import com.superiorsoftllc.agenthq.ui.shell.LocalPlatformHost
import com.superiorsoftllc.agenthq.ui.shell.LocalSoftRestart
import com.superiorsoftllc.agenthq.ui.shell.PlatformHost
import com.superiorsoftllc.agenthq.ui.shell.PluginDropIn
import com.superiorsoftllc.agenthq.ui.shell.blocks.DROP_BOX_CHOOSE_TAG
import com.superiorsoftllc.agenthq.ui.shell.blocks.DROP_BOX_TAG
import com.superiorsoftllc.agenthq.ui.shell.blocks.DROP_CONSENT_INSTALL_TAG
import com.superiorsoftllc.agenthq.ui.shell.blocks.DROP_CONSENT_TAG
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
 * SHOT-PREFS (3e4eb7ed): the UI prefs every shot draws with, in memory, never the java.util.prefs store (the registry
 * on Windows) that the live app writes. blurBehindDialogs=off matches the committed baselines (rendered with
 * -Dagenthq.ui.blur=off), and materialTheme=INFANTRY;dark is the theme they were rendered with (the default RED;dark
 * differs on every shot); every other pref is unset, so the shots draw its default.
 */
val SHOT_UI_PREFS: Map<String, String> = mapOf(BLUR_PREF_KEY to "off", THEME_PREF_KEY to "INFANTRY;dark")

fun pinShotUiPrefs() {
    com.superiorsoftllc.agenthq.ui.shell.uiPrefsOverride = SHOT_UI_PREFS.toMutableMap()
}

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
    pinShotUiPrefs()
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
    var tabs = DesktopPlugins.load(dir, env = env::get, property = { null }).factory
    // Own port per render (concurrent renders must not share a daemon): agenthq.tabshots.port pins one, else a free ephemeral one.
    val port = System.getProperty("agenthq.tabshots.port")?.toIntOrNull() ?: java.net.ServerSocket(0).use { it.localPort }
    val provider = StubProvider(StubScript.default(), delayMs = 0)
    val sessionCount = AtomicInteger()
    val sessionStore = JsonSessionStore(File(dir, "sessions.json"))
    val mailStore = JsonMailStore(File(dir, "mailbox.json"))
    val store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath)
    // TAB-SHOTS-DIFF: fixed session ids (the id shows in the header, the agent row and the composer), so two renders
    // of the same code are pixel-identical.
    val sessionIds = { "5e55a0%02d-0000-4000-8000-000000000000".format(sessionCount.incrementAndGet()) }
    // TAB-SHOTS-DIFF: a fixed 8 GB free of 16 GB, so a host under the 4 GB floor never pushes the "low memory" notice
    // (a 36 px row over the composer) into a shot.
    val memory = object : MemorySource {
        override fun freeMb(): Long = 8192
        override fun totalMb(): Long = 16384
        override fun processes(): List<ProcMem> = emptyList()
    }
    // BLOCKS-LAYOUT L15: -PtabShotsExtra=run gives the daemon a repository (a saved command needs one) and its own
    // commands.json in the temp state, and its own owner credential (saved commands and runs need one), so the Run tab has a
    // command to draw. The default run keeps the daemon's defaults.
    val runShots = System.getProperty("agenthq.tabshots.extra") == "run"
    val runOwner = "tab-shots-run-owner-0123456789abcdef"
    val daemon = if (runShots) AgentHqDaemon.build(
        port = port, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
        repo = File(dir, "run-repo").apply { mkdirs() }, commandsFile = File(dir, "commands.json"), ownerToken = runOwner,
        sessionStore = sessionStore, mailStore = mailStore, store = store, mailBaseUrl = "http://127.0.0.1:1", token = null,
        sessionIds = sessionIds, memory = memory,
    ) else AgentHqDaemon.build(
        port = port, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
        sessionStore = sessionStore, mailStore = mailStore, store = store, mailBaseUrl = "http://127.0.0.1:1", token = null,
        sessionIds = sessionIds, memory = memory,
    )
    daemon.start()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = Controller(scope)
    if (runShots) controller.client.ownerFor("ws://127.0.0.1:$port/ws", runOwner)
    // S4 (drop-in path): -PtabShotsExtra=empty shoots the shell with no module at all (TabFactory(emptyList())): every app
    // slot is an empty placeholder, the locked dialogs and the trust half of Configuration are the core's. Its reference is
    // tab-shots-baseline/empty-shell/ (-PtabShotsBaseline=superiorSoftLibrary/agentHqUi/tab-shots-baseline/empty-shell).
    if (System.getProperty("agenthq.tabshots.extra") == "empty") {
        runDesktopComposeUiTest(1400, 860) {
            setContent { ChromeApp(controller, TabFactory(emptyList()), port) }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive }
            snap(out, "shell-empty-1400")
            // S4c: Configuration through the core's own entry (ShellConfigEntry, drawn while no module fills the header): the
            // frame and the trust tabs (Secrets / Devices / About) are the core's; the open settings are an empty slot.
            onNodeWithTag(CONFIG_ENTRY_TAG).performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(CONFIG_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            snap(out, "shell-empty-config-1400")
        }
        daemon.close()
        exitProcess(0)
    }
    // S6 (drop-in path): -PtabShotsExtra=dropin is the drop-in acceptance (the task runs it without the app UI on the class
    // path). From a plugins root with no app UI in it the shell is empty and draws the drop box; App_UI.ihq, signed here
    // with a throwaway key that this root trusts (so it installs as core), goes through Choose file, the consent dialog
    // and Install; the soft restart rebuilds the module set in this process. The 32 default shots are then rendered with
    // that module set, as the default run renders them with the compiled-in one. Opt-in shots: dropin/.
    if (System.getProperty("agenthq.tabshots.extra") == "dropin") {
        File(root, "core").listFiles().orEmpty().forEach { it.deleteRecursively() }
        val ihq = signedForShots(System.getProperty("agenthq.tabshots.appUiIhq") ?: error("agenthq.tabshots.appUiIhq is not set"), dir, root)
        // Every load starts the loaded modules again (a community module's event handlers too: nothing is unloaded), so the
        // 32 shots use the soft restart's own load, not one more.
        var last: TabFactory? = null
        // PACKAGING (95c26a81): the official modules (Infantry_Cli) come from the copied root's official/ jars, as a packaged
        // app loads them from its bundled root; the task's devTrust lets those unsigned dev jars through.
        val devTrust = { name: String -> if (name == PluginFolders.DEV_TRUST_PROPERTY) System.getProperty(name) else null }
        fun reload() = DesktopPlugins.load(dir, env = env::get, property = devTrust, compiled = emptyList()).factory.also { last = it }
        val dropIn = DesktopDropIn(root, demoLock = false, safeMode = false, picker = { ihq.path })
        check(!reload().fills("infantry.shell.center")) { "the plugins root still fills the center: not an empty shell" }
        runDesktopComposeUiTest(1400, 860) {
            setContent {
                var shown by remember { mutableStateOf(reload()) }
                val base = LocalPlatformHost.current
                val host = object : PlatformHost by base {
                    override val dropIn: PluginDropIn get() = dropIn
                    override fun reloadTabs(): TabFactory = reload()
                }
                CompositionLocalProvider(LocalPlatformHost provides host, LocalWindowChrome provides ShotChrome, LocalSoftRestart provides { shown = host.reloadTabs() }) {
                    key(shown) { App(controller, "ws://127.0.0.1:$port/ws", null, tabs = shown) }
                }
            }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive }
            val dropDir = File(out, "dropin").apply { mkdirs() }
            snap(dropDir, "dropin-empty-shell")
            onNodeWithTag(DROP_BOX_CHOOSE_TAG).performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(DROP_CONSENT_TAG).fetchSemanticsNodes().isNotEmpty() }
            snap(dropDir, "dropin-consent")
            onNodeWithTag(DROP_CONSENT_INSTALL_TAG).performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag(DROP_BOX_TAG).fetchSemanticsNodes().isEmpty() }
            println("dropin: installed ${ihq.name}; the drop box is gone after the soft restart")
        }
        tabs = last!!
        println("dropin: ${tabs.statuses.count { it.state == com.superiorsoftllc.agenthq.plugin.host.ModuleState.LOADED }} of ${tabs.statuses.size} modules loaded after the install")
    }
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
            // BLOCKS-LAYOUT L16: then the board with cards (the empty backlog draws none): the new-ticket form, cards in
            // Backlog, To do and Done (one with a role assignee), and one card opened on its edit form.
            onNodeWithText("+ New ticket").performClick()
            snap(out, "board-new")
            onNodeWithText("Close").performClick()
            kotlinx.coroutines.runBlocking {
                controller.createTicket("Shot ticket one", "first", "feature", "role:frontend")
                controller.createTicket("Shot ticket two with a longer title that wraps onto a second line", "", "bug", null)
                controller.createTicket("Shot ticket three", "", null, null)
            }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.visibleTickets.size == 3 }
            val shotTickets = controller.state.value.visibleTickets.associateBy { it.title }
            kotlinx.coroutines.runBlocking {
                controller.moveTicket(shotTickets.getValue("Shot ticket one").id, com.superiorsoftllc.agenthq.ui.protocol.TicketColumns.TODO)
                controller.moveTicket(shotTickets.getValue("Shot ticket two with a longer title that wraps onto a second line").id, com.superiorsoftllc.agenthq.ui.protocol.TicketColumns.BACKLOG)
                controller.moveTicket(shotTickets.getValue("Shot ticket three").id, com.superiorsoftllc.agenthq.ui.protocol.TicketColumns.DONE)
            }
            waitUntil(timeoutMillis = 20_000) {
                controller.state.value.visibleTickets.map { it.status }.toSet() ==
                    with(com.superiorsoftllc.agenthq.ui.protocol.TicketColumns) { setOf(TODO, BACKLOG, DONE) }
            }
            waitForIdle()
            snap(out, "board-cards")
            onNodeWithTag(TICKET_CARD_TAG_PREFIX + shotTickets.getValue("Shot ticket one").id).performClick()
            snap(out, "board-card-open")
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
        // BLOCKS-LAYOUT L15: -PtabShotsExtra=run renders only the Run tab with a saved command (the default shot has none):
        // the toolbar, the delete confirm, the editor, and a finished run's pill and output. The default run is unchanged.
        if (runShots) {
            val argv = if (System.getProperty("os.name").startsWith("Windows")) listOf("cmd", "/c", "echo", "hello from the run shot")
            else listOf("echo", "hello from the run shot")
            kotlinx.coroutines.runBlocking { controller.saveCommand("echo-shot", "Echo shot", argv, null, 5000L) }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.runs.commands.any { it.id == "echo-shot" } }
            onAllNodesWithTag("center-tab-RUN")[0].performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Echo shot").fetchSemanticsNodes().isNotEmpty() }
            snap(out, "run-toolbar")
            onNodeWithTag(RUN_DELETE_TAG).performClick()
            snap(out, "run-delete")
            onNodeWithTag(RUN_DELETE_CANCEL_TAG).performClick()
            onNodeWithTag(RUN_EDIT_TAG).performClick()
            snap(out, "run-editor")
            onNodeWithTag(RUN_FORM_CANCEL_TAG).performScrollTo().performClick()
            onNodeWithTag(RUN_START_TAG).performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.runs.runs.values.any { it.finished != null } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("hello from the run shot", substring = true).fetchSemanticsNodes().isNotEmpty() }
            snap(out, "run-output")
            daemon.close()
            exitProcess(0)
        }
        // BLOCKS-LAYOUT L17: -PtabShotsExtra=memory renders only the Notes tab over a project with two notes (the default
        // shot has no project): the list, a search's hits, a note open in the modal, the empty add modal, and the role and
        // session scopes. The default run is unchanged.
        if (System.getProperty("agenthq.tabshots.extra") == "memory") {
            kotlinx.coroutines.runBlocking { controller.saveProject("Notes shot project") }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.projects.any { it.name == "Notes shot project" } }
            val project = controller.state.value.projects.single { it.name == "Notes shot project" }
            kotlinx.coroutines.runBlocking {
                controller.selectProject(project.id)
                controller.saveMemory(project.id, "cart-notes", "cart rules", "The cart keeps its items for a day.")
                controller.saveMemory(project.id, "deploy", "", "Deploy from main only.")
            }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.activeProject?.id == project.id }
            onAllNodesWithTag("center-tab-MEMORY")[0].performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("deploy").fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("cart-notes").fetchSemanticsNodes().isNotEmpty() }
            snap(out, "memory-list")
            // Searched through the controller, not typed: a focused field's caret blinks into the shot.
            kotlinx.coroutines.runBlocking { controller.searchMemory(project.id, "cart") }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("cart-notes").fetchSemanticsNodes().size > 1 }
            snap(out, "memory-search")
            // The last "cart-notes" is the note row (the hits are drawn above the list).
            onAllNodesWithText("cart-notes").fetchSemanticsNodes().size.let { n -> onAllNodesWithText("cart-notes")[n - 1].performClick() }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag(MEMORY_NOTE_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("The cart keeps its items for a day.").fetchSemanticsNodes().isNotEmpty() }
            snap(out, "memory-edit")
            onNodeWithTag(MEMORY_NOTE_CANCEL_TAG).performClick()
            onNodeWithTag(MEMORY_ADD_NOTE_BUTTON_TAG).performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag(MEMORY_NOTE_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            snap(out, "memory-add")
            onNodeWithTag(MEMORY_NOTE_CANCEL_TAG).performClick()
            onNodeWithTag(NOTES_SCOPE_ROLE_TAG).performClick()
            waitForIdle()
            snap(out, "memory-role")
            onNodeWithTag(NOTES_SCOPE_SESSION_TAG).performClick()
            waitForIdle()
            snap(out, "memory-session")
            daemon.close()
            exitProcess(0)
        }
        // BLOCKS-LAYOUT L18: -PtabShotsExtra=changespane renders only the Changes tab over a project with a real repository (the
        // default shot has no project): the working tree, the worktree and branch menus, a branch's changes, the history,
        // and a folder that is not a git checkout. Fixed path and commit dates, as the Git shots. The default run is unchanged.
        if (System.getProperty("agenthq.tabshots.extra") == "changespane") {
            val repo = File(System.getProperty("java.io.tmpdir"), "agenthq-tab-shots-changes").apply { deleteRecursively(); mkdirs() }
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
            git("checkout", "-b", "feature-shots")
            File(repo, "feature.txt").writeText("a feature\n")
            git("add", "feature.txt")
            git("commit", "-m", "add the feature")
            git("checkout", "main")
            File(repo, "README.md").writeText("hello from the changes shot\nsecond line\n")
            File(repo, "staged.txt").writeText("staged\n")
            git("add", "staged.txt")
            kotlinx.coroutines.runBlocking { controller.saveProject("Changes shot project") }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.projects.any { it.name == "Changes shot project" } }
            val project = controller.state.value.projects.single { it.name == "Changes shot project" }
            kotlinx.coroutines.runBlocking { controller.selectProject(project.id); controller.saveProjectDirectory(project.id, repo.absolutePath) }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.activeProject?.directory == repo.absolutePath }
            onAllNodesWithTag("right-tab-CHANGES")[0].performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("README.md", substring = true).fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("staged.txt", substring = true).fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag(CHANGES_BRANCH_TAG).fetchSemanticsNodes().isNotEmpty() }
            snap(out, "changespane-top")
            onNodeWithTag(WORKTREE_SELECT_TAG).performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag(WORKTREE_SELECT_OPTION_TAG + "main").fetchSemanticsNodes().isNotEmpty() }
            snap(out, "changespane-worktrees")
            onNodeWithTag(WORKTREE_SELECT_OPTION_TAG + "main").performClick()
            waitForIdle()
            onNodeWithTag(BRANCH_SELECT_TAG).performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag(BRANCH_SELECT_OPTION_TAG + "feature-shots").fetchSemanticsNodes().isNotEmpty() }
            snap(out, "changespane-branches")
            onNodeWithTag(BRANCH_SELECT_OPTION_TAG + "feature-shots").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag(BRANCH_CHANGE_ROW_TAG + "feature.txt").fetchSemanticsNodes().isNotEmpty() }
            snap(out, "changespane-branch")
            onNodeWithTag(BRANCH_SELECT_TAG).performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag(BRANCH_SELECT_OPTION_TAG + WORKING_TREE_KEY).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(BRANCH_SELECT_OPTION_TAG + WORKING_TREE_KEY).performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("staged.txt", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHANGES_MODE_HISTORY_TAG).performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("add the notes").fetchSemanticsNodes().isNotEmpty() }
            snap(out, "changespane-history")
            onNodeWithTag(CHANGES_MODE_WORKING_TREE_TAG).performClick()
            val plain = File(System.getProperty("java.io.tmpdir"), "agenthq-tab-shots-changes-plain").apply { deleteRecursively(); mkdirs() }
            kotlinx.coroutines.runBlocking { controller.saveProjectDirectory(project.id, plain.absolutePath) }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag(NOT_A_GIT_REPOSITORY_TAG).fetchSemanticsNodes().isNotEmpty() }
            snap(out, "changespane-not-git")
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
        // SETTINGS-TAB-1: -PtabShotsExtra=settings is the Settings tab mockup: the strip before, the switch in Configuration >
        // Appearance > Tab groups turned on (stored by this run's daemon), the strip after with Settings open on each of its
        // sub-tabs, then the same at the 800 and 360 px widths. The default run is unchanged.
        if (System.getProperty("agenthq.tabshots.extra") == "settings") {
            snap(out, "settings-0-before-1400")
            onNodeWithContentDescription("Configuration").performClick()
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.APPEARANCE.name).performClick()
            onNodeWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.TAB_GROUPS.name).performScrollTo()
            waitUntil(timeoutMillis = 20_000) { tabs.config.load == com.superiorsoftllc.agenthq.ui.plugin.config.NodeConfigLoad.LOADED }
            waitForIdle()
            snap(out, "settings-1-switch-off")
            onNodeWithTag(com.superiorsoftllc.agenthq.ui.plugin.TAB_GROUP_SWITCH_TAG_PREFIX + "infantry.settings").performClick()
            waitUntil(timeoutMillis = 20_000) { tabs.grouped("infantry.settings") }
            waitForIdle()
            snap(out, "settings-2-switch-on")
            onNodeWithContentDescription("Configuration").performClick()
            waitForIdle()
            snap(out, "settings-3-strip-1400")
            onAllNodesWithTag("center-tab-SETTINGS")[0].performClick()
            for (tag in listOf("PROFILES", "TRIGGERS", "MEMORY", "RUN", "GIT")) {
                onAllNodesWithTag("center-tab-$tag")[0].performClick()
                snap(out, "settings-4-${tag.lowercase()}-1400")
            }
            return@runDesktopComposeUiTest
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
        // S0 (drop-in path): -PtabShotsExtra=config,changes,compact (any of them, comma-separated) shoots what the 32 default
        // shots and the other opt-in sets miss, before any of it moves out of core: every Configuration section, the config
        // search, the Changes panel over a project with and without a repository, and (compact) the board, a config section and
        // the new-agent dialog at the 800 and 360 px widths. The default run is unchanged.
        val s0 = System.getProperty("agenthq.tabshots.extra")?.split(',')?.map { it.trim() }.orEmpty()
        // P1 (f60f4d97): -PtabShotsExtra=registry shoots Configuration > Plugins scrolled to the Registered view (centre strip picked).
        val s0Sets = setOf("config", "changes", "compact", "registry")
        if (s0.any { it in s0Sets }) {
            if ("config" in s0) {
                onNodeWithContentDescription("Configuration").performClick()
                for (tab in ConfigTab.values()) {
                    onNodeWithTag(CONFIG_TAB_TAG_PREFIX + tab.name).performClick()
                    snap(out, "config-" + tab.name.lowercase())
                }
                onNodeWithTag(CONFIG_SEARCH_TAG).performClick()
                snap(out, "config-search")
                onNodeWithContentDescription("Configuration").performClick()
                waitForIdle()
            }
            if ("registry" in s0) {
                onNodeWithContentDescription("Configuration").performClick()
                onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.PLUGINS.name).performClick()
                onNodeWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.REGISTRY.name).performScrollTo()
                // Down to the example community module's tab, so the shot shows the strip's rows, not only the picker.
                onAllNodesWithTag(REGISTRY_CHILD_TAG_PREFIX + "acme.hello").fetchSemanticsNodes().firstOrNull()?.let { onNodeWithTag(REGISTRY_CHILD_TAG_PREFIX + "acme.hello").performScrollTo() }
                waitForIdle()
                snap(out, "config-registry")
                onNodeWithContentDescription("Configuration").performClick()
                waitForIdle()
            }
            if ("changes" in s0) {
                // No project directory yet: the panel's not-a-repository state; then a repository with a modified and an untracked file.
                kotlinx.coroutines.runBlocking { controller.saveProject("Changes shot project") }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.projects.any { it.name == "Changes shot project" } }
                val project = controller.state.value.projects.single { it.name == "Changes shot project" }
                kotlinx.coroutines.runBlocking { controller.selectProject(project.id) }
                onAllNodesWithTag("right-tab-CHANGES")[0].performClick()
                snap(out, "changes-no-directory")
                val repo = File(System.getProperty("java.io.tmpdir"), "agenthq-tab-shots-changes").apply { deleteRecursively(); mkdirs() }
                kotlinx.coroutines.runBlocking { controller.saveProjectDirectory(project.id, repo.absolutePath) }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.activeProject?.directory == repo.absolutePath }
                snap(out, "changes-not-a-repository")
                fun git(vararg a: String) = ProcessBuilder(listOf("git", "-c", "user.name=Shots", "-c", "user.email=shots@test.invalid") + a)
                    .directory(repo).redirectErrorStream(true)
                    .apply { environment()["GIT_AUTHOR_DATE"] = "2026-01-02T03:04:05Z"; environment()["GIT_COMMITTER_DATE"] = "2026-01-02T03:04:05Z" }
                    .start().apply { inputStream.readBytes(); waitFor() }
                git("init", "-b", "main")
                File(repo, "README.md").writeText("hello\nsecond line\n")
                git("add", "README.md")
                git("commit", "-m", "add the readme")
                File(repo, "README.md").writeText("hello from the changes shot\nsecond line\n")
                File(repo, "new.txt").writeText("untracked\n")
                onNodeWithTag(PROJECT_CHANGES_REFRESH_TAG).performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("README.md", substring = true).fetchSemanticsNodes().isNotEmpty() }
                snap(out, "changes-working-tree")
                onNodeWithTag(CHANGES_MODE_HISTORY_TAG).performClick()
                snap(out, "changes-history")
            }
            if ("compact" !in s0) {
                daemon.close()
                exitProcess(0)
            }
            return@runDesktopComposeUiTest
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
    if (System.getProperty("agenthq.tabshots.extra") == "settings") {
        for ((name, width) in listOf("800" to 800, "360" to 360)) {
            runDesktopComposeUiTest(width, 860) {
                setContent { ChromeApp(controller, tabs, port) }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive }
                waitForIdle()
                snap(out, "settings-5-home-$name")
                onAllNodesWithTag("center-tab-SETTINGS")[0].performScrollTo().performClick()
                snap(out, "settings-6-open-$name")
            }
        }
        daemon.close()
        exitProcess(0)
    }
    if ("compact" in System.getProperty("agenthq.tabshots.extra").orEmpty().split(',').map { it.trim() }) {
        // One fresh window per overlay (the compact board has no "Close" text to click back out with).
        for ((name, width) in listOf("compact-800" to 800, "compact-360" to 360)) {
            for (overlay in listOf("board", "newAgent", "config")) {
                runDesktopComposeUiTest(width, 860) {
                    setContent { ChromeApp(controller, tabs, port) }
                    waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive }
                    waitForIdle()
                    // The sidebar lives in the compact drawer, opened from the title bar's left toggle.
                    if (overlay != "config" && onAllNodesWithTag(DRAWER_TAG).fetchSemanticsNodes().isEmpty()) { onNodeWithTag(TITLE_BAR_TOGGLE_LEFT_TAG).performClick(); waitForIdle(); Thread.sleep(800); waitForIdle() }
                    when (overlay) {
                        "board" -> onNodeWithText("Board").performClick()
                        "newAgent" -> onNodeWithText("New agent").performClick()
                        "config" -> onNodeWithContentDescription("Configuration").performClick()
                    }
                    snap(out, "$name-$overlay")
                    if (overlay == "config") {
                        onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.AGENTS.name).performClick()
                        snap(out, "$name-config-agents")
                    }
                }
            }
        }
        daemon.close()
        exitProcess(0)
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

/**
 * S6: a copy of the unsigned [unsigned] App_UI.ihq signed with a throwaway key made here (fixed name, so the consent
 * dialog's signer line is the same every run), and that key's certificate pinned in [root]'s trusted-keys: it installs as core.
 */
private fun signedForShots(unsigned: String, dir: File, root: File): File {
    fun tool(name: String, vararg args: String) {
        val exe = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "$name.exe" else name).path
        val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "$name failed: $text" }
    }
    val ks = File(dir, "shots.p12")
    tool("keytool", "-genkeypair", "-alias", "shots", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Infantry AI (tab shots)",
        "-validity", "30", "-keystore", ks.path, "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit")
    File(root, "trusted-keys").mkdirs()
    tool("keytool", "-exportcert", "-alias", "shots", "-keystore", ks.path, "-storepass", "changeit", "-file", File(root, "trusted-keys/tab-shots.cer").path)
    val ihq = File(dir, "drop/App_UI.ihq").apply { parentFile.mkdirs() }
    File(unsigned).copyTo(ihq, overwrite = true)
    tool("jarsigner", "-keystore", ks.path, "-storepass", "changeit", ihq.path, "shots")
    return ihq
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
