// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.superiorsoftllc.infantry.ui.model.BuiltInChatMarkdownEngine
import com.superiorsoftllc.infantry.ui.model.ChatBlock
import com.superiorsoftllc.infantry.ui.model.parseChatMarkdownSafely
import com.superiorsoftllc.infantry.ui.plugin.config.Implementations
import com.superiorsoftllc.infantry.ui.plugin.config.RegisteredImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** CARD-2: card blocks go to the `chat.cards` registry; everything else draws exactly as before. */
class ChatCardRegistryTest {
    private object Hello : ChatCardRenderer {
        @Composable override fun Render(card: ChatCard, modifier: Modifier) {}
    }

    private fun card(text: String) = parseChatMarkdownSafely(BuiltInChatMarkdownEngine, text, false).last()

    @Test fun cardBlockCarriesItsCard() {
        val c = chatCardOf(card("```card:hello id=a1\n{\"title\":\"Hi\"}\n```"))
        assertEquals(ChatCard(type = "hello", id = "a1", body = "{\"title\":\"Hi\"}"), c)
        assertNull(chatCardOf(ChatBlock.Custom("callout", "x", emptyMap())))
        assertNull(chatCardOf(ChatBlock.Code("kotlin", "x")))
    }

    @Test fun rendererForSendsCardsToTheRegistryAndKeepsOverrides() {
        val r = ChatBlockRenderers().also { it.override("callout", CalloutBlockRenderer) }
        assertSame(ChatCardBlockRenderer, r.rendererFor(card("```card:hello\n{}\n```")))
        assertSame(CalloutBlockRenderer, r.rendererFor(ChatBlock.Custom("callout", "x", emptyMap())))
        assertSame(DefaultChatBlockRenderer, r.rendererFor(ChatBlock.Code("kotlin", "x")))
        // An invalid card fence stays a code block: the default draws it.
        assertSame(DefaultChatBlockRenderer, r.rendererFor(card("```card:hello\nnot json\n```")))
        // An app-level override of one card type still wins over the registry.
        r.override("card:hello", CalloutBlockRenderer)
        assertSame(CalloutBlockRenderer, r.rendererFor(card("```card:hello\n{}\n```")))
    }

    @Test fun registryLookupByType() {
        val impl = Implementations(mapOf(CHAT_CARDS to mapOf("hello" to RegisteredImplementation(CHAT_CARDS, "hello", "Acme_HelloCard", Hello), "bad" to RegisteredImplementation(CHAT_CARDS, "bad", "X", "not a renderer"))))
        val reg = impl.chatCardRenderer("hello")!!
        assertSame(Hello, reg.renderer)
        assertEquals("Acme_HelloCard", reg.module)
        assertNull(impl.chatCardRenderer("bad"))
        assertNull(impl.chatCardRenderer("news"))
        assertNull(Implementations.NONE.chatCardRenderer("hello"))
    }
}
