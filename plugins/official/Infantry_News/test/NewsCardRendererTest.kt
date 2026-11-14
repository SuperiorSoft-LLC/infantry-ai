// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.news

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.ui.ChatCard
import com.superiorsoftllc.infantry.ui.Palette
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewsCardRendererTest {
    private val body = """{"title":"Technology","items":[
        {"title":"New chip doubles battery life in laptops","source":"The Verge","url":"https://news.google.com/rss/articles/a","time":"2026-11-13T08:00:00Z","icon":"https://www.google.com/s2/favicons?domain=theverge.com&sz=64"},
        {"title":"Open-source model tops coding benchmark","source":"Ars Technica","url":"https://news.google.com/rss/articles/b","time":"2026-11-12T20:00:00Z"},
        {"source":"no title: dropped","url":"https://x.y"},
        {"title":"Unsafe link stays text","source":"Example","url":"javascript:alert(1)"}]}"""

    @Test fun `body parses items and drops junk`() {
        val b = NewsCardBody.parse(body)
        assertEquals("Technology", b.title)
        assertEquals(3, b.items.size)
        assertEquals("The Verge", b.items[0].source)
        assertEquals(NewsCardBody("", emptyList()), NewsCardBody.parse("not json"))
        assertEquals(0, NewsCardBody.parse("""{"items":"x"}""").items.size)
    }

    @Test fun `relative time reads like a news site`() {
        val now = 1_763_020_800_000L // 2025-11-13T08:00:00Z
        assertEquals("just now", relativeTime("2025-11-13T07:59:30Z", now))
        assertEquals("5m ago", relativeTime("2025-11-13T07:55:00Z", now))
        assertEquals("3h ago", relativeTime("2025-11-13T05:00:00Z", now))
        assertEquals("2d ago", relativeTime("2025-11-11T08:00:00Z", now))
        assertEquals("", relativeTime("yesterday", now))
        assertEquals("", relativeTime("", now))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun `draws one row per headline with a badge when no image is served, and a png`() = runComposeUiTest {
        setContent {
            Box(Modifier.width(560.dp).background(Palette.Bg).padding(12.dp)) {
                NewsCardRenderer.Render(ChatCard(type = NEWS_CARD, body = body), Modifier)
            }
        }
        waitForIdle()
        assertEquals(1, onAllNodesWithTag(NEWS_CARD_TAG).fetchSemanticsNodes().size)
        assertEquals(3, onAllNodesWithTag(NEWS_ITEM_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
        // No daemon in the test: every thumbnail falls back to the letter badge, never an error.
        assertEquals(3, onAllNodesWithTag(NEWS_BADGE_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
        onNodeWithText("New chip doubles battery life in laptops", useUnmergedTree = true).assertExists()
        onNodeWithText("3 headlines").assertExists()
        val out = File(System.getProperty("news.png") ?: "build/news-card.png")
        out.parentFile?.mkdirs()
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
        assertTrue(out.length() > 0)
    }
}
