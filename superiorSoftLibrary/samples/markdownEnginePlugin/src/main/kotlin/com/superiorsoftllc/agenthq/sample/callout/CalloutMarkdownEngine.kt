package com.superiorsoftllc.agenthq.sample.callout

import com.superiorsoftllc.agenthq.ui.model.ChatBlock
import com.superiorsoftllc.agenthq.ui.model.ChatMarkdownEngine
import com.superiorsoftllc.agenthq.ui.model.ChatMarkdownFeature
import com.superiorsoftllc.agenthq.ui.model.ChatSpan
import com.superiorsoftllc.agenthq.ui.model.ColumnAlign

/**
 * EXAMPLE markdown engine: a small fixed syntax plus one custom block. Copy this file to start your own.
 *
 * Supported: `#`..`###` headings, `-`/`*` lists, ``` fences (kept literal), pipe tables, paragraphs, and
 *
 *     :::callout warning
 *     Text of the callout.
 *     :::
 *
 * which becomes `ChatBlock.Custom("callout", "Text of the callout.", mapOf("kind" to "warning"))`. A host with no
 * renderer for "callout" shows the text as plain text. Unlike the built-in engine, paragraphs are NOT inline-styled
 * (one plain [ChatSpan.Text]), so the two engines are easy to tell apart.
 *
 * [streaming]: the reply may stop anywhere. An unclosed fence or callout is emitted with what has arrived so far, a table
 * with only its header line is a paragraph until the separator row arrives, and nothing here ever throws.
 */
class CalloutMarkdownEngine : ChatMarkdownEngine {
    override val id = "example-callout"
    override val label = "Example (callouts)"

    override fun supports(feature: ChatMarkdownFeature): Boolean = feature != ChatMarkdownFeature.INLINE

    override fun parse(text: String, streaming: Boolean): List<ChatBlock> {
        val lines = text.replace("\r\n", "\n").split('\n')
        val out = ArrayList<ChatBlock>()
        val para = ArrayList<String>()
        fun flushPara() {
            if (para.isNotEmpty()) {
                val t = para.joinToString("\n").trim()
                if (t.isNotEmpty()) out += ChatBlock.Paragraph(t, listOf(ChatSpan.Text(t)))
                para.clear()
            }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flushPara()
                    val body = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) body += lines[i++]
                    out += ChatBlock.Code(trimmed.removePrefix("```").trim(), body.joinToString("\n"))
                    i++ // the closing fence (or past the end when it never arrived)
                }
                trimmed.startsWith(":::callout") -> {
                    flushPara()
                    val kind = trimmed.removePrefix(":::callout").trim().ifEmpty { "note" }
                    val body = ArrayList<String>()
                    i++
                    while (i < lines.size && lines[i].trim() != ":::") body += lines[i++]
                    out += ChatBlock.Custom("callout", body.joinToString("\n").trim(), mapOf("kind" to kind))
                    i++
                }
                trimmed.isEmpty() -> { flushPara(); i++ }
                heading(trimmed) != null -> {
                    flushPara()
                    val (level, t) = heading(trimmed)!!
                    out += ChatBlock.Heading(level, t, listOf(ChatSpan.Text(t)))
                    i++
                }
                isItem(trimmed) -> {
                    flushPara()
                    val items = ArrayList<String>()
                    while (i < lines.size && isItem(lines[i].trim())) items += lines[i++].trim().substring(2).trim()
                    out += ChatBlock.BulletList(items)
                }
                trimmed.contains('|') && i + 1 < lines.size && isSeparator(lines[i + 1]) -> {
                    flushPara()
                    val header = cells(trimmed)
                    val aligns = cells(lines[i + 1]).map(::align).let { a -> List(header.size) { a.getOrElse(it) { ColumnAlign.NONE } } }
                    i += 2
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                        val c = cells(lines[i++].trim())
                        rows += List(header.size) { c.getOrElse(it) { "" } }
                    }
                    out += ChatBlock.Table(header, aligns, rows)
                }
                else -> { para += line; i++ }
            }
        }
        flushPara()
        return out
    }

    private fun heading(t: String): Pair<Int, String>? {
        val hashes = t.takeWhile { it == '#' }.length
        return if (hashes in 1..3 && t.length > hashes && t[hashes] == ' ') hashes to t.substring(hashes + 1).trim() else null
    }

    private fun isItem(t: String) = t.length > 2 && (t[0] == '-' || t[0] == '*') && t[1] == ' '

    private fun isSeparator(raw: String): Boolean {
        val t = raw.trim()
        return t.contains('-') && t.contains('|') && cells(t).let { c -> c.isNotEmpty() && c.all { it.isNotEmpty() && it.all { ch -> ch == '-' || ch == ':' } && it.contains('-') } }
    }

    private fun align(cell: String): ColumnAlign = when {
        cell.startsWith(":") && cell.endsWith(":") -> ColumnAlign.CENTER
        cell.endsWith(":") -> ColumnAlign.END
        cell.startsWith(":") -> ColumnAlign.START
        else -> ColumnAlign.NONE
    }

    /** Cells of one table row; a `|` inside backticks does not split. */
    private fun cells(row: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var code = false
        for (ch in row.trim().removePrefix("|")) {
            if (ch == '`') code = !code
            if (ch == '|' && !code) { out += cur.toString().trim(); cur.clear() } else cur.append(ch)
        }
        if (cur.isNotBlank() || row.trim().let { !it.endsWith("|") }) out += cur.toString().trim()
        return out
    }
}
