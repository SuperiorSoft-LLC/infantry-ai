package com.superiorsoftllc.agenthq.plugins.official.board.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonTool
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import com.superiorsoftllc.agenthq.daemon.api.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** RUNTIME-LOAD-8 / DP-6: the project pipeline tools (PIPE-1), moved from Infantry_Git to Infantry_Board with the pipeline frames. */
object PipelineTools {
    const val SHOW = "agenthq_pipeline"
    const val RECORD = "agenthq_pipeline_record"

    private fun prop(type: String, description: String) = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    private fun text(arguments: JsonObject, key: String) = (arguments[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun refused(text: String) = ToolResult(text, isError = true)

    private fun project(call: ToolCall) = text(call.arguments, "projectId") ?: call.clientProjectId?.trim()?.takeIf { it.isNotEmpty() }

    private fun noProject(tool: String) = refused("$tool needs a projectId (or AGENTHQ_PROJECT_ID in the environment)")

    private fun send(call: ToolCall, type: String, data: JsonObject, session: String?): ToolResult = try {
        val reply = call.request(type, data.toString(), session)
        when (reply.type) {
            "pipeline_state" -> ToolResult(reply.data)
            "error" -> refused(reply.data.ifBlank { "$type failed" })
            else -> refused("$type: unexpected answer ${reply.type}")
        }
    } catch (e: Exception) {
        refused(e.message ?: "$type failed")
    }

    object ShowTool : DaemonTool {
        override val name = SHOW
        override val description =
            "Read the project's verification pipeline: its stages, each commit's current stage (who moved it, when, " +
                "evidence), and the running checks. configured:false when the project has no pipeline."
        override val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("projectId", prop("string", "Defaults to this session's project.")) })
            put("additionalProperties", false)
        }
        override val annotations = buildJsonObject { put("readOnlyHint", true) }

        override fun call(call: ToolCall): ToolResult {
            val project = project(call) ?: return noProject(SHOW)
            return send(call, "pipeline_show", buildJsonObject { put("projectId", project) }, null)
        }
    }

    object RecordTool : DaemonTool {
        override val name = RECORD
        override val description =
            "Report pipeline state instead of being asked by mail: move a commit to a stage (commit + stage + optional " +
                "evidence), or start / ping / finish the running check (check + scope; start also needs commit + what)."
        override val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("projectId", prop("string", "Defaults to this session's project."))
                put("commit", prop("string", "The commit sha."))
                put("stage", prop("string", "One of the project's configured stage names."))
                put("evidence", prop("string", "Suite name, counts, log path."))
                put("check", prop("string", "start, ping or finish: act on the running check instead of a stage."))
                put("scope", prop("string", "The worktree / build dir the check runs in; empty for the default."))
                put("what", prop("string", "What the check runs (start only)."))
            })
            put("additionalProperties", false)
        }
        override val annotations = buildJsonObject {
            put("readOnlyHint", false)
            put("destructiveHint", false)
            put("openWorldHint", false)
        }

        override fun call(call: ToolCall): ToolResult {
            val project = project(call) ?: return noProject(RECORD)
            val session = call.clientSessionId?.trim()?.takeIf { it.isNotEmpty() }
            val args = call.arguments
            val check = text(args, "check")
            if (check != null) {
                return send(call, "pipeline_check", buildJsonObject {
                    put("projectId", project)
                    put("action", check)
                    put("scope", text(args, "scope") ?: "")
                    text(args, "commit")?.let { put("commit", it) }
                    text(args, "what")?.let { put("what", it) }
                    session?.let { put("sessionId", it) }
                }, session)
            }
            val commit = text(args, "commit") ?: return refused("$RECORD needs a commit")
            val stage = text(args, "stage") ?: return refused("$RECORD needs a stage (or check: start|ping|finish)")
            return send(call, "pipeline_record", buildJsonObject {
                put("projectId", project)
                put("commit", commit)
                put("stage", stage)
                text(args, "evidence")?.let { put("evidence", it) }
                session?.let { put("sessionId", it) }
            }, session)
        }
    }
}
