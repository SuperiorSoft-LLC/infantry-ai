package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.ToolInfo
import com.superiorsoftllc.infantry.daemon.api.Tools
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** SCRIPT-4: the self-describing API, generated from the live registry at each call (never a hand-written tool list). */
object ScriptApi {
    /** Compact: the alias table, each engine's binding, then one line per tool. With [tool] (a name or an alias): that tool in full. */
    fun describe(tools: Tools, aliases: Map<String, String>, bindings: Map<String, String>, tool: String? = null): String {
        val catalog = tools.catalog().sortedBy { it.name }
        if (tool != null) {
            val resolved = if (catalog.any { it.name == tool }) tool else aliases[tool]
            val t = catalog.firstOrNull { it.name == resolved } ?: return "unknown tool or alias '$tool'"
            val via = aliases.filterValues { it == t.name }.keys
            return buildString {
                append(t.name).append(if (t.readOnly) "  [read-only]" else "").append('\n')
                if (via.isNotEmpty()) append("aliases: ").append(via.sorted().joinToString(", ")).append('\n')
                append(t.description).append("\n\nargs (JSON Schema):\n").append(t.inputSchema.toString())
            }
        }
        return buildString {
            append("SCRIPT API: ").append(catalog.size).append(" tools (live registry). A script queues actions; the daemon runs them in order as the caller.\n")
            append("Call any tool by name (ih.call(name, args)) or by alias. To read results, queue ih.again(): the script re-runs with input.results.\n\n")
            append("ALIASES (data, setting scripting.aliases):\n")
            if (aliases.isEmpty()) append("  (none)\n") else aliases.toSortedMap().forEach { (a, t) -> append("  ").append(a).append(" -> ").append(t).append('\n') }
            bindings.forEach { (id, text) -> if (text.isNotBlank()) append("\nENGINE ").append(id).append(":\n").append(text.trim().prependIndent("  ")).append('\n') }
            append("\nTOOLS (--tool <name> for one in full):\n")
            catalog.forEach { append("  ").append(line(it)).append('\n') }
        }.trimEnd()
    }

    private fun line(t: ToolInfo): String {
        val props = t.inputSchema["properties"] as? JsonObject
        val required = (t.inputSchema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
        val args = props?.entries?.joinToString(", ") { (k, v) ->
            val type = ((v as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull ?: "any"
            "$k${if (k in required) "*" else ""}:$type"
        }.orEmpty()
        val first = t.description.trim().substringBefore('\n').let { if (it.length > 90) it.take(87) + "..." else it }
        return "${t.name}($args) - $first"
    }
}
