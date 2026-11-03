package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.HarnessEvent
import com.superiorsoftllc.infantry.harness.api.HarnessTranslator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The TRANSLATION step for Claude: one line of `claude -p --output-format stream-json --verbose` in,
 * zero or more typed [HarnessEvent]s out. This is the only place in the plugin that knows Claude's wire
 * schema; everything the caller sees is the neutral vocabulary of HARNESS 1/4.
 *
 * One instance per run (it remembers whether the CLI reported a failure and whether it reported a result at all,
 * to pick the terminal event).
 *
 * THE MAPPING
 *
 * | claude line | -> HarnessEvent |
 * | --- | --- |
 * | `system` / `init` {session_id} | `SessionStarted(session_id)`, once per run (contract 1.1.0) |
 * | `assistant` / content `text` | `TextDelta` (one per block; the CLI sends whole blocks, not token deltas) |
 * | `assistant` / content `thinking` | `Reasoning` |
 * | `assistant` / content `tool_use` {id,name,input} | `ToolCall(callId=id, name, arguments=input as compact JSON)` |
 * | `user` / content `tool_result` {tool_use_id,content,is_error} | `ToolResult(callId=tool_use_id, output=text, isError)` (contract 1.1.0) |
 * | `result` success | `Usage` (when the line carries `usage`), then the terminal [HarnessEvent.Completion] at process exit |
 * | `result` with `is_error` / `subtype` `error*` | remembered, then the terminal [HarnessEvent.Error] at process exit |
 * | other `system`, `rate_limit_event`, anything unknown | nothing |
 * | a line that is not a JSON object | nothing (never guessed into prose) |
 *
 * A process that exits 0 WITHOUT ever printing a `result` line is an Error, not a Completion: nothing said the run
 * finished, so a truncated stream must not read as a success. (Found while writing the Pi harness, whose JSON stream
 * has the same hole: a verdict is part of the protocol, and the interface had no way to say "there was none".)
 *
 * Usage: Claude reports the prompt in three buckets (`input_tokens`, `cache_creation_input_tokens`,
 * `cache_read_input_tokens`). [HarnessEvent.Usage] keeps them as separate fields (never summed).
 */
class ClaudeStreamTranslator : HarnessTranslator {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The reason the CLI gave for a failed run, once a failing `result` line was seen. */
    @Volatile
    private var failure: String? = null

    /** True once any `result` line was seen: the CLI's own verdict that the run finished. */
    @Volatile
    private var sawResult = false

    @Volatile
    private var sessionAnnounced = false

    override fun translate(line: String): List<HarnessEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val obj = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return emptyList()
        return when (obj.string("type")?.lowercase()) {
            "system" -> systemEvents(obj)
            "assistant" -> assistantEvents(obj)
            "user" -> toolResultEvents(obj)
            "result" -> resultEvents(obj)
            else -> emptyList()
        }
    }

    /**
     * The one terminal event of the run, decided when the process has exited: an Error if the CLI reported
     * one, exited non-zero, or never reported a result at all, otherwise a Completion. [stderrTail] only ever
     * explains a failure.
     */
    override fun terminal(exitCode: Int, stderrTail: String): HarnessEvent {
        failure?.let { return HarnessEvent.Error(it) }
        if (exitCode != 0) {
            val detail = stderrTail.trim().takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
            return HarnessEvent.Error("claude exited with code $exitCode$detail")
        }
        if (!sawResult) return HarnessEvent.Error("claude ended without reporting a result: the run was cut short")
        return HarnessEvent.Completion()
    }

    private fun systemEvents(obj: JsonObject): List<HarnessEvent> {
        if (sessionAnnounced || obj.string("subtype")?.lowercase() != "init") return emptyList()
        val id = obj.string("session_id")?.takeIf { it.isNotBlank() } ?: return emptyList()
        sessionAnnounced = true
        return listOf(HarnessEvent.SessionStarted(id))
    }

    private fun toolResultEvents(obj: JsonObject): List<HarnessEvent> {
        val content = (obj["message"] as? JsonObject)?.get("content") as? JsonArray ?: return emptyList()
        return content.mapNotNull { it as? JsonObject }
            .filter { it.string("type") == "tool_result" }
            .mapNotNull { block ->
                val callId = block.string("tool_use_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val isError = block["is_error"].let { it is JsonPrimitive && (it.content == "true" || it.content == "1") }
                HarnessEvent.ToolResult(callId, resultText(block["content"]), isError)
            }
    }

    /** A tool_result's content is either a bare string or a list of blocks; only the text blocks are output. */
    private fun resultText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.string("type") == "text" }?.string("text") }.joinToString("\n")
        else -> ""
    }

    private fun assistantEvents(obj: JsonObject): List<HarnessEvent> {
        val content = (obj["message"] as? JsonObject)?.get("content")
        val blocks: List<JsonObject> = when (content) {
            is JsonArray -> content.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(content)
            else -> emptyList()
        }
        return blocks.mapNotNull { block ->
            when (block.string("type")) {
                "text" -> block.string("text")?.takeIf { it.isNotEmpty() }?.let { HarnessEvent.TextDelta(it) }
                "thinking" -> block.string("thinking")?.takeIf { it.isNotEmpty() }?.let { HarnessEvent.Reasoning(it) }
                "tool_use" -> block.string("name")?.takeIf { it.isNotBlank() }?.let { name ->
                    HarnessEvent.ToolCall(
                        callId = block.string("id").orEmpty(),
                        name = name,
                        arguments = (block["input"] ?: JsonObject(emptyMap())).toString(),
                    )
                }
                else -> null
            }
        }
    }

    private fun resultEvents(obj: JsonObject): List<HarnessEvent> {
        sawResult = true
        val subtype = obj.string("subtype")?.lowercase()
        val isError = obj["is_error"].let { it is JsonPrimitive && (it.content == "true" || it.content == "1") } ||
            (subtype != null && subtype.startsWith("error"))
        if (isError) {
            failure = failureMessage(obj, subtype)
            return emptyList()
        }
        val usage = obj["usage"] as? JsonObject ?: return emptyList()
        return listOf(HarnessEvent.Usage(
            inputTokens = usage.long("input_tokens") ?: 0L,
            outputTokens = usage.long("output_tokens") ?: 0L,
            cacheReadTokens = usage.long("cache_read_input_tokens") ?: 0L,
            cacheWriteTokens = usage.long("cache_creation_input_tokens") ?: 0L,
        ))
    }

    /** The CLI's own reason first (`result`, then `errors[0]`, then `error`/`message`), else the subtype, so the cause is never lost. */
    private fun failureMessage(obj: JsonObject, subtype: String?): String {
        obj.string("result")?.takeIf { it.isNotBlank() }?.let { return it }
        ((obj["errors"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        when (val error = obj["error"]) {
            is JsonPrimitive -> error.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            is JsonObject -> error.string("message")?.takeIf { it.isNotBlank() }?.let { return it }
            else -> Unit
        }
        obj.string("message")?.takeIf { it.isNotBlank() }?.let { return it }
        return "claude reported an error" + (subtype?.let { " ($it)" }.orEmpty())
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonElement as? JsonPrimitive)?.longOrNull
}
