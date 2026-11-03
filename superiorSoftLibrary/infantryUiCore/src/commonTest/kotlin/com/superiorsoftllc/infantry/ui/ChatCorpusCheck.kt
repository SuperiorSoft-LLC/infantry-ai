package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.ChatBlock
import com.superiorsoftllc.infantry.ui.model.ChatMarkdownEngine
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.chatLineBlocks

/** Everything a block would show as text, in order; spans are flattened to their raw text. */
fun visibleChatText(blocks: List<ChatBlock>): String = blocks.joinToString("\n") { b ->
    when (b) {
        is ChatBlock.Paragraph -> b.text
        is ChatBlock.Code -> b.body
        is ChatBlock.BulletList -> b.items.joinToString("\n")
        is ChatBlock.Heading -> b.text
        is ChatBlock.Quote -> b.text
        is ChatBlock.Table -> (listOf(b.header) + b.rows).joinToString("\n") { it.joinToString(" ") }
        is ChatBlock.Custom -> b.text
        ChatBlock.Rule -> ""
    }
}

private fun squash(s: String) = s.trim().replace(Regex("\\s+"), " ")
private val SEPARATOR_ROW = Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$""")
private const val HEADER = "[Infantry AI COMMS] message "
private const val FOOTER = "-- answer this message --"

/**
 * The regression net for chat parsing: runs [text] through the real chat path (chatLineBlocks, as ChatSurface does) with
 * [engine] and returns what is wrong, empty when fine. Rules need no per-sample setup, so a new sample file is a new test:
 *  - the engine never throws, for the whole text and for every prefix of it (streaming);
 *  - the Comms header and footer stay visible as plain text (owner decision: never hide data);
 *  - every fenced region stays literal in Code blocks, closed or not;
 *  - a pipe table (header row + separator row outside a fence) becomes a Table and its separator row is not shown as text.
 */
fun checkChatCorpusSample(text: String, engine: ChatMarkdownEngine): List<String> {
    val problems = ArrayList<String>()
    for (n in 0..text.length) {
        try {
            chatLineBlocks(LineKind.TEXT, text.substring(0, n), engine, streaming = true)
        } catch (e: Throwable) {
            problems += "throws on a prefix of $n chars: $e"
            break
        }
    }
    val blocks = try {
        chatLineBlocks(LineKind.TEXT, text, engine).orEmpty()
    } catch (e: Throwable) {
        return problems + "throws on the full text: $e"
    }
    val visible = squash(visibleChatText(blocks))
    for (marker in listOf(HEADER, FOOTER)) {
        if (marker in text && squash(marker) !in visible) problems += "'$marker' is not visible as plain text"
    }
    val codeText = squash(blocks.filterIsInstance<ChatBlock.Code>().joinToString("\n") { it.body })
    var inFence = false
    val fenced = StringBuilder()
    val lines = text.lines()
    val tableLines = ArrayList<Int>()
    for ((i, line) in lines.withIndex()) {
        if (line.trim().startsWith("```")) { inFence = !inFence; continue }
        if (inFence) fenced.append(line).append('\n')
        else if (i > 0 && '|' in line && SEPARATOR_ROW.matches(line) && '|' in lines[i - 1]) tableLines += i
    }
    for (line in fenced.lines().filter { it.isNotBlank() }) {
        if (squash(line) !in codeText) problems += "fenced line is not literal in a Code block: '$line'"
    }
    if (tableLines.isNotEmpty() && blocks.none { it is ChatBlock.Table }) problems += "pipe table did not become a Table block"
    for (i in tableLines) {
        if (blocks.any { it !is ChatBlock.Table && it !is ChatBlock.Code && squash(visibleChatText(listOf(it))).contains(squash(lines[i])) }) {
            problems += "table separator row shows as text: '${lines[i]}'"
        }
    }
    return problems
}
