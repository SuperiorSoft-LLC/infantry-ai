// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.cli.mcp.McpPluginTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * DOCS-LOOKUP-1: `infantry docs list` and `infantry docs show <page>`, the on-demand in-app docs. Each is one
 * `infantry_capability_call` of the `docs.list` / `docs.show` capability (whichever plugin declares it answers; the CLI knows no
 * page and no module). Nothing is auto-loaded: an agent pays tokens for a page only when it asks. Open to every role.
 */
internal object DocsCli {
    const val USAGE = "usage: infantry docs list | infantry docs show <page>"
    private const val CALL_TOOL = "infantry_capability_call"

    fun usageError(args: List<String>): String? = when {
        args.size == 1 && args[0] == "list" -> null
        args.size == 2 && args[0] == "show" -> null
        else -> USAGE
    }

    fun run(stream: FrameStream, args: List<String>, out: Appendable, err: Appendable, getenv: (String) -> String?): Int = runBlocking {
        val show = args[0] == "show"
        val call = JsonObject(buildMap {
            put("capability", JsonPrimitive(if (show) "docs.show" else "docs.list"))
            if (show) put("args", JsonObject(mapOf("page" to JsonPrimitive(args[1]))))
        })
        val r = McpPluginTools(getenv).call(stream, CALL_TOOL, call)
        if (r.isError) {
            err.appendLine(r.text.removePrefix("$CALL_TOOL: "))
            return@runBlocking 1
        }
        val result = runCatching { Json.parseToJsonElement(r.text).jsonObject["result"]?.jsonObject }.getOrNull()
        if (result == null) {
            out.appendLine(r.text)
            return@runBlocking 0
        }
        out.append(if (show) result.str("text").trimEnd() + "\n" else renderList(result))
        0
    }

    /** One line per page: `<page>  <title>`. */
    fun renderList(result: JsonObject): String {
        val pages = (result["pages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (pages.isEmpty()) return "no docs pages bundled (the docs plugin is not loaded)\n"
        val width = pages.maxOf { it.str("page").length }
        return buildString {
            pages.forEach { p -> appendLine("${p.str("page").padEnd(width)}  ${p.str("title")}") }
            appendLine("`infantry docs show <page>` prints one.")
        }
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
