package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.harness.Harness
import com.superiorsoftllc.agenthq.harness.HarnessEvent
import com.superiorsoftllc.agenthq.harness.HarnessRequest
import com.superiorsoftllc.agenthq.harness.HarnessTranslator
import com.superiorsoftllc.agenthq.harness.CliHarness
import com.superiorsoftllc.agenthq.harness.HarnessCapability
import com.superiorsoftllc.agenthq.harness.claude.ClaudeHarness
import com.superiorsoftllc.agenthq.harness.pi.PiHarness
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.ui.model.ChatBlock
import com.superiorsoftllc.agenthq.ui.model.ChatSpan
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.chatLineBlocks
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chat renders assistant text with ONE shared parser, after [HarnessEvent]: the same logical message told in
 * Claude's wire, in Pi's wire (token deltas) and by a third-party plugin harness (its own translator) must come out
 * as the same display blocks, and an unknown harness that emits plain text must still read as paragraphs.
 * OpenCode has no translator in this layer; the plugin translator below stands in for any harness not built in.
 */
class ChatRichTextHarnessParityTest {
    private val message = "## Plan\n\nDo **this** and *that*:\n\n- one\n- two with `code`\n\n```kotlin\nval x = 1\n```\n\n[docs](https://x.dev/a)"

    private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\n") + "\""
    private val spec = { _: HarnessRequest -> ProcessSpec(listOf("scripted")) }
    private val request = { id: String -> HarnessRequest(prompt = "hi", harnessId = id, project = "p") }

    /** What the UI does with a run's text: the TextDeltas concatenate into one assistant TEXT line, then the shared parser. */
    private fun blocksOf(harness: Harness, id: String): List<ChatBlock> {
        val text = runBlocking { harness.run(request(id)).toList() }.filterIsInstance<HarnessEvent.TextDelta>().joinToString("") { it.text }
        return chatLineBlocks(LineKind.TEXT, text).orEmpty()
    }

    private fun runner(lines: List<String>) = ScriptedRunner(StubScript(stdoutLines = lines), delayMs = 0)

    private fun claude() = ClaudeHarness(
        runner(listOf("""{"type":"assistant","message":{"content":[{"type":"text","text":${json(message)}}]},"session_id":"s"}""")), spec,
    )

    private fun pi(): PiHarness {
        val usage = """{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0,"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}}"""
        // Token-sized deltas, cut mid-word, as a real stream arrives.
        val lines = message.chunked(7).map {
            """{"type":"message_update","usage":$usage,"assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":${json(it)}}}"""
        }
        return PiHarness(runner(lines), spec)
    }

    /** A plugin harness: its own translator, one TextDelta per stdout line. */
    private class LineTranslator : HarnessTranslator {
        override fun translate(line: String) = listOf<HarnessEvent>(HarnessEvent.TextDelta(line + "\n"))
        override fun terminal(exitCode: Int, stderrTail: String): HarnessEvent = HarnessEvent.Completion()
    }

    private fun plugin(lines: List<String>): Harness = object : CliHarness(runner(lines), spec, ::LineTranslator, "plugin") {
        override val id = "plugin"
        override val capabilities = emptySet<HarnessCapability>()
    }

    @Test
    fun sameMessageRendersIdenticallyThroughClaudePiAndAPluginHarness() {
        val fromClaude = blocksOf(claude(), "claude")
        val fromPi = blocksOf(pi(), "pi")
        val fromPlugin = blocksOf(plugin(message.lines()), "plugin")

        // Guard against a vacuous pass: the message really parsed into rich blocks.
        assertEquals(
            listOf(
                ChatBlock.Heading(2, "Plan"),
                ChatBlock.Paragraph("Do **this** and *that*:"),
                ChatBlock.BulletList(listOf("one", "two with `code`")),
                ChatBlock.Code("kotlin", "val x = 1"),
                ChatBlock.Paragraph("[docs](https://x.dev/a)"),
            ),
            fromClaude,
        )
        assertTrue((fromClaude[1] as ChatBlock.Paragraph).spans.any { it is ChatSpan.Bold } && (fromClaude[4] as ChatBlock.Paragraph).spans.single() is ChatSpan.Link)
        assertEquals(fromClaude, fromPi, "pi renders differently from claude")
        assertEquals(fromClaude, fromPlugin, "a plugin harness renders differently from claude")
    }

    @Test
    fun unknownHarnessPlainTextStillReadsAsParagraphs() {
        val blocks = blocksOf(plugin(listOf("first line", "second line", "", "another paragraph")), "plugin")
        assertEquals(listOf(ChatBlock.Paragraph("first line\nsecond line"), ChatBlock.Paragraph("another paragraph")), blocks)
    }
}
