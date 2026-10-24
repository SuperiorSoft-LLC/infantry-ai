package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.FrameStream
import com.superiorsoftllc.agenthq.cli.ProcessesCli
import com.superiorsoftllc.agenthq.session.ProcessesRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * ROGUE-1 (contract 1.120.0): `agenthq_processes` / `agenthq_processes_reap`, the MCP form of `agenthq processes`, on the
 * daemon's `processes` frame. Only processes a session of this daemon provably started can be reaped.
 */
internal class McpProcessTools {

    suspend fun list(stream: FrameStream, arguments: JsonObject): ToolResult = call(stream, ProcessesRequest("list"), arguments.flag("all"))

    suspend fun reap(stream: FrameStream, arguments: JsonObject): ToolResult {
        val all = arguments.flag("all")
        val pidRaw = (arguments["pid"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val req = when {
            all && pidRaw == null -> ProcessesRequest("reap_rogue")
            !all && pidRaw != null -> ProcessesRequest("reap", pidRaw.toLongOrNull() ?: return refused("$REAP: pid must be a number"))
            else -> return refused("$REAP needs exactly one of pid or all=true")
        }
        return call(stream, req, showAll = false)
    }

    private suspend fun call(stream: FrameStream, req: ProcessesRequest, showAll: Boolean): ToolResult = try {
        val answer = ProcessesCli.request(stream, req)
        ToolResult(ProcessesCli.render(answer, showAll), isError = answer.refused != null)
    } catch (e: Exception) {
        refused(e.message ?: "processes failed")
    }

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() == "true"

    private fun refused(text: String) = ToolResult(text, isError = true)

    companion object {
        const val LIST = "agenthq_processes"
        const val REAP = "agenthq_processes_reap"
        val NAMES = listOf(LIST, REAP)

        private fun tool(name: String, description: String, readOnly: Boolean, destructive: Boolean, props: List<Pair<String, String>>) =
            buildJsonObject {
                put("name", name)
                put("description", description)
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        props.forEach { (key, text) -> put(key, buildJsonObject { put("type", "string"); put("description", text) }) }
                    })
                    put("required", buildJsonArray { })
                    put("additionalProperties", false)
                })
                put("annotations", buildJsonObject {
                    put("readOnlyHint", readOnly)
                    put("destructiveHint", destructive)
                    put("idempotentHint", true)
                    put("openWorldHint", false)
                })
            }

        fun descriptors() = buildJsonArray {
            add(tool(
                LIST,
                "List rogue background processes (Gradle/Kotlin daemons, node, JVMs) left behind by ended or idle sessions, with MB, " +
                    "age and owner session, plus free RAM against the floor. all=true also lists healthy tracked processes and heavy " +
                    "processes that are not ours (never reapable).",
                readOnly = true, destructive = false,
                props = listOf("all" to "true to include healthy tracked and unattributed processes."),
            ))
            add(tool(
                REAP,
                "Kill one process (pid) or every rogue one (all=true). Only a process a session of this daemon provably started " +
                    "(pid and start time recorded) is killed; anything else is refused.",
                readOnly = false, destructive = true,
                props = listOf("pid" to "The process id to kill.", "all" to "true to reap every rogue process."),
            ))
        }
    }
}
