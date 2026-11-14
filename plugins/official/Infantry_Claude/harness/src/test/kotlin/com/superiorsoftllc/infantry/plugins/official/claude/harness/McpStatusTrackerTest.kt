// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.McpServerState
import com.superiorsoftllc.infantry.harness.api.McpServerStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class McpStatusTrackerTest {
    private fun rec(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun toolErrorOnMcpToolMarksServerFailed() {
        val t = McpStatusTracker()
        t.seed(listOf(McpServerStatus("github", McpServerState.CONNECTED), McpServerStatus("sentry", McpServerState.CONNECTED)))
        assertNull(t.observe(rec("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"mcp__github__list_issues"}]}}""")))
        val out = t.observe(rec("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","is_error":true,"content":"boom"}]}}"""))!!
        assertEquals(McpServerState.FAILED, out.first { it.name == "github" }.state)
        assertEquals(McpServerState.CONNECTED, out.first { it.name == "sentry" }.state)
    }

    @Test
    fun nonMcpOrSuccessfulResultsChangeNothing() {
        val t = McpStatusTracker()
        t.seed(listOf(McpServerStatus("github", McpServerState.CONNECTED)))
        t.observe(rec("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"a","name":"Bash"},{"type":"tool_use","id":"b","name":"mcp__github__x"}]}}"""))
        assertNull(t.observe(rec("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"a","is_error":true},{"type":"tool_result","tool_use_id":"b"}]}}""")))
    }
}
