package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.executables.ExecutableEnvironment
import com.superiorsoftllc.infantry.executables.ExecutableOverrides
import com.superiorsoftllc.infantry.executables.ExecutableResolver
import com.superiorsoftllc.infantry.executables.HarnessPrefix
import com.superiorsoftllc.infantry.executables.HostOs
import com.superiorsoftllc.infantry.harness.ApiJarTestPlugin
import com.superiorsoftllc.infantry.harness.HarnessHostServices
import com.superiorsoftllc.infantry.harness.HarnessRegistry
import com.superiorsoftllc.infantry.harness.TestHarnessJars
import com.superiorsoftllc.infantry.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.infantry.harness.install.HarnessInstaller
import com.superiorsoftllc.infantry.harness.install.HarnessProcessRunner
import com.superiorsoftllc.infantry.harness.install.ManifestHarnessCli
import com.superiorsoftllc.infantry.harness.install.ProcessResult
import com.superiorsoftllc.infantry.plugin.api.HarnessCliDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ModuleSetting
import com.superiorsoftllc.infantry.plugin.api.ModulesConfig
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.ModuleCandidate
import com.superiorsoftllc.infantry.plugin.host.ModuleResolver
import com.superiorsoftllc.infantry.plugin.host.PluginJson
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HARNESS-PLUGIN-4 H5a (docs/HARNESS-PLUGINS.md 3.8): the harness installer installs what a LOADED harness module declares
 * in `harness.cli.install`, read from the manifest, through a FAKE npm (no process, no network). The `cli` block is the one
 * the example module plugins/examples/Acme_EchoHarness ships. A community module without the cli grants is refused, and
 * then its CLI is neither listed nor installable.
 */
class ManifestHarnessInstallTest {
    private val module = ApiJarTestPlugin.PLUGIN_ID
    private val root: File = Files.createTempDirectory("manifest-install").toFile()
    private val prefix: File = File(root, "harness-tools")
    private val logs = ArrayList<String>()

    /** The `cli` block of the shipped example module, verbatim. */
    private val echoCli: String = run {
        val manifest = listOf(File("../../plugins/examples/Acme_EchoHarness/module.json"), File("plugins/examples/Acme_EchoHarness/module.json"))
            .first { it.isFile }.readText()
        PluginJson.parseToJsonElement(manifest).jsonObject["harness"]!!.jsonObject["cli"]!!.toString()
    }

    private class FakeEnv(val files: MutableSet<String>, override val os: HostOs = HostOs.LINUX) : ExecutableEnvironment {
        override fun env(name: String) = if (name == "PATH") "/usr/bin" else null
        override fun home() = "/home/u"
        override fun exists(path: String) = path in files
        override fun isDirectory(path: String) = false
        override fun isExecutable(path: String) = path in files
        override fun isAbsolute(path: String) = true
    }

    /** npm `install -g --prefix <p> <pkg>@<v>` drops `<p>/bin/acme-echo`; `--version` of it answers 1.2.3. */
    private inner class FakeNpm(private val env: FakeEnv) : HarnessProcessRunner {
        val calls = ArrayList<List<String>>()
        override fun run(argv: List<String>, env: Map<String, String>, timeoutMillis: Long, onLine: (String) -> Unit): ProcessResult {
            calls += argv
            return when {
                argv.last() == "--version" -> if (argv.first() in this.env.files) ProcessResult(0, "acme-echo 1.2.3") else ProcessResult(1, "no")
                argv.getOrNull(1) == "install" -> {
                    this.env.files += this.env.join(prefix.path, "bin", "acme-echo")
                    ProcessResult(0, "added 1 package")
                }
                else -> ProcessResult(1, "unexpected $argv")
            }
        }
    }

    private fun manifestJson(cli: String) = """{"name":"$module","version":"1.0.0","license":"NOASSERTION","author":"test",
        "harness":{"jar":"harness/test-harness.jar","contract":"$HARNESS_API_VERSION","ids":["${ApiJarTestPlugin.HARNESS_ID}"],"cli":$cli}}"""

    private fun candidate(tier: Tier, cli: String = echoCli): ModuleCandidate {
        val json = manifestJson(cli)
        val dir = File(root, "${tier.name.lowercase()}/$module").apply { deleteRecursively(); mkdirs() }
        File(dir, "module.json").writeText(json)
        TestHarnessJars.pack(File(dir, "harness/test-harness.jar"), ApiJarTestPlugin::class.java)
        return ModuleCandidate(module, tier, dir.absolutePath, PluginJson.decodeFromString(ModuleManifest.serializer(), json))
    }

    private class Fixture(val modules: HarnessModules, val installer: HarnessInstaller, val npm: HarnessProcessRunner, val resolver: ExecutableResolver)

    private fun fixture(): Fixture {
        val env = FakeEnv(mutableSetOf("/usr/bin/npm", "/usr/bin/node"))
        val npm = FakeNpm(env)
        val modules = HarnessModules(HarnessRegistry(onFailure = { logs += it.message }), DaemonPluginHost.Trust(emptyList(), true, null), HarnessHostServices.NONE, root, log = { logs += it })
        val resolver = ExecutableResolver(env, ExecutableOverrides.NONE, prefix.path)
        val installer = HarnessInstaller(prefix, resolver, env, ExecutableOverrides.NONE, npm, fromModules = modules::installables)
        return Fixture(modules, installer, npm, resolver)
    }

