package com.superiorsoftllc.agenthq.plugins.official.usage.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.DaemonPlugin
import com.superiorsoftllc.agenthq.daemon.api.DaemonTool
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import com.superiorsoftllc.agenthq.daemon.api.ToolResult
import com.superiorsoftllc.agenthq.daemon.api.usage.UsageReport
import com.superiorsoftllc.agenthq.daemon.api.usage.UsageText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DAEMON-PLUGINS-3: Infantry_Usage's daemon half. `agenthq_usage` (USAGE-PACE, contract 1.119.0) was built into the CLI's MCP
 * server; now it is one `usage_get` frame on the caller's own connection, rendered by the API's shared [UsageText]. Same
 * name, schema, answer and gating (`usage.read`).
 */
class UsageDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.tools.register(UsageTool)
    }
}

object UsageTool : DaemonTool {
    const val USAGE = "agenthq_usage"
    private val reportJson = Json { ignoreUnknownKeys = true }
    private val outJson = Json { encodeDefaults = false }

    override val name = USAGE
    override val description =
        "Read plan usage per meter (claude = the Claude subscription, also spent by pi/opencode running anthropic/* " +
            "models; opencode-go; others): percent of the plan window used (the highest window), when it resets, " +
            "points ahead/under an even burn, and how many ticket workers the pacing rule allows versus how many " +
            "run. An agent's ticket start beyond the allowance is refused, with the reason. Unknown usage = no pacing (fail open). " +
            "Text summary first, then the JSON report."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
        put("required", buildJsonArray {})
        put("additionalProperties", false)
    }
    override val annotations = buildJsonObject {
        put("readOnlyHint", true)
        put("destructiveHint", false)
        put("idempotentHint", true)
        put("openWorldHint", false)
    }

    override fun call(call: ToolCall): ToolResult = try {
        val reply = call.request("usage_get", "", timeoutMs = 45_000L)
        when (reply.type) {
            "usage_report" -> {
                val report = reportJson.decodeFromString(UsageReport.serializer(), reply.data)
                ToolResult(UsageText.render(report) + "\n\n" + outJson.encodeToString(UsageReport.serializer(), report))
            }
            "error" -> ToolResult(reply.data.ifBlank { "usage_get failed" }, isError = true)
            else -> ToolResult("usage_get: unexpected answer ${reply.type}", isError = true)
        }
    } catch (e: Exception) {
        ToolResult(e.message ?: "usage_get failed", isError = true)
    }
}
