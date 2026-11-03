package com.acme.newsagent

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * AGENT-REGISTRY-1 (docs/AGENT-REGISTRY.md section 7): the reference agent plugin. An agent built with no code orchestration at all:
 * no ticket, board, worktree or branch is named anywhere in it (AgentRegistryLayerGuardTest).
 *
 * - agent `acme-news`: declared as data in module.json `agents` (so it is listed even before this code runs);
 * - tool `acme_news_fetch`: fetches a plain-HTTP(S) RSS / Atom / text feed (`url`, else env `ACME_NEWS_FEED_URL`) and lists its titles;
 * - a periodic refresh ([DaemonApi.schedule], the agent's own `schedule.everyMinutes`) of the env feed, which re-contributes the
 *   agent with the latest headlines appended to its instructions, so a new session starts already briefed.
 */
class NewsAgentDaemon : DaemonPlugin {
    private var contribution: AutoCloseable? = null

    override fun start(api: DaemonApi) {
        api.tools.register(FetchTool(api) { titles -> brief(api, titles) })
        val everyMinutes = api.agents.get(AGENT)?.definition?.schedule?.everyMinutes ?: 60
        api.schedule("refresh", everyMinutes * 60_000L) {
            val url = api.env(FEED_ENV)?.trim()?.takeIf { it.isNotEmpty() } ?: return@schedule
            Feed.fetch(url).onSuccess { brief(api, it) }.onFailure { api.log("refresh of $url failed: ${it.message}") }
        }
        api.log("started; agent ${AGENT} ${if (api.agents.get(AGENT) != null) "is in the registry" else "is NOT in the registry"}")
    }

    override fun stop() { contribution?.close() }

    /** Re-contributes the agent with [titles] after its declared instructions. */
    @Synchronized
    private fun brief(api: DaemonApi, titles: List<String>) {
        val declared = api.agents.get(AGENT)?.definition ?: return
        val base = declared.instructions.substringBefore(HEADLINES)
        val withNews = declared.copy(instructions = (base.trimEnd() + "\n\n" + HEADLINES + "\n" + titles.take(10).joinToString("\n") { "- $it" }).take(19_000))
        val previous = contribution
        contribution = api.agents.contribute(withNews)
        previous?.close()
    }

    private class FetchTool(private val api: DaemonApi, private val onTitles: (List<String>) -> Unit) : DaemonTool {
        override val name = "acme_news_fetch"
        override val description = "Fetch a news feed (plain HTTP/HTTPS: RSS, Atom or one item per line) and list its item titles, newest first as the feed orders them."
        override val inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("url") { put("type", "string"); put("description", "the feed URL (http or https); default: the configured feed") }
                putJsonObject("limit") { put("type", "integer"); put("description", "at most this many items (default 10, max 50)") }
            }
            put("required", buildJsonArray {})
            put("additionalProperties", false)
        }
        override val annotations = buildJsonObject { put("readOnlyHint", true) }
        override val timeoutMs: Long = 15_000

        override fun call(call: ToolCall): ToolResult {
            val url = (call.arguments["url"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: api.env(FEED_ENV)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return ToolResult("no feed: pass url, or set $FEED_ENV", isError = true)
            val limit = ((call.arguments["limit"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 10).coerceIn(1, 50)
            return Feed.fetch(url).fold(
                onSuccess = { titles ->
                    runCatching { onTitles(titles) }
                    ToolResult(if (titles.isEmpty()) "the feed has no items" else titles.take(limit).joinToString("\n") { "- $it" })
                },
                onFailure = { ToolResult("fetch failed: ${it.message}", isError = true) },
            )
        }
    }

    companion object {
        const val AGENT = "acme-news"
        const val FEED_ENV = "ACME_NEWS_FEED_URL"
        const val HEADLINES = "Latest headlines (fetched):"
    }
}

/** The feed fetch and parse: plain HTTP(S), at most [MAX_BYTES], RSS `<item>` / Atom `<entry>` titles, else one item per line. */
object Feed {
    const val MAX_BYTES = 1_000_000

    private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build() }

    fun fetch(url: String): Result<List<String>> = runCatching {
        val uri = URI(url)
        require(uri.scheme == "http" || uri.scheme == "https") { "only http and https feeds" }
        val req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Accept", "application/rss+xml, application/atom+xml, text/plain;q=0.8, */*;q=0.5").GET().build()
        val res = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
        check(res.statusCode() in 200..299) { "HTTP ${res.statusCode()}" }
        val bytes = res.body().use { it.readNBytes(MAX_BYTES) }
        titles(String(bytes, Charsets.UTF_8))
    }

    fun titles(text: String): List<String> {
        val blocks = Regex("<(item|entry)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(text).map { it.groupValues[2] }.toList()
        if (blocks.isNotEmpty()) {
            return blocks.mapNotNull { b ->
                Regex("<title\\b[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(b)?.groupValues?.get(1)?.let(::clean)?.takeIf { it.isNotEmpty() }
            }
        }
        if (text.trimStart().startsWith("<")) return emptyList()
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun clean(raw: String): String = raw
        .replace(Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL), "$1")
        .replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
        .replace(Regex("\\s+"), " ").trim()
}
