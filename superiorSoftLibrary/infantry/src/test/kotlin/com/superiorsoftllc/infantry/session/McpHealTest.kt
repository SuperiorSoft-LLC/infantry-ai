// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MCP-RECONNECT-1: a session showing mcp=disconnected is brought back by the daemon alone, told two plain lines, with named retry limits. Fakes only. */
class McpHealTest {
    private val now = AtomicLong(1_000_000)
    private val lines = mutableListOf<String>()
    private val failed = mutableListOf<Int>()

    private object Prov : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest) = ProcessSpec(listOf("fake"))
        override fun sessionIdFromLine(line: String): String? = null
    }

    private class Hang : ProcessRunner {
        override val id = "hang"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess = object : RunningProcess {
            override val pid: Long? = null
            override val isAlive = true
            override fun writeInput(text: String) = Unit
            override fun closeInput() = Unit
            override fun killTree() = listener.onExit(137)
            override fun awaitExit(): Int = 137
        }
    }

    private fun manager() = SessionManager(
        provider = Prov, runner = Hang(), store = JsonSessionStore(File.createTempFile("mcpheal", ".json").apply { delete() }),
        killGraceMs = 10, clock = now::get, watchdogPollMs = 1_000_000, backgroundPollMs = 0, idleSweepMs = 0,
        silentPollMs = 0, mcpHealPollMs = 0,
        bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy(ticketDeadlineMs = 1_000_000, gateLock = GateLock(dir = { null })),
    ).also { m ->
        m.eventSink = { e -> if (e is SessionEvent.Stderr) lines += e.line }
        m.mcpHealFailedSink = { _, tries -> failed += tries }
    }

    @Test fun `a disconnected session is reconnected by the daemon, told two lines, with no manual action`() {
        val m = manager()
        try {
            val s = m.startNew("work")
            m.interrupt(s.id) // the sweep reconnects idle sessions at once; a busy one waits for turn end
            m.reportMcpState(s.id, "disconnected", "bridge dropped")
            assertEquals("disconnected", m.get(s.id)!!.mcp)
            m.healMcp()
            assertTrue(lines.any { it.contains("Your Infantry tools are disconnected") }, "down line told: $lines")
            assertTrue(m.get(s.id)!!.mcp != "disconnected", "healed without an agent: ${m.get(s.id)!!.mcp}")
            m.reportMcpState(s.id, "connected")
            m.healMcp()
            assertEquals(1, lines.count { it.contains("Your Infantry tools are disconnected") }, "told once")
            assertEquals(1, lines.count { it.contains("connected again") }, "back line told once: $lines")
        } finally {
            m.shutdown()
        }
    }
}
