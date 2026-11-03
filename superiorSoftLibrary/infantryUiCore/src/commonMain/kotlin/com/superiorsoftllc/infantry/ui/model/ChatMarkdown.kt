// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

/**
 * Chat formatting model: assistant text (a plain string on the wire, whatever the harness) is parsed into display
 * blocks by ONE pure function, so the rendering rules are testable without a Compose runtime and identical for every
 * harness. The chat composable only maps blocks to widgets. No HTML is interpreted (it stays literal text), images are
 * never fetched (they degrade to their alt text), and a link is only a [ChatSpan.Link] when its scheme is safe.
 */
/**
 * An inline run: plain [Text], [Bold] (`**...**`), [Italic] (`*...*` / `_..._`), [InlineCode] (`` `...` ``) or a
 * [Link] (`[label](url)`, only http/https/mailto; any other scheme degrades to the label as [Text]).
 */
sealed interface ChatSpan {
    data class Text(val text: String) : ChatSpan
    data class Bold(val text: String) : ChatSpan
    data class Italic(val text: String) : ChatSpan
    data class InlineCode(val text: String) : ChatSpan
    data class Link(val label: String, val url: String) : ChatSpan
}

/** How a table column aligns its cells, from the `:--`, `:-:`, `--:` separator row; [NONE] renders like [START]. */
enum class ColumnAlign { NONE, START, CENTER, END }

sealed interface ChatBlock {
    /** Prose between structural blocks (trimmed); [spans] is the inline-parsed form of [text]. */
    data class Paragraph(val text: String, val spans: List<ChatSpan> = parseChatInline(text)) : ChatBlock

    /** A fenced code block; [language] is the info string after the opening fence ("" when absent). */
    data class Code(val language: String, val body: String) : ChatBlock

    /** A bullet (`-`, `*`, `+`) or, when [ordered], numbered (`1.`) list; each entry is one item's text (marker stripped). */
    data class BulletList(val items: List<String>, val ordered: Boolean = false) : ChatBlock

    /** An ATX heading (`# `, `## `, `### `); [level] is 1-3, deeper headings clamp to 3. */
    data class Heading(val level: Int, val text: String, val spans: List<ChatSpan> = parseChatInline(text)) : ChatBlock

    /** A `>` quote; [text] is the quoted lines joined by newline, markers stripped. */
    data class Quote(val text: String, val spans: List<ChatSpan> = parseChatInline(text)) : ChatBlock

    /** A pipe table: [header] cells, one [alignments] entry per column and [rows] of cells (each row padded/truncated to the header width). Cell text is raw; the renderer parses inline markup. */
    data class Table(val header: List<String>, val alignments: List<ColumnAlign> = List(header.size) { ColumnAlign.NONE }, val rows: List<List<String>>) : ChatBlock

    /** A block of a type the built-in parser never makes: an engine or plugin names it in [type] and a renderer registered for that name draws it; with none, [text] shows as plain text. */
    data class Custom(val type: String, val text: String, val data: Map<String, String> = emptyMap()) : ChatBlock

    /** A thematic break (`---`, `***`, `___`). */
    data object Rule : ChatBlock
}

/** Only these schemes are ever opened externally; everything else (javascript:, file:, data:, relative) is plain text. */
fun isSafeChatUrl(url: String): Boolean {
    val u = url.trim().lowercase()
    return (u.startsWith("http://") && u.length > 7) || (u.startsWith("https://") && u.length > 8) || (u.startsWith("mailto:") && u.length > 7)
}

/** Index of the `)` closing a link target that starts at [from], skipping balanced inner parens; -1 if none. */
private fun closingParen(text: String, from: Int): Int {
    var depth = 0
    for (k in from until text.length) when (text[k]) {
        '(' -> depth++
        ')' -> if (depth == 0) return k else depth--
    }
    return -1
}

