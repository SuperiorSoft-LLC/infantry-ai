package com.superiorsoftllc.agenthq.ui.markdown

import com.superiorsoftllc.agenthq.ui.model.BuiltInChatMarkdownEngine
import com.superiorsoftllc.agenthq.ui.model.ChatBlock
import com.superiorsoftllc.agenthq.ui.model.ChatMarkdownEngine
import com.superiorsoftllc.agenthq.ui.model.ChatMarkdownFeature
import com.superiorsoftllc.agenthq.ui.model.ChatMarkdownRegistry
import com.superiorsoftllc.agenthq.ui.model.ColumnAlign
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.chatLineBlocks
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class FakeEngine(override val id: String, val onParse: (String) -> List<ChatBlock>) : ChatMarkdownEngine {
    override val label = id
    override fun parse(text: String, streaming: Boolean) = onParse(text)
    override fun supports(feature: ChatMarkdownFeature) = true
}

class MarkdownEnginePluginTest {
    private val sample = "# Title\n\n:::callout warning\nmind the gap\n:::\n\n| a | b |\n|:--|--:|\n| 1 | `x|y` |\n\n- one\n- two\n"

    @Test
    fun `the example jar loads, registers, parses, and unloads back to the built-in engine`() {
        val log = ArrayList<String>()
        val registry = ChatMarkdownRegistry()
        val loader = MarkdownEnginePluginLoader(pluginDirWithExample(), registry, log = { log += it })
        assertEquals(listOf("example-callout"), loader.loadAll())
        assertEquals(listOf("builtin", "example-callout"), registry.available().map { it.id })

        // Selected through the id the 'Markdown engine' setting stores.
        val engine = registry.resolve("example-callout")
        assertEquals("Example (callouts)", engine.label)
        val blocks = chatLineBlocks(LineKind.TEXT, sample, engine)
        assertEquals(
            listOf(
                ChatBlock.Heading(1, "Title"),
                ChatBlock.Custom("callout", "mind the gap", mapOf("kind" to "warning")),
                ChatBlock.Table(listOf("a", "b"), listOf(ColumnAlign.START, ColumnAlign.END), listOf(listOf("1", "`x|y`"))),
                ChatBlock.BulletList(listOf("one", "two")),
            ),
            blocks,
        )
        // Visibly different from the built-in engine (which has no callout block).
        assertTrue(chatLineBlocks(LineKind.TEXT, sample, BuiltInChatMarkdownEngine).orEmpty().none { it is ChatBlock.Custom })

        // Deselected / unloaded: the built-in engine's output returns.
        loader.unload("example-callout")
        assertEquals(listOf("builtin"), registry.available().map { it.id })
        assertSame(BuiltInChatMarkdownEngine, registry.resolve("example-callout"))
        assertEquals(BuiltInChatMarkdownEngine.parse(sample), chatLineBlocks(LineKind.TEXT, sample, registry.resolve("example-callout")))
        assertEquals(emptyList(), log)
    }

    @Test
    fun `the plugin jar bundles neither the model API nor the Kotlin runtime`() {
        val registry = ChatMarkdownRegistry()
        val loader = MarkdownEnginePluginLoader(pluginDirWithExample(), registry)
        loader.loadAll()
        val names = java.util.zip.ZipFile(exampleJar()).use { z -> z.entries().asSequence().map { it.name }.toList() }
        assertTrue("com/superiorsoftllc/agenthq/sample/callout/CalloutMarkdownEngine.class" in names)
        assertTrue(names.none { it.startsWith("com/superiorsoftllc/agenthq/ui/") || it.startsWith("kotlin/") }, "the jar must not bundle the API or the Kotlin runtime: $names")
        loader.unloadAll()
    }

    @Test
    fun `streaming partials never throw and show what arrived`() {
        val registry = ChatMarkdownRegistry()
        MarkdownEnginePluginLoader(pluginDirWithExample(), registry, timeoutMs = 10_000).loadAll()
        val engine = registry.resolve("example-callout")
        for (n in 0..sample.length) engine.parse(sample.substring(0, n), streaming = true)
        assertEquals(listOf(ChatBlock.Custom("callout", "mind", mapOf("kind" to "note"))), engine.parse(":::callout\nmind", streaming = true))
        assertEquals(listOf(ChatBlock.Code("kt", "val x")), engine.parse("```kt\nval x", streaming = true))
        assertEquals(listOf("| a | b |"), engine.parse("| a | b |", streaming = true).map { (it as ChatBlock.Paragraph).text })
    }

    @Test
    fun `a jar that is not a plugin is reported and skipped, others still load`() {
        val dir = pluginDirWithExample()
        File(dir, "broken.jar").writeText("not a zip")
        val registry = ChatMarkdownRegistry()
        assertEquals(listOf("example-callout"), MarkdownEnginePluginLoader(dir, registry).loadAll())
        assertEquals(listOf("builtin", "example-callout"), registry.available().map { it.id })
    }

    @Test
    fun `an engine that throws on one message falls back for it, is reported once, and is disabled`() {
        val log = ArrayList<String>()
        val registry = ChatMarkdownRegistry()
        val bad = FakeEngine("bad") { t -> if ("boom" in t) error("kaput") else listOf(ChatBlock.Custom("plain", t)) }
        registry.register(GuardedMarkdownEngine(bad) { _, why -> log += "failed: ${why.message}"; registry.unregister("bad") })
        val engine = registry.resolve("bad")
        assertEquals(listOf(ChatBlock.Custom("plain", "fine")), chatLineBlocks(LineKind.TEXT, "fine", engine))
        assertEquals(BuiltInChatMarkdownEngine.parse("# boom"), chatLineBlocks(LineKind.TEXT, "# boom", engine))
        // The old handle is disabled too, and the registry no longer offers it.
        assertEquals(BuiltInChatMarkdownEngine.parse("later"), chatLineBlocks(LineKind.TEXT, "later", engine))
        assertSame(BuiltInChatMarkdownEngine, registry.resolve("bad"))
        assertEquals(listOf("failed: kaput"), log)
    }

    @Test
    fun `an engine that hangs or throws an Error never blocks or breaks the chat`() {
        val failures = ArrayList<Throwable>()
        val hang = GuardedMarkdownEngine(FakeEngine("hang") { Thread.sleep(60_000); emptyList() }, timeoutMs = 100) { _, why -> failures += why }
        val started = System.nanoTime()
        assertEquals(BuiltInChatMarkdownEngine.parse("x | y"), hang.parse("x | y", false))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        val so = GuardedMarkdownEngine(FakeEngine("so") { throw StackOverflowError() }, timeoutMs = 1_000) { _, why -> failures += why }
        assertEquals(BuiltInChatMarkdownEngine.parse("y"), so.parse("y", false))
        assertEquals(2, failures.size)
        assertEquals(1, failures.count { it is java.util.concurrent.TimeoutException })
    }
}
