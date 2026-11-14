// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.news.daemon

import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadRequest
import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NewsDaemonPluginTest {
    private val feed = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <rss xmlns:media="http://search.yahoo.com/mrss/" version="2.0"><channel><title>Top stories - Google News</title>
        <item><title>Rocket lands on the moon - Reuters</title><link>https://news.google.com/rss/articles/CBMiAAA?oc=5</link>
          <pubDate>Thu, 12 Nov 2026 09:30:00 GMT</pubDate><description>&lt;a href="x"&gt;Rocket&lt;/a&gt;</description>
          <source url="https://www.reuters.com">Reuters</source></item>
        <item><title><![CDATA[Markets & stocks rally]]></title><link>https://example.com/markets</link>
          <pubDate>not a date</pubDate><media:content url="https://lh3.googleusercontent.com/abc=w200" medium="image"/></item>
        <item><title>no link item</title></item>
        <item><title>Third - BBC</title><link>https://news.google.com/rss/articles/three</link><source url="https://www.bbc.co.uk">BBC</source></item>
        </channel></rss>"""

    @Test fun parsesGoogleNewsItems() {
        val items = GoogleNewsFeed.parse(feed, 10)
        assertEquals(3, items.size)
        val a = items[0]
        assertEquals("Rocket lands on the moon", a.title, "the ' - Source' suffix is cut")
        assertEquals("Reuters", a.source)
        assertEquals("https://www.reuters.com", a.sourceUrl)
        assertEquals("2026-11-12T09:30:00Z", a.time)
        assertEquals("", a.image)
        val b = items[1]
        assertEquals("Markets & stocks rally", b.title)
        assertEquals("example.com", b.source, "no <source>: the link's host")
        assertEquals("https://lh3.googleusercontent.com/abc=w200", b.image)
        assertEquals("not a date", b.time)
        assertEquals(2, GoogleNewsFeed.parse(feed, 2).size)
    }

    @Test fun refusesDoctypeAndGarbage() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><item><title>&x;</title><link>https://a.b/c</link></item></channel></rss>"""
        assertFailsWith<IllegalArgumentException> { GoogleNewsFeed.parse(xxe, 5) }
        assertFailsWith<IllegalArgumentException> { GoogleNewsFeed.parse("not xml", 5) }
    }

    @Test fun mapsRequestsToFeeds() {
        assertEquals("https://news.google.com/rss?hl=en-US&gl=US&ceid=US%3Aen", GoogleNewsFeed.request(null, null, null, "en-US", "US").url)
        val tech = GoogleNewsFeed.request(null, "Technology", null, "en-US", "US")
        assertEquals("https://news.google.com/rss/headlines/section/topic/TECHNOLOGY?hl=en-US&gl=US&ceid=US%3Aen", tech.url)
        assertEquals("Technology", tech.label)
        val search = GoogleNewsFeed.request("SpaceX", null, "https://www.reuters.com/", "en-US", "US")
        assertTrue(search.url.startsWith("https://news.google.com/rss/search?q=SpaceX+site%3Areuters.com&"), search.url)
        assertEquals("SpaceX (reuters.com)", search.label)
        val freeTopic = GoogleNewsFeed.request(null, "electric cars", null, "en-GB", "GB")
        assertTrue(freeTopic.url.contains("/search?q=electric+cars&hl=en-GB&gl=GB&ceid=GB%3Aen"), freeTopic.url)
        assertTrue(GoogleNewsFeed.request(null, null, "BBC News", "en-US", "US").url.contains("q=BBC+News"))
    }

    @Test fun headlinesAnswerCarriesCardFenceAndCaches() {
        val dir = Files.createTempDirectory("news").toFile()
        val seen = mutableListOf<DownloadRequest>()
        val settings = mapOf("news.items" to "2")
        val news = NewsDaemonPlugin.News(settings::get, dir, { r -> seen += r; r.dest.writeText(feed); DownloadResult(ok = true, file = r.dest, bytes = 1) })
        val out = news.headlines(null, "world", null, null)
        assertEquals(1, seen.size)
        assertEquals("news feed", seen[0].title)
        assertTrue(seen[0].progress != null, "a silent progress handle: no progress-bar task per headline call")
        assertEquals(2, (out["count"] as JsonPrimitive).content.toInt())
        val card = out["card"]!!.jsonPrimitive.content
        assertTrue(card.startsWith("```card:news\n") && card.endsWith("\n```"), card)
        val body = Json.parseToJsonElement(card.removePrefix("```card:news\n").removeSuffix("\n```")).jsonObject
        assertEquals("World", body["title"]!!.jsonPrimitive.content)
        val first = body["items"]!!.jsonArray[0].jsonObject
        assertEquals("https://www.google.com/s2/favicons?domain=reuters.com&sz=64", first["icon"]!!.jsonPrimitive.content)
        assertTrue("image" !in first, "empty fields are left out")
        news.headlines(null, "world", null, 1)
        assertEquals(1, seen.size, "the second identical call is served from the cache")
        assertTrue(dir.listFiles().orEmpty().isEmpty(), "the temp feed file is removed")
    }

    @Test fun failedFetchIsAClearError() {
        val dir = Files.createTempDirectory("news").toFile()
        val news = NewsDaemonPlugin.News({ null }, dir, { DownloadResult(ok = false, status = 503, error = "HTTP 503") })
        val e = assertFailsWith<IllegalStateException> { news.headlines("x", null, null, null) }
        assertEquals("Google News answered HTTP 503", e.message)
        val iconsOff = NewsDaemonPlugin.News(mapOf("news.sourceIcons" to "false")::get, dir, { r -> r.dest.writeText(feed); DownloadResult(ok = true, file = r.dest) })
        val card = iconsOff.headlines(null, null, null, 5)
        assertTrue("items" !in card, "the items travel once, inside the card fence")
        assertTrue("\"icon\"" !in card["card"]!!.jsonPrimitive.content)
    }
}
