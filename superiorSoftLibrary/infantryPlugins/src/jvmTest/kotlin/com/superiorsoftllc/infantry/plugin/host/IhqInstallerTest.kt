// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.Tier
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** S5: real keytool/jarsigner signatures, test keys generated per run (no key is committed). */
class IhqInstallerTest {
    private val tmp = Files.createTempDirectory("ihq-test").toFile()
    private val root = File(tmp, "plugins").also { it.mkdirs() }

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun tool(name: String, vararg args: String) {
        val exe = File(File(System.getProperty("java.home"), "bin"), name + if (File.separatorChar == '\\') ".exe" else "").path
        val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "$name failed: $out" }
    }

    /** A new keystore with one self-signed key; returns the keystore. The public cert is exported next to it. */
    private fun key(alias: String): File {
        val ks = File(tmp, "$alias.p12")
        tool("keytool", "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=$alias", "-validity", "30",
            "-keystore", ks.path, "-storepass", "changeit", "-storetype", "PKCS12")
        return ks
    }

    private fun trust(alias: String, ks: File) {
        val dir = File(root, "trusted-keys").also { it.mkdirs() }
        tool("keytool", "-exportcert", "-alias", alias, "-keystore", ks.path, "-storepass", "changeit", "-file", File(dir, "$alias.cer").path)
    }

    private fun manifest(name: String = "Test_Mod", jar: String = "lib/$name.jar") =
        """{"name":"$name","version":"1.0.0","entry":"x.Y","jar":"$jar"}"""

    private fun zip(name: String, entries: Map<String, ByteArray>): File {
        val f = File(tmp, name)
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return f
    }

    private fun good(name: String = "Test_Mod", extra: Map<String, ByteArray> = emptyMap()) =
        zip("$name.ihq", mapOf("module.json" to manifest(name).toByteArray(), "lib/$name.jar" to ByteArray(200) { it.toByte() }) + extra)

    private fun sign(f: File, ks: File, alias: String) = tool("jarsigner", "-keystore", ks.path, "-storepass", "changeit", f.path, alias)

    private fun assertRefused(r: IhqResult, reason: IhqRefusal) {
        assertIs<IhqResult.Refused>(r)
        assertEquals(reason, r.reason, r.message)
    }

    private fun noHalfInstall() {
        val leftovers = root.walkTopDown().filter { it.name.startsWith("_install-") }.toList()
        assertTrue(leftovers.isEmpty(), "left $leftovers")
    }

    @Test fun `infantry key lands in the core folder`() {
        val ks = key("infantry"); trust("infantry", ks)
        val p = good(); sign(p, ks, "infantry")
        val r = IhqInstaller.install(p, root)
        assertIs<IhqResult.Installed>(r)
        assertEquals(Tier.CORE, r.tier)
        assertTrue(File(root, "core/Test_Mod/module.json").isFile)
        assertTrue(File(root, "core/Test_Mod/lib/Test_Mod.jar").isFile)
        // The loader's own scan sees it.
        val found = PluginFolders.scan(root, PluginFolders.config(root)).single()
        assertEquals(Tier.CORE, found.tier)
        assertEquals("Test_Mod", found.manifest?.name)
        noHalfInstall()
    }

    @Test fun `another key lands in the community folder`() {
        val ks = key("stranger")
        val p = good(); sign(p, ks, "stranger")
        val r = IhqInstaller.install(p, root)
        assertIs<IhqResult.Installed>(r)
        assertEquals(Tier.COMMUNITY, r.tier)
        assertTrue(File(root, "community/Test_Mod/module.json").isFile)
    }

    @Test fun `unsigned is refused unless allowed, then community`() {
        val p = good()
        assertRefused(IhqInstaller.install(p, root), IhqRefusal.UNSIGNED)
        val r = IhqInstaller.install(p, root, settings = InstallSettings(allowUnsigned = true))
        assertIs<IhqResult.Installed>(r)
        assertEquals(Tier.COMMUNITY, r.tier)
    }

    @Test fun `a tampered jar is refused and nothing is left`() {
        val ks = key("infantry"); trust("infantry", ks)
        val p = good(); sign(p, ks, "infantry")
        val bad = File(tmp, "tampered.ihq")
        // Same signature files, one jar byte changed.
        java.util.zip.ZipFile(p).use { zf ->
            ZipOutputStream(bad.outputStream()).use { z ->
                zf.entries().asSequence().forEach { e ->
                    var b = zf.getInputStream(e).readBytes()
                    if (e.name == "lib/Test_Mod.jar") b = b.also { it[5] = (it[5] + 1).toByte() }
                    z.putNextEntry(ZipEntry(e.name)); z.write(b); z.closeEntry()
                }
            }
        }
        assertRefused(IhqInstaller.install(bad, root), IhqRefusal.BAD_SIGNATURE)
        assertFalse(File(root, "core").exists() && File(root, "core").list().orEmpty().isNotEmpty())
        noHalfInstall()
    }

    @Test fun `a tampered module json is refused`() {
        val ks = key("infantry"); trust("infantry", ks)
        val p = good(); sign(p, ks, "infantry")
        val bad = File(tmp, "tampered2.ihq")
        java.util.zip.ZipFile(p).use { zf ->
            ZipOutputStream(bad.outputStream()).use { z ->
                zf.entries().asSequence().forEach { e ->
                    val b = if (e.name == "module.json") """{"name":"Test_Mod","version":"9.9.9","entry":"x.Evil","jar":"lib/Test_Mod.jar"}""".toByteArray() else zf.getInputStream(e).readBytes()
                    z.putNextEntry(ZipEntry(e.name)); z.write(b); z.closeEntry()
                }
            }
        }
        assertRefused(IhqInstaller.install(bad, root), IhqRefusal.BAD_SIGNATURE)
        noHalfInstall()
    }

    @Test fun `an entry added after signing is refused`() {
        val ks = key("infantry"); trust("infantry", ks)
        val p = good(); sign(p, ks, "infantry")
        val bad = File(tmp, "added.ihq")
        java.util.zip.ZipFile(p).use { zf ->
            ZipOutputStream(bad.outputStream()).use { z ->
                zf.entries().asSequence().forEach { e -> z.putNextEntry(ZipEntry(e.name)); z.write(zf.getInputStream(e).readBytes()); z.closeEntry() }
                z.putNextEntry(ZipEntry("lib/Extra.jar")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
            }
        }
        assertRefused(IhqInstaller.install(bad, root), IhqRefusal.BAD_SIGNATURE)
        noHalfInstall()
    }

    @Test fun `a corrupted signature file is refused`() {
        val ks = key("infantry"); trust("infantry", ks)
        val p = good(); sign(p, ks, "infantry")
        val bad = File(tmp, "badsig.ihq")
        java.util.zip.ZipFile(p).use { zf ->
            ZipOutputStream(bad.outputStream()).use { z ->
                zf.entries().asSequence().forEach { e ->
                    val b = zf.getInputStream(e).readBytes()
                    if (e.name.endsWith(".RSA")) for (i in 20 until minOf(60, b.size)) b[i] = (b[i].toInt() xor 0x55).toByte()
                    z.putNextEntry(ZipEntry(e.name)); z.write(b); z.closeEntry()
                }
            }
        }
        val r = IhqInstaller.install(bad, root)
        assertIs<IhqResult.Refused>(r)
        assertTrue(r.reason == IhqRefusal.BAD_SIGNATURE || r.reason == IhqRefusal.UNSIGNED || r.reason == IhqRefusal.IO || r.reason == IhqRefusal.NOT_A_ZIP, "${r.reason} ${r.message}")
        noHalfInstall()
    }

    @Test fun `zip-slip entries are refused`() {
        for (evil in listOf("../evil.txt", "lib/../../evil.jar", "/abs.txt", "C:/win.txt", "lib\\..\\evil.jar")) {
            val p = good(extra = mapOf(evil to byteArrayOf(1)))
            val r = IhqInstaller.install(p, root, settings = InstallSettings(allowUnsigned = true))
            assertRefused(r, IhqRefusal.UNSAFE_ENTRY)
        }
        assertFalse(File(tmp, "evil.txt").exists())
        assertFalse(File(root, "evil.jar").exists())
        noHalfInstall()
    }

    @Test fun `unexpected entries are refused`() {
        val p = good(extra = mapOf("readme.txt" to byteArrayOf(1)))
        assertRefused(IhqInstaller.install(p, root, settings = InstallSettings(allowUnsigned = true)), IhqRefusal.UNEXPECTED_ENTRY)
    }

    @Test fun `a jar path outside lib is refused`() {
        val p = zip("x.ihq", mapOf("module.json" to manifest(jar = "../Test_Mod.jar").toByteArray(), "lib/Test_Mod.jar" to byteArrayOf(1)))
        assertRefused(IhqInstaller.install(p, root, settings = InstallSettings(allowUnsigned = true)), IhqRefusal.BAD_MANIFEST)
    }

    @Test fun `a module json that is not json is refused`() {
        val p = zip("nojson.ihq", mapOf("module.json" to "this is { not json".toByteArray(), "lib/Test_Mod.jar" to byteArrayOf(1)))
        val r = IhqInstaller.install(p, root, settings = InstallSettings(allowUnsigned = true))
        assertRefused(r, IhqRefusal.BAD_MANIFEST)
        assertTrue((r as IhqResult.Refused).message.contains("module.json"), r.message)
        assertTrue(root.list().orEmpty().isEmpty(), "left ${root.list()?.toList()}")
        assertTrue(PluginFolders.scan(root, PluginFolders.config(root)).isEmpty())
        noHalfInstall()
    }

    @Test fun `an invalid module name is refused and leaves no folder`() {
        val bad = listOf("", "a/b", "a\\b", "..", ".", "...", "CON", "nul", "Com1.txt", "LPT9", "_hidden", ".hidden", "trailing.", "x".repeat(65), "sp ace")
        for (name in bad) {
            val json = """{"name":${quote(name)},"version":"1.0.0","entry":"x.Y","jar":"lib/a.jar"}"""
            val p = zip("badname.ihq", mapOf("module.json" to json.toByteArray(), "lib/a.jar" to byteArrayOf(1)))
            val r = IhqInstaller.install(p, root, settings = InstallSettings(allowUnsigned = true))
            assertRefused(r, IhqRefusal.BAD_MANIFEST)
            assertTrue((r as IhqResult.Refused).message.isNotBlank(), "'$name'")
        }
        assertTrue(root.list().orEmpty().isEmpty(), "left ${root.list()?.toList()}")
        assertTrue(PluginFolders.scan(root, PluginFolders.config(root)).isEmpty())
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test fun `oversize is refused by every limit`() {
        val open = InstallSettings(allowUnsigned = true)
        val p = good()
        assertRefused(IhqInstaller.install(p, root, settings = open.copy(maxPackageBytes = 100)), IhqRefusal.TOO_BIG)
        assertRefused(IhqInstaller.install(p, root, settings = open.copy(maxEntryBytes = 100)), IhqRefusal.TOO_BIG)
        assertRefused(IhqInstaller.install(p, root, settings = open.copy(maxTotalBytes = 150)), IhqRefusal.TOO_BIG)
        assertRefused(IhqInstaller.install(p, root, settings = open.copy(maxEntries = 1)), IhqRefusal.TOO_MANY_ENTRIES)
        noHalfInstall()
        assertIs<IhqResult.Installed>(IhqInstaller.install(p, root, settings = open))
    }

    @Test fun `limits come from install json`() {
        File(root, "install.json").writeText("""{"maxEntryBytes":100,"allowUnsigned":true}""")
        assertRefused(IhqInstaller.install(good(), root), IhqRefusal.TOO_BIG)
    }

    @Test fun `the same package twice is refused and the first stays`() {
        val open = InstallSettings(allowUnsigned = true)
        val p = good()
        assertIs<IhqResult.Installed>(IhqInstaller.install(p, root, settings = open))
        val before = File(root, "community/Test_Mod/lib/Test_Mod.jar").length()
        assertRefused(IhqInstaller.install(p, root, settings = open), IhqRefusal.ALREADY_INSTALLED)
        assertEquals(before, File(root, "community/Test_Mod/lib/Test_Mod.jar").length())
        noHalfInstall()
    }

    @Test fun `an interrupted install leaves no folder`() {
        val open = InstallSettings(allowUnsigned = true)
        val p = good()
        val r = runCatching { IhqInstaller.install(p, root, settings = open, beforeCommit = { error("power cut") }) }
        assertTrue(r.isFailure)
        // The process "died" before cleanup ran only if the finally block did not: here it ran.
        noHalfInstall()
        assertFalse(File(root, "community/Test_Mod").exists())
        assertTrue(PluginFolders.scan(root, PluginFolders.config(root)).isEmpty())
    }

    @Test fun `a staging folder from a killed install is invisible to the scan and swept`() {
        val stage = File(root, "_install-dead").also { File(it, "lib").mkdirs() }
        File(stage, "module.json").writeText(manifest())
        assertTrue(PluginFolders.scan(root, PluginFolders.config(root)).isEmpty())
        IhqInstaller.sweep(root)
        assertFalse(stage.exists())
    }

    /** PLUGIN-GRADLE-1: the shape the Gradle plugin's `ihq` task packs for a module with a daemon half (module.json + both jars under lib/). */
    @Test fun `a package with a daemon half packed by the gradle plugin installs`() {
        val m = """{"name":"Test_Mod","version":"1.0.0","entry":"x.Y","jar":"lib/Test_Mod.jar","daemon":{"entry":"x.D","jar":"lib/Test_Mod-daemon.jar","apiAtLeast":"1.8.0"}}"""
        val p = zip("Test_Mod.ihq", mapOf("module.json" to m.toByteArray(), "lib/Test_Mod.jar" to ByteArray(50) { it.toByte() }, "lib/Test_Mod-daemon.jar" to ByteArray(60) { it.toByte() }))
        val r = IhqInstaller.install(p, root, settings = InstallSettings(allowUnsigned = true))
        assertIs<IhqResult.Installed>(r, r.toString())
        assertTrue(File(root, "community/Test_Mod/lib/Test_Mod-daemon.jar").isFile)
    }

    @Test fun `not a zip is refused`() {
        val f = File(tmp, "junk.ihq").also { it.writeText("hello") }
        assertRefused(IhqInstaller.install(f, root), IhqRefusal.NOT_A_ZIP)
    }
}
