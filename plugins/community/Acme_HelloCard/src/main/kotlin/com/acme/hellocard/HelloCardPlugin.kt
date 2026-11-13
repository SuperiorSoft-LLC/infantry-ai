// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.acme.hellocard

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.CHAT_CARDS
import com.superiorsoftllc.infantry.ui.ChatCard
import com.superiorsoftllc.infantry.ui.ChatCardRenderer
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

/** What a `card:hello` body says: `{"title": "...", "text": "..."}`; a missing key reads as empty, a non-object as nothing. */
data class HelloCardBody(val title: String, val text: String) {
    companion object {
        fun parse(body: String): HelloCardBody {
            val o = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            fun s(k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            return HelloCardBody(s("title").ifEmpty { "Hello" }, s("text"))
        }
    }
}

/** Draws one hello card: a bordered box with a bold title and its text, in the window's Material colours. */
object HelloCardRenderer : ChatCardRenderer {
    @Composable
    override fun Render(card: ChatCard, modifier: Modifier) {
        val body = HelloCardBody.parse(card.body)
        val colors = MaterialTheme.colorScheme
        Column(
            modifier.fillMaxWidth().testTag(HELLO_CARD_TAG)
                .border(1.dp, colors.primary, RoundedCornerShape(8.dp))
                .padding(12.dp),
        ) {
            Text("👋 ${body.title}", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (body.text.isNotEmpty()) Text(body.text, color = colors.onSurface, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
