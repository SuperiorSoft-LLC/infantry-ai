// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.pi.harness

import com.superiorsoftllc.infantry.harness.api.HarnessProcess
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

    /** The [MCP_ENV] entry for [launch], or nothing when the host grants no MCP launch. */
    fun mcpEnv(launch: HarnessProcess?): Map<String, String> {
        if (launch == null || launch.command.isEmpty()) return emptyMap()
        val json = buildJsonObject {
            put("command", JsonArray(launch.command.map { JsonPrimitive(it) }))
            put("env", buildJsonObject { launch.env.forEach { (k, v) -> put(k, v) } })
        }
        return mapOf(MCP_ENV to json.toString())
    }

    /** `-e infantry-mcp.ts` only when the host granted an MCP launch. */
    fun mcpArgs(dir: File, launch: HarnessProcess?): List<String> = if (mcpEnv(launch).isEmpty()) emptyList() else launchArgs(dir, MCP)

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
