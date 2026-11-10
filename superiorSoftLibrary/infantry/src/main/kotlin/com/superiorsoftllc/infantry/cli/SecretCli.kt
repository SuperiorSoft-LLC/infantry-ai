// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.cli.mcp.McpPluginTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * SECRET-RENDER-1: `infantry secret render <template> --out <file> [--project <id>] [--allow-missing] [--check]`. One call of the
 * `infantry_secret_render` tool (so CLI, MCP and Lua share one answer and one gate). The path is made absolute HERE, against
 * the caller's own working directory, because the daemon resolves nothing relative to the CLI. Output: names and the written
 * path only; a value never reaches this process.
 */
internal object SecretCli {
    const val USAGE = "infantry secret render <template> --out <file> [--project <id>] [--allow-missing] | infantry secret render <template> --check"

    fun usageError(args: List<String>, out: String?, check: Boolean): String? {
        if (args.firstOrNull() != "render") return "secret needs a subcommand: $USAGE"
        if (args.size != 2) return "secret render takes one template file: $USAGE"
        if (!check && out == null) return "secret render needs --out <file> (or --check to write nothing): $USAGE"
        return null
    }

    fun run(
        stream: FrameStream, args: List<String>, out: String?, project: String?, crossProject: Boolean, allowMissing: Boolean, check: Boolean,
        stdout: Appendable, err: Appendable, getenv: (String) -> String?,
    ): Int = runBlocking {
        val m = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        m["template"] = JsonPrimitive(File(args[1]).absoluteFile.path)
        out?.let { m["out"] = JsonPrimitive(File(it).absoluteFile.path) }
        project?.let { m["project"] = JsonPrimitive(it) }
        if (crossProject) m["allowCrossProject"] = JsonPrimitive(true)
        if (allowMissing) m["allowMissing"] = JsonPrimitive(true)
        if (check) m["check"] = JsonPrimitive(true)
        val result = McpPluginTools(getenv).call(stream, "infantry_secret_render", JsonObject(m))
        if (result.text.startsWith("Unknown tool:")) {
            err.appendLine("error: this daemon has no Run tools (Infantry_Run's daemon half is not loaded)")
            return@runBlocking 1
        }
        (if (result.isError) err else stdout).appendLine(result.text)
        if (result.isError) 1 else 0
    }
}
