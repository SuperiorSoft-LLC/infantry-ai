// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.install

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.File

/**
 * CLI-PATH-1: where a PATH entry is kept, the ONE seam the PATH writer goes through.
 *
 * The writer itself ([CliPath]) never touches an environment, a registry or a shell profile: it gets a store and asks
 * it to [read] and [write] a value. That is what makes it testable without touching the owner's machine's real PATH
 * (a fake store in the unit tests; [RegistryUserPath] also takes its registry key, so a test can use a throwaway key).
 *
 * [read] answers the entries this store owns, separated by [separator]: for the Windows user PATH that is the whole
 * value ([shared] = true, so every other tool's entry in it is preserved), for a POSIX shell-profile block it is only
 * what Infantry AI itself put there ([shared] = false, the rest of the profile is not ours to parse).
 */
interface UserPathStore {
    /** Where the entry lives, in words a person reads: `your user PATH (HKCU\Environment)`, `~/.zshrc`. */
    val where: String

    /** The separator of the entries [read] answers with and [write] takes. */
    val separator: Char

    /** True when the value holds entries of other tools too, which every write must keep, in their order. */
    val shared: Boolean

    /** True when the entries are Windows paths (compared case-insensitively). */
    val windowsPaths: Boolean

    /** The stored entries, or null when nothing is stored yet. */
    fun read(): String?

    /**
     * The stored entries as a list. A shared store splits its value on [separator]; a store that owns only its own block
     * answers the one entry it holds (a POSIX path may legitimately contain a colon, so nothing is split there).
     */
    fun entries(): List<String> = CliPath.entries(read(), separator)

    /** Store [value] (the entries, joined with [separator]); an empty value removes what this store owns. */
    fun write(value: String)

    /**
     * Entries an earlier write put here, kept beside the value where the store can (the Windows user PATH records them
     * in its own registry key). They make a DEAD entry recognisable as ours: the folder of an install that moved, or of
     * an uninstall that already deleted it, is still ours to remove and is not left pointing at nothing.
     */
    fun recorded(): List<String> = emptyList()

    /** Remember [entries] as ours; an empty list forgets them. */
    fun record(entries: List<String>) = Unit

    /** Tell the running system the environment changed (Windows: WM_SETTINGCHANGE). Best effort, never throws. */
    fun broadcast() = Unit
}

/** The store a real install uses on this OS, or null where PATH is not ours to write (nothing is, today, on a container). */
object UserPathStores {
    /** `C:\Users\x\AppData\Local\...\app\resources` on Windows, `<home>/.local/bin`'s profile on POSIX. */
    fun forThisMachine(
        os: String = System.getProperty("os.name").orEmpty(),
        home: String = System.getProperty("user.home"),
        env: (String) -> String? = { System.getenv(it) },
    ): UserPathStore = if (os.lowercase().startsWith("windows")) {
        RegistryUserPath()
    } else {
        PosixProfilePath(home, env("SHELL"), os)
    }
}

/**
 * The Windows USER PATH: `HKCU\Environment` value `Path` (never the machine PATH, never an administrator's).
 *
 * [keyPath] is a parameter so a test writes into a throwaway key (`Software\InfantryTest\Env-<random>`) instead of the
 * real `Environment` one — a unit test must never change the owner's PATH. The value's type is kept as it was found:
 * a PATH stored as `REG_EXPAND_SZ` (the normal case: it holds `%USERPROFILE%`) stays expandable, so nothing another
 * tool wrote stops expanding.
 */
