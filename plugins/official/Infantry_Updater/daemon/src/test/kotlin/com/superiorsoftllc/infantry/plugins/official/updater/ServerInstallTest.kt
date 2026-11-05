// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerInstallTest {
    private val linux = ServerArchive.Platform("linux", "x64")
    private val windows = ServerArchive.Platform("windows", "x64")
    private fun tmp(): File = Files.createTempDirectory("server-install-test").toFile()

    private fun asset(name: String) = ReleaseAsset(name, 10)

    private fun tarEntry(out: ByteArrayOutputStream, name: String, body: ByteArray, mode: Int = 0b110_100_100, type: Char = '0') {
        val h = ByteArray(512)
        fun put(off: Int, text: String) = text.toByteArray().copyInto(h, off)
        put(0, name)
        put(100, "%07o".format(mode))
        put(124, "%011o".format(body.size))
        h[156] = type.code.toByte()
        put(257, "ustar")
        out.write(h)
        out.write(body)
        out.write(ByteArray((512 - body.size % 512) % 512))
    }

    private fun tarGz(file: File, entries: List<Triple<String, String, Int>>): File {
        val raw = ByteArrayOutputStream()
        entries.forEach { (n, b, m) -> tarEntry(raw, n, b.toByteArray(), m) }
        raw.write(ByteArray(1024))
        GZIPOutputStream(file.outputStream()).use { it.write(raw.toByteArray()) }
        return file
    }

    private fun verified(archive: File, version: String): VerifiedInstaller {
        val v = SemVer.parse(version)!!
        val a = asset(archive.name)
        val update = AvailableUpdate(SemVer(0, 0, 1), v, GitHubRelease(tagName = "v$version"), a, null)
        return VerifiedInstaller(update, archive, "", false)
    }

    private fun serverTar(dir: File, version: String): File = tarGz(
        File(dir, "InfantryAI-server-$version-linux-x64.tar.gz"),
        listOf(
            Triple("InfantryAI-server-$version-linux-x64/bin/infantry", "#!/bin/sh", 0b111_101_101),
            Triple("InfantryAI-server-$version-linux-x64/lib/app.jar", "jar", 0b110_100_100),
        ),
    )

    @Test fun `picks the archive for this os and arch, new prefix over legacy`() {
        val assets = listOf(
            asset("AgentHQ-server-1.2.0-linux-x64.tar.gz"), asset("InfantryAI-server-1.2.0-linux-x64.tar.gz"),
            asset("InfantryAI-server-1.2.0-windows-x64.zip"), asset("InfantryAI-server-1.2.0-linux-arm64.tar.gz"),
        )
        assertEquals("InfantryAI-server-1.2.0-linux-x64.tar.gz", ServerArchive.pick(assets, SemVer(1, 2, 0), linux)?.name)
        assertEquals("InfantryAI-server-1.2.0-windows-x64.zip", ServerArchive.pick(assets, SemVer(1, 2, 0), windows)?.name)
        assertEquals("AgentHQ-server-1.2.0-linux-x64.tar.gz", ServerArchive.pick(listOf(assets[0]), SemVer(1, 2, 0), linux)?.name)
        assertNull(ServerArchive.pick(assets, SemVer(1, 2, 0), ServerArchive.Platform("macos", "x64")))
        assertNull(ServerArchive.parse("InfantryAI-server-1.2.0-linux-x64.zip"))
    }

    @Test fun `host platform spells x64 like the build`() {
        assertEquals(ServerArchive.Platform("windows", "x64"), ServerArchive.hostPlatform("Windows 11", "amd64"))
        assertEquals(ServerArchive.Platform("macos", "arm64"), ServerArchive.hostPlatform("Mac OS X", "aarch64"))
    }

    @Test fun `local dir offers the server archive after verifying its checksum`() {
        val dir = tmp()
        val ver = File(dir, "1.2.0").also { it.mkdirs() }
        val archive = serverTar(ver, "1.2.0")
        File(ver, "SHA256SUMS").writeText(ReleaseAssets.sumsLine(ReleaseAssets.sha256(archive), archive.name) + "\n")
        val source = LocalDirSource(dir, SemVer(1, 1, 0), AssetKind.ServerArchiveFor(linux))
        val update = assertIs<UpdateCheck.Available>(source.check()).update
        val got = assertIs<Fetched.Ok<VerifiedInstaller>>(source.download(update, tmp())).value
        assertTrue(got.checksumVerified)
        archive.appendBytes(byteArrayOf(1)) // tamper after the sums were written
        assertIs<Fetched.Failed>(source.download(update, tmp()))
    }

    @Test fun `stage unpacks, switches current, keeps previous and rolls back`() {
        val root = tmp()
        val install = ServerInstall(root)
        install.stage(verified(serverTar(tmp(), "1.0.0"), "1.0.0"))
        install.stage(verified(serverTar(tmp(), "1.1.0"), "1.1.0"))
        assertEquals("1.1.0", install.current())
        assertEquals("1.0.0", install.previous())
        assertTrue(File(install.dirOf("1.1.0"), "bin/infantry").canExecute() || System.getProperty("os.name").startsWith("Windows"))
        assertEquals("1.0.0", install.rollback())
        assertEquals("1.0.0", install.current())
        assertEquals("1.1.0", install.previous())
    }

    @Test fun `prune keeps only current and previous`() {
        val install = ServerInstall(tmp())
        listOf("1.0.0", "1.1.0", "1.2.0", "1.3.0").forEach { install.stage(verified(serverTar(tmp(), it), it)) }
        assertTrue(install.dirOf("1.3.0").isDirectory && install.dirOf("1.2.0").isDirectory)
        assertTrue(!install.dirOf("1.0.0").exists() && !install.dirOf("1.1.0").exists())
    }

    @Test fun `an archive that is not a server install is refused and leaves nothing`() {
        val install = ServerInstall(tmp())
        val bad = tarGz(File(tmp(), "InfantryAI-server-1.0.0-linux-x64.tar.gz"), listOf(Triple("x/readme.txt", "hi", 0b110_100_100)))
        assertFailsWith<IOException> { install.stage(verified(bad, "1.0.0")) }
        assertNull(install.current())
        assertTrue(!install.dirOf("1.0.0").exists())
    }

    @Test fun `zip slip and links are refused`() {
        val zip = File(tmp(), "InfantryAI-server-1.0.0-windows-x64.zip")
        ZipOutputStream(zip.outputStream()).use { it.putNextEntry(ZipEntry("top/../../evil.txt")); it.write(1); it.closeEntry() }
        assertFailsWith<IOException> { ArchiveUnpacker.unpack(zip, tmp()) }

        val raw = ByteArrayOutputStream()
        tarEntry(raw, "top/link", ByteArray(0), type = '2')
        raw.write(ByteArray(1024))
        val tgz = File(tmp(), "l.tar.gz")
        GZIPOutputStream(tgz.outputStream()).use { it.write(raw.toByteArray()) }
        assertFailsWith<IOException> { ArchiveUnpacker.unpack(tgz, tmp()) }
    }

    @Test fun `windows zip unpacks`() {
        val zip = File(tmp(), "InfantryAI-server-1.0.0-windows-x64.zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("InfantryAI-server-1.0.0-windows-x64/bin/infantry.bat")); it.write(1); it.closeEntry()
            it.putNextEntry(ZipEntry("InfantryAI-server-1.0.0-windows-x64/lib/")); it.closeEntry()
        }
        val install = ServerInstall(tmp())
        install.stage(verified(zip, "1.0.0"))
        assertTrue(File(install.dirOf("1.0.0"), "bin/infantry.bat").isFile)
    }
}
