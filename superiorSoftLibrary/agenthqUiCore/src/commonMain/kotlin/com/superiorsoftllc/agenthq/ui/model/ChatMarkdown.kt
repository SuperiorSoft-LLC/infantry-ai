package com.superiorsoftllc.agenthq.ui.model

/**
 * Chat formatting model: an assistant reply is parsed into display blocks by a pure function, so the
 * rendering rules are testable without a Compose runtime. The chat composable only maps blocks to widgets.
 */
/**
 * An inline run inside a paragraph: plain [Text], [Bold] (`**...**`), [InlineCode] (`` `...` ``) or a
 * [Link] (`[label](url)`).
 */
sealed interface ChatSpan {
    data class Text(val text: String) : ChatSpan
    data class Bold(val text: String) : ChatSpan
    data class InlineCode(val text: String) : ChatSpan
    data class Link(val label: String, val url: String) : ChatSpan
}

sealed interface ChatBlock {
    /** Prose between structural blocks (trimmed); [spans] is the inline-parsed form of [text]. */
    data class Paragraph(val text: String, val spans: List<ChatSpan> = parseChatInline(text)) : ChatBlock

    /** A fenced code block; [language] is the info string after the opening fence ("" when absent). */
    data class Code(val language: String, val body: String) : ChatBlock

    /** A "-" bullet list; each entry is one item's text (leading marker stripped). */
    data class BulletList(val items: List<String>) : ChatBlock

    /** An ATX heading (`# `, `## `, `### `); [level] is 1-3, deeper headings clamp to 3. */
    data class Heading(val level: Int, val text: String) : ChatBlock
}

/** Parse a paragraph's inline markup (`**bold**`, `` `code` ``) into [ChatSpan]s. */
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

    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    spans += ChatSpan.Bold(text.substring(i + 2, end))
                    i = end + 2
                } else {
                    plain.append(text[i])
                    i++
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    flushPlain()
                    spans += ChatSpan.InlineCode(text.substring(i + 1, end))
                    i = end + 1
                } else {
                    plain.append(text[i])
                    i++
                }
            }
            text[i] == '[' -> {
                val labelEnd = text.indexOf(']', i + 1)
                if (labelEnd > i + 1 && labelEnd + 1 < text.length && text[labelEnd + 1] == '(') {
                    val urlEnd = text.indexOf(')', labelEnd + 2)
                    if (urlEnd > labelEnd + 2) {
                        flushPlain()
                        spans += ChatSpan.Link(text.substring(i + 1, labelEnd), text.substring(labelEnd + 2, urlEnd))
                        i = urlEnd + 1
                    } else {
                        plain.append(text[i])
                        i++
                    }
                } else {
                    plain.append(text[i])
                    i++
                }
            }
            else -> {
                plain.append(text[i])
                i++
            }
        }
    }
    flushPlain()
    return spans
}

/** Blocks for a transcript line: only assistant [LineKind.TEXT] is formatted; every other kind stays plain (null). */
fun chatLineBlocks(kind: LineKind, text: String): List<ChatBlock>? =
    if (kind == LineKind.TEXT) parseChatMarkdown(text) else null

/**
 * A leading ATX heading marker (`#`, `##`, `###`, ...) followed by a space becomes a heading: its
 * level is the marker count clamped to 3, and its text is the rest of the line trimmed. `#foo` (no
 * space) is not a heading.
 */
private fun parseChatHeading(line: String): Pair<Int, String>? {
    val t = line.trimStart()
    var n = 0
    while (n < t.length && t[n] == '#') n++
    if (n == 0 || n >= t.length || t[n] != ' ') return null
    val text = t.substring(n + 1).trim()
    if (text.isEmpty()) return null
    return n.coerceAtMost(3) to text
}

/** Parse a reply into ordered [ChatBlock]s. */
fun parseChatMarkdown(text: String): List<ChatBlock> {
    val blocks = ArrayList<ChatBlock>()
    val prose = ArrayList<String>()
    var bullets: ArrayList<String>? = null
    var code: ArrayList<String>? = null
    var language = ""

    fun flushProse() {
        val joined = prose.joinToString("\n").trim()
        if (joined.isNotEmpty()) blocks += ChatBlock.Paragraph(joined)
        prose.clear()
    }

    fun flushBullets() {
        val open = bullets ?: return
        blocks += ChatBlock.BulletList(open)
        bullets = null
    }

    for (line in text.lines()) {
        val open = code
        val heading = if (open == null) parseChatHeading(line) else null
        when {
            open == null && line.trimStart().startsWith("```") -> {
                flushProse()
                flushBullets()
                language = line.trimStart().removePrefix("```").trim()
                code = ArrayList()
            }
            open != null && line.trim() == "```" -> {
                blocks += ChatBlock.Code(language, open.joinToString("\n"))
                code = null
            }
            open != null -> open += line
            heading != null -> {
                flushProse()
                flushBullets()
                blocks += ChatBlock.Heading(heading.first, heading.second)
            }
            line.trimStart().startsWith("- ") -> {
                flushProse()
                val items = bullets ?: ArrayList<String>().also { bullets = it }
                items += line.trimStart().removePrefix("- ").trim()
            }
            else -> {
                flushBullets()
                prose += line
            }
        }
    }
    // A reply still streaming (or cut off) can end inside an open fence: show what we have as code.
    code?.let { blocks += ChatBlock.Code(language, it.joinToString("\n")) } ?: run {
        flushProse()
        flushBullets()
    }
    return blocks
}
