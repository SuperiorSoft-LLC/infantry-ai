// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.ChangeKind
import com.superiorsoftllc.infantry.ui.protocol.ChangedFile
import com.superiorsoftllc.infantry.ui.protocol.WorktreeStatus

/**
 * What the git view shows for one agent, derived purely from the daemon's [WorktreeStatus].
 *
 * Three DISTINCT states, because collapsing them is the silent-wrong-answer shape: "we could not read
 * it" must never render as "clean", and "clean" must never render as "nothing reported yet".
 */
sealed interface ChangesView {
    /** No usable answer: [reason] says why, in words the pane can show as-is. */
    data class Unknown(val reason: String) : ChangesView

    /** The daemon read the worktree and git listed nothing. */
    data object Clean : ChangesView

    /** Changed files, split by index side and ordered by path inside each side. */
    data class Files(
        val staged: List<ChangedFile>,
        val unstaged: List<ChangedFile>,
        val truncated: Boolean,
    ) : ChangesView {
        val total: Int get() = staged.size + unstaged.size
    }
}

fun changesView(status: WorktreeStatus?): ChangesView = when {
    status == null -> ChangesView.Unknown("status not reported yet")
    !status.exists -> ChangesView.Unknown("worktree missing on disk")
    status.changes == null -> ChangesView.Unknown("the daemon did not report changed files (older daemon, or git could not be read)")
    status.changes.isEmpty() -> ChangesView.Clean
    else -> ChangesView.Files(
        staged = status.changes.filter { it.staged }.sortedBy { it.path },
        unstaged = status.changes.filter { !it.staged }.sortedBy { it.path },
        truncated = status.changesTruncated,
    )
}

/** One-letter badge, git's own vocabulary (`A M D R ? U`). */
fun ChangeKind.badge(): String = when (this) {
    ChangeKind.ADDED -> "A"
    ChangeKind.MODIFIED -> "M"
    ChangeKind.DELETED -> "D"
    ChangeKind.RENAMED -> "R"
    ChangeKind.UNTRACKED -> "?"
    ChangeKind.CONFLICTED -> "U"
}

fun ChangeKind.label(): String = name.lowercase()

/** The path as shown: a rename reads `old -> new`. */
fun ChangedFile.display(): String = if (fromPath != null) "$fromPath -> $path" else path

/** Header line for the section, e.g. `3 changed files` or `120+ changed files (list cut)`. */
fun ChangesView.Files.summary(): String =
    if (truncated) "$total+ changed files (list cut by the daemon)" else "$total changed file${if (total == 1) "" else "s"}"
