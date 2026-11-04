// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.RandomAccessFile

/*
 * CLI-PTY-1: the interactive claude writes its conversation to its session transcript
 * (`<claude config dir>/projects/<cwd slug>/<session id>.jsonl`), one JSON record per line. In interactive mode that file is the
 * output channel: each record is turned into the stream-json line `claude -p --output-format stream-json --verbose` would have
 * printed for it, so the host's raw stream, the [ClaudeStreamTranslator] and everything behind them see the same records in
 * both modes. The screen is never parsed for content.
 */

/** What one transcript record means to the interactive session. */
sealed interface TranscriptSignal {
    /** A stream-json line to report as if the process had printed it. */
    data class Line(val json: String) : TranscriptSignal

    /** The typed prompt was recorded (the turn started). */
    data object PromptRecorded : TranscriptSignal

    /** CLI-PTY-4: the CLI recorded a prompt it made itself ([kind] = its `origin.kind`, e.g. `task-notification`): a turn nobody typed. */
    data class OwnPrompt(val kind: String) : TranscriptSignal

    /** The CLI recorded that the user interrupted the turn (Esc). */
    data object Interrupted : TranscriptSignal

    /** The CLI gave up on the request (an API error it shows as an assistant message); [text] is its own words. */
    data class ApiError(val text: String) : TranscriptSignal
}

/** Pure: transcript record -> stream-json lines and turn signals. One per session (it sums the turn's usage). */
class TranscriptMapper(private val sessionId: String) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The last usage of each assistant message id in the current turn (the CLI writes one record per content block, each with the usage so far). */
    private val usageByMessage = LinkedHashMap<String, JsonObject>()
    private var lastText: String = ""

    fun map(line: String): List<TranscriptSignal> {
        val record = runCatching { json.parseToJsonElement(line.trim()) as? JsonObject }.getOrNull() ?: return emptyList()
        if (record.bool("isSidechain")) return emptyList()
        return when (record.str("type")) {
            "assistant" -> assistant(record)
            "user" -> user(record)
            else -> emptyList()
        }
    }

    private fun assistant(record: JsonObject): List<TranscriptSignal> {
        val message = record["message"] as? JsonObject ?: return emptyList()
        if (record.bool("isApiErrorMessage")) return listOf(TranscriptSignal.ApiError(texts(message["content"]).ifBlank { "claude reported an API error" }))
        if (message.str("model") == SYNTHETIC_MODEL) return emptyList()
        message.str("id")?.let { id -> (message["usage"] as? JsonObject)?.let { usageByMessage[id] = it } }
        texts(message["content"]).takeIf { it.isNotBlank() }?.let { lastText = it }
        return listOf(TranscriptSignal.Line(buildJsonObject {
            put("type", "assistant")
            put("message", message)
            put("parent_tool_use_id", JsonNull)
            put("session_id", sessionId)
            record.str("uuid")?.let { put("uuid", it) }
        }.toString()))
    }

    private fun user(record: JsonObject): List<TranscriptSignal> {
        if (record.bool("isMeta")) return emptyList()
        val message = record["message"] as? JsonObject ?: return emptyList()
        val content = message["content"]
        val blocks = (content as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        if (blocks.any { it.str("type") == "tool_result" }) {
            return listOf(TranscriptSignal.Line(buildJsonObject {
                put("type", "user")
                put("message", message)
                put("parent_tool_use_id", JsonNull)
                put("session_id", sessionId)
                record.str("uuid")?.let { put("uuid", it) }
            }.toString()))
        }
        val text = when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            else -> blocks.filter { it.str("type") == "text" }.mapNotNull { it.str("text") }.joinToString("\n")
        }
        val origin = (record["origin"] as? JsonObject)?.str("kind")?.takeIf { it.isNotBlank() }
        return when {
            text.startsWith(INTERRUPTED_PREFIX) -> listOf(TranscriptSignal.Interrupted)
            origin != null && origin != ORIGIN_HUMAN && text.isNotBlank() -> listOf(TranscriptSignal.OwnPrompt(origin))
            text.isNotBlank() -> listOf(TranscriptSignal.PromptRecorded)
            else -> emptyList()
        }
    }

    /** The stream-json `result` record that ends the turn (headless prints one per turn): the turn's summed usage and last text. Resets the turn. */
    fun resultLine(isError: Boolean = false, error: String? = null): String {
        val usage = usageByMessage.values
        val line = buildJsonObject {
            put("type", "result")
            put("subtype", if (isError) "error_during_execution" else "success")
            put("is_error", isError)
            put("result", if (isError) error.orEmpty() else lastText)
            put("session_id", sessionId)
            if (usage.isNotEmpty()) put("usage", buildJsonObject {
                for (key in USAGE_KEYS) put(key, usage.sumOf { (it[key] as? JsonPrimitive)?.longOrNull ?: 0L })
            })
        }.toString()
        usageByMessage.clear()
        lastText = ""
        return line
    }

    /** The stream-json `system`/`init` record, so the translator reports the session id as it does for a headless start. */
    fun initLine(): String = buildJsonObject {
        put("type", "system")
        put("subtype", "init")
        put("session_id", sessionId)
    }.toString()

    private fun texts(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }.joinToString("\n")
        else -> ""
    }

    companion object {
        /** The model name the CLI gives the messages it writes itself (e.g. after an interrupt); not a model reply. */
        const val SYNTHETIC_MODEL = "<synthetic>"
        const val INTERRUPTED_PREFIX = "[Request interrupted by user"
        /** The `origin.kind` of a prompt typed (or pasted) into the terminal; any other kind is one the CLI made itself. */
        const val ORIGIN_HUMAN = "human"
        val USAGE_KEYS = listOf("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.contentOrNull == "true"

/** Reads the lines appended to a file since the last call, from byte [start] on; a line still being written waits for its newline. One caller thread. */
class AppendedLines(start: Long = 0L, private val file: () -> File?) {
    private var offset = start
    private var partial = StringBuilder()
    private val bytes = java.io.ByteArrayOutputStream()

    fun read(): List<String> {
        val f = file()?.takeIf { it.isFile } ?: return emptyList()
        val length = f.length()
        if (length < offset) { offset = 0; partial = StringBuilder() } // replaced or truncated: start over
        if (length == offset) return emptyList()
        bytes.reset()
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(minOf(length - offset, READ_CHUNK.toLong()).toInt())
            var left = length - offset
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                bytes.write(buf, 0, n)
                left -= n
            }
        }
        // Only whole lines advance the offset, so a UTF-8 character is never cut.
        val chunk = bytes.toByteArray()
        val lastNewline = chunk.lastIndexOf('\n'.code.toByte())
        if (lastNewline < 0) return emptyList()
        offset += lastNewline + 1
        return String(chunk, 0, lastNewline + 1, Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }
    }

    private companion object {
        const val READ_CHUNK = 64 * 1024
    }
}