/** Parse a paragraph's inline markup into [ChatSpan]s. */
fun parseChatInline(text: String): List<ChatSpan> {
    val spans = ArrayList<ChatSpan>()
    val plain = StringBuilder()
    var i = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            spans += ChatSpan.Text(plain.toString())
            plain.clear()
        }
    }

    fun literal() {
        plain.append(text[i])
        i++
    }

    while (i < text.length) {
        val c = text[i]
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    spans += ChatSpan.Bold(text.substring(i + 2, end))
                    i = end + 2
                } else literal()
            }
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    flushPlain()
                    spans += ChatSpan.InlineCode(text.substring(i + 1, end))
                    i = end + 1
                } else literal()
            }
            c == '*' && i + 1 < text.length && !text[i + 1].isWhitespace() -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1 && !text[end - 1].isWhitespace()) {
                    flushPlain()
                    spans += ChatSpan.Italic(text.substring(i + 1, end))
                    i = end + 1
                } else literal()
            }
            c == '_' && (i == 0 || !text[i - 1].isLetterOrDigit()) && i + 1 < text.length && !text[i + 1].isWhitespace() -> {
                val end = text.indexOf('_', i + 1)
                if (end > i + 1 && !text[end - 1].isWhitespace() && (end + 1 == text.length || !text[end + 1].isLetterOrDigit())) {
                    flushPlain()
                    spans += ChatSpan.Italic(text.substring(i + 1, end))
                    i = end + 1
                } else literal()
            }
            c == '[' || (c == '!' && i + 1 < text.length && text[i + 1] == '[') -> {
                val image = c == '!'
                val open = if (image) i + 1 else i
                val labelEnd = text.indexOf(']', open + 1)
                val urlEnd = if (labelEnd > open + 1 && labelEnd + 1 < text.length && text[labelEnd + 1] == '(') closingParen(text, labelEnd + 2) else -1
                if (urlEnd > labelEnd + 2) {
                    val label = text.substring(open + 1, labelEnd)
                    val url = text.substring(labelEnd + 2, urlEnd).trim()
                    flushPlain()
                    // An image is never fetched: it shows its alt text. An unsafe link shows its label.
                    spans += if (!image && isSafeChatUrl(url)) ChatSpan.Link(label, url) else ChatSpan.Text(label)
                    i = urlEnd + 1
                } else literal()
            }
            else -> literal()
        }
    }
    flushPlain()
    return spans
}

/** Blocks for a transcript line: only assistant [LineKind.TEXT] is formatted; every other kind stays plain (null). */
fun chatLineBlocks(kind: LineKind, text: String, engine: ChatMarkdownEngine = BuiltInChatMarkdownEngine, streaming: Boolean = false): List<ChatBlock>? =
    if (kind == LineKind.TEXT) parseChatMarkdownSafely(engine, text, streaming) else null

/** A leading ATX heading marker followed by a space; level clamps to 3. `#foo` (no space) is not a heading. */
private fun parseChatHeading(line: String): Pair<Int, String>? {
    val t = line.trimStart()
    var n = 0
    while (n < t.length && t[n] == '#') n++
    if (n == 0 || n >= t.length || t[n] != ' ') return null
    val text = t.substring(n + 1).trim()
    if (text.isEmpty()) return null
    return n.coerceAtMost(3) to text
}

private val ORDERED_ITEM = Regex("^(\\d{1,9})[.)] +(.*)$")
private val RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")

private val SEPARATOR_CELL = Regex("^:?-+:?$")

/** Split a table row into trimmed cells; `\|` is a literal pipe inside a cell, and one leading/trailing pipe is the row's frame. */
private fun tableCells(line: String): List<String> {
    val t = line.trim()
    val cells = ArrayList<String>()
    val cur = StringBuilder()
    var i = if (t.startsWith("|")) 1 else 0
    while (i < t.length) {
        val c = t[i]
        when {
            c == '\\' && i + 1 < t.length && t[i + 1] == '|' -> { cur.append('|'); i += 2 }
            c == '|' -> { cells += cur.toString().trim(); cur.clear(); i++ }
            else -> { cur.append(c); i++ }
        }
    }
    // The text after the last pipe is a cell unless it is only the closing frame.
    if (cur.isNotBlank() || !t.endsWith("|") || t.endsWith("\\|")) cells += cur.toString().trim()
    return cells
}

/** The column alignments of a separator row, or null when [line] is not one (every cell is dashes with optional colons, and it has a pipe). */
private fun tableAlignments(line: String): List<ColumnAlign>? {
    if (!line.contains('|')) return null
    val cells = tableCells(line)
    if (cells.isEmpty() || !cells.all { SEPARATOR_CELL.matches(it) }) return null
    return cells.map {
        when {
            it.startsWith(":") && it.endsWith(":") -> ColumnAlign.CENTER
            it.endsWith(":") -> ColumnAlign.END
            it.startsWith(":") -> ColumnAlign.START
            else -> ColumnAlign.NONE
        }
    }
}

