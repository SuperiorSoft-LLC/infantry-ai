package com.superiorsoftllc.agenthq.daemon.plugins

import com.superiorsoftllc.agenthq.harness.ApiCliHarness
import com.superiorsoftllc.agenthq.harness.ApiJarTestPlugin
import com.superiorsoftllc.agenthq.harness.HarnessHostServices
import com.superiorsoftllc.agenthq.harness.HarnessRegistry
import com.superiorsoftllc.agenthq.harness.TestHarnessJars
import com.superiorsoftllc.agenthq.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ModuleSetting
import com.superiorsoftllc.agenthq.plugin.api.ModulesConfig
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.ModuleCandidate
import com.superiorsoftllc.agenthq.plugin.host.ModuleResolver
import com.superiorsoftllc.agenthq.plugin.host.PluginJson
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HARNESS-PLUGIN-3 H4: module.json `harness` block -> trust and grants -> the ONE registry. Default deny: a module that fails
 * a rule is refused before its jar is opened, never the daemon. Pins the threat list in docs/HARNESS-PLUGINS.md section 4.
 */
class HarnessModulesTest {
    private val module = ApiJarTestPlugin.PLUGIN_ID
    private val harnessId = ApiJarTestPlugin.HARNESS_ID
    private val logs = CopyOnWriteArrayList<String>()
    private val root: File = Files.createTempDirectory("harness-modules").toFile().apply { deleteOnExit() }

    private fun manifestJson(
        ids: String = "[\"$harnessId\"]", contract: String = HARNESS_API_VERSION, hooks: String = "[]",
        cli: String = "", extra: String = "",
    ) = """{"name":"$module","version":"1.0.0","license":"NOASSERTION","author":"test",
        "harness":{"jar":"harness/test-harness.jar","contract":"$contract","ids":$ids,"hooks":$hooks,"aliases":{"api-echo":"$harnessId"}$cli$extra}}"""

    /** A module folder under `<root>/<tier>/` with the test plugin packed into its harness jar. */
    private fun candidate(tier: Tier, json: String = manifestJson(), withJar: Boolean = true): ModuleCandidate {
        val dir = File(root, "${tier.name.lowercase()}/$module").apply { deleteRecursively(); mkdirs() }
        File(dir, "module.json").writeText(json)
        if (withJar) TestHarnessJars.pack(File(dir, "harness/test-harness.jar"), ApiJarTestPlugin::class.java)
        return ModuleCandidate(module, tier, dir.absolutePath, PluginJson.decodeFromString(ModuleManifest.serializer(), json))
    }

    private fun config(enabled: Boolean? = null, vararg allow: String) =
        ModulesConfig(modules = mapOf(module to ModuleSetting(enabled = enabled ?: true, allow = allow.toList())))

    private class Fixture(val registry: HarnessRegistry, val modules: HarnessModules)

    private fun fixture(devTrust: Boolean = true, reserved: Set<String> = emptySet()): Fixture {
        val registry = HarnessRegistry(onFailure = { logs += it.message })
        return Fixture(registry, HarnessModules(registry, DaemonPluginHost.Trust(emptyList(), devTrust, null), HarnessHostServices.NONE, root, { reserved }, { logs += it }))
    }

    private fun Fixture.sync(c: ModuleCandidate, cfg: ModulesConfig) = modules.sync(ModuleResolver.resolve(listOf(c), cfg), cfg)

    @Test fun `module json carries the harness block through the resolver`() {
        val c = candidate(Tier.OFFICIAL, manifestJson(cli = ""","cli":{"id":"echo","command":"echo","install":{"npm":"@acme/echo","bin":"cli.js"}}""", extra = ""","icon":"icons/echo.svg","settings":["echo.extra"]"""))
        val loaded = ModuleResolver.resolve(listOf(c), config()).loaded.single()
        val h = loaded.manifest!!.harness!!
        assertEquals(listOf(harnessId), h.ids)
        assertEquals(HARNESS_API_VERSION, h.contract)
        assertEquals("@acme/echo", h.cli!!.install!!.npm)
        assertEquals(mapOf("api-echo" to harnessId), h.aliases)
        assertEquals(listOf("echo.extra"), h.settings)
        assertEquals("icons/echo.svg", h.icon)
    }

    @Test fun `a trusted module loads its harness into the registry, with its aliases`() {
        val f = fixture()
        f.sync(candidate(Tier.OFFICIAL), config())
        assertEquals("loaded", f.modules.status(module), "logs: $logs")
        assertIs<ApiCliHarness>(f.registry.resolve(harnessId))
        assertEquals(harnessId, f.modules.alias("api-echo"))
        assertEquals(listOf(harnessId), f.modules.idsOf(module))
    }

