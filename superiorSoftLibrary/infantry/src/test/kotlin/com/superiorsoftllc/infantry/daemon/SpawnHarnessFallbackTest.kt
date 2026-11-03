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

    @Test
    fun `no provider and a registry harness - the spawn is accepted`() {
        val dir = EchoCliFixture.tempDir("spawn-fallback")
        val source = EchoCliFixture.writeSource(dir)
        val hd = File(dir, "harnesses").also { it.mkdirs() }
        File(hd, "echo.json").writeText(EchoCliFixture.descriptorJson("echo-cli", source))
        run(loadHarnesses(ServiceLoaderPluginSource(), descriptorDir = hd)) { f ->
            f.daemon.manager.checkSpawn(null, null)
            f.daemon.manager.checkSpawn("  ", null)
        }
    }

    @Test
    fun `no provider and nothing installed - refused with the install hint`() {
        run(HarnessRegistry()) { f ->
            val e = assertFailsWith<IllegalArgumentException> { f.daemon.manager.checkSpawn("claude", null) }
            assertTrue(NoHarnessProvider.NOT_INSTALLED in e.message.orEmpty(), e.message)
        }
    }
}
