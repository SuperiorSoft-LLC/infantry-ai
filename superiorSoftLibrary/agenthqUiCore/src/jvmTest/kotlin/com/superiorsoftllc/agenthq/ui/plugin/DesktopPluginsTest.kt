package com.superiorsoftllc.agenthq.ui.plugin

import com.superiorsoftllc.agenthq.plugin.api.DaemonReply
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.Services
import com.superiorsoftllc.agenthq.plugin.api.Tier
import kotlinx.coroutines.runBlocking
import com.superiorsoftllc.agenthq.plugin.host.ModuleChange
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.PluginFolders
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PLUGINS-1: the desktop loader over a real plugins root on disk. */
class DesktopPluginsTest {
    private val state = Files.createTempDirectory("plugins-state").toFile()
    private val root = File(state, "plugins")

    private fun community(name: String, json: String) = File(root, "community/$name").apply { mkdirs() }.resolve("module.json").writeText(json)

    private fun load(vararg env: Pair<String, String>, compiled: List<com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule> = CompiledModules.all) =
        DesktopPlugins.load(state, env = env.toMap()::get, property = { null }, compiled = compiled, print = {})

    private fun manifest(name: String, extra: String = "") =
        """{"name":"$name","version":"1.0.0","author":"Acme","license":"NOASSERTION","entry":"com.acme.Nope","jar":"build/libs/$name.jar"$extra}"""

    @Test
    fun `with no plugins root the compiled core modules load, unchanged`() {
        val loaded = load(compiled = CoreModules.all)
        assertEquals(CoreModules.all.map { it.manifest.name }.sorted(), loaded.factory.statuses.map { it.name }, "load order: sequence, then name")
        assertTrue(loaded.factory.statuses.all { it.state == ModuleState.LOADED && it.tier == Tier.CORE })
        assertEquals(CoreModules.factory.tabs(TabContainers.CENTER).map { it.testTag }, loaded.factory.tabs(TabContainers.CENTER).map { it.testTag })
        assertEquals(CoreModules.factory.tabs(TabContainers.RIGHT).map { it.testTag }, loaded.factory.tabs(TabContainers.RIGHT).map { it.testTag })
        assertTrue(loaded.factory.loadsAtRunTime)
    }

    @Test
    fun `broken json, a missing dependency, a missing jar and a community override of core are refused or skipped`() {
        root.mkdirs()
        File(root, "modules.json").writeText(
            """{"modules":{"Acme_Broken":{"enabled":true},"Acme_Needy":{"enabled":true},"Acme_NoJar":{"enabled":true},"Acme_Git":{"enabled":true}}}""",
        )
        community("Acme_Broken", """{"name": "Acme_Broken", """)
        community("Acme_Needy", manifest("Acme_Needy", ""","dependencies":[{"id":"Acme_Missing"}]"""))
        community("Acme_NoJar", manifest("Acme_NoJar"))
        community("Acme_Git", manifest("Acme_Git", ""","tabs":[{"id":"acme.git","container":"center"}],"preferences":[{"replace":"infantry.git","with":"acme.git"}]"""))
        community("Acme_Off", manifest("Acme_Off"))

        val loaded = load()
        val f = loaded.factory
        fun reason(n: String) = f.statuses.single { it.name == n }.reason.orEmpty()
        assertTrue(reason("Acme_Broken").startsWith("invalid module.json"), reason("Acme_Broken"))
        assertEquals("missing dependency Acme_Missing", reason("Acme_Needy"))
        assertEquals("jar build/libs/Acme_NoJar.jar not found (build it first)", reason("Acme_NoJar"))
        assertEquals("community, off until enabled in modules.json", reason("Acme_Off"))
        assertTrue(loaded.log.any { "cannot replace core infantry.git" in it }, "the override is refused")
        assertEquals("Infantry_Git", f.find("infantry.git")?.module, "the core Git tab stays")
        assertTrue(CoreModules.all.all { m -> f.statuses.single { it.name == m.manifest.name }.state == ModuleState.LOADED }, "a broken module never stops the others")
        assertTrue(loaded.log.any { it.startsWith("[plugins] Acme_Broken (community): refused") })
    }

    @Test
    fun `under the lockdown no community module loads and the core tabs are unchanged`() {
        root.mkdirs()
        File(root, "modules.json").writeText("""{"modules":{"Acme_NoJar":{"enabled":true}}}""")
        community("Acme_NoJar", manifest("Acme_NoJar"))
        val loaded = load(PluginFolders.LOCKDOWN_ENV to "1", compiled = CoreModules.all)
        assertTrue(loaded.lockdown)
        assertEquals(ModuleState.LOCKED, loaded.factory.statuses.single { it.name == "Acme_NoJar" }.state)
        assertEquals(CoreModules.factory.tabs(TabContainers.CENTER).map { it.id }, loaded.factory.tabs(TabContainers.CENTER).map { it.id })
    }

