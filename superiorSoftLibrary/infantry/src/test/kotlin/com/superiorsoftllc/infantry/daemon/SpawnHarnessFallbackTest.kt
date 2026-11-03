// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.harness.HarnessRegistry
import com.superiorsoftllc.infantry.harness.NoHarnessProvider
import com.superiorsoftllc.infantry.harness.ServiceLoaderPluginSource
import com.superiorsoftllc.infantry.harness.descriptor.EchoCliFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.protocol.RoleBinding
import com.superiorsoftllc.infantry.protocol.SpawnOptions

/** SPAWN-HARNESS-1: a spawn naming no provider uses a registry harness when the daemon default is "none". */
class SpawnHarnessFallbackTest {
    private fun run(registry: HarnessRegistry, block: (DaemonFixture) -> Unit) {
        val fixture = DaemonFixture.launch(defaultProvider = NoHarnessProvider, harnesses = registry)
        try {
            fixture.client.await(Envelope.READY)
            block(fixture)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    private fun echoRegistry(): HarnessRegistry {
        val dir = EchoCliFixture.tempDir("spawn-fallback")
        val source = EchoCliFixture.writeSource(dir)
        val hd = File(dir, "harnesses").also { it.mkdirs() }
        File(hd, "echo.json").writeText(EchoCliFixture.descriptorJson("echo-cli", source))
        return loadHarnesses(ServiceLoaderPluginSource(), descriptorDir = hd)
    }

    @Test
    fun `no provider and a registry harness - the session starts on the harness`() {
        run(echoRegistry()) { f ->
            val r = f.daemon.agents.resolveSpawn(SpawnOptions())
            val s = f.daemon.manager.startNew(prompt = "hi", providerId = r.providerId, model = r.model, agentId = r.agentId, role = r.role)
            assertEquals("echo-cli", s.provider)
        }
    }

    @Test
    fun `a routing rule picks the provider before the first harness`() {
        run(echoRegistry()) { f ->
            f.daemon.agents.spawnRoute = { pid, role -> if (pid == "p1" && role == "backend") RoleBinding("backend", provider = "echo-cli", model = "m-routed") else null }
            val r = f.daemon.agents.resolveSpawn(SpawnOptions(projectId = "p1", role = "backend"))
            assertEquals("echo-cli", r.providerId)
            assertEquals("m-routed", r.model)
        }
    }

    @Test
    fun `an explicit provider beats the routing rule`() {
        run(echoRegistry()) { f ->
            f.daemon.agents.spawnRoute = { _, _ -> RoleBinding("backend", provider = "nope") }
            val r = f.daemon.agents.resolveSpawn(SpawnOptions(provider = "echo-cli", role = "backend"))
            assertEquals("echo-cli", r.providerId)
        }
    }

    @Test
    fun `a spawn with an agentId and no provider starts on the registry harness`() {
        run(echoRegistry()) { f ->
            val a = f.daemon.agents.save(AgentProfile(name = "W", role = "backend"))
            val r = f.daemon.agents.resolveSpawn(SpawnOptions(agentId = a.id))
            val s = f.daemon.manager.startNew(prompt = "hi", providerId = r.providerId, model = r.model, agentId = r.agentId, role = r.role)
            assertEquals("echo-cli", s.provider)
        }
    }

    @Test
    fun `no provider and nothing installed - refused naming what was checked`() {
        run(HarnessRegistry()) { f ->
            val e = assertFailsWith<IllegalArgumentException> { f.daemon.manager.startNew(prompt = "hi", providerId = null) }
            assertTrue(NoHarnessProvider.NOT_INSTALLED in e.message.orEmpty(), e.message)
            assertTrue("checked:" in e.message.orEmpty(), e.message)
        }
    }
}
