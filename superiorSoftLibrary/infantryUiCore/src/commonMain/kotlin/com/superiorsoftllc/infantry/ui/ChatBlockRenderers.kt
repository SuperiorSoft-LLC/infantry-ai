// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.BuiltInChatMarkdownEngine
import com.superiorsoftllc.infantry.ui.model.ChatBlock
import com.superiorsoftllc.infantry.ui.model.ChatMarkdownEngine
import com.superiorsoftllc.infantry.ui.model.ChatSpan
import com.superiorsoftllc.infantry.ui.model.chatBlockType
import com.superiorsoftllc.infantry.ui.model.parseChatInline

/** Draws one [ChatBlock]. Register one in [ChatBlockRenderers] to change how a block type (or a plugin's custom type) looks. */
fun interface ChatBlockRenderer {
    @Composable
    fun Render(block: ChatBlock)
}

/**
 * CHAT-MD-ENGINE: which composable draws which block type. Anything without an override uses [DefaultChatBlockRenderer],
 * which draws every built-in block and shows an unknown [ChatBlock.Custom] as its plain text, so a block this client
 * does not know never crashes it.
 */
class ChatBlockRenderers {
    private val overrides = HashMap<String, ChatBlockRenderer>()

    /** Draw blocks of [type] (see chatBlockType) with [renderer]; replaces an earlier override of that type. */
    fun override(type: String, renderer: ChatBlockRenderer) { overrides[type] = renderer }

    fun clear(type: String) { overrides.remove(type) }

    fun rendererFor(block: ChatBlock): ChatBlockRenderer = overrides[chatBlockType(block)] ?: DefaultChatBlockRenderer
}

/** What the chat reads: the engine that parses and the renderers that draw. */
data class ChatMarkdownConfig(val engine: ChatMarkdownEngine = BuiltInChatMarkdownEngine, val renderers: ChatBlockRenderers = ChatBlockRenderers())

val LocalChatMarkdown = staticCompositionLocalOf { ChatMarkdownConfig() }

/** The built-in look of every block type; also the safe fallback for a block with no renderer. */
object DefaultChatBlockRenderer : ChatBlockRenderer {
    @Composable
    override fun Render(block: ChatBlock) {
        when (block) {
            is ChatBlock.Paragraph -> Text(spansText(block.spans), color = Palette.Text, fontFamily = Mono, fontSize = 12.sp)
            is ChatBlock.BulletList -> Column {
                for ((n, item) in block.items.withIndex()) Text(spansText(listOf<ChatSpan>(ChatSpan.Text(if (block.ordered) "${n + 1}. " else "• ")) + parseChatInline(item)), color = Palette.Text, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
            ChatBlock.Rule -> HorizontalDivider(color = Palette.Border)
            is ChatBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(Palette.Border))
                Text(spansText(block.spans), color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
            is ChatBlock.Table -> ChatTable(block)
            is ChatBlock.Heading -> Text(
                spansText(block.spans),
                color = Palette.Text,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = when (block.level) {
                    1 -> 18.sp
                    2 -> 16.sp
                    else -> 14.sp
                },
            )
            is ChatBlock.Code -> {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).border(1.dp, Palette.Border, RoundedCornerShape(6.dp)).padding(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(block.language, color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        CopyValueButton(block.body)
                    }
                    Text(block.body, color = Palette.Text, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.horizontalScroll(rememberScrollState()))
                }
            }
            // A block type this client has no renderer for: its text, plain.
            is ChatBlock.Custom -> Text(block.text, color = Palette.Text, fontFamily = Mono, fontSize = 12.sp)
        }
    }
}

/**
 * MD-PLUGIN-1: draws the `callout` block the example markdown plugin emits (`ChatBlock.Custom("callout", text, {kind})`).
 * Plugins stay Compose-free, so the host owns the look: a tinted, bordered box; kind `warning`/`error` uses the danger tone.
 */
object CalloutBlockRenderer : ChatBlockRenderer {
    @Composable
    override fun Render(block: ChatBlock) {
        val custom = block as? ChatBlock.Custom ?: return
        val kind = custom.data["kind"].orEmpty().ifEmpty { "note" }
        val tone = if (kind == "warning" || kind == "error") Palette.Danger else Palette.Info
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Palette.Raised).border(1.dp, tone, RoundedCornerShape(4.dp)).padding(8.dp)) {
            Text(kind.uppercase(), color = tone, fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(custom.text, color = Palette.Text, fontFamily = Mono, fontSize = 12.sp)
        }
    }
}
