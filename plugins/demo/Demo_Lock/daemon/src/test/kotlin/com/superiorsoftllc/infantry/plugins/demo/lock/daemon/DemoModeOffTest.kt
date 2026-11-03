package com.superiorsoftllc.infantry.plugins.demo.lock.daemon

import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.plugin.api.PolicyPreset
import com.superiorsoftllc.infantry.policy.DirectoryPolicyProvider
import com.superiorsoftllc.infantry.policy.PolicyRuntime
import com.superiorsoftllc.infantry.protocol.Envelope
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEMO-MERGE-1: without the demo preset active the lock gates nothing and adds nothing. The preset is selected the way a
 * deployment selects it (its `selectWhenEnv`, `INFANTRY_DEMO_LOCK`), read by core from this module's `policies/`.
 */
class DemoModeOffTest {
    private val state: File = Files.createTempDirectory("demo-mode-off").toFile()

    @AfterTest fun cleanUp() { state.deleteRecursively() }

    /** The lock the plugin builds for the preset core selects under [env]. */
    private fun lockUnder(env: Map<String, String>): DemoLock {
        val runtime = PolicyRuntime(listOf(DirectoryPolicyProvider({ listOf(DemoPreset.file.parentFile) })), { env[it] }, null, state)
        return DemoLock.of(runtime.current, { env[it] }, state)
    }

    @Test fun `no demo env means production, and the lock is off with no token and no turn limit`() {
        val off = lockUnder(emptyMap())
        assertFalse(off.enabled)
        assertNull(off.publicToken)
        assertNull(off.turnLimit)
        assertFalse(DemoLock.of(PolicyPreset.Production, { null }, state).enabled)
    }

    @Test fun `an off lock refuses no frame, no mail and no route`() {
        val off = lockUnder(emptyMap())
        val policy = PresetPolicy(PolicyPreset.Production)
        for (type in listOf(Envelope.PROMPT, Envelope.KILL, Envelope.INTERRUPT, Envelope.SETTINGS_SET, Envelope.PROJECT_SAVE, "anything")) {
            assertNull(off.frameRefusal(Frame(type, "x", "s1"), null, policy), type)
        }
        assertNull(off.mailRefusal("a", "b", false))
        assertNull(off.httpRefusal("POST", "/update/apply", false))
    }

    @Test fun `the demo variables alone do not switch the lock on`() {
        val lock = lockUnder(mapOf("INFANTRY_DEMO_PUBLIC_TOKEN" to "t".repeat(40), "INFANTRY_DEMO_TURNS_PER_IP" to "3"))
        assertFalse(lock.enabled)
        assertNull(lock.publicToken)
    }

    @Test fun `INFANTRY_DEMO_LOCK=1 selects the demo preset, and its lock refuses a write frame`() {
        val env = mapOf("INFANTRY_DEMO_LOCK" to "1", "INFANTRY_DEMO_TURNS_PER_IP" to "10", "INFANTRY_DEMO_TURN_WINDOW_HOURS" to "24")
        val lock = lockUnder(env)
        assertTrue(lock.enabled)
        assertEquals(10, lock.turnLimit?.perClient)
        assertNotNull(PresetPolicy(DemoPreset.preset).gate(lock, Frame(Envelope.SETTINGS_SET, "x")))
    }
}
