package com.superiorsoftllc.agenthq.plugins.official.claude.harness

import com.superiorsoftllc.agenthq.harness.api.HarnessStatEvent
import com.superiorsoftllc.agenthq.harness.api.HarnessStatsRow
import com.superiorsoftllc.agenthq.harness.api.HarnessUsageReading
import com.superiorsoftllc.agenthq.harness.api.HarnessUsageSource
import com.superiorsoftllc.agenthq.harness.api.HarnessUsageWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * HARNESS H7b-2: reads one Claude Code session from its own store, `<home>/.claude/projects/<any>/<providerSessionId>.jsonl`, streamed line by
 * line (real sessions run to tens of MB). Every field is null when the store does not say; billed tokens and cost are not in the record.
 */
object ClaudeStats {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(projects: File, psid: String): HarnessStatsRow? {
        val file = projects.listFiles { f -> f.isDirectory }?.map { File(it, "$psid.jsonl") }?.firstOrNull { it.isFile } ?: return null
        var first: Long? = null
        var last: Long? = null
        var prompts = 0
        var turns = 0
        var tools = 0
        val seenMessages = HashSet<String>()
        val seenTools = HashSet<String>()
        val models = LinkedHashSet<String>()
        var lastContext: Long? = null
        val events = ArrayList<HarnessStatEvent>()
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                // Only user/assistant lines are parsed; the rest (attachments, snapshots) only lend a timestamp.
                if ("\"user\"" !in line && "\"assistant\"" !in line) {
                    TIMESTAMP.find(line)?.let { m -> runCatching { Instant.parse(m.groupValues[1]).toEpochMilli() }.getOrNull() }?.let { events += HarnessStatEvent.Seen(it) }
                    continue
                }
                val o = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                val at = o.str("timestamp")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                if (at != null) { if (first == null || at < first!!) first = at; if (last == null || at > last!!) last = at }
                events += HarnessStatEvent.Seen(at)
                val message = o["message"] as? JsonObject ?: continue
                when (o.str("type")) {
                    "user" -> {
                        (message["content"] as? JsonArray)?.forEach { b ->
                            val block = b as? JsonObject ?: return@forEach
                            if (block.str("type") == "tool_result") events += HarnessStatEvent.ToolResult(textLength(block["content"]), at)
                        }
                        if (o.str("isMeta") != "true" && o.str("isCompactSummary") != "true" && o.str("isSidechain") != "true" && isPrompt(message["content"])) { prompts++; events += HarnessStatEvent.Prompt }
                    }
                    "assistant" -> {
                        val id = message.str("id")
                        events += HarnessStatEvent.AssistantAt(at)
                        if (id != null && seenMessages.add(id)) {
                            turns++
                            val model = message.str("model")
                            model?.let { models += it }
                            events += HarnessStatEvent.Turn(model)
                            (message["usage"] as? JsonObject)?.let { u ->
                                lastContext = listOf("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens").sumOf { u.long(it) }
                                events += HarnessStatEvent.Request(at, model, u.long("input_tokens"), u.long("output_tokens"), u.long("cache_read_input_tokens"), u.long("cache_creation_input_tokens"))
                            }
                        }
                        (message["content"] as? JsonArray)?.forEach { b ->
                            val block = b as? JsonObject ?: return@forEach
                            if (block.str("type") == "tool_use") { val tid = block.str("id"); if (tid == null || seenTools.add(tid)) { tools++; events += HarnessStatEvent.Tool(block.str("name") ?: "unknown", at) } }
                        }
                    }
                }
            }
        }
        return HarnessStatsRow(
            startedAt = first, endedAt = last, userPrompts = prompts, assistantTurns = turns, toolUses = tools,
            models = models.toList(), lastContextTokens = lastContext, events = events,
        )
    }

    private val TIMESTAMP = Regex("\"timestamp\":\"([^\"]+)\"")

    private fun textLength(content: JsonElement?): Int = when (content) {
        is JsonPrimitive -> content.content.length
        is JsonArray -> content.sumOf { (it as? JsonObject)?.str("text")?.length ?: 0 }
        else -> 0
    }

    /** A real prompt: plain string content, or text blocks with no tool_result (that is a tool's answer). */
    private fun isPrompt(content: JsonElement?): Boolean = when (content) {
        is JsonPrimitive -> content.isString
        is JsonArray -> content.none { (it as? JsonObject)?.str("type") == "tool_result" } && content.any { (it as? JsonObject)?.str("type") == "text" }
        else -> false
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toLongOrNull() ?: 0L
}