class RegistryUserPath(
    private val keyPath: String = "Environment",
    private val valueName: String = "Path",
    private val recordName: String = "InfantryCliPath",
    private val root: WinReg.HKEY = WinReg.HKEY_CURRENT_USER,
) : UserPathStore {
    override val where: String get() = "your user PATH (HKCU\\$keyPath)"
    override val separator: Char get() = ';'
    override val shared: Boolean get() = true
    override val windowsPaths: Boolean get() = true

    override fun read(): String? = if (!Advapi32Util.registryValueExists(root, keyPath, valueName)) null else value()

    override fun recorded(): List<String> =
        runCatching { Advapi32Util.registryGetStringValue(root, keyPath, recordName) }.getOrNull()
            ?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    override fun record(entries: List<String>) {
        runCatching {
            if (entries.isEmpty()) {
                if (Advapi32Util.registryValueExists(root, keyPath, recordName)) Advapi32Util.registryDeleteValue(root, keyPath, recordName)
            } else {
                if (!Advapi32Util.registryKeyExists(root, keyPath)) Advapi32Util.registryCreateKey(root, keyPath)
                Advapi32Util.registrySetStringValue(root, keyPath, recordName, entries.joinToString(";"))
            }
        }
    }

    override fun write(value: String) {
        if (value.isBlank()) {
            if (Advapi32Util.registryValueExists(root, keyPath, valueName)) Advapi32Util.registryDeleteValue(root, keyPath, valueName)
            return
        }
        if (!Advapi32Util.registryKeyExists(root, keyPath)) Advapi32Util.registryCreateKey(root, keyPath)
        if (expandable()) Advapi32Util.registrySetExpandableStringValue(root, keyPath, valueName, value)
        else Advapi32Util.registrySetStringValue(root, keyPath, valueName, value)
    }

    /** The value, whichever of the two string types holds it (a `REG_SZ` PATH is legal, just rarer). */
    private fun value(): String? =
        runCatching { Advapi32Util.registryGetExpandableStringValue(root, keyPath, valueName) }
            .recoverCatching { Advapi32Util.registryGetStringValue(root, keyPath, valueName) }
            .getOrNull()

    /** True while the stored value is `REG_EXPAND_SZ` (or absent: a new PATH is written expandable). */
    private fun expandable(): Boolean = runCatching { Advapi32Util.registryGetExpandableStringValue(root, keyPath, valueName) }.isSuccess

    /** WM_SETTINGCHANGE to every top-level window: what makes a NEW terminal (and Explorer) re-read the environment. */
    override fun broadcast() {
        runCatching {
            val buffer = com.sun.jna.Memory(ENVIRONMENT.length + 1L)
            buffer.setString(0, ENVIRONMENT)
            User32Env.INSTANCE.SendMessageTimeoutA(Pointer.createConstant(HWND_BROADCAST), WM_SETTINGCHANGE, 0, buffer, SMTO_ABORTIFHUNG, NOTIFY_TIMEOUT_MS, IntByReference())
        }
    }

    private companion object {
        /** The lParam MSDN documents for a settings change: the name of the environment block that changed. */
        const val ENVIRONMENT = "Environment"
        const val HWND_BROADCAST = 0xffffL
        const val WM_SETTINGCHANGE = 0x001A
        const val SMTO_ABORTIFHUNG = 0x0002
        const val NOTIFY_TIMEOUT_MS = 5_000
    }

    /** `SendMessageTimeoutA(HWND_BROADCAST, WM_SETTINGCHANGE, 0, "Environment", SMTO_ABORTIFHUNG, 5 s)`. */
    private interface User32Env : StdCallLibrary {
        fun SendMessageTimeoutA(hWnd: Pointer, msg: Int, wParam: Int, lParam: Pointer, flags: Int, timeout: Int, result: IntByReference): Int

        companion object {
            val INSTANCE: User32Env by lazy { Native.load("user32", User32Env::class.java) }
        }
    }
}

/**
 * macOS/Linux: a marked block in the user's own shell profile (never a system-wide file, never `/etc`).
 *
 * The block is the PATH entry:
 * ```
 * # >>> Infantry AI CLI (infantry install-cli) >>>
 * export PATH="/Applications/InfantryAI.app/Contents/app/resources:$PATH"
 * # <<< Infantry AI CLI <<<
 * ```
 * so [read] answers only that one directory and a write rewrites (or, for an empty value, removes) the block; every
 * other line of the profile is left exactly as it was. The file is the shell's own startup file: `~/.zshrc` for zsh
 * (the macOS default), `~/.bashrc` for bash on Linux, `~/.bash_profile` for bash on macOS, else `~/.profile`.
 */