/**
 * The separator's alignments when [line] (header) and [next] (separator) open a table: same column count, and while
 * [streaming] a separator still arriving (last line, no closing pipe) is not yet a table, so no flicker.
 */
private fun tableStart(line: String, next: String?, nextIsLast: Boolean, streaming: Boolean): List<ColumnAlign>? {
    if (next == null || !line.contains('|')) return null
    val aligns = tableAlignments(next) ?: return null
    if (aligns.size != tableCells(line).size) return null
    if (streaming && nextIsLast && !next.trimEnd().endsWith("|")) return null
    return aligns
}

/** Bullet marker (`- `, `* `, `+ `) item text, or null. */
private fun bulletItem(t: String): String? =
    if (t.length >= 2 && (t[0] == '-' || t[0] == '*' || t[0] == '+') && t[1] == ' ') t.substring(2).trim() else null

/** Parse a reply into ordered [ChatBlock]s. Plain text becomes one paragraph per blank-line-separated run. */
fun parseChatMarkdown(text: String, streaming: Boolean = false): List<ChatBlock> {
    val blocks = ArrayList<ChatBlock>()
    val prose = ArrayList<String>()
    val quote = ArrayList<String>()
    var list: ArrayList<String>? = null
    var listOrdered = false
    var code: ArrayList<String>? = null
    var language = ""

    fun flushProse() {
        val joined = prose.joinToString("\n").trim()
        if (joined.isNotEmpty()) blocks += ChatBlock.Paragraph(joined)
        prose.clear()
    }
    fun flushList() {
        list?.let { blocks += ChatBlock.BulletList(it, listOrdered) }
        list = null
    }
    fun flushQuote() {
        if (quote.isNotEmpty()) blocks += ChatBlock.Quote(quote.joinToString("\n").trim())
        quote.clear()
    }
    fun flushAll() { flushProse(); flushList(); flushQuote() }

    val lines = text.lines()
    var idx = 0
    while (idx < lines.size) {
        val line = lines[idx]
        val t = line.trimStart()
        val open = code
        if (open != null) {
            if (line.trim() == "```") {
                blocks += ChatBlock.Code(language, open.joinToString("\n"))
                code = null
            } else open += line
            idx++
            continue
        }
        val heading = parseChatHeading(line)
        val ordered = ORDERED_ITEM.matchEntire(t)
        val bullet = bulletItem(t)
        val tableAligns = tableStart(line, lines.getOrNull(idx + 1), idx + 1 == lines.lastIndex, streaming)
        when {
            t.startsWith("```") -> {
                flushAll()
                language = t.removePrefix("```").trim()
                code = ArrayList()
            }
            heading != null -> { flushAll(); blocks += ChatBlock.Heading(heading.first, heading.second) }
            RULE.matches(t.trim()) -> { flushAll(); blocks += ChatBlock.Rule }
            tableAligns != null -> {
                flushAll()
                val header = tableCells(line)
                val rows = ArrayList<List<String>>()
                idx += 2
                while (idx < lines.size && lines[idx].isNotBlank() && lines[idx].contains('|')) {
                    val cells = tableCells(lines[idx])
                    rows += List(header.size) { cells.getOrElse(it) { "" } }
                    idx++
                }
                blocks += ChatBlock.Table(header, tableAligns, rows)
                continue
            }
            t.startsWith(">") -> { flushProse(); flushList(); quote += t.removePrefix(">").removePrefix(" ") }
            ordered != null || bullet != null -> {
                flushProse(); flushQuote()
                val isOrdered = ordered != null
                if (list != null && listOrdered != isOrdered) flushList()
                val items = list ?: ArrayList<String>().also { list = it; listOrdered = isOrdered }
                items += (ordered?.groupValues?.get(2) ?: bullet!!).trim()
            }
            line.isBlank() -> flushAll()
            else -> { flushList(); flushQuote(); prose += line }
        }
        idx++
    }
    // A reply still streaming (or cut off) can end inside an open fence: show what we have as code.
    code?.let { blocks += ChatBlock.Code(language, it.joinToString("\n")) } ?: flushAll()
    return blocks
}
