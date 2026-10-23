package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.ChangedFile

/**
 * DIFF-MODAL: what the diff modal draws, derived purely from [PatchView] (itself derived from the daemon's
 * `project_git_patch` answer). Reusable for any file list (the Changes panel today, a branch's files later).
 */

/**
 * One file the modal can open: its path and the index side it was listed under. [branch] null is the working tree;
 * a name means that branch's diff vs its base (BRANCH-VIEW-2, always the unstaged side). [worktree] null is the
 * project's directory; a path is that worktree of the project's repository (UX-CHANGES-1).
 */
data class DiffTarget(val path: String, val staged: Boolean, val branch: String? = null, val worktree: String? = null)

enum class DiffLineKind { ADD, DEL, CONTEXT, HUNK, META }

/** One drawn row: [oldNo]/[newNo] are the gutter numbers (null where that side has no line). */
data class DiffRow(val kind: DiffLineKind, val oldNo: Int?, val newNo: Int?, val text: String)

/** The modal's body. Unknown carries the daemon's reason and is never drawn as "no changes". */
sealed interface DiffBody {
    data object Loading : DiffBody
    data class Unknown(val reason: String) : DiffBody
    data object Binary : DiffBody
    data class Lines(val rows: List<DiffRow>, val added: Int, val removed: Int, val truncated: Boolean) : DiffBody
}

fun diffBody(patch: PatchView): DiffBody = when (patch) {
    PatchView.None -> DiffBody.Loading
    is PatchView.Unknown -> DiffBody.Unknown(patch.reason)
    PatchView.Binary -> DiffBody.Binary
    is PatchView.Text -> diffRows(patch.lines).let { rows ->
        DiffBody.Lines(rows, rows.count { it.kind == DiffLineKind.ADD }, rows.count { it.kind == DiffLineKind.DEL }, patch.truncated)
    }
}

private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

/**
 * Unified diff lines to rows with old/new line numbers. Everything before the first `@@` (diff --git, index,
 * ---/+++) is META, so a removed line that itself starts with `--` inside a hunk is still a removal.
 */
fun diffRows(lines: List<String>): List<DiffRow> {
    var inHunk = false
    var old = 0
    var new = 0
    return lines.map { line ->
        val hunk = HUNK.find(line)
        when {
            hunk != null -> {
                inHunk = true
                old = hunk.groupValues[1].toInt()
                new = hunk.groupValues[2].toInt()
                DiffRow(DiffLineKind.HUNK, null, null, line)
            }
            line.startsWith("diff --git") -> { inHunk = false; DiffRow(DiffLineKind.META, null, null, line) }
            !inHunk || line.startsWith("\\") -> DiffRow(DiffLineKind.META, null, null, line)
            line.startsWith("+") -> DiffRow(DiffLineKind.ADD, null, new++, line)
            line.startsWith("-") -> DiffRow(DiffLineKind.DEL, old++, null, line)
            else -> DiffRow(DiffLineKind.CONTEXT, old++, new++, line)
        }
    }
}

/** The order the modal steps through: the panel's own order, staged first, then not staged, in [worktree]. */
fun diffTargets(view: ChangesView, worktree: String? = null): List<Pair<DiffTarget, ChangedFile>> = when (view) {
    is ChangesView.Files ->
        view.staged.map { DiffTarget(it.path, true, worktree = worktree) to it } +
            view.unstaged.map { DiffTarget(it.path, false, worktree = worktree) to it }
    else -> emptyList()
}

/** The neighbour [delta] steps from [current], or null at either end or when [current] is no longer listed. */
fun List<DiffTarget>.step(current: DiffTarget, delta: Int): DiffTarget? {
    val i = indexOf(current)
    return if (i < 0) null else getOrNull(i + delta)
}
