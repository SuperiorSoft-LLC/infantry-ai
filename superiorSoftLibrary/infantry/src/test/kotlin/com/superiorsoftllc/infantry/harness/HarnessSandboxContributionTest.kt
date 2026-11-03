package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.infantry.harness.api.HarnessCapability
import com.superiorsoftllc.infantry.harness.api.HarnessProcess
import com.superiorsoftllc.infantry.harness.api.HarnessRegistrar
import com.superiorsoftllc.infantry.harness.api.HarnessRequest
import com.superiorsoftllc.infantry.harness.api.SandboxContribution
import com.superiorsoftllc.infantry.harness.api.SandboxRoots
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import com.superiorsoftllc.infantry.harness.api.Harness as ApiHarness
import com.superiorsoftllc.infantry.harness.api.HarnessEvent as ApiEvent
import com.superiorsoftllc.infantry.harness.api.HarnessPlugin as ApiPlugin
import com.superiorsoftllc.infantry.harness.api.HarnessTranslator as ApiTranslator

/** H5f: API 2.7.0 `Harness.sandbox()` adds dirs only under its data folder and the worktree, behind `harness.hook.sandbox`. */
class HarnessSandboxContributionTest {
    private val failures = CopyOnWriteArrayList<PluginFailure>()
    private val state = File.createTempFile("h5f", "").also { it.delete(); it.mkdirs() }
    private val work = File(state, "wt").also { it.mkdirs() }
    private val mine = File(state, "plugin-data/Acme_Box")

    private fun registry(
        sandbox: (SandboxRoots) -> SandboxContribution?,
        hooks: Set<String> = setOf("sandbox"),
        granted: Boolean = true,
    ): HarnessRegistry {
        val plugin = object : ApiPlugin {
            override val pluginId = "Acme_Box"
            override val contractVersion = HARNESS_API_VERSION
            override fun register(registrar: HarnessRegistrar) = registrar.register(object : ApiHarness {
                override val id = "boxy"
                override val capabilities = emptySet<HarnessCapability>()
                override fun process(request: HarnessRequest) = HarnessProcess(listOf("echo"))
                override fun newTranslator() = object : ApiTranslator {
                    override fun translate(line: String) = emptyList<ApiEvent>()
                    override fun terminal(exitCode: Int, stderrTail: String): ApiEvent = ApiEvent.Completion()
                }
                override fun sandbox(dirs: SandboxRoots) = sandbox(dirs)
            })
        }
        val registry = HarnessRegistry(onFailure = { failures += it })
        val host = ModuleHarnessHost("Acme_Box", state, HarnessHostDeclaration(hooks = hooks), { granted || !it.startsWith("harness.hook.") }, HarnessHostServices(), {})
        assertIs<PluginOutcome.Accepted>(registry.register(ApiHarnessPluginBridge(plugin, host, onFailure = { failures += it })))
        return registry
    }

    @Test fun `dirs under the data folder and worktree are allowed`() {
        val s = registry({ SandboxContribution(listOf(File(it.dataDir, "r")), listOf(File(it.worktree, "out")), setOf("TOK")) }).sandboxFor("boxy", work)!!
        assertEquals(listOf(File(mine, "r").canonicalFile), s.readDirs)
        assertEquals(listOf(File(work, "out").canonicalFile), s.writeDirs)
        assertEquals(setOf("TOK"), s.envNames)
    }

    @Test fun `a dir outside the allowed roots or a bad env name refuses the whole contribution`() {
        assertNull(registry({ SandboxContribution(listOf(File(it.dataDir, "r")), listOf(File(state, "elsewhere"))) }).sandboxFor("boxy", work))
        assertNull(registry({ SandboxContribution(listOf(File(it.dataDir, "../../x"))) }).sandboxFor("boxy", work))
        assertNull(registry({ SandboxContribution(envNames = setOf("INFANTRY_TOKEN")) }).sandboxFor("boxy", work))
    }

    @Test fun `ungranted or undeclared gives nothing`() {
        val c = { r: SandboxRoots -> SandboxContribution(listOf(File(r.dataDir, "r"))) }
        assertNull(registry(c, granted = false).sandboxFor("boxy", work))
        assertNull(registry(c, hooks = emptySet()).sandboxFor("boxy", work))
    }

    @Test fun `a throwing sandbox is isolated and attributed`() {
        assertNull(registry({ error("boom") }).sandboxFor("boxy", work))
        assertEquals("Acme_Box", failures.single().plugin)
    }
}