    private fun Fixture.sync(c: ModuleCandidate, vararg allow: String) {
        val cfg = ModulesConfig(modules = mapOf(module to ModuleSetting(enabled = true, allow = allow.toList())))
        modules.sync(ModuleResolver.resolve(listOf(c), cfg), cfg)
    }

    @AfterTest
    fun clean() {
        root.deleteRecursively()
    }

    @Test
    fun `the echo module's cli block is listed after the built-ins and installs through npm into the app prefix`() {
        val f = fixture()
        f.sync(candidate(Tier.OFFICIAL))
        assertEquals("loaded", f.modules.status(module), "logs: $logs")

        val before = f.installer.status()
        assertEquals(listOf("claude", "opencode", "pi", "acme-echo"), before.harnesses.map { it.id }, "built-ins unchanged, then the module's")
        val echo = before.harnesses.last()
        assertEquals("missing", echo.state)
        assertEquals("Acme Echo CLI", echo.label)
        assertEquals("@acme/echo-harness-cli", echo.npmPackage)

        val result = f.installer.install(listOf("acme-echo"), update = false)
        assertEquals(listOf("installed"), result.outcomes.map { it.result }, "outcomes: ${result.outcomes}")
        assertEquals("1.2.3", result.outcomes.single().version)
        val install = (f.npm as FakeNpm).calls.single { it.getOrNull(1) == "install" }
        assertEquals(prefix.path, install[install.indexOf("--prefix") + 1], "into the app prefix only")
        assertEquals("@acme/echo-harness-cli@latest", install.last(), "the package comes from the manifest")
        val after = result.report.harnesses.single { it.id == "acme-echo" }
        assertEquals("installed", after.state)
        assertTrue(after.managed, "found inside the app prefix")
        // The harness's own CLI lookup (HarnessHost.resolveCli in the daemon) finds the installed file through the same tool.
        assertEquals(after.path, f.resolver.resolve(assertNotNull(f.modules.cliTool("acme-echo"))).path)
    }

    @Test
    fun `an ungranted community cli and install is refused, so the installer neither lists nor installs it`() {
        val f = fixture()
        f.sync(candidate(Tier.COMMUNITY), "harness.code")
        assertEquals("refused: missing grant 'harness.cli' in modules.json", f.modules.status(module))
        f.sync(candidate(Tier.COMMUNITY), "harness.code", "harness.cli")
        assertEquals("refused: missing grant 'harness.cli.install' in modules.json", f.modules.status(module), "installing runs npm scripts: a grant of its own")
        assertEquals(listOf("claude", "opencode", "pi"), f.installer.status().harnesses.map { it.id })
        assertFailsWith<IllegalArgumentException> { f.installer.install(listOf("acme-echo"), update = false) }
        assertTrue((f.npm as FakeNpm).calls.none { it.getOrNull(1) == "install" }, "npm never ran")
        assertNull(f.modules.cliTool("acme-echo"))

        f.sync(candidate(Tier.COMMUNITY), "harness.code", "harness.cli", "harness.cli.install")
        assertEquals("loaded", f.modules.status(module), "logs: $logs")
        assertEquals("acme-echo", f.installer.status().harnesses.last().id)
    }

    @Test
    fun `a module that is unloaded takes its cli out of the installer`() {
        val f = fixture()
        f.sync(candidate(Tier.OFFICIAL))
        assertEquals("acme-echo", f.installer.status().harnesses.last().id)
        f.modules.unload(module)
        assertEquals(listOf("claude", "opencode", "pi"), f.installer.status().harnesses.map { it.id })
    }

    @Test
    fun `a cli block that could reach a flag, a path or a built-in is refused before the jar is opened`() {
        val cases = mapOf(
            """{"id":"acme-echo","install":{"npm":"--registry=http://evil"}}""" to "is not an npm package name",
            """{"id":"acme-echo","command":"/bin/sh"}""" to "must be a bare command name",
            """{"id":"acme-echo","install":{"npm":"@acme/x","bin":"../../evil.js"}}""" to "must be a relative path inside the package",
            """{"id":"git"}""" to "is a built-in CLI of the daemon",
            """{"id":"claude","install":{"npm":"@acme/x"}}""" to "is a built-in CLI of the daemon",
        )
        for ((cli, why) in cases) {
            val f = fixture()
            f.sync(candidate(Tier.OFFICIAL, cli))
            val status = f.modules.status(module).orEmpty()
            assertTrue(status.startsWith("refused: ") && why in status, "$cli -> $status")
            assertEquals(listOf("claude", "opencode", "pi"), f.installer.status().harnesses.map { it.id })
        }
    }

    @Test
    fun `on Windows the manifest tool is found at its npm bin inside the prefix, and the built-in table is untouched`() {
        val e = FakeEnv(mutableSetOf(), HostOs.WINDOWS)
        val tool = ManifestHarnessCli.tool(PluginJson.decodeFromString(HarnessCliDeclaration.serializer(), echoCli))
        assertEquals(
            listOf(e.join("C:\\t", "node_modules/@acme/echo-harness-cli/bin/acme-echo.js"), e.join("C:\\t", "acme-echo.cmd")),
            HarnessPrefix.locations(e, tool, "C:\\t"),
        )
        assertEquals(setOf(".js"), tool.extraExtensions, "a .js bin is launchable as such")
        val pi = com.superiorsoftllc.infantry.executables.CliTools.PI
        assertEquals(listOf(e.join("C:\\t", "node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js"), e.join("C:\\t", "pi.cmd")), HarnessPrefix.locations(e, pi, "C:\\t"), "built-ins carry their own prefixBin")
    }
}
