// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.pi.harness

import com.superiorsoftllc.infantry.harness.api.INFANTRY_MCP_SERVER
import com.superiorsoftllc.infantry.harness.api.McpPlan
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.harness.api.McpTransport
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The pi extension files this plugin ships as resources and loads with `-e <file>`. */
internal object PiExtensions {
    /** TODO-PROGRESS-1: pi has no to-do tool of its own; this extension registers `todowrite` in opencode's full-list shape (drives the to-do box and the progress bar). */
    const val TODO = "infantry-todo.ts"

    /** STALL-3 / watch: rewrites a shell tool call so it runs through the watched runner (the daemon's one rule set), when the session's watch is on. */
    const val WATCH = "infantry-watch.ts"

    /** HARNESS-GAP G5: enforces the session's ToolApproval mode (env [APPROVAL_ENV]) in pi's `tool_call` handler; ASK goes through a rpc confirm dialog ([APPROVAL_MARKER]). */
    const val APPROVAL = "infantry-approval.ts"
    const val APPROVAL_ENV = "INFANTRY_TOOL_APPROVAL"
    const val APPROVAL_MARKER = "infantry-approval:"

    /** HARNESS-GAP G6: bridges the session's infantry MCP server ([MCP_ENV] = JSON `{command, env}` from the host's mcpLaunch) into pi tools. */
    const val MCP = "infantry-mcp.ts"
    const val MCP_ENV = "INFANTRY_PI_MCP"

    /** RUNNER-GREEN-RUN-1: pi has no Command Code provider; this registers `commandcode` from the service's own model list when CMD_API_KEY is set. */
    const val COMMANDCODE = "infantry-commandcode.ts"

    /**
     * MCP-PI-1: the [MCP_ENV] entry for [plan]: a LIST of the stdio servers the extension starts (infantry, Infantry-owned and plugin specs).
     * pi loads its own servers itself ([PiMcp]), so a HARNESS spec is never in it; an HTTP spec has no child process to bridge.
     * The infantry server's tools keep their plain names; every other server's tools are registered as pi names them, `mcp__<server>__<tool>`.
     */
    fun mcpEnv(plan: McpPlan): Map<String, String> {
        val servers = plan.delivered.filter { it.source != McpSource.HARNESS }.mapNotNull { spec ->
            val t = spec.transport as? McpTransport.Stdio ?: return@mapNotNull null
            buildJsonObject {
                put("name", spec.name)
                put("prefix", spec.name != INFANTRY_MCP_SERVER)
                put("command", JsonArray(t.command.map { JsonPrimitive(it) }))
                put("env", buildJsonObject { t.env.forEach { (k, v) -> put(k, v) } })
            }
        }
        if (servers.isEmpty()) return emptyMap()
        return mapOf(MCP_ENV to buildJsonObject { put("servers", JsonArray(servers)) }.toString())
    }

    /** `-e infantry-mcp.ts` only when the plan delivers a server to bridge. */
    fun mcpArgs(dir: File, plan: McpPlan): List<String> = if (mcpEnv(plan).isEmpty()) emptyList() else launchArgs(dir, MCP)

    /** `--exclude-tools` (pi's documented denylist, MCP tools included) for the user servers the plan turns off; nothing when none. */
    fun denyArgs(plan: McpPlan): List<String> =
        plan.deny.filter { it != INFANTRY_MCP_SERVER }.sorted().map(PiMcp::toolPattern).takeIf { it.isNotEmpty() }
            ?.let { listOf("--exclude-tools", it.joinToString(",")) } ?: emptyList()

    fun source(name: String): String =
        requireNotNull(PiExtensions::class.java.getResource("/pi/$name")) { "missing resource /pi/$name" }.readText()

    /** Writes extension [name] into [dir] (only when missing or different) and returns the file. */
    fun install(dir: File, name: String): File {
        val file = File(dir, name)
        val text = source(name)
        if (!file.isFile || file.readText() != text) {
            dir.mkdirs()
            file.writeText(text)
        }
        return file
    }

    /** `-e <file>`, the file re-installed if it went missing; nothing if it cannot be written. */
    fun launchArgs(dir: File, name: String): List<String> =
        runCatching { install(dir, name) }.getOrNull()?.let { listOf("-e", it.absolutePath) } ?: emptyList()
}
