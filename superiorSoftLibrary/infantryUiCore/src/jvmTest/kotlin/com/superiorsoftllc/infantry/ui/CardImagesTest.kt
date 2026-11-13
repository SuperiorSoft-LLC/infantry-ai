// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** CARD-IMG-1: the client asks the daemon (`card_image`) and turns its answer into a thumbnail or a badge reason. */
class CardImagesTest {
    private val bitmap: ImageBitmap = CardFakeBitmap
    private val decode: (ByteArray) -> ImageBitmap = { bitmap }

    @Test
    fun `an image answer is shown and cached, a refusal is a badge`() { runBlocking {
        CardImageLoader.clear()
        val asked = mutableListOf<String>()
        val ok: suspend (String, String) -> Pair<String, String>? = { t, d -> asked += d; t to """{"ok":true,"mime":"image/png","base64":"iVBORw=="}""" }
        assertIs<CardImage.Shown>(CardImageLoader.load("hello", "https://upload.wikimedia.org/a.png", ok, decode))
        assertIs<CardImage.Shown>(CardImageLoader.load("hello", "https://upload.wikimedia.org/a.png", ok, decode))
        assertEquals(1, asked.size, "the second ask is served from the client cache")
        assertTrue(""""type":"hello"""" in asked.single() && "upload.wikimedia.org" in asked.single())

        val no: suspend (String, String) -> Pair<String, String>? = { t, _ -> t to """{"ok":false,"refused":"host x is not an image host"}""" }
        val r = CardImageLoader.load("hello", "https://x.example/a.png", no, decode)
        assertIs<CardImage.Unavailable>(r)
        assertTrue(r.refused)
        }
    }

    @Test
    fun `no url, no link, an error frame and a failure never throw`() { runBlocking {
        CardImageLoader.clear()
        assertIs<CardImage.Unavailable>(CardImageLoader.load("hello", null, null, decode))
        assertIs<CardImage.Unavailable>(CardImageLoader.load("hello", "https://a.b/c.png", null, decode))
        val err: suspend (String, String) -> Pair<String, String>? = { _, _ -> "error" to "unknown frame type 'card_image'" }
        assertEquals(false, (CardImageLoader.load("hello", "https://a.b/c.png", err, decode) as CardImage.Unavailable).refused)
        val failed: suspend (String, String) -> Pair<String, String>? = { t, _ -> t to """{"ok":false,"failed":"HTTP 404"}""" }
        assertEquals("HTTP 404", (CardImageLoader.load("hello", "https://a.b/d.png", failed, decode) as CardImage.Unavailable).reason)
        val bad: (ByteArray) -> ImageBitmap = { error("not an image") }
        val ok: suspend (String, String) -> Pair<String, String>? = { t, _ -> t to """{"ok":true,"base64":"AAAA"}""" }
        assertIs<CardImage.Unavailable>(CardImageLoader.load("hello", "https://a.b/e.png", ok, bad))
    }
    }
}

/** No Skiko native in a unit test: a bitmap that is only a value. */
private object CardFakeBitmap : ImageBitmap {
    override val width = 1
    override val height = 1
    override val config = androidx.compose.ui.graphics.ImageBitmapConfig.Argb8888
    override val hasAlpha = true
    override val colorSpace = androidx.compose.ui.graphics.colorspace.ColorSpaces.Srgb
    override fun prepareToDraw() {}
    override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) {}
}
