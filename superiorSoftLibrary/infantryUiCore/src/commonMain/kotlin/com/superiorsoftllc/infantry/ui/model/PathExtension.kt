package com.superiorsoftllc.infantry.ui.model

/**
 * Splits a path into (stem, extension) so a row can ellipsize the stem and keep the extension visible:
 * `"a/b/sync-worktree-base.sh"` -> `("a/b/sync-worktree-base", ".sh")`. Only the last segment counts; a name
 * with no dot, or a dotfile (`.gitignore`), has no extension.
 */
fun splitExtension(path: String): Pair<String, String> {
    val dot = path.lastIndexOf('.')
    // No dot, a dot in a directory name, or the leading dot of a dotfile: no extension.
    if (dot <= path.lastIndexOf('/') + 1) return path to ""
    return path.substring(0, dot) to path.substring(dot)
}
