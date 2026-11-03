// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.agents.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.agents.AgentEntry
import com.superiorsoftllc.infantry.daemon.api.agents.Agents
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * AGENT-REGISTRY-1 (docs/AGENT-REGISTRY.md section 3.4): the agent registry's tools, over DaemonApi `agents`. Reading is open to
 * every caller; enabling and disabling an agent is the user's override, so an AGENT caller (a session's MCP process, or the
 * agent token) is refused, like the roles tools' create / delete. CLI: `infantry agent-def list|show|enable|disable`.
 */
internal object AgentRegistryTools {
    const val LIST = "infantry_agent_list"
    const val GET = "infantry_agent_get"
    const val ENABLE = "infantry_agent_enable"
    const val DISABLE = "infantry_agent_disable"

    fun all(agents: () -> Agents): List<DaemonTool> = listOf(ListTool(agents), GetTool(agents), Toggle(agents, true), Toggle(agents, false))

    private val json = Json { encodeDefaults = false; prettyPrint = true }

    private fun ToolCall.callerIsAgent(): Boolean = caller.agent || clientSessionId != null || clientRole != null

    private fun ToolCall.id(): String? = (arguments["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun schema(withId: Boolean) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            if (withId) putJsonObject("id") { put("type", "string"); put("description", "the agent id (see infantry_agent_list)") }
        }
        put("required", buildJsonArray { if (withId) add(JsonPrimitive("id")) })
        put("additionalProperties", false)
    }

    private fun annotations(readOnly: Boolean) = buildJsonObject { put("readOnlyHint", readOnly); put("destructiveHint", false) }

    /** One line per agent: `id  name  [disabled]  (contributor/source)`. */
    fun line(e: AgentEntry): String =
        "${e.definition.id}\t${e.definition.name}${if (e.enabled) "" else "\t[disabled]"}\t(${e.contributor}/${e.source})"

    private class ListTool(private val agents: () -> Agents) : DaemonTool {
        override val name = LIST
        override val description = "Every agent the daemon's agent registry holds (built-in roles and plugin-contributed agents): id, name, whether it is enabled, who contributed it."
        override val inputSchema = schema(false)
        override val annotations = annotations(true)
        override fun call(call: ToolCall): ToolResult {
            val all = agents().list()
            return ToolResult(if (all.isEmpty()) "no agents" else all.joinToString("\n", transform = ::line))
        }
    }

    private class GetTool(private val agents: () -> Agents) : DaemonTool {
        override val name = GET
        override val description = "One agent of the registry in full: its definition (instructions or role, default harness/model/context limit, tools, schedule, ui hints), contributor and enabled state."
        override val inputSchema = schema(true)
        override val annotations = annotations(true)
        override fun call(call: ToolCall): ToolResult {
            val id = call.id() ?: return ToolResult("id is required", isError = true)
            val e = agents().get(id) ?: return ToolResult("no agent '$id'", isError = true)
            return ToolResult(json.encodeToString(AgentEntry.serializer(), e))
        }
    }

    private class Toggle(private val agents: () -> Agents, private val enable: Boolean) : DaemonTool {
        override val name = if (enable) ENABLE else DISABLE
        override val description = if (enable) "Re-enable a disabled agent (non-agent caller only)." else
            "Disable an agent: new sessions of it are refused, running ones carry on (non-agent caller only)."
        override val inputSchema = schema(true)
        override val annotations = annotations(false)
        override fun call(call: ToolCall): ToolResult {
            if (call.callerIsAgent()) return ToolResult("refused: an agent may not ${if (enable) "enable" else "disable"} agents; ask the owner", isError = true)
            val id = call.id() ?: return ToolResult("id is required", isError = true)
            val e = agents().setEnabled(id, enable) ?: return ToolResult("no agent '$id'", isError = true)
            return ToolResult("agent '${e.definition.id}' is ${if (e.enabled) "enabled" else "disabled"}")
        }
    }
}