/** HARNESS H7b-2: Claude's plan limits, `GET api.anthropic.com/api/oauth/usage` with Claude Code's own OAuth token (`<home>/.claude/.credentials.json`). */
class ClaudeUsage(
    private val home: () -> File,
    private val fetch: (String, Map<String, String>) -> String = ::httpGet,
) : HarnessUsageSource {
    override fun read(nowMs: Long): HarnessUsageReading {
        val token = token(home(), System.getenv("CLAUDE_CONFIG_DIR")) ?: return HarnessUsageReading(emptyList(), "no Claude Code credentials")
        val body = fetch(URL, mapOf(
            "Authorization" to "Bearer $token", "anthropic-beta" to "oauth-2025-04-20",
            "User-Agent" to "claude-cli/1.0 (external, cli)", "Accept" to "application/json",
        ))
        return HarnessUsageReading(parse(body))
    }

    companion object {
        const val URL = "https://api.anthropic.com/api/oauth/usage"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun token(home: File, configDir: String?): String? {
            val dir = configDir?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, ".claude")
            val root = runCatching { json.parseToJsonElement(File(dir, ".credentials.json").readText()) as? JsonObject }.getOrNull() ?: return null
            return ((root["claudeAiOauth"] as? JsonObject)?.get("accessToken") as? JsonPrimitive)?.contentOrNull
        }

        /** `five_hour` / `seven_day` objects, or a `limits` array some accounts get instead. */
        fun parse(body: String): List<HarnessUsageWindow> {
            val root = json.parseToJsonElement(body) as JsonObject
            val byKind = LinkedHashMap<String, JsonObject>()
            (root["five_hour"] as? JsonObject)?.let { byKind["five_hour"] = it }
            (root["seven_day"] as? JsonObject)?.let { byKind["seven_day"] = it }
            (root["limits"] as? JsonArray)?.forEach { item ->
                val obj = item as? JsonObject ?: return@forEach
                val kind = listOf("type", "name", "window", "id").firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.contentOrNull }?.lowercase() ?: return@forEach
                when {
                    "five" in kind || "5h" in kind || "session" in kind -> byKind.putIfAbsent("five_hour", obj)
                    "seven" in kind || "week" in kind -> byKind.putIfAbsent("seven_day", obj)
                }
            }
            return listOfNotNull(
                byKind["five_hour"]?.let { window("Current session", 300, it) },
                byKind["seven_day"]?.let { window("Current week", 10_080, it) },
            )
        }

        private fun window(label: String, minutes: Int, obj: JsonObject): HarnessUsageWindow {
            val pct = (obj["utilization"] ?: obj["percent"]).let { p ->
                val prim = p as? JsonPrimitive
                if (prim == null) null else if (prim.isString) prim.content.trim().removeSuffix("%").trim().toDoubleOrNull() else prim.doubleOrNull
            }
            return HarnessUsageWindow(label, minutes, pct, resetMillis(obj["resets_at"] ?: obj["resetsAt"]))
        }

        private fun resetMillis(e: JsonElement?): Long? {
            val p = e as? JsonPrimitive ?: return null
            p.doubleOrNull?.let { return if (it < 1e11) (it * 1000).toLong() else it.toLong() }
            return runCatching { Instant.parse(p.content).toEpochMilli() }.getOrNull()
                ?: runCatching { java.time.OffsetDateTime.parse(p.content).toInstant().toEpochMilli() }.getOrNull()
        }

        private fun httpGet(url: String, headers: Map<String, String>): String {
            val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            val res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
            check(res.statusCode() in 200..299) { "claude usage answered HTTP ${res.statusCode()}" }
            return res.body()
        }
    }
}
