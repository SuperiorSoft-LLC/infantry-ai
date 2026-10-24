package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.FrameStream
import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.session.ProgressReducer
import com.superiorsoftllc.agenthq.session.ProgressReport
import com.superiorsoftllc.agenthq.session.SessionProgress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Contract 1.117.0: `agenthq_progress`, the MCP form of `agenthq progress`: the same `progress` frame, for the session in
 * `AGENTHQ_SESSION_ID`. Outside a session it is a no-op that says so (never an error), like the CLI.
 */
internal class McpProgressTools(private val getenv: (String) -> String?) {

    suspend fun progress(stream: FrameStream, arguments: JsonObject): ToolResult {
        val report = try {
            reportOf(arguments)
        } catch (e: IllegalArgumentException) {
            return ToolResult("$PROGRESS: ${e.message}", isError = true)
        }
        val session = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult("not running inside an Infantry AI session (no ${AccessPolicy.SESSION_ID_ENV}): nothing reported")
        val answer = try {
            stream.request(
                Envelope(Envelope.PROGRESS, json.encodeToString(ProgressReport.serializer(), report), session),
                Envelope.PROGRESS, "progress", idempotent = false,
            )
        } catch (e: Exception) {
            return ToolResult("$PROGRESS refused: ${e.message ?: "progress failed"}", isError = true)
        }
        val now = System.currentTimeMillis()
        val p = answer.data.takeIf { it.trim() != "null" }?.let { json.decodeFromString(SessionProgress.serializer(), it) }
        return ToolResult(p?.let { ProgressReducer.line(it, now) } ?: "no progress shown")
    }

    private fun reportOf(a: JsonObject): ProgressReport {
        val mode = a.string("mode") ?: throw IllegalArgumentException("needs mode (${MODES.joinToString(", ")})")
        val label = a.string("label")
        return when (mode) {
            "percent" -> ProgressReport("percent", percent = a.number("percent") ?: throw IllegalArgumentException("percent mode needs percent (0-100)"), label = label)
            "plan" -> {
                val steps = (a["steps"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
                val count = (a["count"] as? JsonPrimitive)?.intOrNull
                require(steps.isNotEmpty() || (count ?: 0) > 0) { "plan mode needs steps (names) or count" }
                ProgressReport("plan", steps = steps, count = count, label = label)
            }
            "step" -> ProgressReport("step", step = a.string("step"), label = label)
            "bytes", "count" -> ProgressReport(
                mode,
                done = (a["done"] as? JsonPrimitive)?.longOrNull ?: throw IllegalArgumentException("$mode mode needs done"),
                total = (a["total"] as? JsonPrimitive)?.longOrNull,
                label = label,
            )
            "status" -> ProgressReport("status", label = label)
            "done", "fail", "clear", "get" -> ProgressReport(mode, label = label)
            else -> throw IllegalArgumentException("unknown mode '${mode.take(40)}' (${MODES.joinToString(", ")})")
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    companion object {
        const val PROGRESS = "agenthq_progress"
        val NAMES = listOf(PROGRESS)
        val MODES = listOf("percent", "plan", "step", "bytes", "count", "status", "done", "fail", "clear", "get")

        private val json = Json { ignoreUnknownKeys = true }

        fun descriptors() = buildJsonArray {
            add(buildJsonObject {
                put("name", PROGRESS)
                put(
                    "description",
                    "Report how far along YOUR long job is (a gate, a build, a download); it shows as a live bar on your card " +
                        "and the PM reads it in agenthq_list_sessions. Prefer checkpoints (mode plan with step names, then mode " +
                        "step as each completes) or a counter (mode count, done/total); percent when the tool prints one; status " +
                        "(indeterminate, label only) when nothing can be measured. Never invent a number. done / fail end it. " +
                        "For output streams use the CLI: agenthq run --label x -- <cmd>, with ##infantry marker lines.",
                )
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("mode", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { MODES.forEach { add(JsonPrimitive(it)) } })
                        })
                        put("percent", buildJsonObject { put("type", "number"); put("description", "mode percent: 0-100.") })
                        put("label", buildJsonObject { put("type", "string"); put("description", "What the job is; mode fail: the reason.") })
                        put("steps", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                            put("description", "mode plan: the checkpoint names, in order.")
                        })
                        put("count", buildJsonObject { put("type", "integer"); put("description", "mode plan: a bare number of checkpoints.") })
                        put("step", buildJsonObject { put("type", "string"); put("description", "mode step: the step that just completed (omit = the current one).") })
                        put("done", buildJsonObject { put("type", "integer"); put("description", "mode bytes / count: units done.") })
                        put("total", buildJsonObject { put("type", "integer"); put("description", "mode bytes / count: the total (omit = unknown).") })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("mode")) })
                    put("additionalProperties", false)
                })
                put("annotations", buildJsonObject {
                    put("readOnlyHint", false)
                    put("destructiveHint", false)
                    put("idempotentHint", false)
                    put("openWorldHint", false)
                })
            })
        }
    }
}
