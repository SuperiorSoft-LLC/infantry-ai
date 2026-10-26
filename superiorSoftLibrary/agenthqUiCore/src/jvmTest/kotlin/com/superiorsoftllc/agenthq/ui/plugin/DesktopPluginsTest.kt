package com.superiorsoftllc.agenthq.ui.plugin

import com.superiorsoftllc.agenthq.plugin.api.DaemonReply
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.Services
import com.superiorsoftllc.agenthq.plugin.api.Tier
import kotlinx.coroutines.runBlocking
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

    private fun load(vararg env: Pair<String, String>) = DesktopPlugins.load(state, env = env.toMap()::get, property = { null }, print = {})

    private fun manifest(name: String, extra: String = "") =
        """{"name":"$name","version":"1.0.0","author":"Acme","license":"NOASSERTION","entry":"com.acme.Nope","jar":"build/libs/$name.jar"$extra}"""

    @Test
    fun `with no plugins root the compiled core modules load, unchanged`() {
        val loaded = load()
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
        community("Acme_Needy", manifest("Acme_Needy", ""","sequence":["Acme_Missing"]"""))
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
    fun `under the demo lock no community module loads and the core tabs are unchanged`() {
        root.mkdirs()
        File(root, "modules.json").writeText("""{"modules":{"Acme_NoJar":{"enabled":true}}}""")
        community("Acme_NoJar", manifest("Acme_NoJar"))
        val loaded = load(PluginFolders.DEMO_LOCK_ENV to "1")
        assertTrue(loaded.demoLock)
        assertEquals(ModuleState.LOCKED, loaded.factory.statuses.single { it.name == "Acme_NoJar" }.state)
        assertEquals(CoreModules.factory.tabs(TabContainers.CENTER).map { it.id }, loaded.factory.tabs(TabContainers.CENTER).map { it.id })
    }

    @Test
    fun `under the demo lock a module's daemon access is its declared routes AND the lock's`() = runBlocking {
        root.mkdirs()
        File(root, "modules.json").writeText("""{"demoLock":{"allowRoutes":["GET /health"]}}""")
        val probe = AgentHqModule(ModuleManifest("Infantry_Probe", "1.0.0", services = listOf("daemon"), routes = listOf("GET /health", "POST /tickets")))
        fun daemon(vararg env: Pair<String, String>) =
            DesktopPlugins.load(state, env = env.toMap()::get, property = { null }, compiled = listOf(probe), print = {})
                .services("Infantry_Probe").get(Services.DAEMON)!!

        val locked = daemon(PluginFolders.DEMO_LOCK_ENV to "1")
        val refused = locked.call("POST /tickets", "{}")
        assertTrue(refused is DaemonReply.Refused && "demo lock" in refused.reason, "$refused")
        assertTrue(locked.call("GET /sessions", null) is DaemonReply.Refused, "never declared")
        assertTrue(locked.call("GET /health", null) !is DaemonReply.Refused, "declared and lock-allowed: sent (no daemon here, so it fails)")
        assertTrue(daemon().call("POST /tickets", "{}") !is DaemonReply.Refused, "without the lock a declared route is sent")
    }

    @Test
    fun `the plugins root can be set by env`() {
        val other = Files.createTempDirectory("plugins-root").toFile()
        assertEquals(other.absolutePath, load(PluginFolders.ROOT_ENV to other.absolutePath).root.absolutePath)
    }
}
