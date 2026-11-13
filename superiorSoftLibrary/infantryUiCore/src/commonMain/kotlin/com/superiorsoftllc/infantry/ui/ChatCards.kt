// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import com.superiorsoftllc.infantry.ui.block.layout.GuardedBlock
import com.superiorsoftllc.infantry.ui.block.layout.OverrideHealth
import com.superiorsoftllc.infantry.ui.block.layout.OverrideVerdict
import com.superiorsoftllc.infantry.ui.model.CARD_TYPE_PREFIX
import com.superiorsoftllc.infantry.ui.model.CardKeys
import com.superiorsoftllc.infantry.ui.model.ChatBlock
import com.superiorsoftllc.infantry.ui.plugin.PluginFaults
import com.superiorsoftllc.infantry.ui.plugin.config.Implementations

/**
 * CARD-2 (docs/CARD-REGISTRY.md, section 2.2): the registry a chat card renderer goes in. A module registers one with
 * `registrar.implementation(CHAT_CARDS, "<card type>", renderer)` and declares it under `implementations` in its
 * module.json; a reply's ```` ```card:<type> ```` fence then draws with it. Any module, any tier, any role: cards are
 * open to the whole app. With nothing registered for a type (a disabled or removed plugin), or a renderer that throws,
 * the card draws as its original fenced code block, exactly what a client without the plugin shows.
 */
const val CHAT_CARDS = "chat.cards"

/** CARD-2: how complete a card's body is. v1 renderers only ever see [COMPLETE]; the others are reserved (section 2.1). */
enum class CardState { COMPLETE, STREAMING, INVALID }

/**
 * CARD-2: everything a card renderer may use. Plain data: [type] is the card type without the `card:` prefix (`hello`),
 * [id] the optional `id=` attribute, [body] the fence's JSON object as text (the renderer parses it), [updates] the raw
 * bodies of later update fences (CARD-UPDATE-1; empty in v1). A new field is added with a default, so an existing
 * renderer keeps compiling.
 */
data class ChatCard(
    val type: String,
    val id: String? = null,
    val body: String = "{}",
    val state: CardState = CardState.COMPLETE,
    val updates: List<String> = emptyList(),
)

/** CARD-2: a plugin's drawing of one chat card. A renderer that throws is dropped for the code block and reported as its module's fault. */
fun interface ChatCardRenderer {
    @Composable
    fun Render(card: ChatCard, modifier: Modifier)
}

/** CARD-2: the [ChatCard] a card block carries, or null when [block] is not a card block. */
fun chatCardOf(block: ChatBlock): ChatCard? {
    val custom = block as? ChatBlock.Custom ?: return null
    if (!custom.type.startsWith(CARD_TYPE_PREFIX)) return null
    val state = when (custom.data[CardKeys.STATE]) {
        "streaming" -> CardState.STREAMING
        "invalid" -> CardState.INVALID
        else -> CardState.COMPLETE
    }
    return ChatCard(
        type = custom.type.removePrefix(CARD_TYPE_PREFIX),
        id = custom.data[CardKeys.ID]?.takeIf { it.isNotEmpty() },
        body = custom.data[CardKeys.BODY] ?: "{}",
        state = state,
    )
}

/** CARD-2: a renderer a module registered for a card type: the type, the module that owns it, and the renderer. */
class ChatCardRegistration(val type: String, val module: String, val renderer: ChatCardRenderer)

/** CARD-2: what is registered for card [type] under [CHAT_CARDS], or null (nothing, or not a [ChatCardRenderer]). */
fun Implementations.chatCardRenderer(type: String): ChatCardRegistration? {
    val renderer = get(CHAT_CARDS, type) as? ChatCardRenderer ?: return null
    return ChatCardRegistration(type, moduleOf(CHAT_CARDS, type) ?: type, renderer)
}

/**
 * CARD-2: the chat's renderer of every `card:*` block. The registry is read at DRAW time from the window's
 * [LocalTabFactory], so installing, enabling, disabling or removing a card plugin swaps the drawing live. A complete card
 * with a registered renderer draws as that module (probed once, like an avatar style); everything else falls back to
 * [DefaultChatBlockRenderer] (the original code block).
 */
object ChatCardBlockRenderer : ChatBlockRenderer {
    @Composable
    override fun Render(block: ChatBlock) {
        val card = chatCardOf(block)
        val reg = card?.takeIf { it.state == CardState.COMPLETE }?.let { LocalTabFactory.current.implementations.chatCardRenderer(it.type) }
        if (card == null || reg == null) {
            DefaultChatBlockRenderer.Render(block)
            return
        }
        val key = OverrideHealth.key(reg.module, "card", "$CHAT_CARDS/${reg.type}")
        com.superiorsoftllc.infantry.ui.plugin.ModuleScope(reg.module) {
            GuardedBlock(key, reg.renderer, fallback = { DefaultChatBlockRenderer.Render(block) }) {
                reg.renderer.Render(card, Modifier)
            }
        }
        (OverrideHealth.verdict(key, reg.renderer) as? OverrideVerdict.Faulted)?.let { fault ->
            SideEffect { PluginFaults.record(reg.module, IllegalStateException(fault.message), PluginFaults.generationLookup(reg.module)) }
        }
    }
}
