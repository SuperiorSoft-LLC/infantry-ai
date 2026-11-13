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
 * CARD-3 (docs/CARD-REGISTRY.md 2.3): `infantry card list` and `infantry card show <type>`, the on-demand card directory. Each is
 * one `infantry_capability_call` of the `cards.list` / `cards.show` capability (whichever plugin declares it answers; the CLI
 * knows no card and no module), so an agent pays tokens for cards only when it asks. Open to every role.
 */
internal object CardCli {
    const val USAGE = "usage: infantry card list | infantry card show <type>"
    private const val CALL_TOOL = "infantry_capability_call"

    fun usageError(args: List<String>): String? = when {
        args.size == 1 && args[0] == "list" -> null
        args.size == 2 && args[0] == "show" -> null
        else -> USAGE
    }

    fun run(stream: FrameStream, args: List<String>, out: Appendable, err: Appendable, getenv: (String) -> String?): Int = runBlocking {
        val show = args[0] == "show"
        val call = JsonObject(buildMap {
            put("capability", JsonPrimitive(if (show) "cards.show" else "cards.list"))
            if (show) put("args", JsonObject(mapOf("type" to JsonPrimitive(args[1]))))
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
        out.append(if (show) renderShow(result) else renderList(result))
        0
    }

    /** One line per card type: `<type>  <summary>  (<module>)`. */
    fun renderList(result: JsonObject): String {
        val cards = (result["cards"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (cards.isEmpty()) return "no chat cards installed (no loaded plugin declares module.json cards)\n"
        val width = cards.maxOf { it.str("type").length }
        return buildString {
            cards.forEach { c -> appendLine("${c.str("type").padEnd(width)}  ${c.str("summary")}  (${c.str("module")})") }
            appendLine("Write one as a ```card:<type> fenced block; `infantry card show <type>` gives its spec.")
        }
    }

    /** The header line, then the spec as the plugin wrote it. */
    fun renderShow(result: JsonObject): String = buildString {
        appendLine("card:${result.str("type")} (${result.str("module")}): ${result.str("summary")}")
        appendLine()
        appendLine(result.str("spec").trimEnd())
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
