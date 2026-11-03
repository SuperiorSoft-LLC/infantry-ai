package com.superiorsoftllc.infantry.harness.descriptor

import com.superiorsoftllc.infantry.harness.HarnessEvent
import com.superiorsoftllc.infantry.harness.HarnessTranslator
import com.superiorsoftllc.infantry.harness.claude.ClaudeStreamTranslator
import com.superiorsoftllc.infantry.harness.EventStreamTranslator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What a descriptor's `jsonl` rule can produce, and the `fields` each kind of event takes. */
internal enum class MappedEvent(val wire: String, val required: Set<String>, val optional: Set<String> = emptySet()) {
    SESSION("session", setOf("id")),
    TEXT("text", setOf("text")),
    REASONING("reasoning", setOf("text")),
    TOOL("tool", setOf("name"), setOf("id", "arguments")),
    TOOL_RESULT("tool_result", setOf("id"), setOf("output", "isError")),
    USAGE("usage", setOf("input", "output")),

    /** Not an event of its own: it only records the reason the run's single terminal event will carry. */
    COMPLETION("completion", emptySet(), setOf("reason")),

    /** Likewise: it records the message of the run's single terminal Error. */
    ERROR("error", setOf("message")),
}

/**
 * One `jsonl` mapping rule: when EVERY `match` entry equals the value at that dotted path of a stdout record, the
 * record produces [event], each of its `fields` read from the dotted path given. A rule with an empty `match` fits
 * every record. Several rules may fit one record, and all of them apply. A record that lacks a field the event
 * needs produces nothing, never a blank event.
 */
internal class FieldRule(val match: Map<String, String>, val event: MappedEvent, val fields: Map<String, String>)

/** How a descriptor harness's stdout becomes [HarnessEvent]s. */
class DescriptorOutput internal constructor(private val format: Format, private val rules: List<FieldRule>) {
    internal enum class Format(val wire: String) { TEXT("text"), JSONL("jsonl"), CLAUDE("claude"), PI("pi") }

    /** A fresh translator for one run. [cliName] only words the exit-code error. */
    fun newTranslator(cliName: String): HarnessTranslator = when (format) {
        Format.TEXT -> TextTranslator(cliName)
        Format.JSONL -> MappedTranslator(cliName, rules)
        // The shipped translators, reused as they are: the descriptor adds no second reading of either stream.
        Format.CLAUDE -> ClaudeStreamTranslator()
        Format.PI -> EventStreamTranslator()
    }

    internal companion object {
        fun parse(element: JsonElement?): DescriptorOutput {
            if (element == null) return DescriptorOutput(Format.TEXT, emptyList())
            val obj = element as? JsonObject ?: throw InvalidDescriptor("field 'output' is not an object")
            val format = Format.entries.firstOrNull { it.wire == obj.text("format") } ?: throw InvalidDescriptor("'output.format' is not text, jsonl, claude or pi")
            if (format != Format.JSONL) return DescriptorOutput(format, emptyList())
            val map = obj["map"] as? JsonArray ?: throw InvalidDescriptor("'output.map' is required for jsonl")
            if (map.isEmpty()) throw InvalidDescriptor("'output.map' is empty")
            return DescriptorOutput(format, map.map(::parseRule))
        }

        private fun parseRule(element: JsonElement): FieldRule {
            val obj = element as? JsonObject ?: throw InvalidDescriptor("an 'output.map' rule is not an object")
            val event = MappedEvent.entries.firstOrNull { it.wire == obj.text("event") } ?: throw InvalidDescriptor("an 'output.map' rule names an unknown event")
            val match = (obj["match"] ?: JsonObject(emptyMap())).let { raw ->
                (raw as? JsonObject ?: throw InvalidDescriptor("a rule's 'match' is not an object"))
                    .mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: throw InvalidDescriptor("a rule's 'match' holds a non-scalar") }
            }
            val fields = (obj["fields"] ?: JsonObject(emptyMap())).let { raw ->
                (raw as? JsonObject ?: throw InvalidDescriptor("a rule's 'fields' is not an object"))
                    .mapValues { (_, v) -> v.string() ?: throw InvalidDescriptor("a rule's 'fields' holds a non-string path") }
            }
            if (!fields.keys.all { it in event.required || it in event.optional }) throw InvalidDescriptor("a rule maps a field its event does not have")
            if (!fields.keys.containsAll(event.required)) throw InvalidDescriptor("a rule does not map a field its event needs")
            return FieldRule(match, event, fields)
        }
    }
}

