package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.FrameStream
import com.superiorsoftllc.agenthq.cli.UsageCli
import com.superiorsoftllc.agenthq.usage.UsageReport
import com.superiorsoftllc.agenthq.usage.UsageText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * USAGE-PACE (contract 1.119.0): `agenthq_usage`, read-only. The plan usage per meter and the pacing allowance, so a PM
 * can pace how many ticket workers it starts. Nothing here is enforced yet (S1): the PM paces by reading it.
 */
internal class McpUsageTools {

    suspend fun usage(stream: FrameStream): ToolResult = try {
        val report = UsageCli.report(stream)
        ToolResult(UsageText.render(report) + "\n\n" + json.encodeToString(UsageReport.serializer(), report))
    } catch (e: Exception) {
        ToolResult(e.message ?: "usage_get failed", isError = true)
    }

    companion object {
        const val USAGE = "agenthq_usage"
        val NAMES = listOf(USAGE)

        private val json = Json { encodeDefaults = false }

        fun descriptors() = buildJsonArray {
            add(buildJsonObject {
                put("name", USAGE)
                put(
                    "description",
                    "Read plan usage per meter (claude = the Claude subscription, also spent by pi/opencode running anthropic/* " +
                        "models; opencode-go; others): percent of the plan window used (the highest window), when it resets, " +
                        "points ahead/under an even burn, and how many ticket workers the pacing rule allows versus how many " +
                        "run. Pace your ticket starts to it. Unknown usage = no pacing (fail open). " +
                        "Text summary first, then the JSON report.",
                )
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {})
                    put("required", buildJsonArray {})
                    put("additionalProperties", false)
                })
                put("annotations", buildJsonObject {
                    put("readOnlyHint", true)
                    put("destructiveHint", false)
                    put("idempotentHint", true)
                    put("openWorldHint", false)
                })
            })
        }
    }
}
