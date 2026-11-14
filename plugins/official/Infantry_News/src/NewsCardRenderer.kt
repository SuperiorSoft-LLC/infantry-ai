// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.news

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.CardImage
import com.superiorsoftllc.infantry.ui.ChatCard
import com.superiorsoftllc.infantry.ui.ChatCardRenderer
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.clickable
import com.superiorsoftllc.infantry.ui.model.isSafeChatUrl
import com.superiorsoftllc.infantry.ui.rememberCardImage
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

const val NEWS_CARD_TAG = "news-card"
const val NEWS_ITEM_TAG = "news-card-item"
const val NEWS_THUMB_TAG = "news-card-thumb"
const val NEWS_ICON_TAG = "news-card-icon"
const val NEWS_BADGE_TAG = "news-card-badge"

/** How often the relative times ("5m ago") are redrawn while the card is on screen. */
private const val CLOCK_TICK_MS = 60_000L

/**
 * NEWS-1: what a `card:news` body says: `{"title": "...", "items": [{title, source, url, time?, image?, icon?}]}`. An item
 * without a title is dropped; a malformed body reads as no items (the renderer then says so in one muted line).
 */
data class NewsCardBody(val title: String, val items: List<Item>) {
    data class Item(val title: String, val source: String, val url: String, val time: String, val image: String, val icon: String)

    companion object {
        fun parse(body: String): NewsCardBody {
            val o = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            val items = (o?.get("items") as? JsonArray).orEmpty().mapNotNull { e ->
                val i = e as? JsonObject ?: return@mapNotNull null
                val title = i.s("title").ifEmpty { return@mapNotNull null }
                Item(title, i.s("source"), i.s("url"), i.s("time"), i.s("image"), i.s("icon"))
            }
            return NewsCardBody(o?.s("title").orEmpty(), items)
        }
    }
}

/** NEWS-1: [iso] (ISO-8601) as an age next to [nowMs]: "just now", "5m ago", "3h ago", "2d ago"; unparsable = empty. */
@OptIn(ExperimentalTime::class)
fun relativeTime(iso: String, nowMs: Long): String {
    if (iso.isBlank()) return ""
    val then = runCatching { Instant.parse(iso.trim()).toEpochMilliseconds() }.getOrNull() ?: return ""
    val s = ((nowMs - then) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        else -> "${s / 86_400}d ago"
    }
}

@OptIn(ExperimentalTime::class)
private fun nowMs() = Clock.System.now().toEpochMilliseconds()

/**
 * NEWS-1 (docs/CARD-REGISTRY.md 2.6): draws a `card:news` block as a bordered card holding a list of headlines, each its own
 * bordered row: a thumbnail on the left (the item's `image`; else the source's site icon; else a letter badge, so the layout
 * never jumps), the source and relative time in small muted text, the headline in bold. A row opens its link through the
 * platform URI handler, and only an http(s) link (the chat's safe-link rule). Images come only through the daemon proxy
 * (`rememberCardImage`), from the hosts module.json declares for this card type.
 */
object NewsCardRenderer : ChatCardRenderer {
    @Composable
    override fun Render(card: ChatCard, modifier: Modifier) {
        val body = remember(card.body) { NewsCardBody.parse(card.body) }
        var now by remember { mutableLongStateOf(nowMs()) }
        LaunchedEffect(Unit) { while (true) { delay(CLOCK_TICK_MS); now = nowMs() } }
        Column(
            modifier.fillMaxWidth().testTag(NEWS_CARD_TAG)
                .border(1.dp, Palette.Border, RoundedCornerShape(10.dp))
                .background(Palette.Panel, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📰", fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text(body.title.ifEmpty { "Headlines" }, color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (body.items.isNotEmpty()) Text("${body.items.size} headlines", color = Palette.Muted, fontSize = 11.sp)
            }
            if (body.items.isEmpty()) Text("No headlines in this card.", color = Palette.Muted, fontSize = 12.sp)
            body.items.forEach { HeadlineRow(it, now) }
        }
    }

    @Composable
    private fun HeadlineRow(item: NewsCardBody.Item, now: Long) {
        val uri = LocalUriHandler.current
        val canOpen = item.url.startsWith("http") && isSafeChatUrl(item.url)
        Row(
            Modifier.fillMaxWidth().testTag(NEWS_ITEM_TAG)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Palette.Border, RoundedCornerShape(8.dp))
                .background(Palette.Raised)
                .clickable(enabled = canOpen, onClickLabel = "Open article") { runCatching { uri.openUri(item.url) } }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(item)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                val age = relativeTime(item.time, now)
                val meta = listOf(item.source, age).filter { it.isNotEmpty() }.joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, color = Palette.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.title, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (canOpen) Text("↗", color = Palette.Info, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }

    /** The left square: the article image, else the source icon (small, centred on a tile), else the source's first letter. */
    @Composable
    private fun Thumbnail(item: NewsCardBody.Item) {
        val shape = RoundedCornerShape(6.dp)
        val tile = Modifier.size(64.dp).clip(shape)
        val image = if (item.image.isNotEmpty()) rememberCardImage(NEWS_CARD, item.image) else null
        if (image is CardImage.Shown) {
            Image(image.bitmap, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = tile.testTag(NEWS_THUMB_TAG))
            return
        }
        val icon = if (item.icon.isNotEmpty()) rememberCardImage(NEWS_CARD, item.icon) else null
        Box(tile.background(Palette.Bg).border(1.dp, Palette.Border, shape), contentAlignment = Alignment.Center) {
            if (icon is CardImage.Shown) {
                Image(icon.bitmap, contentDescription = item.source, contentScale = ContentScale.Fit, modifier = Modifier.size(32.dp).testTag(NEWS_ICON_TAG))
            } else {
                val letter = (item.source.ifEmpty { item.title }).firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
                Text(letter, color = Palette.Accent, fontWeight = FontWeight.Bold, fontSize = 24.sp, modifier = Modifier.testTag(NEWS_BADGE_TAG))
            }
        }
    }
}
