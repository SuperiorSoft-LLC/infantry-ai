package com.superiorsoftllc.agenthq.plugins.official.processes.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.DaemonPlugin
import com.superiorsoftllc.agenthq.daemon.api.DaemonTool
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import com.superiorsoftllc.agenthq.daemon.api.ToolResult
import com.superiorsoftllc.agenthq.daemon.api.processes.ProcessesAnswer
import com.superiorsoftllc.agenthq.daemon.api.processes.ProcessesRequest
import com.superiorsoftllc.agenthq.daemon.api.processes.ProcessesText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * DAEMON-PLUGINS-3: Infantry_Processes' daemon half. `agenthq_processes` / `agenthq_processes_reap` (ROGUE-1, contract
 * 1.120.0) were built into the CLI's MCP server; now each is one `processes` / `processes_reap` frame on the caller's own
 * connection, rendered by the API's shared [ProcessesText]. Same names, schemas, answers and gating.
 */
class ProcessesDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.tools.register(ProcessesTools.ListTool)
        api.tools.register(ProcessesTools.ReapTool)
    }
}

object ProcessesTools {
    const val LIST = "agenthq_processes"
    const val REAP = "agenthq_processes_reap"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun schema(props: List<Pair<String, String>>) = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            props.forEach { (key, text) -> put(key, buildJsonObject { put("type", "string"); put("description", text) }) }
        })
        put("required", buildJsonArray { })
        put("additionalProperties", false)
    }

    private fun hints(readOnly: Boolean, destructive: Boolean) = buildJsonObject {
        put("readOnlyHint", readOnly)
        put("destructiveHint", destructive)
        put("idempotentHint", true)
        put("openWorldHint", false)
    }

    object ListTool : DaemonTool {
        override val name = LIST
        override val description =
            "List rogue background processes (Gradle/Kotlin daemons, node, JVMs) left behind by ended or idle sessions, with MB, " +
                "age and owner session, plus free RAM against the floor. all=true also lists healthy tracked processes and heavy " +
                "processes that are not ours (never reapable)."
        override val inputSchema = schema(listOf("all" to "true to include healthy tracked and unattributed processes."))
        override val annotations = hints(readOnly = true, destructive = false)
        override fun call(call: ToolCall): ToolResult = send(call, ProcessesRequest("list"), call.arguments.flag("all"))
    }

    object ReapTool : DaemonTool {
        override val name = REAP
        override val description =
            "Kill one process (pid) or every rogue one (all=true). Only a process a session of this daemon provably started " +
                "(pid and start time recorded) is killed; anything else is refused."
        override val inputSchema = schema(listOf("pid" to "The process id to kill.", "all" to "true to reap every rogue process."))
        override val annotations = hints(readOnly = false, destructive = true)
        override fun call(call: ToolCall): ToolResult {
            val all = call.arguments.flag("all")
            val pidRaw = (call.arguments["pid"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val req = when {
                all && pidRaw == null -> ProcessesRequest("reap_rogue")
                !all && pidRaw != null -> ProcessesRequest("reap", pidRaw.toLongOrNull() ?: return refused("$REAP: pid must be a number"))
                else -> return refused("$REAP needs exactly one of pid or all=true")
            }
            return send(call, req, showAll = false)
        }
    }

    /** POLICY-1 (1.143.0): a list is the `processes` frame, a reap `processes_reap`, so a read-only policy can still list. */
    private fun send(call: ToolCall, req: ProcessesRequest, showAll: Boolean): ToolResult = try {
        val reply = call.request(if (req.op == "list") "processes" else "processes_reap", json.encodeToString(ProcessesRequest.serializer(), req))
        when (reply.type) {
            "processes" -> {
                val answer = json.decodeFromString(ProcessesAnswer.serializer(), reply.data)
                ToolResult(ProcessesText.render(answer, showAll), isError = answer.refused != null)
            }
            "error" -> refused(reply.data.ifBlank { "processes failed" })
            else -> refused("processes: unexpected answer ${reply.type}")
        }
    } catch (e: Exception) {
        refused(e.message ?: "processes failed")
    }

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() == "true"

    private fun refused(text: String) = ToolResult(text, isError = true)
}