    @Test
    fun `under the lockdown a module's daemon access is its declared routes AND the lock's`() = runBlocking {
        root.mkdirs()
        File(root, "modules.json").writeText("""{"demoLock":{"allowRoutes":["GET /health"]}}""")
        val probe = AgentHqModule(ModuleManifest("Infantry_Probe", "1.0.0", services = listOf("daemon"), routes = listOf("GET /health", "POST /tickets")))
        fun daemon(vararg env: Pair<String, String>) =
            DesktopPlugins.load(state, env = env.toMap()::get, property = { null }, compiled = listOf(probe), print = {})
                .services("Infantry_Probe").get(Services.DAEMON)!!

        val locked = daemon(PluginFolders.LOCKDOWN_ENV to "1")
        val refused = locked.call("POST /tickets", "{}")
        assertTrue(refused is DaemonReply.Refused && "lockdown" in refused.reason, "$refused")
        assertTrue(locked.call("GET /sessions", null) is DaemonReply.Refused, "never declared")
        assertTrue(locked.call("GET /health", null) !is DaemonReply.Refused, "declared and lock-allowed: sent (no daemon here, so it fails)")
        assertTrue(daemon().call("POST /tickets", "{}") !is DaemonReply.Refused, "without the lock a declared route is sent")
    }

    @Test
    fun `the plugins root can be set by env`() {
        val other = Files.createTempDirectory("plugins-root").toFile()
        assertEquals(other.absolutePath, load(PluginFolders.ROOT_ENV to other.absolutePath).root.absolutePath)
    }

    // PLUGINS-2: the repository's plugins/ (Gradle runs the tests in agenthqUiCore; plugins/ is at the root).
    private val repoPlugins = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "plugins") }.first { File(it, "_template").isDirectory }

    /** The template's sources and module.json, without whatever a build left in it. */
    private fun copyTemplate(to: File) {
        val from = File(repoPlugins, "_template")
        from.walkTopDown().onEnter { it.name != "build" && it.name != ".gradle" }.filter { it.isFile }
            .forEach { it.copyTo(File(to, it.relativeTo(from).path), overwrite = true) }
    }

    @Test
    fun `the loader ignores the starter template, wherever it is copied with its underscore name`() {
        copyTemplate(File(root, "_template"))
        copyTemplate(File(root, "community/_template"))
        File(root, "modules.json").writeText("""{"modules":{"Example_Starter":{"enabled":true}}}""")
        val loaded = load()
        assertTrue(loaded.factory.statuses.none { it.name == "Example_Starter" }, "${loaded.factory.statuses.map { it.name }}")
        // Copied under its module name, it is a module like any other (here refused: the copy has no built jar).
        copyTemplate(File(root, "community/Example_Starter"))
        val copy = load().factory.statuses.single { it.name == "Example_Starter" }
        assertEquals("jar build/libs/Example_Starter.jar not found (build it first)", copy.reason)
    }

    @Test
    fun `Settings changes go through the dependency rules and only an allowed one is written`() {
        root.mkdirs()
        File(root, "modules.json").writeText("""{"modules":{"Acme_Base":{"enabled":false},"Acme_App":{"enabled":true}},"note":"kept"}""")
        community("Acme_Base", manifest("Acme_Base"))
        community("Acme_App", manifest("Acme_App", ""","dependencies":[{"id":"Acme_Base","version":"^1.0"},{"id":"Infantry_Git"}]"""))
        val loaded = load()
        assertEquals("dependency Acme_Base is disabled", loaded.factory.statuses.single { it.name == "Acme_App" }.reason)
        val changes = loaded.factory.changes!!
        val text = { File(root, "modules.json").readText() }

        // Disabling core Infantry_Git while Acme_App (enabled) needs it: refused, nothing written.
        val before = text()
        val refused = changes.request(ModuleRequest("Infantry_Git", enable = false))
        assertTrue(refused is ModuleChange.Refused && refused.dependents == listOf("Acme_App"), refused.message)
        assertEquals(before, text())

        // Enable Acme_Base, then disable it with cascade: Acme_App goes off too; other keys stay.
        assertTrue(changes.request(ModuleRequest("Acme_Base", enable = true)) is ModuleChange.Applied)
        assertEquals(true, PluginFolders.config(root).setting("Acme_Base").enabled)
        val cascade = changes.request(ModuleRequest("Acme_Base", enable = false, cascade = true))
        assertTrue(cascade is ModuleChange.Applied && cascade.changed == listOf("Acme_App", "Acme_Base"), cascade.message)
        assertEquals(false, PluginFolders.config(root).setting("Acme_App").enabled)
        assertTrue("\"note\"" in text())

        // Now Acme_App cannot be enabled until Acme_Base is.
        val again = changes.request(ModuleRequest("Acme_App", enable = true))
        assertTrue(again is ModuleChange.Refused && again.mustEnableFirst == listOf("Acme_Base"), again.message)

        // Under the lockdown Settings changes nothing.
        val locked = load(PluginFolders.LOCKDOWN_ENV to "1").factory.changes!!.request(ModuleRequest("Acme_Base", enable = true))
        assertTrue(locked is ModuleChange.Refused && "lockdown" in locked.message)
    }
}
