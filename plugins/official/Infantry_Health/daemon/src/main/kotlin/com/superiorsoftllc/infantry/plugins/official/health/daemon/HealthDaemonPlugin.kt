// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.health.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DAEMON-HEALTH-1: `infantry_health`, the daemon health and environment report as an agent tool (MCP) and, because every tool an
 * agent has is reachable from a Lua script (`ih.call`), from hooks. The report is built by the daemon's core (`health` frame);
 * this tool only asks for it. `json: true` returns the machine form, otherwise the same text as `infantry health`.
 */
class HealthDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.tools.register(HealthTool)
    }

    object HealthTool : DaemonTool {
        override val name = "infantry_health"
        override val description =
            "Daemon health and environment, read by the daemon (never probe with shell commands): up/down, pid, port, build, uptime, last restart and why, " +
                "plugins and any PluginGuard disabled, MCP bridge per session (connected / dropped / reconnects), the restart and disconnect log, OS, shells " +
                "and their syntax caveats, project/worktree path, branch, JDK and Gradle, the OWNER's off-limits ports and the free test port range. " +
                "json: true returns JSON. The CLI twin is `infantry health`, which also works when the daemon or the MCP bridge is down."
        override val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("json", buildJsonObject { put("type", "boolean"); put("description", "Return the report as JSON instead of text.") })
            })
            put("additionalProperties", false)
        }

        override fun call(call: ToolCall): ToolResult = try {
            val wantJson = (call.arguments["json"] as? JsonPrimitive)?.booleanOrNull == true
            val request = buildJsonObject {
                call.clientSessionId?.let { put("sessionId", it) }
                call.clientProjectId?.let { put("projectId", it) }
                if (!wantJson) put("format", "text")
            }.toString()
            val reply = call.request("health", request, timeoutMs = REPLY_TIMEOUT_MS)
            if (reply.type != "health" || reply.data.isBlank()) ToolResult(reply.data.ifBlank { "infantry_health: the daemon has no health service" }, isError = true)
            else ToolResult(reply.data)
        } catch (e: Exception) {
            ToolResult("infantry_health failed: ${e.message}", isError = true)
        }

        private const val REPLY_TIMEOUT_MS = 15_000L
    }
}
