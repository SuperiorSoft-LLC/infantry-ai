package com.superiorsoftllc.agenthq.harness

import com.superiorsoftllc.agenthq.harness.api.HarnessCapability
import com.superiorsoftllc.agenthq.harness.api.HarnessProcess
import com.superiorsoftllc.agenthq.harness.api.HarnessRegistrar
import com.superiorsoftllc.agenthq.harness.api.HarnessRequest
import com.superiorsoftllc.agenthq.harness.api.HarnessUsageReading
import com.superiorsoftllc.agenthq.harness.api.HarnessUsageSource
import com.superiorsoftllc.agenthq.harness.api.HarnessUsageWindow
import com.superiorsoftllc.agenthq.harness.api.HarnessEvent as ApiEvent
import com.superiorsoftllc.agenthq.harness.api.HarnessTranslator as ApiTranslator
import com.superiorsoftllc.agenthq.harness.api.Harness as ApiHarness
import com.superiorsoftllc.agenthq.harness.api.HarnessPlugin as ApiPlugin
import com.superiorsoftllc.agenthq.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.agenthq.usage.LimitReason
import com.superiorsoftllc.agenthq.usage.LimitService
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** H5c: API 2.4.0 `Harness.usageSource()` reaches the usage frame, behind the `harness.hook.usage` grant, isolated. */
class HarnessUsageSourceTest {
    private val failures = CopyOnWriteArrayList<PluginFailure>()

    private fun registryWith(source: HarnessUsageSource?, hooks: Set<String> = setOf("usage"), granted: Boolean = true): HarnessRegistry {
        val plugin = object : ApiPlugin {
            override val pluginId = "Acme_Quota"
            override val contractVersion = HARNESS_API_VERSION
            override fun register(registrar: HarnessRegistrar) = registrar.register(object : ApiHarness {
                override val id = "quota"
                override val capabilities = emptySet<HarnessCapability>()
                override fun process(request: HarnessRequest) = HarnessProcess(listOf("x"))
                override fun newTranslator() = object : ApiTranslator {
                    override fun translate(line: String) = emptyList<ApiEvent>()
                    override fun terminal(exitCode: Int, stderrTail: String): ApiEvent = ApiEvent.Completion()
                }
                override fun usageSource() = source
            })
        }
        val registry = HarnessRegistry(onFailure = { failures += it })
        val host = ModuleHarnessHost("Acme_Quota", File.createTempFile("h5c", "").also { it.delete(); it.mkdirs() }, HarnessHostDeclaration(hooks = hooks), { granted || !it.startsWith("harness.hook.") }, HarnessHostServices.NONE, {})
        val bridge = ApiHarnessPluginBridge(plugin, host, onFailure = { failures += it })
        assertIs<PluginOutcome.Accepted>(registry.register(bridge))
        return registry
    }

    private val ok = HarnessUsageSource { HarnessUsageReading(listOf(HarnessUsageWindow("Monthly", 43_200, 12.5, System.currentTimeMillis() + 60_000))) }

    private fun service(r: HarnessRegistry) = LimitService(emptyList(), liveEnabled = { true }, extraSources = { r.limitSources() })

    @Test fun `the module's source shows in the usage frame tagged by harness id`() {
        val rows = service(registryWith(ok)).snapshot(force = true)
        val row = rows.single()
        assertEquals("quota", row.provider)
        assertEquals("ok", row.status)
        assertEquals(12.5, row.windows.single().percent)
        assertEquals("quota", row.windows.single().provider)
    }

    @Test fun `ungranted or undeclared usage hook is refused and shows nothing`() {
        assertTrue(registryWith(ok, granted = false).limitSources().isEmpty())
        assertTrue(registryWith(ok, hooks = emptySet()).limitSources().isEmpty())
    }

    @Test fun `a harness with no source contributes none`() {
        assertTrue(registryWith(null).limitSources().isEmpty())
    }

    @Test fun `a throwing source is isolated and attributed`() {
        val rows = service(registryWith(HarnessUsageSource { error("boom") })).snapshot(force = true)
        assertEquals(LimitReason.SOURCE_FAILED, rows.single().reason)
        assertEquals("Acme_Quota", failures.single().plugin)
    }

    @Test fun `a slow source is cut by the timeout and attributed`() {
        val src = PluginLimitSource("quota", "Acme_Quota", HarnessUsageSource { Thread.sleep(5_000); HarnessUsageReading(emptyList()) }, 200, { failures += it })
        val row = src.read(0L)
        assertEquals(LimitReason.SOURCE_FAILED, row.reason)
        assertTrue(failures.any { it.plugin == "Acme_Quota" && "timed out" in it.message })
    }

    @Test fun `an empty reading is unknown never zero percent`() {
        val rows = service(registryWith(HarnessUsageSource { HarnessUsageReading(emptyList()) })).snapshot(force = true)
        assertEquals("unknown", rows.single().status)
        assertNull(rows.single().windows.firstOrNull())
    }
}
