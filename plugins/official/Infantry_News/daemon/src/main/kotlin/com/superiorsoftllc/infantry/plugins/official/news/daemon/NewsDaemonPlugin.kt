// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.news.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadRequest
import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadResult
import com.superiorsoftllc.infantry.plugin.api.NoProgress
import com.superiorsoftllc.infantry.plugin.api.ProgressSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * NEWS-1 (docs/CARD-REGISTRY.md 2.6): Infantry_News' daemon half. One capability, `news.headlines {query?, topic?, source?, max?}`,
 * reached through the EXISTING `infantry_capability_call` / `infantry capability call` (no new tool schema, no `http.fetch`
 * opt-in), so every agent on every harness (claude, opencode, pi, runner sessions) can use it while the plugin is loaded. It reads
 * Google News RSS through the core Downloads seam and answers with the finished `card:news` fence
 * (the items live only inside it: sending them twice doubled the answer and cut long lists at the MCP result cap). Nothing is saved: no sources, no history (owner); a short in-memory cache spares Google repeated identical calls.
 */
class NewsDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        val news = News(
            setting = api::config,
            tmpDir = File(api.stateDir, "news-tmp"),
            download = api.downloads::download,
        )
        api.capabilities.handle(HEADLINES) { call ->
            fun s(k: String) = (call.args[k] as? JsonPrimitive)?.contentOrNull
            news.headlines(s("query"), s("topic"), s("source"), s("max")?.toDoubleOrNull()?.toInt())
        }
    }

    /** The capability's logic, testable without a daemon: [download] is the Downloads seam, [setting] the daemon settings. */
    class News(
        private val setting: (String) -> String?,
        private val tmpDir: File,
        private val download: (DownloadRequest) -> DownloadResult,
        private val now: () -> Long = System::currentTimeMillis,
    ) {
        private data class Cached(val at: Long, val items: List<GoogleNewsFeed.Item>)
        private val cache = ConcurrentHashMap<String, Cached>()

        private fun int(key: String, default: Int, min: Int, max: Int) = setting(key)?.trim()?.toDoubleOrNull()?.toInt()?.coerceIn(min, max) ?: default
        private fun bool(key: String, default: Boolean) = setting(key)?.trim()?.lowercase()?.let { it != "false" && it != "0" && it != "off" } ?: default
        private fun text(key: String, default: String) = setting(key)?.trim()?.takeIf { it.isNotEmpty() } ?: default

        fun headlines(query: String?, topic: String?, source: String?, max: Int?): JsonObject {
            val cap = int(MAX_ITEMS_KEY, DEFAULT_MAX_ITEMS, 1, HARD_MAX_ITEMS)
            val n = (max ?: int(DEFAULT_ITEMS_KEY, DEFAULT_ITEMS, 1, cap)).coerceIn(1, cap)
            val req = GoogleNewsFeed.request(clip(query), clip(topic), clip(source), text(LANG_KEY, DEFAULT_LANG), text(COUNTRY_KEY, DEFAULT_COUNTRY))
            val items = fetch(req.url, cap).take(n).let { list ->
                if (!bool(ICONS_KEY, true)) list else list.map { it.copy(icon = GoogleNewsFeed.iconFor(it.sourceUrl)) }
            }
            val body = cardBody(req.label, items)
            return buildJsonObject {
                put("title", req.label)
                put("feed", req.url)
                put("count", items.size)
                put("card", "```card:news\n$body\n```")
            }
        }

        private fun fetch(url: String, cap: Int): List<GoogleNewsFeed.Item> {
            val ttl = int(CACHE_MINUTES_KEY, DEFAULT_CACHE_MINUTES, 0, MAX_CACHE_MINUTES) * MINUTE_MS
            cache[url]?.takeIf { ttl > 0 && now() - it.at < ttl }?.let { return it.items }
            tmpDir.mkdirs()
            val dest = File(tmpDir, "${UUID.randomUUID()}.xml")
            try {
                val result = download(
                    DownloadRequest(
                        url = url, dest = dest, title = "news feed",
                        headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/rss+xml, application/xml;q=0.9, */*;q=0.5"),
                        maxBytes = int(FEED_BYTES_KEY, DEFAULT_FEED_BYTES, MIN_FEED_BYTES, MAX_FEED_BYTES).toLong(),
                        // A headline fetch is not a task the owner watches on the progress bar.
                        progress = NoProgress.start(ProgressSpec(title = "news feed")),
                    ),
                )
                if (!result.ok) {
                    throw IllegalStateException(
                        if (result.status in 400..599) "Google News answered HTTP ${result.status}"
                        else "the news feed could not be fetched (${result.error?.take(160) ?: "offline?"})",
                    )
                }
                val xml = (result.file ?: dest).readText(Charsets.UTF_8)
                val items = GoogleNewsFeed.parse(xml, cap)
                if (ttl > 0) {
                    if (cache.size >= MAX_CACHED_FEEDS) cache.clear()
                    cache[url] = Cached(now(), items)
                }
                return items
            } finally {
                dest.delete()
                File(dest.path + ".part").delete()
            }
        }

        private fun clip(s: String?) = s?.trim()?.take(MAX_PARAM_CHARS)?.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val HEADLINES = "news.headlines"
        const val DEFAULT_ITEMS_KEY = "news.items"
        const val DEFAULT_ITEMS = 6
        const val MAX_ITEMS_KEY = "limits.news.maxItems"
        const val DEFAULT_MAX_ITEMS = 20
        private const val HARD_MAX_ITEMS = 100
        const val LANG_KEY = "news.language"
        const val DEFAULT_LANG = "en-US"
        const val COUNTRY_KEY = "news.country"
        const val DEFAULT_COUNTRY = "US"
        const val ICONS_KEY = "news.sourceIcons"
        const val CACHE_MINUTES_KEY = "limits.news.cacheMinutes"
        const val DEFAULT_CACHE_MINUTES = 5
        private const val MAX_CACHE_MINUTES = 1440
        const val FEED_BYTES_KEY = "limits.news.feedBytes"
        const val DEFAULT_FEED_BYTES = 2_097_152
        private const val MIN_FEED_BYTES = 16_384
        private const val MAX_FEED_BYTES = 20_971_520
        private const val MAX_CACHED_FEEDS = 64
        private const val MAX_PARAM_CHARS = 200
        private const val MINUTE_MS = 60_000
        private const val USER_AGENT = "InfantryAI-News/1.0"

        /** The `card:news` body: `{"title": <label>, "items": [{title, source, url, time, sourceUrl?, image?, icon?}]}`. */
        fun cardBody(label: String, items: List<GoogleNewsFeed.Item>): JsonObject = buildJsonObject {
            put("title", label)
            put("items", buildJsonArray {
                items.forEach { i ->
                    add(buildJsonObject {
                        put("title", i.title)
                        put("source", i.source)
                        put("url", i.url)
                        if (i.time.isNotEmpty()) put("time", i.time)
                        if (i.sourceUrl.isNotEmpty()) put("sourceUrl", i.sourceUrl)
                        if (i.image.isNotEmpty()) put("image", i.image)
                        if (i.icon.isNotEmpty()) put("icon", i.icon)
                    })
                }
            })
        }
    }
}
