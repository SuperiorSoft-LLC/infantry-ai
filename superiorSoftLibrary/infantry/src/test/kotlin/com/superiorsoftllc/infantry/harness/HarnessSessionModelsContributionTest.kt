// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.infantry.harness.api.HarnessCapability
import com.superiorsoftllc.infantry.harness.api.HarnessModels
import com.superiorsoftllc.infantry.harness.api.HarnessProcess
import com.superiorsoftllc.infantry.harness.api.HarnessRegistrar
import com.superiorsoftllc.infantry.harness.api.HarnessRequest
import com.superiorsoftllc.infantry.harness.api.HarnessSession
import com.superiorsoftllc.infantry.harness.api.SessionConnection
import com.superiorsoftllc.infantry.harness.api.SessionOutput
import com.superiorsoftllc.infantry.harness.api.SessionTurnEnd
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.session.host.AdapterSink
import com.superiorsoftllc.infantry.session.host.TurnEnd
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.superiorsoftllc.infantry.harness.api.Harness as ApiHarness
import com.superiorsoftllc.infantry.harness.api.HarnessEvent as ApiEvent
import com.superiorsoftllc.infantry.harness.api.HarnessPlugin as ApiPlugin
import com.superiorsoftllc.infantry.harness.api.HarnessTranslator as ApiTranslator

/** H6a: API 2.8.0 `Harness.session()` and `Harness.models()` reach the daemon behind `harness.hook.session` / `harness.hook.models`. */
class HarnessSessionModelsContributionTest {
    private val failures = CopyOnWriteArrayList<PluginFailure>()
    private val state = File.createTempFile("h6a", "").also { it.delete(); it.mkdirs() }
    private val written = CopyOnWriteArrayList<String>()

    private val session = object : HarnessSession {
        override val label = "echo-rpc"
        override fun fingerprint(request: HarnessRequest, env: Map<String, String>) = "fp:${request.model}:${request.sessionId}"
        override fun launch(request: HarnessRequest, env: Map<String, String>) = HarnessProcess(listOf("echo-rpc", "--resume=${request.sessionId}"))
        override fun connect(out: SessionOutput) = object : SessionConnection {
            override fun onStdout(line: String) {
                if (line == "settled") out.turnEnded(SessionTurnEnd.COMPLETED, 0) else if (line.startsWith("sid=")) out.providerSession(line.removePrefix("sid="))
            }
            override fun sendTurn(request: HarnessRequest) { out.write("prompt:${request.prompt}\n") }
            override fun abortTurn(): Boolean = out.write("abort\n")
            override fun close(): Boolean { out.closeInput(); return true }
        }
    }

    private fun registry(hooks: Set<String>, granted: Boolean = true, models: () -> List<HarnessModels>? = { listOf(HarnessModels("g", "G", listOf("m1"))) }): HarnessRegistry {
        val plugin = object : ApiPlugin {
            override val pluginId = "Acme_Rpc"
            override val contractVersion = HARNESS_API_VERSION
            override fun register(registrar: HarnessRegistrar) = registrar.register(object : ApiHarness {
                override val id = "rpcy"
                override val capabilities = emptySet<HarnessCapability>()
                override fun process(request: HarnessRequest) = HarnessProcess(listOf("echo"))
                override fun newTranslator() = object : ApiTranslator {
                    override fun translate(line: String) = emptyList<ApiEvent>()
                    override fun terminal(exitCode: Int, stderrTail: String): ApiEvent = ApiEvent.Completion()
                }
                override fun session() = session
                override fun models() = models()
            })
        }
        val registry = HarnessRegistry(onFailure = { failures += it })
        val host = ModuleHarnessHost("Acme_Rpc", state, HarnessHostDeclaration(hooks = hooks), { granted || !it.startsWith("harness.hook.") }, HarnessHostServices(), {})
        assertIs<PluginOutcome.Accepted>(registry.register(ApiHarnessPluginBridge(plugin, host, onFailure = { failures += it })))
        return registry
    }

    private class FakeRunner(val written: MutableList<String>, val specs: MutableList<ProcessSpec>) : ProcessRunner {
        override val id = "fake"
        var listener: ProcessListener? = null
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            specs += spec
            this.listener = listener
            return object : RunningProcess {
                override val isAlive = true
                override fun writeInput(text: String) { written += text }
                override fun closeInput() { written += "<eof>" }
                override fun killTree() {}
                override fun awaitExit() = 0
            }
        }
    }

    private class Sink(val ends: MutableList<TurnEnd>, val sessions: MutableList<String>, val out: MutableList<String>) : AdapterSink {
        override fun output(line: String) { out += line }
        override fun diagnostic(line: String) {}
        override fun detail(text: String) {}
        override fun providerSession(id: String) { sessions += id }
        override fun turnEnded(end: TurnEnd, exitCode: Int?, reason: String?) { ends += end }
    }

    @Test fun `a granted session runs through the host adapter`() {
        val h = registry(setOf("session")).resolve("rpcy") as ApiCliHarness
        val adapter = assertNotNull(h.sessionAdapter())
        assertEquals("echo-rpc", adapter.label)
        val req = RunRequest("hi", workingDir = state, resumeSessionId = "S1", model = "m")
        assertEquals("fp:m:null", adapter.fingerprint(req, emptyMap()))
        val runner = FakeRunner(written, CopyOnWriteArrayList())
        val ends = CopyOnWriteArrayList<TurnEnd>()
        val sess = CopyOnWriteArrayList<String>()
        val out = CopyOnWriteArrayList<String>()
        val p = adapter.launch(req, emptyMap(), runner, Sink(ends, sess, out))
        assertEquals(listOf("echo-rpc", "--resume=S1"), runner.specs.single().command)
        adapter.sendTurn(p, req)
        assertEquals(listOf("prompt:hi\n"), written.toList())
        runner.listener!!.onStdout("sid=abc")
        runner.listener!!.onStdout("settled")
        assertEquals(listOf("abc"), sess.toList())
        assertEquals(listOf("sid=abc", "settled"), out.toList())
        assertEquals(listOf(TurnEnd.COMPLETED), ends.toList())
        assertTrue(adapter.abortTurn(p))
        assertTrue(adapter.close(p))
        assertEquals("<eof>", written.last())
        assertTrue(failures.isEmpty())
    }

    @Test fun `an undeclared or ungranted session hook gives no adapter`() {
        assertNull((registry(emptySet()).resolve("rpcy") as ApiCliHarness).sessionAdapter())
        assertNull((registry(setOf("session"), granted = false).resolve("rpcy") as ApiCliHarness).sessionAdapter())
    }

    @Test fun `models become provider rows stamped with the harness id and a throw is attributed`() {
        val rows = registry(setOf("models")).resolve("rpcy")!!.catalog()!!.models()
        assertEquals(listOf("rpcy"), rows.map { it.provider })
        assertEquals(listOf("m1"), rows.single().models)
        val bad = registry(setOf("models")) { error("boom") }.resolve("rpcy")!!.catalog()!!
        assertTrue(runCatching { bad.models() }.isFailure)
        assertTrue(failures.any { it.plugin == "Acme_Rpc" })
        assertNull(registry(emptySet()).resolve("rpcy")!!.catalog())
    }
}
