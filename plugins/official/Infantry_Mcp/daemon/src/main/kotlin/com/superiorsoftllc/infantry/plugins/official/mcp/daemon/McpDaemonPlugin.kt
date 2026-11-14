// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.mcp.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * MCP-CLI-1: `infantry_mcp`, the agent face of the MCP registry (the `mcp_*` frames; the CLI `infantry mcp` and the MCP Tools screen are the
 * other faces of the same backend). Reads: `list`, `status`, `my_tools` (what THIS session was handed and why), `grants`. Changes
 * (`add`, `remove`, `enable`, `disable`, `grant`, `revoke`) are refused to an agent session: an agent must never widen its own tools
 * (walled by default), so they stay with the owner, or the PM through the CLI. Output is compact; the registry never carries a secret value.
 */
class McpDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.tools.register(McpTool)
    }

    object McpTool : DaemonTool {
        private val OPS = listOf("list", "status", "my_tools", "grants", "add", "remove", "enable", "disable", "grant", "revoke")
        private val CHANGES = setOf("add", "remove", "enable", "disable", "grant", "revoke")
        override val name = "infantry_mcp"
        override val description =
            "MCP servers. op list: every server (source, state, tools). op status: one server (name, optional target). op my_tools: the servers THIS " +
                "session was handed and the reason for each (walled: only granted ones). op grants: who was granted what. add/remove/enable/disable/grant/revoke " +
                "are refused to agents (ask the PM or owner; CLI: infantry mcp)."
        override val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("op", buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray(OPS.map { JsonPrimitive(it) }))
                })
                put("name", buildJsonObject { put("type", "string"); put("description", "Server name (status, remove, enable, disable).") })
                put("target", buildJsonObject { put("type", "string"); put("description", "infantry (default) or a harness id such as claude, opencode, pi.") })
                put("scope", buildJsonObject { put("type", "string"); put("description", "The CLI scope, or app / project for an Infantry server.") })
                put("server", buildJsonObject { put("type", "object"); put("description", "add: the server (name, command[] or url, env, envSecrets, headers, headerSecrets, provides).") })
                put("grant", buildJsonObject { put("type", "object"); put("description", "grant: {server, scope role|agent|ticket, target, purpose, approvedBy}.") })
                put("grantId", buildJsonObject { put("type", "string"); put("description", "revoke: the grant id.") })
            })
            put("required", JsonArray(listOf(JsonPrimitive("op"))))
            put("additionalProperties", false)
        }

        override fun call(call: ToolCall): ToolResult =
            handle(call.arguments, call.caller.agent || call.clientSessionId != null || call.clientRole != null, call.clientSessionId, call.clientProjectId) { type, data, session ->
                call.request(type, data, sessionId = session, timeoutMs = REPLY_TIMEOUT_MS)
            }

        /** The whole tool, with the frame round trip as [send] (type, payload, session) so a test can stand in for the daemon. */
        fun handle(args: JsonObject, agent: Boolean, sessionId: String?, projectId: String?, send: (String, String, String?) -> Frame): ToolResult {
            val op = (args["op"] as? JsonPrimitive)?.contentOrNull ?: return fail("op is required (${OPS.joinToString("|")})")
            if (op !in OPS) return fail("unknown op '$op' (${OPS.joinToString("|")})")
            if (op in CHANGES && agent) {
                return fail("$op refused. Agent sessions cannot change MCP servers or grants (walled by default); ask the PM or the owner.")
            }
            fun str(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            fun body(vararg extra: Pair<String, JsonElement>) = buildJsonObject {
                put("target", str("target") ?: "infantry")
                projectId?.let { put("projectId", it) }
                str("scope")?.let { put("scope", it) }
                extra.forEach { (k, v) -> put(k, v) }
            }.toString()
            val name = str("name")
            val type: String
            val payload: String
            when (op) {
                "list" -> { type = "mcp_list"; payload = body() }
                "status" -> { type = "mcp_test"; payload = body("name" to JsonPrimitive(name ?: return fail("status needs name"))) }
                "my_tools" -> {
                    type = "mcp_plan"
                    payload = buildJsonObject { put("sessionId", sessionId ?: return fail("my_tools needs a calling agent session")) }.toString()
                }
                "grants" -> { type = "mcp_grants"; payload = "{}" }
                "add" -> { type = "mcp_add"; payload = body("server" to (args["server"] ?: return fail("add needs server"))) }
                "remove" -> { type = "mcp_remove"; payload = body("name" to JsonPrimitive(name ?: return fail("remove needs name"))) }
                "enable", "disable" -> {
                    type = "mcp_set_enabled"
                    payload = body("name" to JsonPrimitive(name ?: return fail("$op needs name")), "enabled" to JsonPrimitive(op == "enable"))
                }
                "grant" -> { type = "mcp_assign"; payload = body("grant" to (args["grant"] ?: return fail("grant needs grant"))) }
                else -> { type = "mcp_revoke"; payload = body("grantId" to JsonPrimitive(str("grantId") ?: return fail("revoke needs grantId"))) }
            }
            return try {
                val reply = send(type, payload, if (op == "my_tools") sessionId else null)
                if (reply.type == "error") fail("$op: ${reply.data}") else ToolResult(compact(reply.data))
            } catch (e: Exception) {
                fail("$op failed: ${e.message}")
            }
        }

        private fun fail(text: String) = ToolResult("infantry_mcp: $text", isError = true)

        /** One line per server / grant; anything unrecognised comes back as the (secret-free) JSON the daemon sent. */
        internal fun compact(data: String): String {
            val root = try { Json.parseToJsonElement(data) } catch (e: Exception) { return data }
            return when {
                root is JsonObject && "servers" in root -> {
                    val lines = root["servers"]!!.jsonArray.map { serverLine(it.jsonObject) }
                    (lines.ifEmpty { listOf("no MCP servers") } + notes(root)).joinToString("\n")
                }
                root is JsonObject && "add" in root -> planLines(root)
                root is JsonObject && "name" in root && "state" in root -> statusLine(root)
                root is JsonArray -> root.map { grantLine(it.jsonObject) }.ifEmpty { listOf("no grants (walled: only the infantry server)") }.joinToString("\n")
                else -> data
            }
        }

        private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

        private fun serverLine(o: JsonObject): String {
            val off = if ((o["enabled"] as? JsonPrimitive)?.booleanOrNull == false) " (off)" else ""
            val tools = (o["toolCount"] as? JsonPrimitive)?.intOrNull?.let { "  $it tools" } ?: ""
            val shadow = o.s("shadowedBy")?.let { "  shadowed by $it" } ?: ""
            return "${o.s("name")}  ${o.s("target")}/${o.s("source")}${o.s("scope")?.let { ":$it" } ?: ""}  ${o.s("state")}$off$tools$shadow"
        }

        private fun notes(root: JsonObject): List<String> =
            (root["targets"] as? JsonArray).orEmpty().mapNotNull { t -> t.jsonObject.let { o -> o.s("note")?.let { "note ${o.s("id")}: $it" } } }

        private fun statusLine(o: JsonObject): String =
            "${o.s("name")} ${o.s("state")}${(o["toolCount"] as? JsonPrimitive)?.intOrNull?.let { " $it tools" } ?: ""}${o.s("detail")?.let { " - $it" } ?: ""}"

        private fun grantLine(o: JsonObject): String =
            "${o.s("id")}  ${o.s("server")} -> ${o.s("scope")} ${o.s("target")}  by ${o.s("approvedBy")}: ${o.s("purpose")}"

        private fun planLines(o: JsonObject): String {
            val add = (o["add"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val deny = (o["deny"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val why = (o["reasons"] as? JsonObject).orEmpty()
            val live = (o["status"] as? JsonObject).orEmpty().mapValues { (it.value as? JsonPrimitive)?.contentOrNull }
            val lines = mutableListOf("you get: ${add.joinToString(", ") { n -> live[n]?.let { "$n [$it]" } ?: n }.ifEmpty { "(none)" }}")
            if (deny.isNotEmpty()) lines += "skipped: ${deny.joinToString(", ")}"
            why.forEach { (k, v) -> lines += "  $k: ${(v as? JsonPrimitive)?.contentOrNull ?: v}" }
            return lines.joinToString("\n")
        }

        private const val REPLY_TIMEOUT_MS = 45_000L
    }
}