class PosixProfilePath(
    private val home: String,
    shell: String? = null,
    os: String = System.getProperty("os.name").orEmpty(),
) : UserPathStore {
    val file: File = profileFile(home, shell, os)

    override val where: String get() = file.path
    override val separator: Char get() = ':'
    override val shared: Boolean get() = false
    override val windowsPaths: Boolean get() = false

    override fun read(): String? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trim() == MARK_START }
        if (start < 0) return null
        val end = lines.drop(start).indexOfFirst { it.trim() == MARK_END }.let { if (it < 0) lines.size - 1 else start + it }
        return lines.subList(start + 1, end)
            .firstOrNull { EXPORT.containsMatchIn(it) }
            ?.let { EXPORT.find(it)?.groupValues?.get(1) }
            // The block puts our folder IN FRONT of the PATH it found: `"<dir>:$PATH"` reads back as `<dir>`.
            ?.removeSuffix(PATH_SUFFIX)
            ?.let { unescape(it) }
            ?.takeIf { it.isNotBlank() }
    }

    /** One entry, never split: the block holds exactly our folder, and a POSIX path may contain a colon. */
    override fun entries(): List<String> = listOfNotNull(read())

    override fun write(value: String) {
        val text = runCatching { file.readText() }.getOrDefault("")
        val kept = dropBlock(text)
        val next = if (value.isBlank()) kept else buildString {
            if (kept.isNotEmpty() && !kept.endsWith("\n")) append('\n')
            append(kept)
            append(MARK_START).append('\n')
            append("export PATH=\"").append(escape(value)).append(PATH_SUFFIX).append("\"\n")
            append(MARK_END).append('\n')
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".infantry-tmp")
        temp.writeText(next)
        if (!temp.renameTo(file)) {
            file.writeText(next)
            temp.delete()
        }
    }

    /** What a shell reads as an escape inside double quotes: `$`, a backquote, a quote and a backslash, nothing else. */
    private fun escape(path: String): String = buildString {
        for (c in path) {
            if (c in ESCAPED) append('\\')
            append(c)
        }
    }

    /** [escape] undone, so the path one run wrote compares equal to the same path in a later run. */
    private fun unescape(path: String): String = buildString {
        var i = 0
        while (i < path.length) {
            val c = path[i]
            if (c == '\\' && i + 1 < path.length && path[i + 1] in ESCAPED) {
                append(path[i + 1])
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    private fun dropBlock(text: String): String {
        if (text.isEmpty()) return text
        val lines = text.lines().toMutableList()
        while (true) {
            val start = lines.indexOfFirst { it.trim() == MARK_START }
            if (start < 0) break
            val rel = lines.drop(start).indexOfFirst { it.trim() == MARK_END }
            val end = if (rel < 0) lines.size - 1 else start + rel
            repeat(end - start + 1) { lines.removeAt(start) }
        }
        return lines.joinToString("\n").trimEnd('\n').let { if (it.isEmpty()) "" else it + "\n" }
    }

    companion object {
        const val MARK_START = "# >>> Infantry AI CLI (infantry install-cli) >>>"
        const val MARK_END = "# <<< Infantry AI CLI <<<"

        private val EXPORT = Regex("""^\s*export\s+PATH="([^"]*)""")

        /** What every block appends: the PATH the shell already had. */
        const val PATH_SUFFIX = ":\$PATH"

        /** The characters a shell reads as an escape inside double quotes. */
        private const val ESCAPED = "\"\\$`"

        fun profileFile(home: String, shell: String?, os: String = System.getProperty("os.name").orEmpty()): File {
            val mac = os.lowercase().startsWith("mac")
            val name = when {
                shell.orEmpty().endsWith("zsh") -> ".zshrc"
                shell.orEmpty().endsWith("bash") -> if (mac) ".bash_profile" else ".bashrc"
                mac -> ".zshrc"
                else -> ".profile"
            }
            return File(home, name).absoluteFile
        }
    }
}

/**
 * A plain file holding the entries of one line, the store for hosts where the profile is not the answer (a container
 * whose image already sets PATH, a CI box, a test): `INFANTRY_CLI_PATH_FILE=<file>` selects it, and
 * `infantry install-cli` then edits that file only. Also what the tests use, so no test ever writes a real PATH.
 */
class FileUserPath(private val file: File) : UserPathStore {
    override val where: String get() = file.path
    override val separator: Char get() = File.pathSeparatorChar
    override val shared: Boolean get() = true
    override val windowsPaths: Boolean get() = isWindows()

    /** The record is a sibling file, so a dead entry written here is still recognised (the same job as the registry value). */
    private val recordFile: File get() = File(file.parentFile, file.name + ".ours")

    override fun read(): String? = runCatching { file.readText() }.getOrNull()?.trimEnd('\n', '\r')?.takeIf { it.isNotBlank() }

    override fun recorded(): List<String> =
        runCatching { recordFile.readText() }.getOrNull()?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    override fun record(entries: List<String>) {
        runCatching {
            if (entries.isEmpty()) recordFile.delete() else {
                recordFile.parentFile?.mkdirs()
                recordFile.writeText(entries.joinToString("\n") + "\n")
            }
        }
    }

    override fun write(value: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".infantry-tmp")
        temp.writeText(value)
        if (!temp.renameTo(file)) {
            file.writeText(value)
            temp.delete()
        }
    }

    private fun isWindows() = File.separatorChar == '\\'
}
