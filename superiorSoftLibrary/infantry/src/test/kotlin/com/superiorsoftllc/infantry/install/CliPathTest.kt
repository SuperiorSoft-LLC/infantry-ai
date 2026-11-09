// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.install

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CLI-PATH-1: the PATH writer, tested through [UserPathStore] so NO test ever changes the owner's real PATH or the
 * machine's environment. A fake store is used for the rules, [FileUserPath] over a temp file for the file-backed one,
 * [PosixProfilePath] over a temp home for the shell-profile one, and [RegistryUserPath] against a THROWAWAY registry
 * key (never `HKCU\Environment`) for the Windows user-environment one.
 */
class CliPathTest {
    /**
     * CI-TESTS-1: another tool's PATH entries in this OS's own form. A `C:\...` literal carries the drive colon, which IS
     * the separator on Linux and macOS, so there it split into two entries and the tests failed on the fixture, not the code.
     */
    private val FOREIGN_JAVA = if (File.pathSeparatorChar == ';') "C:\\Program Files\\Java\\bin" else "/opt/Program Files/java/bin"
    private val FOREIGN_SYS = if (File.pathSeparatorChar == ';') "C:\\Windows" else "/usr/bin"
    private val temp: File = File(System.getProperty("java.io.tmpdir"), "infantry-clipath-test-" + System.nanoTime()).apply { mkdirs() }
    private val windows: Boolean = CliPath.isWindows()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    /** A launcher directory as the app ships it: `infantry.cmd` naming the CLI's main class. */
    private fun launcherDir(name: String): File = File(temp, name).apply {
        mkdirs()
        File(this, "infantry.cmd").writeText("@echo off\r\njava -cp \"%~dp0..\\*\" ${CliPath.MAIN_CLASS} %*\r\n")
    }

    /** A directory that only LOOKS like a launcher directory: it holds `infantry.cmd`, but not Infantry AI's one. */
    private fun impostorDir(name: String): File = File(temp, name).apply {
        mkdirs()
        File(this, "infantry.cmd").writeText("@echo off\r\nother-tool.exe %*\r\n")
    }

    /** An in-memory shared PATH store (other tools' entries included), with the record a real store keeps. */
    private class FakeStore(initial: String?, private val sep: Char = File.pathSeparatorChar) : UserPathStore {
        var value: String? = initial
        var writes = 0
        var notified = 0
        var record: List<String> = emptyList()
        override val where: String get() = "a test PATH"
        override val separator: Char get() = sep
        override val shared: Boolean get() = true
        override val windowsPaths: Boolean get() = CliPath.isWindows()
        override fun read(): String? = value
        override fun write(value: String) {
            writes++
            this.value = value
        }
        override fun recorded(): List<String> = record
        override fun record(entries: List<String>) {
            record = entries
        }
        override fun broadcast() {
            notified++
        }
        fun entryList(): List<String> = CliPath.entries(value, sep)
    }

    @Test
    fun `install adds the launcher dir and keeps every other entry`() {
        val bin = launcherDir("bin")
        val other = File(temp, "other").apply { mkdirs() }
        val store = FakeStore(listOf(other.path, FOREIGN_JAVA).joinToString(File.pathSeparator))

        val out = CliPath.install(store, bin)

        assertTrue(out.changed)
        assertEquals(listOf(other.path, FOREIGN_JAVA, bin.absolutePath), store.entryList())
        assertEquals(listOf(bin.absolutePath), out.plan!!.added)
        assertTrue(store.entryList().any { it == other.path }, "another tool's entry is preserved")
        assertEquals(1, store.notified, "the user environment change is broadcast once")
    }

    @Test
    fun `installing twice writes nothing the second time`() {
        val bin = launcherDir("bin")
        val store = FakeStore(null)

        val first = CliPath.install(store, bin)
        val second = CliPath.install(store, bin)

        assertTrue(first.changed)
        assertFalse(second.changed, "the second install is a no-op")
        assertEquals(1, store.writes, "the value is written once")
        assertEquals(listOf(bin.absolutePath), store.entryList())
    }

