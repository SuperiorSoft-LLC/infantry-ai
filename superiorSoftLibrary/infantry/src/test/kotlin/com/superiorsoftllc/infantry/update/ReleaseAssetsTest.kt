// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.update

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RELEASE-ASSETS-1: what `:infantryUi:releaseAssets` writes must be exactly what UPDATE-1's Updater accepts. A fake
 * .msi stands in for the jpackage output; the SHA256SUMS line format is the one the Updater's parser reads.
 */
class ReleaseAssetsTest {
    private val temp = Files.createTempDirectory("release-assets-test-").toFile()

    @AfterTest
    fun cleanup() {
        temp.deleteRecursively()
    }

    private fun fakeMsi(version: String): File =
        File(temp, "jpackage/InfantryAI-$version.msi").apply { parentFile.mkdirs(); writeBytes("fake installer $version".toByteArray()) }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun `the SHA256SUMS the task writes is accepted by the Updater's parser for that msi`() {
        val out = File(temp, "release/1.0.7")
        val written = ReleaseAssets.write(fakeMsi("1.0.7"), out, "1.0.7", gitSha = "abc1234")

        val msi = File(out, "InfantryAI-1.0.7.msi")
        assertTrue(msi.isFile, "the msi is copied into the release dir")
        val sums = File(out, "SHA256SUMS")
        // UPDATER-1: the Updater (its parser and asset picking) moved to the Infantry_Updater plugin, which core tests cannot
        // link; its own tests (UpdaterTest) cover the picking. Here: the line format that parser reads, `<sha256>  <file>`.
        val line = sums.readLines().single { it.endsWith(msi.name) }
        assertEquals(listOf(sha256(msi), msi.name), line.trim().split(Regex("\\s+")).map { it.trimStart('*') })
        assertEquals(sha256(msi), written.sha256)
    }

    @Test
    fun `release notes stub carries the version, tag and git sha`() {
        val out = File(temp, "release/1.0.8")
        ReleaseAssets.write(fakeMsi("1.0.8"), out, "1.0.8", gitSha = "deadbee")
        val notes = File(out, "RELEASE-NOTES.md").readText()
        assertTrue("1.0.8" in notes && "v1.0.8" in notes && "deadbee" in notes, notes)
    }

    @Test
    fun `a missing or invalid version is refused`() {
        for (bad in listOf(null, "", "1.0", "v1.0.1", "0.1.0", "1.256.0", "1.0.65536", "1.0.x")) {
            assertFailsWith<IllegalArgumentException>("version '$bad'") { ReleaseAssets.requireVersion(bad) }
        }
        assertEquals("1.0.9", ReleaseAssets.requireVersion(" 1.0.9 "))
    }

    @Test
    fun `an msi whose name does not match the version is refused`() {
        assertFailsWith<IllegalArgumentException> { ReleaseAssets.write(fakeMsi("1.0.1"), File(temp, "out"), "1.0.2", "abc") }
    }
}
