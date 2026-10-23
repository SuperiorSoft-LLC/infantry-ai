package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.FileWrite
import kotlinx.serialization.json.Json

/**
 * UX-WRITE-1: what the chat's change box draws for one file-writing tool call (contract 1.80.0 `status.write`).
 *
 * The rows are the Changes panel's own [DiffRow]s, so the box draws them with the same [DiffLineKind] styling:
 *  - a written file ([FileWrite.content]) is its lines, numbered from 1, as plain [DiffLineKind.CONTEXT] rows;
 *  - an edit ([FileWrite.edits]) is a line diff of each old text against its new text: only the replaced snippet,
 *    never the whole file. Its numbers count within the snippet (the call does not say where in the file it is);
 *  - a patch ([FileWrite.patch]) is its own lines, styled by their `+` / `-` / `@@` prefix.
 */
enum class ChangeKind(val label: String) { CREATED("created"), EDITED("edited") }

data class ChangeBoxView(
    val path: String,
    val kind: ChangeKind,
    val rows: List<DiffRow>,
    val added: Int,
    val removed: Int,
    val truncated: Boolean,
)

object ChangeBox {
    /** A collapsed box shows at most this many rows. */
    const val PREVIEW_LINES = 12

    /** True when the box has more rows than its preview, so it needs a View all / Collapse control. */
    fun collapsible(view: ChangeBoxView): Boolean = view.rows.size > PREVIEW_LINES

    /** The rows drawn: the first [PREVIEW_LINES] while collapsed, every row once expanded. */
    fun visibleRows(view: ChangeBoxView, expanded: Boolean): List<DiffRow> =
        if (expanded || !collapsible(view)) view.rows else view.rows.take(PREVIEW_LINES)

    /** How many rows a collapsed box hides ("+28 more lines"); 0 when it shows everything. */
    fun hiddenCount(view: ChangeBoxView): Int = (view.rows.size - PREVIEW_LINES).coerceAtLeast(0)

    fun moreLabel(view: ChangeBoxView): String = hiddenCount(view).let { "+$it more ${if (it == 1) "line" else "lines"}" }

    fun viewAllLabel(view: ChangeBoxView): String = "View all (${view.rows.size} lines)"

    const val COLLAPSE_LABEL = "Collapse"
}

fun changeBoxView(write: FileWrite): ChangeBoxView {
    val content = write.content
    val edits = write.edits.orEmpty()
    val patch = write.patch
    val (kind, rows) = when {
        content != null -> ChangeKind.CREATED to linesOf(content).mapIndexed { i, l -> DiffRow(DiffLineKind.CONTEXT, null, i + 1, l) }
        edits.isNotEmpty() -> ChangeKind.EDITED to edits.flatMapIndexed { i, e ->
            val header = if (edits.size > 1) listOf(DiffRow(DiffLineKind.HUNK, null, null, "@@ edit ${i + 1} of ${edits.size} @@")) else emptyList()
            header + lineDiff(linesOf(e.oldText), linesOf(e.newText))
        }
        patch != null -> ChangeKind.EDITED to patchRows(linesOf(patch))
        else -> ChangeKind.EDITED to emptyList()
    }
    val added = if (kind == ChangeKind.CREATED) rows.size else rows.count { it.kind == DiffLineKind.ADD }
    return ChangeBoxView(write.path, kind, rows, added, rows.count { it.kind == DiffLineKind.DEL }, write.truncated)
}

/** A text's lines; a final newline ends the last line rather than opening an empty one. */
private fun linesOf(text: String): List<String> =
    if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split("\n").map { it.removeSuffix("\r") }

/** Above this many old x new lines the edit is drawn as all-removed then all-added rather than diffed. */
private const val LCS_MAX_CELLS = 1_000_000

/**
 * The line diff of [old] against [new], as unified-diff rows: common lines are context, the rest `-` / `+`. The common
 * head and tail are matched first, so a typical edit (a few lines changed inside some context) costs almost nothing.
 */
internal fun lineDiff(old: List<String>, new: List<String>): List<DiffRow> {
    var head = 0
    while (head < old.size && head < new.size && old[head] == new[head]) head++
    var tail = 0
    while (tail < old.size - head && tail < new.size - head && old[old.size - 1 - tail] == new[new.size - 1 - tail]) tail++
    val a = old.subList(head, old.size - tail)
    val b = new.subList(head, new.size - tail)
    val ops = ArrayList<Pair<DiffLineKind, String>>()
    old.subList(0, head).forEach { ops += DiffLineKind.CONTEXT to it }
    if (a.size.toLong() * b.size > LCS_MAX_CELLS) {
        a.forEach { ops += DiffLineKind.DEL to it }
        b.forEach { ops += DiffLineKind.ADD to it }
    } else {
        // lcs[i][j]: the longest common subsequence of a[i..] and b[j..].
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) when {
            i < a.size && j < b.size && a[i] == b[j] -> { ops += DiffLineKind.CONTEXT to a[i]; i++; j++ }
            // On a tie the removal goes first, so a replaced line reads `-old` then `+new`, as in a unified diff.
            i < a.size && (j == b.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> { ops += DiffLineKind.DEL to a[i]; i++ }
            else -> { ops += DiffLineKind.ADD to b[j]; j++ }
        }
    }
    old.subList(old.size - tail, old.size).forEach { ops += DiffLineKind.CONTEXT to it }
    var o = 1
    var n = 1
    return ops.map { (kind, text) ->
        when (kind) {
            DiffLineKind.ADD -> DiffRow(kind, null, n++, "+$text")
            DiffLineKind.DEL -> DiffRow(kind, o++, null, "-$text")
            else -> DiffRow(DiffLineKind.CONTEXT, o++, n++, " $text")
        }
    }
}

private val UNIFIED_HUNK = Regex("""^@@ -\d+(?:,\d+)? \+\d+(?:,\d+)? @@""")

/**
 * A patch's rows. A unified diff (with `@@ -a,b +c,d @@` headers) goes through the Changes panel's own [diffRows], so it
 * gets real file line numbers. Otherwise (opencode's `*** Begin Patch` format) each line is styled by its prefix and
 * numbered within its file section.
 */
internal fun patchRows(lines: List<String>): List<DiffRow> {
    if (lines.any { UNIFIED_HUNK.containsMatchIn(it) }) return diffRows(lines)
    var o = 1
    var n = 1
    return lines.map { line ->
        when {
            line.startsWith("***") -> { o = 1; n = 1; DiffRow(DiffLineKind.META, null, null, line) }
            line.startsWith("@@") -> DiffRow(DiffLineKind.HUNK, null, null, line)
            line.startsWith("+") -> DiffRow(DiffLineKind.ADD, null, n++, line)
            line.startsWith("-") -> DiffRow(DiffLineKind.DEL, o++, null, line)
            else -> DiffRow(DiffLineKind.CONTEXT, o++, n++, line)
        }
    }
}

private val writeJson = Json { ignoreUnknownKeys = true }

/** A [FileWrite] as the text of its [LineKind.WRITE] transcript entry. */
fun fileWriteText(write: FileWrite): String = writeJson.encodeToString(FileWrite.serializer(), write)

/** The [FileWrite] a [LineKind.WRITE] entry carries, or null if its text is not one. */
fun fileWriteOf(text: String): FileWrite? = runCatching { writeJson.decodeFromString(FileWrite.serializer(), text) }.getOrNull()
