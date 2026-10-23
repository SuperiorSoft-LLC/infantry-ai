package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.FrameStream
import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.pipeline.PipelineCheckRequest
import com.superiorsoftllc.agenthq.pipeline.PipelineRecord
import com.superiorsoftllc.agenthq.pipeline.PipelineShow
import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * PIPE-1: an agent reads and reports its project's optional pipeline through `agenthq mcp serve`, over the same
 * `pipeline_*` frames as `agenthq pipeline`. The project is the argument `projectId`, else `AGENTHQ_PROJECT_ID`.
 * `agenthq_pipeline` returns the daemon's `pipeline_state` JSON verbatim (`configured:false` when the project has none);
 * `agenthq_pipeline_record` moves a commit to a stage, or with `check` starts / pings / finishes the running check.
 * A daemon refusal (no pipeline, unknown stage, check already running) comes back as an error result, code first.
 */
internal class McpPipelineTools(private val getenv: (String) -> String?) {

    suspend fun show(stream: FrameStream, arguments: JsonObject): ToolResult {
        val project = project(arguments) ?: return noProject(SHOW)
        return call(stream, Envelope.PIPELINE_SHOW, json.encodeToString(PipelineShow.serializer(), PipelineShow(project)), idempotent = true)
    }

    suspend fun record(stream: FrameStream, arguments: JsonObject): ToolResult {
        val project = project(arguments) ?: return noProject(RECORD)
        val session = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
        val check = text(arguments, "check")
        if (check != null) {
            val request = PipelineCheckRequest(project, check, text(arguments, "scope") ?: "", text(arguments, "commit"), text(arguments, "what"), session)
            return call(stream, Envelope.PIPELINE_CHECK, json.encodeToString(PipelineCheckRequest.serializer(), request), idempotent = false)
        }
        val commit = text(arguments, "commit") ?: return refused("$RECORD needs a commit")
        val stage = text(arguments, "stage") ?: return refused("$RECORD needs a stage (or check: start|ping|finish)")
        val request = PipelineRecord(project, commit, stage, text(arguments, "evidence"), session)
        return call(stream, Envelope.PIPELINE_RECORD, json.encodeToString(PipelineRecord.serializer(), request), idempotent = false)
    }

    private suspend fun call(stream: FrameStream, type: String, data: String, idempotent: Boolean): ToolResult = try {
        ToolResult(stream.request(Envelope(type, data), Envelope.PIPELINE_STATE, type, idempotent = idempotent).data)
    } catch (e: Exception) {
        refused(e.message ?: "$type failed")
    }

    private fun project(arguments: JsonObject) =
        text(arguments, "projectId") ?: getenv(AccessPolicy.PROJECT_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }

    private fun text(arguments: JsonObject, key: String) = (arguments[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun noProject(tool: String) = refused("$tool needs a projectId (or AGENTHQ_PROJECT_ID in the environment)")

    private fun refused(text: String) = ToolResult(text, isError = true)

    companion object {
        const val SHOW = "agenthq_pipeline"
        const val RECORD = "agenthq_pipeline_record"
        val NAMES = listOf(SHOW, RECORD)

        private val json = Json { ignoreUnknownKeys = true }

        private fun prop(type: String, description: String) = buildJsonObject {
            put("type", type)
            put("description", description)
        }

        fun descriptors() = buildJsonArray {
            add(buildJsonObject {
                put("name", SHOW)
                put(
                    "description",
                    "Read the project's verification pipeline: its stages, each commit's current stage (who moved it, when, " +
                        "evidence), and the running checks. configured:false when the project has no pipeline.",
                )
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject { put("projectId", prop("string", "Defaults to this session's project.")) })
                    put("additionalProperties", false)
                })
                put("annotations", buildJsonObject { put("readOnlyHint", true) })
            })
            add(buildJsonObject {
                put("name", RECORD)
                put(
                    "description",
                    "Report pipeline state instead of being asked by mail: move a commit to a stage (commit + stage + optional " +
                        "evidence), or start / ping / finish the running check (check + scope; start also needs commit + what).",
                )
                put("inputSchema", buildJsonObject {
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
                })
                put("annotations", buildJsonObject {
                    put("readOnlyHint", false)
                    put("destructiveHint", false)
                    put("openWorldHint", false)
                })
            })
        }
    }
}
