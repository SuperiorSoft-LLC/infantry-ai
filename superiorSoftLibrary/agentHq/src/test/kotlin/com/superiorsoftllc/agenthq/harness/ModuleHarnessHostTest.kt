package com.superiorsoftllc.agenthq.harness

import com.superiorsoftllc.agenthq.harness.api.HarnessAnswer
import com.superiorsoftllc.agenthq.harness.api.HarnessProcess
import com.superiorsoftllc.agenthq.harness.api.HarnessProcessListener
import com.superiorsoftllc.agenthq.harness.api.HarnessQuestion
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HARNESS-PLUGIN-3 H3/H4: a module's host answers only what the module declared and (community) was granted. */
class ModuleHarnessHostTest {
    private val logs = CopyOnWriteArrayList<String>()
    private val state: File = Files.createTempDirectory("harness-host").toFile().apply { deleteOnExit() }
    private val services = HarnessHostServices(
        setting = { m, k -> "$m/$k" },
        resolveCli = { id, cmd -> "/bin/$id-$cmd" },
        mcpLaunch = { HarnessProcess(listOf("agenthq", "mcp", "serve")) },
        watchLauncher = { "agenthq" },
        ask = { _, q -> HarnessAnswer(listOf(q.options.first()), null, true) },
    )
    private val declared = HarnessHostDeclaration(settings = listOf("a"), cliId = "echo", cliCommand = "echo-cli", hooks = setOf("mcp", "ask"))
    private val noop = object : HarnessProcessListener {
        override fun onStdout(line: String) {}
        override fun onStderr(line: String) {}
        override fun onExit(exitCode: Int) {}
    }

    private fun host(grants: Set<String>?, d: HarnessHostDeclaration = declared) =
        ModuleHarnessHost("Acme_Host", state, d, { grants == null || it in grants }, services, { logs += it })

    @Test fun `a trusted module gets what it declared, and only that`() = runBlocking<Unit> {
        val h = host(null)
        assertEquals("Acme_Host/a", h.setting("a"))
        assertNull(h.setting("b"))
        assertEquals("/bin/echo-echo-cli", h.resolveCli("echo"))
        assertNull(h.resolveCli("claude"), "only its own declared CLI")
        assertEquals(listOf("agenthq", "mcp", "serve"), h.mcpLaunch(emptyMap())!!.command)
        assertNull(h.watchLauncher(emptyMap()), "watch not declared")
        assertEquals(listOf("yes"), h.ask("s1", HarnessQuestion("ok?", listOf("yes", "no"))).answers)
        assertEquals(File(state, "plugin-data/Acme_Host").absoluteFile, h.stateDir.absoluteFile)
        assertTrue(logs.any { "setting b is not declared" in it } && logs.all { it.startsWith("[harness Acme_Host]") }, "logs: $logs")
    }

    @Test fun `a community module without grants gets nothing, and may not start processes`() = runBlocking<Unit> {
        val h = host(emptySet())
        assertNull(h.setting("a"))
        assertNull(h.resolveCli("echo"))
        assertNull(h.mcpLaunch(emptyMap()))
        assertEquals(HarnessAnswer.NONE, h.ask("s1", HarnessQuestion("ok?", listOf("yes"))))
        assertFailsWith<SecurityException> { h.start(HarnessProcess(listOf("whoami")), noop) }
        val granted = host(setOf("harness.setting:a", "harness.hook.mcp"))
        assertEquals("Acme_Host/a", granted.setting("a"))
        assertTrue(granted.mcpLaunch(emptyMap()) != null)
    }

    @Test fun `a module without a declared cli may not start processes even when trusted`() {
        assertFailsWith<SecurityException> { host(null, HarnessHostDeclaration()).start(HarnessProcess(listOf("whoami")), noop) }
    }
}