    @Test
    fun `a trailing separator or a different case is the same entry`() {
        val bin = launcherDir("bin")
        val spelled = bin.absolutePath + File.separator
        val store = FakeStore(if (windows) spelled.uppercase() else spelled)

        val out = CliPath.install(store, bin)

        assertFalse(out.changed, "the same directory spelled differently is not added twice")
        assertEquals(1, CliPath.entries(store.value).size)
    }

    @Test
    fun `uninstall removes our entry and leaves everyone else's`() {
        val bin = launcherDir("bin")
        val other = File(temp, "other").apply { mkdirs() }
        val store = FakeStore(listOf(bin.path, other.path).joinToString(File.pathSeparator))
        CliPath.install(store, bin)

        val out = CliPath.uninstall(store, bin)

        assertTrue(out.changed)
        assertEquals(listOf(other.path), store.entryList())
        assertFalse(out.status.onPath)
    }

    @Test
    fun `uninstall writes nothing when we were never on the PATH`() {
        val bin = launcherDir("bin")
        val other = File(temp, "other").apply { mkdirs() }
        val store = FakeStore(other.path)

        val out = CliPath.uninstall(store, bin)

        assertFalse(out.changed)
        assertEquals(0, store.writes)
        assertEquals(listOf(other.path), store.entryList())
    }

    @Test
    fun `an update that moves the app replaces the entry that pointed at the old folder`() {
        val old = launcherDir("old-install")
        val new = launcherDir("new-install")
        val other = File(temp, "other").apply { mkdirs() }
        val store = FakeStore(listOf(old.path, other.path).joinToString(File.pathSeparator))
        CliPath.install(store, old)

        // The update removed the old install folder before the new version started.
        old.deleteRecursively()
        val out = CliPath.install(store, new)

        assertTrue(out.changed)
        assertEquals(listOf(other.path, new.absolutePath), store.entryList(), "the dead entry is gone, the new one is in")
        assertEquals(listOf(new.absolutePath), out.plan!!.added)
    }

    @Test
    fun `a dead entry we never wrote is left alone`() {
        val gone = File(temp, "some-other-app").apply { mkdirs() }.absolutePath
        File(gone).deleteRecursively()
        val store = FakeStore(gone)
        val bin = launcherDir("bin")

        CliPath.install(store, bin)

        assertEquals(listOf(gone, bin.absolutePath), store.entryList(), "another product's stale entry is not ours to remove")
    }

    @Test
    fun `a folder holding a foreign infantry script is not ours`() {
        val impostor = impostorDir("impostor")
        val bin = launcherDir("bin")
        val store = FakeStore(impostor.path)
        CliPath.install(store, bin)

        CliPath.uninstall(store, bin)

        assertEquals(listOf(impostor.path), store.entryList(), "a same-named script that does not start our CLI is left alone")
    }

    @Test
    fun `status says where the entry is and whether it still points at a launcher`() {
        val bin = launcherDir("bin")
        val store = FakeStore(null)
        assertFalse(CliPath.status(store, bin).onPath)
        assertTrue(CliPath.status(store, bin).line().contains("NOT on"))

        CliPath.install(store, bin)
        val live = CliPath.status(store, bin)
        assertTrue(live.onPath)
        assertTrue(live.live)
        assertEquals(bin.absolutePath, live.entry)

        File(bin, "infantry.cmd").delete()
        val stale = CliPath.status(store, bin)
        assertTrue(stale.onPath)
        assertFalse(stale.live, "the entry no longer holds a launcher")
        assertTrue(stale.line().contains("holds no launcher"))
    }

    @Test
    fun `a dry run reports the plan and writes nothing`() {
        val bin = launcherDir("bin")
        val store = FakeStore(null)

        val out = CliPath.install(store, bin, write = false)

        assertTrue(out.changed)
        assertEquals(0, store.writes)
        assertNull(store.value)
    }

