// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.files

import com.superiorsoftllc.infantry.daemon.api.files.DirectoryProjectFiles
import com.superiorsoftllc.infantry.daemon.api.files.NoProjectFiles
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DP-10a: the project-scoped file surface refuses every path that leaves `<project>/.infantry/`, and behaves inside it. */
class ProjectFilesTest {
    private val dirs = mutableListOf<File>()
    private fun project(): File = Files.createTempDirectory("infantry-pf").toFile().also { dirs += it }

    @AfterTest fun clean() { dirs.forEach { it.deleteRecursively() }; dirs.clear() }

    @Test
    fun putGetStatListDeleteTouchStayUnderTheInfantryFolder() {
        val root = project()
        val files = DirectoryProjectFiles(root)
        files.put("uploads/chat/a.bin", byteArrayOf(1, 2, 3), modifiedMillis = 5_000_000L)
        assertTrue(File(root, ".infantry/uploads/chat/a.bin").isFile)
        assertContentEquals(byteArrayOf(1, 2, 3), files.get("uploads/chat/a.bin"))
        assertEquals(3L, files.stat("uploads/chat/a.bin")?.size)
        assertEquals(listOf("a.bin"), files.list("uploads/chat").map { it.name })
        assertEquals(File(root, ".infantry/uploads/chat/a.bin").absoluteFile.normalize().path, files.absolutePath("uploads/chat/a.bin"))
        assertTrue(files.touch("uploads/chat/a.bin", 9_000_000L))
        assertEquals(9_000_000L / 1000, files.stat("uploads/chat/a.bin")!!.modifiedMillis / 1000)
        assertTrue(files.delete("uploads/chat/a.bin"))
        assertFalse(files.delete("uploads/chat/a.bin"))
        assertNull(files.get("uploads/chat/a.bin"))
        assertEquals(emptyList(), files.list("nothing/here"))
    }

    @Test
    fun parentAbsoluteDriveAndEmptySegmentPathsAreRefusedAndNothingIsWritten() {
        val root = project()
        val files = DirectoryProjectFiles(root)
        for (bad in listOf("../x", "a/../../x", "/etc/x", "\\x", "C:/x", "C:x", "a//b", "./x", "a/./b", "", "  ", "a\\..\\..\\x")) {
            assertFailsWith<IllegalArgumentException>("put '$bad'") { files.put(bad, byteArrayOf(1)) }
            assertFailsWith<IllegalArgumentException>("get '$bad'") { files.get(bad) }
            assertFailsWith<IllegalArgumentException>("delete '$bad'") { files.delete(bad) }
        }
        assertFalse(File(root, ".infantry").exists(), "a refused path leaves no trace")
        assertEquals(emptyList<String>(), root.parentFile.list { _, n -> n == "x" }!!.toList())
    }

    @Test
    fun listOfTheRootIsAllowedButDeleteOfItIsNot() {
        val files = DirectoryProjectFiles(project())
        files.put("top.txt", byteArrayOf(1))
        assertEquals(listOf("top.txt"), files.list("").map { it.name })
        assertFailsWith<IllegalArgumentException> { files.delete("") }
    }

    @Test
    fun aLinkInsideTheFolderThatLeavesItIsRefused() {
        val root = project()
        val outside = project()
        File(outside, "secret.txt").writeText("s")
        File(root, ".infantry").mkdirs()
        val link = File(root, ".infantry/out").toPath()
        try { Files.createSymbolicLink(link, outside.toPath()) } catch (e: Exception) { return } // no link privilege on this host
        val files = DirectoryProjectFiles(root)
        assertFailsWith<IllegalArgumentException> { files.get("out/secret.txt") }
        assertFailsWith<IllegalArgumentException> { files.put("out/new.txt", byteArrayOf(1)) }
        assertFalse(File(outside, "new.txt").exists())
    }

    @Test
    fun theDefaultSurfaceRefusesEveryCall() {
        assertFailsWith<IllegalStateException> { NoProjectFiles.get("a") }
        assertFailsWith<IllegalStateException> { NoProjectFiles.put("a", byteArrayOf(1)) }
    }
}