/** `text`: each stdout line is a piece of the answer; the exit code decides the verdict. */
private class TextTranslator(private val cliName: String) : HarnessTranslator {
    private var first = true

    override fun translate(line: String): List<HarnessEvent> {
        val text = if (first) line else "\n$line"
        first = false
        return listOf(HarnessEvent.TextDelta(text))
    }

    override fun terminal(exitCode: Int, stderrTail: String): HarnessEvent = exitVerdict(cliName, exitCode, stderrTail) ?: HarnessEvent.Completion()
}

/** `jsonl`: stdout records are read through the descriptor's rules; the run's verdict is the mapped error, else the exit code. */
private class MappedTranslator(private val cliName: String, private val rules: List<FieldRule>) : HarnessTranslator {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var completionReason: String? = null
    @Volatile private var errorMessage: String? = null

    override fun translate(line: String): List<HarnessEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val record = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return emptyList()
        return rules.filter { rule -> rule.match.all { (path, expected) -> record.at(path)?.scalar() == expected } }
            .mapNotNull { rule -> eventFor(rule, record) }
    }

    override fun terminal(exitCode: Int, stderrTail: String): HarnessEvent {
        errorMessage?.let { return HarnessEvent.Error(it) }
        exitVerdict(cliName, exitCode, stderrTail)?.let { return it }
        return HarnessEvent.Completion(completionReason ?: "stop")
    }

    private fun eventFor(rule: FieldRule, record: JsonObject): HarnessEvent? {
        fun field(name: String): String? = rule.fields[name]?.let { record.at(it)?.asText() }
        return when (rule.event) {
            MappedEvent.SESSION -> field("id")?.takeIf { it.isNotBlank() }?.let { HarnessEvent.SessionStarted(it) }
            MappedEvent.TEXT -> field("text")?.takeIf { it.isNotEmpty() }?.let { HarnessEvent.TextDelta(it) }
            MappedEvent.REASONING -> field("text")?.takeIf { it.isNotEmpty() }?.let { HarnessEvent.Reasoning(it) }
            MappedEvent.TOOL -> field("name")?.takeIf { it.isNotBlank() }
                ?.let { HarnessEvent.ToolCall(field("id").orEmpty(), it, field("arguments") ?: "{}") }
            MappedEvent.TOOL_RESULT -> field("id")?.takeIf { it.isNotBlank() }
                ?.let { HarnessEvent.ToolResult(it, field("output").orEmpty(), field("isError") == "true") }
            MappedEvent.USAGE -> {
                val input = field("input")
                val output = field("output")
                if (input == null && output == null) null else HarnessEvent.Usage(input.toTokens(), output.toTokens())
            }
            MappedEvent.COMPLETION -> { completionReason = field("reason")?.takeIf { it.isNotBlank() } ?: completionReason; null }
            MappedEvent.ERROR -> { errorMessage = field("message")?.takeIf { it.isNotBlank() } ?: errorMessage; null }
        }
    }

    private fun String?.toTokens(): Long = this?.toDoubleOrNull()?.toLong() ?: 0L

    /** The value at a dotted path (`a.b.0.c`: object keys and array indexes), or null. */
    private fun JsonObject.at(path: String): JsonElement? =
        path.split('.').fold(this as JsonElement?) { node, key ->
            when (node) {
                is JsonObject -> node[key]
                is JsonArray -> key.toIntOrNull()?.let { node.getOrNull(it) }
                else -> null
            }
        }

    private fun JsonElement.scalar(): String? = (this as? JsonPrimitive)?.contentOrNull

    /** A primitive's text; an object or array as compact JSON (a tool's arguments). */
    private fun JsonElement.asText(): String? = if (this is JsonPrimitive) contentOrNull else toString()
}

/** The verdict a failed exit gives, or null for a clean one. The stderr tail is the CLI's own words, never the descriptor's. */
private fun exitVerdict(cliName: String, exitCode: Int, stderrTail: String): HarnessEvent? {
    if (exitCode == 0) return null
    val detail = stderrTail.trim().takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
    return HarnessEvent.Error("$cliName exited with code $exitCode$detail")
}