    @Test
    fun `the file store adds, repeats and removes idempotently and records what it owns`() {
        val bin = launcherDir("bin")
        val other = File(temp, "other").apply { mkdirs() }
        val file = File(temp, "path.txt")
        file.writeText(listOf(other.path, FOREIGN_SYS).joinToString(File.pathSeparator))
        val store = FileUserPath(file)

        assertTrue(CliPath.install(store, bin).changed)
        assertFalse(CliPath.install(store, bin).changed)
        assertEquals(listOf(bin.absolutePath), FileUserPath(file).recorded())

        bin.deleteRecursively() // an update moved or removed the folder: only the record knows it was ours
        val moved = launcherDir("moved")
        assertTrue(CliPath.install(store, moved).changed)
        assertEquals(listOf(other.path, FOREIGN_SYS, moved.absolutePath), CliPath.entries(FileUserPath(file).read()))

        assertTrue(CliPath.uninstall(store, moved).changed)
        assertEquals(listOf(other.path, FOREIGN_SYS), CliPath.entries(FileUserPath(file).read()))
        assertTrue(FileUserPath(file).recorded().isEmpty())
    }

    @Test
    fun `a posix profile gets one marked block and keeps everything else`() {
        val home = File(temp, "home").apply { mkdirs() }
        val profile = File(home, ".bashrc").apply { writeText("export EDITOR=vim\nalias ll='ls -l'\n") }
        val bin = launcherDir("bin")
        val store = PosixProfilePath(home.path, "/bin/bash", "Linux")

        assertEquals(profile.absolutePath, store.file.path)
        assertTrue(CliPath.install(store, bin).changed)
        assertFalse(CliPath.install(store, bin).changed, "a second install changes nothing")
        val text = profile.readText()
        assertTrue(text.contains("export EDITOR=vim"), "the user's own lines survive")
        assertTrue(text.contains(PosixProfilePath.MARK_START))
        assertEquals(1, Regex(Regex.escape(PosixProfilePath.MARK_START)).findAll(text).count())
        assertEquals(bin.absolutePath, CliPath.status(store, bin).entry)

        assertTrue(CliPath.uninstall(store, bin).changed)
        val after = profile.readText()
        assertFalse(after.contains(PosixProfilePath.MARK_START))
        assertTrue(after.contains("alias ll='ls -l'"))
        assertNull(CliPath.status(store, bin).entry)
    }

    @Test
    fun `the profile file follows the shell that will read it`() {
        assertEquals(".zshrc", PosixProfilePath.profileFile("/home/x", "/bin/zsh", "Linux").name)
        assertEquals(".bashrc", PosixProfilePath.profileFile("/home/x", "/bin/bash", "Linux").name)
        assertEquals(".bash_profile", PosixProfilePath.profileFile("/Users/x", "/bin/bash", "Mac OS X").name)
        assertEquals(".zshrc", PosixProfilePath.profileFile("/Users/x", null, "Mac OS X").name)
        assertEquals(".profile", PosixProfilePath.profileFile("/home/x", "/bin/sh", "Linux").name)
    }

    @Test
    fun `the windows user environment store writes a throwaway key, never the real PATH`() {
        if (!windows) return // the registry is Windows-only; the rules above are covered by the fake store everywhere
        val key = "Software\\InfantryTest\\CliPath-" + System.nanoTime()
        val other = File(temp, "other").apply { mkdirs() }
        val store = RegistryUserPath(key)
        try {
            assertNull(store.read(), "the throwaway key starts empty")
            store.write(listOf(other.path, "%USERPROFILE%\\bin").joinToString(";"))
            assertEquals(listOf(other.path, "%USERPROFILE%\\bin"), CliPath.entries(store.read(), ';'))

            val bin = launcherDir("bin")
            assertTrue(CliPath.install(store, bin).changed)
            assertFalse(CliPath.install(store, bin).changed, "a repeated install writes nothing")
            assertEquals(listOf(bin.absolutePath), store.recorded())
            assertTrue(store.read()!!.startsWith(other.path + ";"), "another tool's entry stays, in its order")
            assertTrue(store.read()!!.contains("%USERPROFILE%\\bin"), "an expandable PATH stays expandable: %USERPROFILE% survives")

            assertTrue(CliPath.uninstall(store, bin).changed)
            assertEquals(listOf(other.path, "%USERPROFILE%\\bin"), CliPath.entries(store.read(), ';'))
            assertTrue(store.recorded().isEmpty())
        } finally {
            runCatching { com.sun.jna.platform.win32.Advapi32Util.registryDeleteKey(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, "Software\\InfantryTest", key.substringAfterLast('\\')) }
        }
    }
}
