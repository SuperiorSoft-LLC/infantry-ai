package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.BuiltInChatMarkdownEngine
import com.superiorsoftllc.agenthq.ui.model.ChatBlock
import com.superiorsoftllc.agenthq.ui.model.ChatMarkdownEngine
import com.superiorsoftllc.agenthq.ui.model.ChatMarkdownFeature
import com.superiorsoftllc.agenthq.ui.model.ChatMarkdownRegistry
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.chatBlockType
import com.superiorsoftllc.agenthq.ui.model.chatLineBlocks
import com.superiorsoftllc.agenthq.ui.model.parseChatMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A tiny second engine: everything is one plain-text block. Proves a library-backed engine can drop in. */
private object PlainTextEngine : ChatMarkdownEngine {
    override val id = "plain"
    override val label = "Plain text"
    override fun parse(text: String, streaming: Boolean): List<ChatBlock> = listOf(ChatBlock.Custom("plain", text))
    override fun supports(feature: ChatMarkdownFeature) = false
}

private object ThrowingEngine : ChatMarkdownEngine {
    override val id = "boom"
    override val label = "Boom"
    override fun parse(text: String, streaming: Boolean): List<ChatBlock> = error("engine failed")
    override fun supports(feature: ChatMarkdownFeature) = false
}

class ChatMarkdownEngineTest {
    private val reply = "# T\n\n- a\n- b"

    @Test
    fun `the built-in engine is the default and parses like the old function`() {
        val registry = ChatMarkdownRegistry()
        assertSame(BuiltInChatMarkdownEngine, registry.resolve(null))
        assertSame(BuiltInChatMarkdownEngine, registry.resolve("no-such-engine"))
        assertEquals(parseChatMarkdown(reply), chatLineBlocks(LineKind.TEXT, reply))
        assertEquals(listOf("builtin"), registry.available().map { it.id })
    }

    @Test
    fun `a registered engine is selectable and is the one the chat uses`() {
        val registry = ChatMarkdownRegistry().apply { register(PlainTextEngine) }
        assertEquals(listOf("builtin", "plain"), registry.available().map { it.id })
        val engine = registry.resolve("plain")
        assertSame(PlainTextEngine, engine)
        assertEquals(listOf(ChatBlock.Custom("plain", reply)), chatLineBlocks(LineKind.TEXT, reply, engine))
        registry.unregister("plain")
        assertSame(BuiltInChatMarkdownEngine, registry.resolve("plain"))
    }

    @Test
    fun `the built-in engine id cannot be replaced`() {
        val registry = ChatMarkdownRegistry()
        registry.register(object : ChatMarkdownEngine by PlainTextEngine { override val id = "builtin" })
        assertSame(BuiltInChatMarkdownEngine, registry.resolve("builtin"))
    }

    @Test
    fun `an engine that throws degrades to one plain paragraph`() {
        assertEquals(listOf(ChatBlock.Paragraph("x | y")), chatLineBlocks(LineKind.TEXT, "x | y", ThrowingEngine))
        assertEquals(emptyList(), chatLineBlocks(LineKind.TEXT, "  ", ThrowingEngine))
    }

    @Test
    fun `a per-block override is used for its type only`() {
        val renderers = ChatBlockRenderers()
        assertSame(DefaultChatBlockRenderer, renderers.rendererFor(ChatBlock.Rule))
        val mine = ChatBlockRenderer { }
        renderers.override("rule", mine)
        renderers.override("plain", mine)
        assertSame(mine, renderers.rendererFor(ChatBlock.Rule))
        assertSame(mine, renderers.rendererFor(ChatBlock.Custom("plain", "x")))
        assertSame(DefaultChatBlockRenderer, renderers.rendererFor(ChatBlock.Paragraph("p")))
        renderers.clear("rule")
        assertSame(DefaultChatBlockRenderer, renderers.rendererFor(ChatBlock.Rule))
    }

    @Test
    fun `an unknown custom block falls back to the default renderer`() {
        val block = ChatBlock.Custom("mermaid", "graph TD", mapOf("k" to "v"))
        assertEquals("mermaid", chatBlockType(block))
        assertSame(DefaultChatBlockRenderer, ChatBlockRenderers().rendererFor(block))
        assertTrue(ChatMarkdownConfig().engine === BuiltInChatMarkdownEngine)
    }
}
