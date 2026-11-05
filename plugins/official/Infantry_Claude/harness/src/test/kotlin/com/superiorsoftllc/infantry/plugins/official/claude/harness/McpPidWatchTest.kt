// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.ApprovalReply
import com.superiorsoftllc.infantry.harness.api.McpState
import com.superiorsoftllc.infantry.harness.api.SessionOutput
import com.superiorsoftllc.infantry.harness.api.SessionTurnEnd
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** HARNESS-GAP G12: the pid-file watch of the headless claude reports up, gone and up again, once per change. */
class McpPidWatchTest {
    private class Out : SessionOutput {
        val states = mutableListOf<McpState>()
        override fun write(text: String) = true
        override fun closeInput() {}
        override fun detail(text: String) {}
        override fun providerSession(id: String) {}
        override fun turnEnded(end: SessionTurnEnd, exitCode: Int?, reason: String?) {}
        override fun requestApproval(tool: String, input: String): ApprovalReply = ApprovalReply.DENY
        override fun mcpState(state: McpState, reason: String?) { states += state }
    }

    @Test
    fun reportsConnectedThenDisconnectedThenConnectedAgain() {
        val file = File.createTempFile("mcp", ".pid").apply { delete() }
        val out = Out()
        var now = 0L
        val live = mutableSetOf<Long>()
        val watch = McpPidWatch(file, out, { it in live }, { now })
        fun tick() { now += McpPidWatch.MCP_CHECK_MS; watch.check() }
        tick(); assertEquals(emptyList(), out.states, "no file yet: nothing to say")
        file.writeText("42"); live += 42; tick(); tick()
        assertEquals(listOf(McpState.CONNECTED), out.states)
        live -= 42; tick(); tick()
        assertEquals(listOf(McpState.CONNECTED, McpState.DISCONNECTED), out.states)
        file.writeText("43"); live += 43; tick()
        assertEquals(listOf(McpState.CONNECTED, McpState.DISCONNECTED, McpState.CONNECTED), out.states)
        file.delete()
    }
}
