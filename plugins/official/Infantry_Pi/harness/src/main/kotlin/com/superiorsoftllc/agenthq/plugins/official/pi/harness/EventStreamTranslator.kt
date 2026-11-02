package com.superiorsoftllc.agenthq.plugins.official.pi.harness

import com.superiorsoftllc.agenthq.harness.api.HarnessEvent
import com.superiorsoftllc.agenthq.harness.api.HarnessTranslator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * HARNESS H6c: the plugin's copy of the daemon's reader (harness/EventStreamTranslator.kt) of the "event stream" JSONL wire format that descriptor harnesses name as `output.format` "pi".
 * The pi harness module (plugins/official/Infantry_Pi) keeps an identical copy written against the harness API (a plugin sees only that API);
 * EventStreamTranslatorCopyTest fails when the two drift. The TRANSLATION step for Pi: one JSONL record of `pi --mode json` in, zero or more typed [HarnessEvent]s out. The
 * only code in the plugin that knows Pi's wire schema (docs/json.md and docs/message-types.md of pi 1.3.0).
 *
 * One instance per run: it accumulates usage across the run's assistant messages and remembers how the run ended,
 * because Pi's verdict is spread over several records, not carried by one `result` line the way Claude's is.
 *
 * THE MAPPING
 *
 * | pi record | -> HarnessEvent |
 * | --- | --- |
 * | `session` {id} | `SessionStarted(id)`, once |
 * | `message_update` / `text_delta` {delta} | `TextDelta(delta)` (a real token delta; `text_end` repeats the block and is ignored) |
 * | `message_update` / `thinking_delta` {delta} | `Reasoning(delta)` (`thinking_end` likewise ignored) |
 * | `message_update` / `toolcall_end` {toolCall:{id,name,arguments}} | `ToolCall(id, name, arguments as compact JSON)` |
 * | `tool_execution_end` {toolCallId,result,isError} | `ToolResult(toolCallId, text of result, isError)` |
 * | assistant `message_end` | accumulated for `Usage`; its `stopReason` decides success or failure |
 * | `agent_settled` | `Usage` (one, the run's sum, only if the run did not fail), and the run is known to have finished |
 * | everything else (`agent_start`, `turn_*`, `message_start`, `tool_execution_start|update`, `queue_update`, compaction, retry, ...) | nothing |
 * | a line that is not a JSON object | nothing (never guessed into prose) |
 *
 * Granularity: Pi streams real deltas, Claude delivers whole blocks. Both are legal; the declared mapping that
 * relates them is `HarnessParity.STREAM_GRANULARITY`. Usage: Pi reports it per assistant message, so the run's
 * total is their sum, emitted ONCE before the terminal event (as Claude's single `result` usage is); the prompt
 * side is `input + cacheRead + cacheWrite`, the same three buckets Claude's sum covers.
 *
 * HOW A PI RUN ENDS (the part that differs most from Claude, and the reason the terminal rule is not "exit code"):
 *  - Pi exits **0** on a failed or aborted run. The failure is the `stopReason` of the LAST assistant message
 *    (`error` / `aborted`, with `errorMessage`). Only the last one counts: an `error` that Pi's automatic retry
 *    recovered from is followed by a later assistant message that ends `stop`, which clears it.
 *  - `agent_settled` means Pi has no automatic work left. Exit 0 without it is a truncated stream and is an Error.
 *  - `stopReason: "length"` (cut off at the output limit) completes with reason `length`, not `stop`.
 */
internal class EventStreamTranslator : HarnessTranslator {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var sessionAnnounced = false
    @Volatile private var settled = false
    @Volatile private var aborted = false

    /** Why the LAST assistant message failed, or null if it did not (or none ended yet). */
    @Volatile private var lastAssistantFailure: String? = null
    @Volatile private var lastStopReason: String? = null

    /** Pi's own explanation when automatic retry gave up; better than the bare provider error when present. */
    @Volatile private var retryFinalError: String? = null

    private var assistantMessages = 0
    private var inputTokens = 0L
    private var cacheReadTokens = 0L
    private var cacheWriteTokens = 0L
    private var outputTokens = 0L

    /**
     * Pi streams thinking as arbitrary-sized `thinking_delta`s (often mid-word). Every other harness hands the daemon
     * a whole block as one reasoning event, and a consumer shows each reasoning event as its own line, so the
     * deltas are joined here, verbatim, and emitted as ONE `Reasoning` when the thinking block ends (the next
     * event that is not a `thinking_delta`).
     */
    private val thinking = StringBuilder()

    private fun flushThinking(): List<HarnessEvent> {
        if (thinking.isEmpty()) return emptyList()
        val text = thinking.toString()
        thinking.setLength(0)
        return listOf(HarnessEvent.Reasoning(text))
    }

    private fun thinkingDelta(obj: JsonObject): String? {
        if (obj.string("type") != "message_update") return null
        val update = obj["assistantMessageEvent"] as? JsonObject ?: return null
        if (update.string("type") != "thinking_delta") return null
        return update.string("delta")?.takeIf { it.isNotEmpty() }
    }

    override fun translate(line: String): List<HarnessEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val obj = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return emptyList()
        thinkingDelta(obj)?.let { thinking.append(it); return emptyList() }
        val pending = flushThinking()
        return pending + translateObject(obj)
    }

    private fun translateObject(obj: JsonObject): List<HarnessEvent> {
        return when (obj.string("type")) {
            "session" -> sessionEvents(obj)
            "message_update" -> updateEvents(obj)
            "message_end" -> { messageEnded(obj); emptyList() }
            "tool_execution_end" -> toolResultEvents(obj)
            "auto_retry_end" -> { retryEnded(obj); emptyList() }
            "agent_settled" -> settledEvents(obj)
            else -> emptyList()
        }
    }

    override fun terminal(exitCode: Int, stderrTail: String): HarnessEvent {
        lastAssistantFailure?.let { return HarnessEvent.Error(retryFinalError ?: it) }
        if (aborted) return HarnessEvent.Error("pi run was aborted")
        if (exitCode != 0) {
            val detail = stderrTail.trim().takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
            return HarnessEvent.Error("pi exited with code $exitCode$detail")
        }
        if (!settled) return HarnessEvent.Error("pi ended without reporting that the run settled: the run was cut short")
        return if (lastStopReason == "length") HarnessEvent.Completion("length") else HarnessEvent.Completion()
    }

    private fun sessionEvents(obj: JsonObject): List<HarnessEvent> {
        if (sessionAnnounced) return emptyList()
        val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: return emptyList()
        sessionAnnounced = true
        return listOf(HarnessEvent.SessionStarted(id))
    }

    private fun updateEvents(obj: JsonObject): List<HarnessEvent> {
        val update = obj["assistantMessageEvent"] as? JsonObject ?: return emptyList()
        return when (update.string("type")) {
            "text_delta" -> update.string("delta")?.takeIf { it.isNotEmpty() }?.let { listOf(HarnessEvent.TextDelta(it)) }.orEmpty()
            "toolcall_end" -> {
                val call = update["toolCall"] as? JsonObject
                val name = call?.string("name")?.takeIf { it.isNotBlank() }
                if (call == null || name == null) emptyList()
                else listOf(HarnessEvent.ToolCall(call.string("id").orEmpty(), name, (call["arguments"] ?: JsonObject(emptyMap())).toString()))
            }
            else -> emptyList()
        }
    }

    private fun toolResultEvents(obj: JsonObject): List<HarnessEvent> {
        val callId = obj.string("toolCallId")?.takeIf { it.isNotBlank() } ?: return emptyList()
        val isError = (obj["isError"] as? JsonPrimitive)?.contentOrNull == "true"
        val content = (obj["result"] as? JsonObject)?.get("content")
        return listOf(HarnessEvent.ToolResult(callId, resultText(content), isError))
    }

    /** A tool result's content is a list of blocks; only the text blocks are output. */
    private fun resultText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.string("type") == "text" }?.string("text") }.joinToString("\n")
        else -> ""
    }

    private fun messageEnded(obj: JsonObject) {
        val message = obj["message"] as? JsonObject ?: return
        if (message.string("role") != "assistant") return
        assistantMessages++
        (message["usage"] as? JsonObject)?.let { usage ->
            inputTokens += usage.number("input")
            cacheReadTokens += usage.number("cacheRead")
            cacheWriteTokens += usage.number("cacheWrite")
            outputTokens += usage.number("output")
        }
        val stop = message.string("stopReason")
        lastStopReason = stop
        lastAssistantFailure = when (stop) {
            "error" -> message.string("errorMessage")?.takeIf { it.isNotBlank() } ?: "pi reported an error"
            "aborted" -> message.string("errorMessage")?.takeIf { it.isNotBlank() } ?: "pi run was aborted"
            else -> null
        }
    }

    private fun retryEnded(obj: JsonObject) {
        val failed = (obj["success"] as? JsonPrimitive)?.contentOrNull == "false"
        retryFinalError = if (failed) obj.string("finalError")?.takeIf { it.isNotBlank() } else null
    }

    private fun settledEvents(obj: JsonObject): List<HarnessEvent> {
        settled = true
        aborted = (obj["aborted"] as? JsonPrimitive)?.contentOrNull == "true"
        // A failed run reports no usage, as Claude's does not: the verdict is the event, not a token count.
        if (lastAssistantFailure != null || aborted || assistantMessages == 0) return emptyList()
        return listOf(HarnessEvent.Usage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens))
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Long = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L
}
