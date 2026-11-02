package com.superiorsoftllc.agenthq.harness

import com.superiorsoftllc.agenthq.protocol.EventNormalizer
import com.superiorsoftllc.agenthq.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import com.superiorsoftllc.agenthq.usagemeter.UsageMeter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.superiorsoftllc.agenthq.harness.api.Harness as ApiHarness
import com.superiorsoftllc.agenthq.harness.api.HarnessEvent as ApiEvent
import com.superiorsoftllc.agenthq.harness.api.HarnessPlugin as ApiPlugin
import com.superiorsoftllc.agenthq.harness.api.HarnessProcess
import com.superiorsoftllc.agenthq.harness.api.HarnessTranslator as ApiTranslator

/** H5b: API 2.3.0 `Denial`, and a plugin harness's `Usage` events reaching the usage meter. */
class HarnessDenialTest {
    private fun plugin(version: String, events: (String) -> List<ApiEvent>) = object : ApiPlugin {
        override val pluginId = "Acme_Denier"
        override val contractVersion = version
        override fun register(registrar: com.superiorsoftllc.agenthq.harness.api.HarnessRegistrar) = registrar.register(object : ApiHarness {
            override val id = "denier"
            override val capabilities = emptySet<com.superiorsoftllc.agenthq.harness.api.HarnessCapability>()
            override fun process(request: com.superiorsoftllc.agenthq.harness.api.HarnessRequest) = HarnessProcess(listOf("x"))
            override fun newTranslator() = object : ApiTranslator {
                override fun translate(line: String) = events(line)
                override fun terminal(exitCode: Int, stderrTail: String): ApiEvent = ApiEvent.Completion()
            }
        })
    }

    private fun harnessOf(version: String, events: (String) -> List<ApiEvent>): CliHarness {
        val registry = HarnessRegistry(onFailure = {})
        val host = ModuleHarnessHost("Acme_Denier", File.createTempFile("h5b", "").also { it.delete(); it.mkdirs() }, HarnessHostDeclaration(), { true }, HarnessHostServices.NONE, {})
        assertIs<PluginOutcome.Accepted>(registry.register(ApiHarnessPluginBridge(plugin(version, events), host)))
        return registry.resolve("denier") as CliHarness
    }

    @Test fun `a 2_3 translator Denial becomes the same line as a built-in denial`() {
        val h = harnessOf(HARNESS_API_VERSION) { listOf(ApiEvent.Denial("shell", "not allowed")) }
        val ev = h.newTranslator().translate("x").single()
        assertEquals(HarnessEvent.Denial("shell", "not allowed"), ev)
        assertEquals(EventNormalizer.Line.Denial("denier", "shell"), (ev as HarnessEvent.Denial).toLine("denier"))
    }

    @Test fun `a denial with no tool is an attributed Error`() {
        val h = harnessOf(HARNESS_API_VERSION) { listOf(ApiEvent.Denial(" ")) }
        val ev = h.newTranslator().translate("x").single()
        assertTrue(ev is HarnessEvent.Error && "denial" in ev.message, "$ev")
    }

    @Test fun `plugins built against 2_0_0 and 2_2_0 still load on 2_3`() {
        for ((v, id) in listOf("2.0.0" to "a", "2.2.0" to "b")) {
            val registry = HarnessRegistry(onFailure = {})
            val host = ModuleHarnessHost("Acme_Denier", File.createTempFile("h5b", "").also { it.delete(); it.mkdirs() }, HarnessHostDeclaration(), { true }, HarnessHostServices.NONE, {})
            assertIs<PluginOutcome.Accepted>(registry.register(ApiHarnessPluginBridge(plugin(v) { emptyList() }, host)), id)
        }
    }

    @Test fun `a plugin harness Usage event reaches the usage meter`() {
        val h = harnessOf(HARNESS_API_VERSION) { line -> if (line == "u") listOf(ApiEvent.Usage(100, 20)) else emptyList() }
        val rec = SessionRecord(id = "s1", provider = "denier", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0, state = SessionState.IDLE, role = "backend", model = "m")
        val m = UsageMeter.open(File.createTempFile("meter", ".db").also { it.deleteOnExit() })
        m.eventTranslatorFor = { h.newTranslator() }
        m.onSessionEvent(SessionEvent.Stdout("s1", "u"), { rec })
        m.onSessionEvent(SessionEvent.Exit("s1", 0), { rec })
        val rows = m.report().rows
        assertEquals(100L, rows.sumOf { it.input }); assertEquals(20L, rows.sumOf { it.output })
        m.close()
    }
}
