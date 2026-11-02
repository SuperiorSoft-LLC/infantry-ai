package com.superiorsoftllc.agenthq.harness

import com.superiorsoftllc.agenthq.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.agenthq.harness.api.HarnessCapability
import com.superiorsoftllc.agenthq.harness.api.HarnessProcess
import com.superiorsoftllc.agenthq.harness.api.HarnessRegistrar
import com.superiorsoftllc.agenthq.harness.api.HarnessRequest
import com.superiorsoftllc.agenthq.harness.api.WatchContribution
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import com.superiorsoftllc.agenthq.harness.api.Harness as ApiHarness
import com.superiorsoftllc.agenthq.harness.api.HarnessEvent as ApiEvent
import com.superiorsoftllc.agenthq.harness.api.HarnessPlugin as ApiPlugin
import com.superiorsoftllc.agenthq.harness.api.HarnessTranslator as ApiTranslator

/** H5e: API 2.6.0 `Harness.watch()` gets the host's launcher, writes only in its own data folder, behind `harness.hook.watch`. */
class HarnessWatchContributionTest {
    private val failures = CopyOnWriteArrayList<PluginFailure>()
    private val state = File.createTempFile("h5e", "").also { it.delete(); it.mkdirs() }
    private val on = mapOf("AGENTHQ_WATCH" to "on")
    private val base = ProcessSpec(listOf("echo"), null, emptyMap())

    private fun registry(
        watch: (Map<String, String>, String) -> WatchContribution?,
        hooks: Set<String> = setOf("watch"),
        granted: Boolean = true,
        launcher: String? = "L",
    ): HarnessRegistry {
        val plugin = object : ApiPlugin {
            override val pluginId = "Acme_Watch"
            override val contractVersion = HARNESS_API_VERSION
            override fun register(registrar: HarnessRegistrar) = registrar.register(object : ApiHarness {
                override val id = "watchy"
                override val capabilities = emptySet<HarnessCapability>()
                override fun process(request: HarnessRequest) = HarnessProcess(listOf("echo"))
                override fun newTranslator() = object : ApiTranslator {
                    override fun translate(line: String) = emptyList<ApiEvent>()
                    override fun terminal(exitCode: Int, stderrTail: String): ApiEvent = ApiEvent.Completion()
                }
                override fun watch(env: Map<String, String>, launcher: String) = watch(env, launcher)
            })
        }
        val registry = HarnessRegistry(onFailure = { failures += it })
        val host = ModuleHarnessHost("Acme_Watch", state, HarnessHostDeclaration(hooks = hooks), { granted || !it.startsWith("harness.hook.") }, HarnessHostServices(watchLauncher = { launcher }), {})
        assertIs<PluginOutcome.Accepted>(registry.register(ApiHarnessPluginBridge(plugin, host, onFailure = { failures += it })))
        return registry
    }

    @Test fun `a watched run gets the launcher on its argv and env`() {
        val s = registry({ _, l -> WatchContribution(listOf("--runner", l), mapOf("W" to l)) }).applyWatch("watchy", on, base)
        assertEquals(listOf("echo", "--runner", "L"), s.command); assertEquals("L", s.env["W"])
    }

    @Test fun `files land in the data folder and a path leaving it is refused`() {
        registry({ _, _ -> WatchContribution(files = mapOf("hook/a.js" to "x", "../../evil.txt" to "y")) }).applyWatch("watchy", on, base)
        assertEquals("x", File(state, "plugin-data/Acme_Watch/hook/a.js").readText())
        assertFalse(File(state, "evil.txt").exists()); assertFalse(File(state, "plugin-data/evil.txt").exists())
    }

    @Test fun `ungranted, undeclared or watch-off gives the spec unchanged`() {
        val c = { _: Map<String, String>, _: String -> WatchContribution(listOf("--x")) }
        assertEquals(base, registry(c, granted = false).applyWatch("watchy", on, base))
        assertEquals(base, registry(c, hooks = emptySet()).applyWatch("watchy", on, base))
        assertEquals(base, registry(c, launcher = null).applyWatch("watchy", emptyMap(), base))
    }

    @Test fun `a throwing watch is isolated and attributed`() {
        assertEquals(base, registry({ _, _ -> error("boom") }).applyWatch("watchy", on, base))
        assertEquals("Acme_Watch", failures.single().plugin)
    }
}
