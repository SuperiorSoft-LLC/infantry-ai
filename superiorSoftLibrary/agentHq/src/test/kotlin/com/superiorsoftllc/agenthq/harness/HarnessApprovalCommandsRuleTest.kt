package com.superiorsoftllc.agenthq.harness

import com.superiorsoftllc.agenthq.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.agenthq.harness.api.HarnessCapability
import com.superiorsoftllc.agenthq.harness.api.HarnessCommandAction
import com.superiorsoftllc.agenthq.harness.api.HarnessCommandSpec
import com.superiorsoftllc.agenthq.harness.api.HarnessProcess
import com.superiorsoftllc.agenthq.harness.api.HarnessRegistrar
import com.superiorsoftllc.agenthq.harness.api.HarnessRequest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import com.superiorsoftllc.agenthq.harness.api.Harness as ApiHarness
import com.superiorsoftllc.agenthq.harness.api.HarnessEvent as ApiEvent
import com.superiorsoftllc.agenthq.harness.api.HarnessPlugin as ApiPlugin
import com.superiorsoftllc.agenthq.harness.api.HarnessTranslator as ApiTranslator
import com.superiorsoftllc.agenthq.harness.api.ToolApproval as ApiApproval

/** H7b-1: API 2.10.0 slots (approvalArgs, commands, mcpToolRule) reach the daemon's Harness; defaults add nothing. */
class HarnessApprovalCommandsRuleTest {
    private fun harness(full: Boolean): Harness {
        val state = File.createTempFile("h7b1", "").also { it.delete(); it.mkdirs() }
        val plugin = object : ApiPlugin {
            override val pluginId = "Acme_Slots"
            override val contractVersion = HARNESS_API_VERSION
            override fun register(registrar: HarnessRegistrar) = registrar.register(object : ApiHarness {
                override val id = "slotty"
                override val capabilities = emptySet<HarnessCapability>()
                override fun process(request: HarnessRequest) = HarnessProcess(listOf("echo"))
                override fun newTranslator() = object : ApiTranslator {
                    override fun translate(line: String) = emptyList<ApiEvent>()
                    override fun terminal(exitCode: Int, stderrTail: String): ApiEvent = ApiEvent.Completion()
                }
                override fun approvalArgs(approval: ApiApproval) = if (full && approval == ApiApproval.ALLOW_ALL) listOf("--yes") else emptyList()
                override fun commands() = if (full) listOf(HarnessCommandSpec("compact", "Compact", HarnessCommandAction.NATIVE, native = "/compact")) else emptyList()
                override fun mcpToolRule(serverName: String) = if (full) "mcp__${serverName}__*" else null
            })
        }
        val registry = HarnessRegistry()
        val host = ModuleHarnessHost("Acme_Slots", state, HarnessHostDeclaration(), { true }, HarnessHostServices(), {})
        assertIs<PluginOutcome.Accepted>(registry.register(ApiHarnessPluginBridge(plugin, host)))
        return registry.resolve("slotty")!!
    }

    @Test fun `a filled harness exposes its argv, commands and rule`() {
        val h = harness(full = true)
        assertEquals(listOf("--yes"), h.approvalArgs(ToolApproval.ALLOW_ALL))
        assertEquals(emptyList(), h.approvalArgs(ToolApproval.DENY))
        assertEquals("compact", h.commands.single().name)
        assertEquals("/compact", h.commands.single().native)
        assertEquals("mcp__agenthq__*", h.mcpToolRule("agenthq"))
    }

    @Test fun `defaults add nothing`() {
        val h = harness(full = false)
        assertEquals(emptyList(), h.approvalArgs(ToolApproval.ALLOW_ALL))
        assertEquals(emptyList(), h.commands)
        assertNull(h.mcpToolRule("agenthq"))
    }

}
