// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.cli.mcp.McpPluginTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * SCRIPT-3: `infantry event emit <name> [--payload <json>|-] [--cause <id>] [--depth N]`: one call of the built-in `infantry_event_emit`
 * tool (`plugin_tool_call`), so the CLI and MCP share one answer and one gate. Open to every role.
 */
internal object EventCli {
    const val USAGE = "usage: infantry event emit <name> [--payload <json>|-]"

    fun usageError(args: List<String>): String? = if (args.size == 2 && args[0] == "emit") null else USAGE

    fun run(stream: FrameStream, args: List<String>, payload: String?, out: Appendable, err: Appendable, getenv: (String) -> String?): Int = runBlocking {
        val arguments = buildMap<String, JsonPrimitive> {
            put("name", JsonPrimitive(args[1]))
            payload?.let { put("payload", JsonPrimitive(it)) }
            getenv("INFANTRY_EVENT_CAUSE")?.takeIf { it.isNotBlank() }?.let { put("causeId", JsonPrimitive(it)) }
        }
        val result = McpPluginTools(getenv).call(stream, "infantry_event_emit", JsonObject(arguments))
        (if (result.isError) err else out).appendLine(result.text)
        if (result.isError) 1 else 0
    }
}