/** Where claude keeps a session's transcript. */
object ClaudeTranscripts {
    /** `CLAUDE_CONFIG_DIR` when set, else `~/.claude`. */
    fun configDir(env: Map<String, String> = System.getenv(), home: File = File(System.getProperty("user.home"))): File =
        env["CLAUDE_CONFIG_DIR"]?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, ".claude")

    /** claude's project folder name for [cwd]: every character that is not a letter or digit becomes `-`. */
    fun slug(cwd: String): String = cwd.map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }.joinToString("")

    /** The transcript of [sessionId]: under the slug of [cwd] first, else any project folder that holds `<sessionId>.jsonl`. */
    fun find(configDir: File, cwd: String?, sessionId: String): File? {
        val projects = File(configDir, "projects")
        cwd?.takeIf { it.isNotBlank() }?.let { File(File(projects, slug(it)), "$sessionId.jsonl") }?.takeIf { it.isFile }?.let { return it }
        return projects.listFiles()?.asSequence()?.map { File(it, "$sessionId.jsonl") }?.firstOrNull { it.isFile }
    }
}

/** Plain text of a terminal screen chunk: escape sequences and control characters dropped. Only for dialog detection and the debug scrollback. */
object ScreenText {
    fun strip(s: String): String {
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\u001b') {
                i++
                if (i >= s.length) break
                when (s[i]) {
                    '[' -> { i++; while (i < s.length && s[i] !in '@'..'~') i++; i++ }
                    ']' -> { i++; while (i < s.length && s[i] != '\u0007' && s[i] != '\u001b') i++; if (i < s.length && s[i] == '\u001b') i++; i++ }
                    '(', ')' -> i += 2
                    else -> i++
                }
            } else {
                if (c >= ' ' || c == '\n') b.append(c)
                i++
            }
        }
        return b.toString()
    }

    /** Lower case, no whitespace: a cursor move between words leaves no space on screen, so matching ignores spaces. */
    fun squeeze(s: String): String = s.filterNot { it.isWhitespace() }.lowercase()
}
