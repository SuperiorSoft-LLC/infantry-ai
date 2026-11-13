// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.acme.hellocard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.CHAT_CARDS
import com.superiorsoftllc.infantry.ui.ChatCard
import com.superiorsoftllc.infantry.ui.ChatCardRenderer
import com.superiorsoftllc.infantry.ui.CardImage
import com.superiorsoftllc.infantry.ui.rememberCardImage
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** CARD-2 example: registers the `hello` card type in the `chat.cards` registry (declared under `implementations` in module.json). */
class HelloCardPlugin : UiPlugin {
    override fun register(registrar: UiRegistrar) {
        registrar.implementation(CHAT_CARDS, HELLO_CARD, HelloCardRenderer)
    }
}

const val HELLO_CARD = "hello"
const val HELLO_CARD_TAG = "acme-hello-card"
const val HELLO_THUMB_TAG = "acme-hello-card-thumb"
const val HELLO_BADGE_TAG = "acme-hello-card-badge"

/**
 * What a `card:hello` body says: `{"title": "...", "text": "...", "image": "https://..."}`; a missing key reads as empty, a
 * non-object as nothing. [image] (CARD-IMG-1) is fetched by the daemon, only from the hosts module.json declares.
 */
data class HelloCardBody(val title: String, val text: String, val image: String = "") {
    companion object {
        fun parse(body: String): HelloCardBody {
            val o = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            fun s(k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            return HelloCardBody(s("title").ifEmpty { "Hello" }, s("text"), s("image"))
        }
    }
}

/**
 * Draws one hello card: a bordered box with a bold title and its text, in the window's Material colours. With an `image`, a
 * thumbnail sits on the left (CARD-IMG-1, fetched by the daemon); while it loads, or when the daemon refused or failed it, the
 * same spot shows a badge with the title's first letter, so the layout never breaks.
 */
object HelloCardRenderer : ChatCardRenderer {
    @Composable
    override fun Render(card: ChatCard, modifier: Modifier) {
        val body = HelloCardBody.parse(card.body)
        val colors = MaterialTheme.colorScheme
        Row(
            modifier.fillMaxWidth().testTag(HELLO_CARD_TAG)
                .border(1.dp, colors.primary, RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (body.image.isNotEmpty()) {
                val thumb = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp))
                when (val img = rememberCardImage(HELLO_CARD, body.image)) {
                    is CardImage.Shown -> Image(img.bitmap, contentDescription = body.title, contentScale = ContentScale.Crop, modifier = thumb.testTag(HELLO_THUMB_TAG))
                    else -> Box(thumb.background(colors.primaryContainer).testTag(HELLO_BADGE_TAG), contentAlignment = Alignment.Center) {
                        Text(body.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", color = colors.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                }
            }
            Column(Modifier.padding(start = if (body.image.isNotEmpty()) 12.dp else 0.dp)) {
                Text("👋 ${body.title}", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (body.text.isNotEmpty()) Text(body.text, color = colors.onSurface, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
