package com.superiorsoftllc.infantry.worktree

import java.io.File

/**
 * Turns `git status --porcelain=v1 -z` into [ChangedFile]s. Pure text in, list out, so the one place
 * that interprets git's two-letter codes is testable without a repository.
 *
 * Format (git-status(1), "Porcelain Format Version 1"): each entry is `XY <path>` terminated by NUL,
 * where `X` is the INDEX (staged) side and `Y` the WORKING TREE (unstaged) side. A rename or copy on
 * either side is followed by one extra NUL-terminated field holding the ORIGINAL path. `-z` means paths
 * are never quoted or escaped, which is why it is used rather than parsing the default output.
 *
 * Mapping, stated once because getting it wrong is silent:
 *  - `??` is [ChangeKind.UNTRACKED] (unstaged); `!!` (ignored) is skipped.
 *  - `DD AU UD UA DU AA UU` are unmerged: ONE [ChangeKind.CONFLICTED] entry, not staged.
 *  - Otherwise each non-blank side is its own entry: `X` -> staged, `Y` -> unstaged. So `MM` is two
 *    entries, and `RM` is a staged rename plus an unstaged modification of the new path.
 *  - `A`->ADDED, `M`/`T`->MODIFIED, `D`->DELETED, `R`->RENAMED, `C`->ADDED (with [ChangedFile.fromPath]).
 *    A letter git has not documented is reported as MODIFIED: the file DID change, and dropping it
 *    would be the worse lie.
 */
internal object WorktreeChanges {

    private val CONFLICT_CODES = setOf("DD", "AU", "UD", "UA", "DU", "AA", "UU")

    fun parse(porcelainZ: String): List<ChangedFile> {
        // GitResult output is line-oriented text, so a trailing "\n" can ride after the final NUL.
        val fields = porcelainZ.split('\u0000')
        val out = ArrayList<ChangedFile>()
        var i = 0
        while (i < fields.size) {
            val entry = fields[i++]
            if (entry.length < 4 || entry[2] != ' ') continue // blank tail / the stray newline
            val code = entry.substring(0, 2)
            val path = entry.substring(3)
            val x = code[0]
            val y = code[1]
            // A rename/copy on either side consumes the next field as the original path.
            val fromPath = if (x == 'R' || x == 'C' || y == 'R' || y == 'C') fields.getOrNull(i++) else null

            when {
                code == "??" -> out += ChangedFile(path, ChangeKind.UNTRACKED, staged = false)
                code == "!!" -> Unit
                code in CONFLICT_CODES -> out += ChangedFile(path, ChangeKind.CONFLICTED, staged = false)
                else -> {
                    if (x != ' ') out += ChangedFile(path, kindOf(x), staged = true, fromPath = fromPath.takeIf { x == 'R' || x == 'C' })
                    if (y != ' ') out += ChangedFile(path, kindOf(y), staged = false, fromPath = fromPath.takeIf { y == 'R' || y == 'C' })
                }
            }
        }
        return out
    }

    private fun kindOf(letter: Char): ChangeKind = when (letter) {
        'A', 'C' -> ChangeKind.ADDED
        'D' -> ChangeKind.DELETED
        'R' -> ChangeKind.RENAMED
        else -> ChangeKind.MODIFIED // 'M', 'T' (type change), and anything git adds later
    }
}

/**
 * The seam for "what changed in this working directory". The frame schema is NEUTRAL ([ChangeKind],
 * [ChangedFile]); only this one implementation knows about git, so another VCS or source can be swapped
 * in without touching the wire. A `null` result means UNKNOWN (never "clean").
 */
fun interface WorkspaceChangeReader {
    fun read(dir: File): List<ChangedFile>?
}

/**
 * [WorkspaceChangeReader] over the `git` CLI: ONE read-only status call with `cwd = dir`.
 * `--no-optional-locks` matters: plain `git status` takes the index lock to refresh stat data, which
 * makes the agent's own `git add` in that worktree fail intermittently.
 */
class GitChangeReader(private val git: GitCli) : WorkspaceChangeReader {
    override fun read(dir: File): List<ChangedFile>? {
        val result = git.run(
            dir,
            listOf("--no-optional-locks", "status", "--porcelain=v1", "-z", "--untracked-files=all"),
        )
        // A failed read is UNKNOWN (null): exit codes are data here, not exceptions.
        return if (result.ok) WorktreeChanges.parse(result.stdout) else null
    }
}