    @Test fun `a community harness without the harness code grant is refused and never loaded`() {
        val f = fixture()
        f.sync(candidate(Tier.COMMUNITY), config(true))
        assertEquals("refused: community harness code needs the 'harness.code' grant in modules.json", f.modules.status(module))
        assertNull(f.registry.resolve(harnessId))
        assertTrue(f.registry.pluginIds().isEmpty())
    }

    @Test fun `a community harness needs a grant for each hook it declares, and its cli`() {
        val f = fixture()
        f.sync(candidate(Tier.COMMUNITY, manifestJson(hooks = "[\"watch\"]")), config(true, "harness.code"))
        assertEquals("refused: missing grant 'harness.hook.watch' in modules.json", f.modules.status(module))
        f.sync(candidate(Tier.COMMUNITY, manifestJson(cli = ""","cli":{"id":"echo"}""")), config(true, "harness.code"))
        assertEquals("refused: missing grant 'harness.cli' in modules.json", f.modules.status(module))
        assertNull(f.registry.resolve(harnessId))
        f.sync(candidate(Tier.COMMUNITY, manifestJson(hooks = "[\"watch\"]")), config(true, "harness.code", "harness.hook.watch"))
        assertEquals("loaded", f.modules.status(module), "logs: $logs")
    }

    @Test fun `a community harness may not shadow an installed id`() {
        val f = fixture(reserved = setOf(harnessId))
        f.sync(candidate(Tier.COMMUNITY), config(true, "harness.code"))
        assertTrue(f.modules.status(module)!!.contains("community harness id '$harnessId' is already taken"), f.modules.status(module))
        assertNull(f.registry.resolve(harnessId))
    }

    @Test fun `a community module stays off until enabled`() {
        val f = fixture()
        f.sync(candidate(Tier.COMMUNITY), ModulesConfig(modules = mapOf(module to ModuleSetting(allow = listOf("harness.code")))))
        assertNull(f.registry.resolve(harnessId))
        assertTrue(f.modules.status(module)!!.startsWith("refused: module"), f.modules.status(module))
    }

    @Test fun `an unknown contract is refused with a clear message before the jar is opened`() {
        val f = fixture()
        f.sync(candidate(Tier.OFFICIAL, manifestJson(contract = "3.0.0"), withJar = false), config())
        assertEquals("refused: harness.contract: implements harness API 3.0.0; this daemon implements $HARNESS_API_VERSION (major 2). Rebuild the plugin against harness API 2.x.", f.modules.status(module))
        f.sync(candidate(Tier.OFFICIAL, manifestJson(contract = "2.0.1")), config())
        assertEquals("refused: the plugin implements harness API '$HARNESS_API_VERSION' but module.json harness.contract says '2.0.1'", f.modules.status(module))
        assertNull(f.registry.resolve(harnessId))
    }

    @Test fun `a plugin registering ids the manifest does not declare fails and registers nothing`() {
        val f = fixture()
        f.sync(candidate(Tier.OFFICIAL, manifestJson(ids = "[\"other-id\"]").replace("\"aliases\":{\"api-echo\":\"$harnessId\"}", "\"aliases\":{}")), config())
        val s = f.modules.status(module)!!
        assertTrue(s.startsWith("failed: plugin '$module' failed during register") && "module.json harness.ids does not declare" in s, s)
        assertTrue(f.registry.harnessIds().isEmpty())
    }

    @Test fun `an official jar that is neither bundled nor signed is refused`() {
        val f = fixture(devTrust = false)
        f.sync(candidate(Tier.OFFICIAL), config())
        assertTrue(f.modules.status(module)!!.startsWith("refused: official harness jar refused"), f.modules.status(module))
        assertNull(f.registry.resolve(harnessId))
    }

    @Test fun `a missing jar is refused, never fatal`() {
        val f = fixture()
        f.sync(candidate(Tier.OFFICIAL, withJar = false), config())
        assertTrue(f.modules.status(module)!!.startsWith("refused: jar missing"), f.modules.status(module))
    }

    @Test fun `disabling the module unloads its harness and enabling it loads it again`() {
        val f = fixture()
        val c = candidate(Tier.OFFICIAL)
        f.sync(c, config())
        val first = f.registry.resolve(harnessId)
        assertIs<ApiCliHarness>(first)
        f.sync(c, config(enabled = false))
        assertNull(f.registry.resolve(harnessId))
        assertNull(f.modules.alias("api-echo"))
        assertTrue(f.modules.status(module)!!.startsWith("refused:"), f.modules.status(module))
        f.sync(c, config())
        val second = assertIs<ApiCliHarness>(f.registry.resolve(harnessId))
        assertTrue(first !== second, "a fresh plugin instance after the reload")
        f.modules.close()
        assertNull(f.registry.resolve(harnessId))
    }
}
