// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.infantry.harness.api.HarnessCapability
import com.superiorsoftllc.infantry.harness.api.HarnessProcess
import com.superiorsoftllc.infantry.harness.api.HarnessRegistrar
import com.superiorsoftllc.infantry.harness.api.HarnessRequest
import com.superiorsoftllc.infantry.harness.api.HarnessStats
import com.superiorsoftllc.infantry.harness.api.HarnessStatsRow
import com.superiorsoftllc.infantry.stats.SessionStatsService
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

/** H5d: API 2.5.0 `Harness.stats()` rows reach SessionStatsService with no harness branch, behind `harness.hook.stats`. */
class HarnessStatsSourceTest {
    private val failures = CopyOnWriteArrayList<PluginFailure>()

    private fun registryWith(stats: HarnessStats?, hooks: Set<String> = setOf("stats"), granted: Boolean = true): HarnessRegistry {
        val plugin = object : ApiPlugin {
            override val pluginId = "Acme_Stats"
            override val contractVersion = HARNESS_API_VERSION
            override fun register(registrar: HarnessRegistrar) = registrar.register(object : ApiHarness {
                override val id = "statsy"
                override val capabilities = emptySet<HarnessCapability>()
                override fun process(request: HarnessRequest) = HarnessProcess(listOf("x"))
                override fun newTranslator() = object : ApiTranslator {
                    override fun translate(line: String) = emptyList<ApiEvent>()
                    override fun terminal(exitCode: Int, stderrTail: String): ApiEvent = ApiEvent.Completion()
                }
                override fun stats() = stats
            })
        }
        val registry = HarnessRegistry(onFailure = { failures += it })
        val host = ModuleHarnessHost("Acme_Stats", File.createTempFile("h5d", "").also { it.delete(); it.mkdirs() }, HarnessHostDeclaration(hooks = hooks), { granted || !it.startsWith("harness.hook.") }, HarnessHostServices.NONE, {})
        assertIs<PluginOutcome.Accepted>(registry.register(ApiHarnessPluginBridge(plugin, host, onFailure = { failures += it })))
        return registry
    }

    private fun service(r: HarnessRegistry) = SessionStatsService(File(System.getProperty("java.io.tmpdir"))).apply { pluginStats = r::statsSource }

    @Test fun `a module's stats row is read with no harness branch`() {
        val s = service(registryWith(HarnessStats { HarnessStatsRow(startedAt = 1000, endedAt = 4000, userPrompts = 2, billedTokens = 55, models = listOf("m")) }))
            .read("s1", "statsy", "abc", "idle")
        assertEquals(3000L, s.durationMs); assertEquals(2, s.userPrompts); assertEquals(55L, s.billedTokens); assertEquals(listOf("m"), s.models)
    }

    @Test fun `ungranted or undeclared stats hook gives identity-only stats`() {
        assertNull(service(registryWith(HarnessStats { HarnessStatsRow(userPrompts = 1) }, granted = false)).read("s1", "statsy", "abc", null).userPrompts)
        assertNull(service(registryWith(HarnessStats { HarnessStatsRow(userPrompts = 1) }, hooks = emptySet())).read("s1", "statsy", "abc", null).userPrompts)
    }

    @Test fun `a throwing stats source is isolated and attributed`() {
        val s = service(registryWith(HarnessStats { error("boom") })).read("s1", "statsy", "abc", null)
        assertNull(s.userPrompts); assertEquals("s1", s.sessionId)
        assertEquals("Acme_Stats", failures.single().plugin)
    }
}
