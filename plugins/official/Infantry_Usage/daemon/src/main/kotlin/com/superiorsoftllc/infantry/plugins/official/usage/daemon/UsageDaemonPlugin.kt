// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.usage.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.usage.UsageReport
import com.superiorsoftllc.infantry.daemon.api.usage.UsageText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * DAEMON-PLUGINS-3: Infantry_Usage's daemon half. `infantry_usage` (USAGE-PACE, contract 1.119.0) was built into the CLI's MCP
 * server; now it is one `usage_get` frame on the caller's own connection, rendered by the API's shared [UsageText]. Same
 * name, schema, answer and gating (`usage.read`). USAGE-MOCK-1: also the opt-in mock meters ([MockUsage]).
 */
class UsageDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.tools.register(UsageTool)
        // USAGE-MOCK-1: `usage.mock` on = usage_get, limits and the pushed limits frame carry the mock; pacing stays on the real readings
        // unless `usage.mock.affectsPacing`. A setting (or its INFANTRY_USAGE_MOCK* env) is read at every reading, so a change counts at once.
        fun value(setting: String, env: String) = api.env(env)?.takeIf { it.isNotBlank() } ?: api.config(setting)
        api.hooks.usageReadings(
            affectsPacing = { MockUsage.on(value(MockUsage.PACING_SETTING, MockUsage.PACING_ENV)) },
            readings = { now -> if (MockUsage.on(value(MockUsage.SETTING, MockUsage.ENV))) MockUsage.windows(value(MockUsage.DATA_SETTING, MockUsage.DATA_ENV), now) else null },
        )
    }
}

object UsageTool : DaemonTool {
    const val USAGE = "infantry_usage"
    private val reportJson = Json { ignoreUnknownKeys = true }
    private val outJson = Json { encodeDefaults = false }

    override val name = USAGE
    override val description =
        "Read plan usage per meter (claude = the Claude subscription, also spent by pi/opencode running anthropic/* " +
            "models; opencode-go; others): percent of the plan window used (the highest window), when it resets, " +
            "points ahead/under an even burn, and how many ticket workers the pacing rule allows versus how many " +
            "run. An agent's ticket start beyond the allowance is refused, with the reason. Unknown usage = no pacing (fail open). " +
            "Text summary; pass json=true to append the full JSON report."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("json", buildJsonObject {
                put("type", "boolean")
                put("description", "Append the full JSON report after the text summary (default false: the summary says it all).")
            })
        })
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
