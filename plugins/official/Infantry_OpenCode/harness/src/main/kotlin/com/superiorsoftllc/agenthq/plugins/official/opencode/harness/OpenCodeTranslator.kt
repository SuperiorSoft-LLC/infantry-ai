package com.superiorsoftllc.agenthq.plugins.official.opencode.harness

import com.superiorsoftllc.agenthq.harness.api.HarnessEvent
import com.superiorsoftllc.agenthq.harness.api.HarnessTranslator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * HARNESS H8b-2: reads the lines `opencode run --format json` prints, and the same lines the serve session re-emits from its event stream
 * ([OpenCodeServeSession]). The only place opencode's wire schema is known to the plugin.
 *
 * | line | events |
 * |---|---|
 * | any line with `sessionID` (first) | `SessionStarted` |
 * | `text` | `TextDelta` |
 * | `reasoning` | `Reasoning` |
 * | `tool_start` (serve: the call began) | `ToolCall` |
 * | `tool_use` / `tool` | `ToolCall` (unless the serve session already sent its `tool_start`), `Denial` when the part was refused by `opencode run` itself, `ToolResult` for a finished part |
 * | `permission_denied` (serve: a reject it made) | `Denial` |
 * | `step_finish` with `part.tokens` | `Usage` (input, output, cache read / write; reasoning tokens are not added) |
 * | `error` | remembered, then the terminal [HarnessEvent.Error] |
 *
 * The usage rule is the core `OpenCodeUsageParser`'s: a `step_finish` without both input and output counts says nothing (never 0).
 */
class OpenCodeTranslator : HarnessTranslator {
    private var failure: String? = null
    private var sessionSeen = false

    override fun translate(line: String): List<HarnessEvent> {
        val obj = runCatching { json.parseToJsonElement(line.trim()) as? JsonObject }.getOrNull() ?: return emptyList()
        val type = obj.str("type")?.lowercase() ?: return emptyList()
        val part = obj["part"] as? JsonObject
        val out = ArrayList<HarnessEvent>()
        if (!sessionSeen) obj.str("sessionID")?.takeIf { it.isNotBlank() }?.let { sessionSeen = true; out += HarnessEvent.SessionStarted(it) }
        when (type) {
            "text" -> text(obj)?.takeIf { it.isNotEmpty() }?.let { out += HarnessEvent.TextDelta(it) }
            "reasoning" -> text(obj)?.takeIf { it.isNotEmpty() }?.let { out += HarnessEvent.Reasoning(it) }
            "error" -> failure = message(obj) ?: "provider reported an error"
            "tool_start" -> toolName(obj)?.let { out += HarnessEvent.ToolCall(callId(part), it, input(part)) }
            "tool_use", "tool" -> toolName(obj)?.let { name ->
                val state = part?.get("state") as? JsonObject
                val status = state.str("status")
                // The serve session already forwarded the start of this call as `tool_start`; do not announce the call twice.
                if (!flag(obj, START_REPORTED)) out += HarnessEvent.ToolCall(callId(part), name, input(part))
                if (rejected(obj, state)) out += HarnessEvent.Denial(name, state.str("error").orEmpty())
                if (status == "completed" || status == "error") out += HarnessEvent.ToolResult(callId(part), state.str("output") ?: state.str("error").orEmpty(), isError = status == "error")
            }
            "permission_denied" -> out += HarnessEvent.Denial(obj.str("tool")?.takeIf { it.isNotBlank() } ?: "unknown", if (flag(obj, "prompted")) "owner did not approve" else "")
            "step_finish" -> usage(part)?.let { out += it }
        }
        return out
    }

    override fun terminal(exitCode: Int, stderrTail: String): HarnessEvent {
        failure?.let { return HarnessEvent.Error(it) }
        if (exitCode != 0) return HarnessEvent.Error("opencode exited with code $exitCode" + stderrTail.trim().takeIf { it.isNotEmpty() }?.let { ": " + it.take(300) }.orEmpty())
        return HarnessEvent.Completion()
    }

    private fun usage(part: JsonObject?): HarnessEvent.Usage? {
        val tokens = part?.get("tokens") as? JsonObject ?: return null
        val input = (tokens["input"] as? JsonPrimitive)?.longOrNull ?: return null
        val output = (tokens["output"] as? JsonPrimitive)?.longOrNull ?: return null
        val cache = tokens["cache"] as? JsonObject
        return HarnessEvent.Usage(input, output, (cache?.get("read") as? JsonPrimitive)?.longOrNull ?: 0, (cache?.get("write") as? JsonPrimitive)?.longOrNull ?: 0)
    }

    private fun rejected(obj: JsonObject, state: JsonObject?): Boolean {
        if (flag(obj, DENIAL_REPORTED)) return false
        return state.str("status") == "error" && state.str("error")?.startsWith(REJECTION_PREFIX) == true
    }

    private fun text(obj: JsonObject): String? = obj.str("text") ?: (obj["part"] as? JsonObject).str("text")

    private fun toolName(obj: JsonObject): String? {
        val part = obj["part"] as? JsonObject
        return listOf(obj.str("name"), part.str("tool"), part.str("name"), obj.str("tool")).firstOrNull { !it.isNullOrBlank() }
    }

    private fun callId(part: JsonObject?): String = part.str("callID") ?: part.str("id").orEmpty()

    private fun input(part: JsonObject?): String = ((part?.get("state") as? JsonObject)?.get("input") ?: part?.get("input"))?.toString() ?: "{}"

    private fun message(obj: JsonObject): String? {
        when (val error = obj["error"]) {
            is JsonPrimitive -> if (error.isString && error.content.isNotBlank()) return error.content
            is JsonObject -> error.str("message")?.takeIf { it.isNotBlank() }?.let { return it }
            else -> Unit
        }
        return obj.str("message")?.takeIf { it.isNotBlank() }
    }

    private fun flag(obj: JsonObject, key: String) = (obj[key] as? JsonPrimitive)?.content == "true"

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Set (`true`) by the serve session on a forwarded tool part whose reject it already reported. */
        const val DENIAL_REPORTED = "agenthqDenialReported"

        /** Set (`true`) by the serve session on a finished tool part whose running start it already forwarded as `tool_start`. */
        const val START_REPORTED = "agenthqStartReported"

        /** The start of opencode's error text for a refused permission ask. */
        const val REJECTION_PREFIX = "The user rejected permission"

        internal fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
