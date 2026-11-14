// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.news.daemon

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * NEWS-1: Google News RSS, the pure half (no network): which feed URL a request maps to, and the parse of that feed into
 * headline items (the `card:news` item shape). Google News needs no key; its RSS lives under `news.google.com/rss`.
 */
object GoogleNewsFeed {
    const val BASE = "https://news.google.com/rss"

    /** The Google News topic sections (`/rss/headlines/section/topic/<ID>`); a `topic` outside them is searched for instead. */
    val TOPICS = linkedMapOf(
        "world" to "WORLD", "nation" to "NATION", "us" to "NATION", "business" to "BUSINESS", "technology" to "TECHNOLOGY",
        "tech" to "TECHNOLOGY", "entertainment" to "ENTERTAINMENT", "sports" to "SPORTS", "sport" to "SPORTS",
        "science" to "SCIENCE", "health" to "HEALTH",
    )

    private val DOMAIN = Regex("^(?:https?://)?(?:www\\.)?([a-z0-9-]+(?:\\.[a-z0-9-]+)+)/?$", RegexOption.IGNORE_CASE)

    /** What the request asks for, resolved: the feed [url] and a short [label] for the card's heading. */
    data class Request(val url: String, val label: String)

    /**
     * The feed for [query] / [topic] / [source] in locale [lang] (`en-US`) and country [country] (`US`):
     * - nothing: the top stories (`/rss`);
     * - a known [topic] alone: its section feed;
     * - anything else: the search feed, `q` = query + topic + the source (`site:<domain>` when it looks like a domain, else its name).
     */
    fun request(query: String?, topic: String?, source: String?, lang: String, country: String): Request {
        val q = query?.trim().orEmpty()
        val t = topic?.trim().orEmpty()
        val s = source?.trim().orEmpty()
        val ceid = "hl=${enc(lang)}&gl=${enc(country)}&ceid=${enc("$country:${lang.substringBefore('-')}")}"
        val section = TOPICS[t.lowercase()]
        if (q.isEmpty() && s.isEmpty()) {
            if (t.isEmpty()) return Request("$BASE?$ceid", "Top stories")
            if (section != null) return Request("$BASE/headlines/section/topic/$section?$ceid", t.replaceFirstChar { it.uppercase() })
        }
        val domain = DOMAIN.matchEntire(s)?.groupValues?.get(1)?.lowercase()
        val terms = listOf(q, t, domain?.let { "site:$it" } ?: s).filter { it.isNotEmpty() }
        val name = domain ?: s
        val label = listOf(q, t).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Latest" } + if (name.isNotEmpty()) " ($name)" else ""
        return Request("$BASE/search?q=${enc(terms.joinToString(" "))}&$ceid", label)
    }

    /** One headline: the `card:news` item shape (empty strings are left out of the JSON). */
    data class Item(
        val title: String,
        val source: String,
        val url: String,
        val time: String,
        val sourceUrl: String = "",
        val image: String = "",
        val icon: String = "",
    )

    /**
     * The items of an RSS 2.0 feed (Google News or any other), in feed order, at most [max]. A Google News title carries
     * " - <Source>" at its end; that suffix is cut when it equals the item's `<source>`. `media:content` / `media:thumbnail` /
     * an image `enclosure` give [Item.image]. A malformed feed throws [IllegalArgumentException].
     */
    fun parse(xml: String, max: Int): List<Item> {
        val doc = runCatching { builder().parse(InputSource(StringReader(xml))) }
            .getOrElse { throw IllegalArgumentException("the feed is not valid RSS (${it.message?.take(120)})") }
        val nodes = doc.getElementsByTagName("item")
        val out = ArrayList<Item>()
        for (i in 0 until nodes.length) {
            if (out.size >= max) break
            val e = nodes.item(i) as? Element ?: continue
            val sourceEl = child(e, "source")
            val source = sourceEl?.textContent?.clean().orEmpty()
            var title = text(e, "title")
            if (source.isNotEmpty() && title.endsWith(" - $source")) title = title.removeSuffix(" - $source").trim()
            val url = text(e, "link")
            if (title.isEmpty() || !url.startsWith("http")) continue
            out += Item(
                title = title,
                source = source.ifEmpty { hostOf(url) },
                url = url,
                time = isoTime(text(e, "pubDate")),
                sourceUrl = sourceEl?.getAttribute("url")?.trim().orEmpty(),
                image = imageOf(e),
            )
        }
        return out
    }

    /** The source's site icon through Google's favicon service (redirects to `*.gstatic.com`); empty when [sourceUrl] has no host. */
    fun iconFor(sourceUrl: String): String {
        val host = hostOf(sourceUrl).ifEmpty { return "" }
        return "https://www.google.com/s2/favicons?domain=${enc(host)}&sz=64"
    }

    /** RFC 1123 (`Thu, 12 Nov 2026 09:30:00 GMT`) to ISO-8601; unparsable stays as given. */
    fun isoTime(raw: String): String = runCatching {
        ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString()
    }.getOrElse { raw.trim() }

    private fun imageOf(e: Element): String {
        for (tag in listOf("media:content", "media:thumbnail", "enclosure")) {
            val nodes = e.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val m = nodes.item(i) as? Element ?: continue
                val url = m.getAttribute("url").trim()
                val type = m.getAttribute("type").trim().lowercase()
                val medium = m.getAttribute("medium").trim().lowercase()
                if (!url.startsWith("https://")) continue
                if (tag == "enclosure" && !type.startsWith("image/")) continue
                if (tag == "media:content" && medium.isNotEmpty() && medium != "image") continue
                return url
            }
        }
        return ""
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url.trim()).host?.lowercase()?.removePrefix("www.") }.getOrNull().orEmpty()

    private fun child(e: Element, tag: String): Element? {
        val nodes = e.getElementsByTagName(tag)
        return if (nodes.length > 0) nodes.item(0) as? Element else null
    }

    private fun text(e: Element, tag: String) = child(e, tag)?.textContent?.clean().orEmpty()

    private fun String.clean() = replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    /** A parser with DOCTYPEs and external entities off (a feed is untrusted input). Namespaces off: `media:content` is matched by its qualified name. */
    private fun builder() = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
        isNamespaceAware = false
    }.newDocumentBuilder()
}
