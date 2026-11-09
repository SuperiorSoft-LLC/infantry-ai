// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.install

import java.io.File

/**
 * CLI-PATH-1: put the `infantry` command on the user's PATH, so it runs from any terminal without being in the app
 * folder — and take it off again.
 *
 * The launcher directory holds the shipped launchers (`infantry` / `infantry.bat` / `infantry.cmd`): `build/install/
 * infantry/bin` of an `installDist`, or the installed app's `<install>\app\resources` (CLIPATH-2). This object only
 * decides WHAT to write; [UserPathStore] decides WHERE (the Windows user PATH, a POSIX shell-profile block, a file).
 * The store is injected for exactly that reason: a test never changes a real PATH.
 *
 * Ownership needs no state file of its own, only what a store can keep beside the value: an entry is ours when a
 * directory holds one of Infantry AI's launcher scripts ([holdsLauncher] — the file names ours, and a script that names
 * our [MAIN_CLASS]) or when an earlier write recorded it ([UserPathStore.recorded], which is how a DEAD entry is still
 * recognised). So a repeated install adds nothing, an install from a NEW folder replaces the entry that pointed at the
 * old one (an update that moves the app), and `uninstall` removes every entry of ours without touching anyone else's.
 */
object CliPath {
    /** The launcher file names Infantry AI ships, as the shipped `appResources` (CLIPATH-2) and installDist name them. */
    val LAUNCHERS: List<String> = listOf("infantry", "infantry.bat", "infantry.cmd", "infantry.ps1")

    /** The class every shipped launcher starts; a launcher file that names it is ours and nobody else's. */
    const val MAIN_CLASS = "com.superiorsoftllc.infantry.cli.CliMainKt"

    /** Read at most this much of a launcher script to see [MAIN_CLASS] in it. */
    private const val LAUNCHER_PROBE_BYTES = 8_192

    /** What `open a NEW terminal` means, said once: PATH is read when a shell starts, never re-read by a running one. */
    const val NEW_TERMINAL_NOTE = "Open a NEW terminal: a terminal that is already open keeps the PATH it started with."

    /** One entry of a PATH: no surrounding quotes or blanks, no trailing separator (so two spellings compare equal). */
    fun normalize(entry: String): String = entry.trim().trim('"').trimEnd(File.separatorChar, '/', '\\').ifEmpty { entry.trim().trim('"') }

