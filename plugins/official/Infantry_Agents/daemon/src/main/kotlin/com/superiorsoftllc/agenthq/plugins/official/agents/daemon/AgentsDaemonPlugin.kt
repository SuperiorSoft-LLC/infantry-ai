package com.superiorsoftllc.agenthq.plugins.official.agents.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.DaemonPlugin
import com.superiorsoftllc.agenthq.daemon.api.DaemonTool
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import com.superiorsoftllc.agenthq.daemon.api.ToolResult
import com.superiorsoftllc.agenthq.daemon.api.harness.HarnessInstallReport
import com.superiorsoftllc.agenthq.daemon.api.harness.HarnessInstallText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * RUNTIME-LOAD-1: Infantry_Agents' daemon half. `agenthq_harness_status` (HARNESS-INSTALL-1, contract 1.100.0) was built into the
 * CLI's MCP server; now it is one `harness_install_status` frame on the caller's own connection, printed by the API's shared
 * [HarnessInstallText]. Same name, schema, answer and gating (`harness.install.read`). `agenthq_harness_install` stays in core: a
 * plugin tool call is cut off after limits.plugins.toolTimeoutMs, and an npm install runs for minutes.
 */
class AgentsDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.tools.register(HarnessStatusTool)
    }
}

object HarnessStatusTool : DaemonTool {
    const val NAME = "agenthq_harness_status"
    /** The 15 minutes an `npm view` per harness may take, as the CLI allowed. */
    private const val CHECK_LATEST_TIMEOUT_MS = 15 * 60_000L
    private const val ANSWER_TIMEOUT_MS = 20_000L
    private val readJson = Json { ignoreUnknownKeys = true }
    private val outJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    override val name = NAME
    override val description =
        "Which harness CLIs (claude, opencode, pi) are installed on the daemon's machine: state installed / missing / " +
            "outdated, the version, where it runs from, and whether npm is available. checkLatest=true asks npm for the " +
            "latest version too (slower). Install the missing ones with agenthq_harness_install."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("checkLatest") {
                put("type", "boolean")
                put("description", "Also ask npm for each CLI's latest version (default false).")
            }
        }
        put("additionalProperties", false)
    }
    override val annotations = buildJsonObject { put("readOnlyHint", true) }

    override fun call(call: ToolCall): ToolResult = try {
        val check = (call.arguments["checkLatest"] as? JsonPrimitive)?.contentOrNull == "true"
        val reply = call.request(
            "harness_install_status", if (check) """{"checkLatest":true}""" else "{}",
            timeoutMs = if (check) CHECK_LATEST_TIMEOUT_MS else ANSWER_TIMEOUT_MS,
        )
        when (reply.type) {
            "harness_install_status" -> {
                val report = readJson.decodeFromString(HarnessInstallReport.serializer(), reply.data)
                ToolResult(
                    outJson.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("report", outJson.encodeToJsonElement(HarnessInstallReport.serializer(), report))
                            put("text", StringBuilder().also { HarnessInstallText.render(report, it) }.toString())
                        },
                    ),
                )
            }
            "error" -> ToolResult(reply.data.ifBlank { "harness_install_status failed" }, isError = true)
            else -> ToolResult("harness_install_status: unexpected answer ${reply.type}", isError = true)
        }
    } catch (e: Exception) {
        ToolResult(e.message ?: "harness_install_status failed", isError = true)
    }
}