    /** True when [a] and [b] name the same directory: Windows compares case-insensitively, and separators are trimmed. */
    fun samePath(a: String, b: String, windows: Boolean = isWindows()): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        return if (windows) x.equals(y, ignoreCase = true) else x == y
    }

    /** The entries of a stored PATH value; blanks dropped, order kept (a shared PATH keeps every other tool's entry). */
    fun entries(value: String?, separator: Char = File.pathSeparatorChar): List<String> =
        value.orEmpty().split(separator).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * True when [dir] is one of Infantry AI's launcher directories: a directory holding a [LAUNCHERS] file that names
     * [MAIN_CLASS]. This is the ownership test, so nothing else on the user's PATH is ever removed.
     */
    fun holdsLauncher(dir: String, read: (File) -> String = ::headOf): Boolean {
        val d = File(normalize(dir))
        if (!d.isDirectory) return false
        return LAUNCHERS.any { name ->
            val f = File(d, name)
            f.isFile && runCatching { read(f) }.getOrDefault("").contains(MAIN_CLASS)
        }
    }

    /** The first [LAUNCHER_PROBE_BYTES] of [file] as text, so a big script is never read whole. */
    private fun headOf(file: File): String = file.inputStream().use { input ->
        val buffer = ByteArray(LAUNCHER_PROBE_BYTES)
        val read = input.read(buffer)
        if (read <= 0) "" else String(buffer, 0, read, Charsets.UTF_8)
    }

    /** What an install or an uninstall would do (or did): the entries before and after, and the difference. */
    data class Plan(val before: List<String>, val after: List<String>, val added: List<String>, val removed: List<String>) {
        val changed: Boolean get() = added.isNotEmpty() || removed.isNotEmpty()
    }

    /**
     * The new entry list: every entry of ours that is not [launcherDir] is dropped (a moved install, a duplicate), no
     * other entry is touched or reordered, and [launcherDir] is appended when it is not on the PATH already.
     * [isOurs] is the ownership test of the store: [holdsLauncher] for a shared PATH, every stored entry for a store
     * that holds only our own block.
     */
    fun plan(
        current: List<String>,
        launcherDir: String,
        isOurs: (String) -> Boolean,
        windows: Boolean = isWindows(),
    ): Plan {
        val mine = normalize(launcherDir)
        val after = mutableListOf<String>()
        val removed = mutableListOf<String>()
        for (entry in current) {
            when {
                !isOurs(entry) -> after += entry
                samePath(entry, mine, windows) -> if (after.none { samePath(it, mine, windows) }) after += mine else removed += entry
                else -> removed += entry
            }
        }
        val added = if (after.any { samePath(it, mine, windows) }) emptyList() else listOf(mine).also { after += mine }
        return Plan(current, after, added, removed)
    }

    /** The PATH entry as it stands: whether it is there, which stored entry it is, and whether that directory is live. */
    data class Status(val launcherDir: String, val where: String, val entry: String?, val onPath: Boolean, val live: Boolean) {
        /** The one line a person reads: it says what is on the PATH and whether that still points at a launcher. */
        fun line(): String = when {
            !onPath -> "`infantry` is NOT on $where. Add it: infantry install-cli"
            !live -> "`infantry` is on $where at $entry, but that folder holds no launcher any more. Fix it: infantry install-cli"
            else -> "`infantry` is on $where at $entry"
        }
    }

    /** The result of an install/uninstall: the status afterwards and what was written (null = nothing had to change). */
    data class Outcome(val status: Status, val plan: Plan?, val store: UserPathStore) {
        val where: String get() = store.where
        val changed: Boolean get() = plan?.changed == true
    }

    /** Add [launcherDir] to the user's PATH; a repeated call writes nothing. [write] = false is a dry run. */
    fun install(store: UserPathStore, launcherDir: File, write: Boolean = true): Outcome =
        apply(store, launcherDir, add = true, write = write)

    /** Remove every entry of ours from the user's PATH; a repeated call writes nothing. */
    fun uninstall(store: UserPathStore, launcherDir: File, write: Boolean = true): Outcome =
        apply(store, launcherDir, add = false, write = write)

    /** The status without writing anything. */
    fun status(store: UserPathStore, launcherDir: File): Status =
        statusOf(store, normalize(launcherDir.absolutePath), store.entries())

    private fun apply(store: UserPathStore, launcherDir: File, add: Boolean, write: Boolean): Outcome {
        val mine = normalize(launcherDir.absolutePath)
        val current = store.entries()
        val plan = if (add) {
            plan(current, mine, { isOurs(store, it) }, store.windowsPaths)
        } else {
            // Uninstall drops every entry of ours, the current one included.
            val ours = current.filter { isOurs(store, it) }
            Plan(current, current - ours.toSet(), emptyList(), ours)
        }
        val written = plan.changed && write
        if (written) {
            store.write(plan.after.joinToString(store.separator.toString()))
            store.broadcast()
        }
        // Recorded even when the entry was already there: that is what still recognises it as ours once the folder is
        // gone (an install that moved, or one an uninstall deleted).
        if (write) store.record(if (add) listOf(mine) else emptyList())
        return Outcome(statusOf(store, mine, if (written) plan.after else current), plan, store)
    }

    /** The status of [stored] (what was just written, or what is still stored). */
    private fun statusOf(store: UserPathStore, mine: String, stored: List<String>): Status {
        val entry = stored.firstOrNull { samePath(it, mine, store.windowsPaths) } ?: stored.firstOrNull { isOurs(store, it) }
        return Status(mine, store.where, entry, stored.any { samePath(it, mine, store.windowsPaths) }, entry != null && holdsLauncher(entry))
    }

    /** A shared store keeps other tools' entries: ours is a directory holding our launcher, or one our writes recorded. */
    private fun isOurs(store: UserPathStore, entry: String): Boolean =
        if (store.shared) holdsLauncher(entry) || store.recorded().any { samePath(it, entry, store.windowsPaths) } else entry.isNotBlank()

    /** Windows compares paths case-insensitively; POSIX does not. */
    fun isWindows(os: String = System.getProperty("os.name").orEmpty()): Boolean = os.lowercase().startsWith("windows")
}
