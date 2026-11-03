package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.Tier
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RUNTIME-2: real keytool/jarsigner signatures, a test key generated per run (no key is committed). */
class OfficialTrustTest {
    private val tmp = Files.createTempDirectory("official-test").toFile()
    private val root = File(tmp, "plugins").also { it.mkdirs() }
    private val bundled = File(tmp, "install/modules")

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun tool(name: String, vararg args: String) {
        val exe = File(File(System.getProperty("java.home"), "bin"), name + if (File.separatorChar == '\\') ".exe" else "").path
        val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "$name failed: $out" }
    }

    /** An official module folder under [base]/official: module.json on disk and the same one inside its jar. */
    private fun module(base: File, name: String): File {
        val json = """{"name":"$name","version":"1.0.0","tier":"official","entry":"x.Y","jar":"$name.jar"}"""
        val dir = File(base, "official/$name").also { it.mkdirs() }
        File(dir, "module.json").writeText(json)
        val jar = File(dir, "$name.jar")
        ZipOutputStream(jar.outputStream()).use { z ->
            mapOf("module.json" to json.toByteArray(), "x/Y.class" to ByteArray(64) { it.toByte() }).forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return jar
    }

    @Test fun `an Infantry-signed official jar loads, an unsigned one is refused`() {
        val ks = File(tmp, "infantry.p12")
        tool("keytool", "-genkeypair", "-alias", "infantry", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Infantry", "-validity", "30",
            "-keystore", ks.path, "-storepass", "changeit", "-storetype", "PKCS12")
        // The install ships the Infantry key next to its bundled modules.
        val keys = File(bundled, "trusted-keys").also { it.mkdirs() }
        tool("keytool", "-exportcert", "-alias", "infantry", "-keystore", ks.path, "-storepass", "changeit", "-file", File(keys, "infantry.cer").path)
        tool("jarsigner", "-keystore", ks.path, "-storepass", "changeit", module(bundled, "Infantry_Signed").path, "infantry")
        module(root, "Infantry_Unsigned")
        // A second key whose cert is dropped into trusted-keys but is NOT pinned in the binary: refused as official.
        val rogue = File(tmp, "rogue.p12")
        tool("keytool", "-genkeypair", "-alias", "rogue", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Rogue", "-validity", "30",
            "-keystore", rogue.path, "-storepass", "changeit", "-storetype", "PKCS12")
        tool("keytool", "-exportcert", "-alias", "rogue", "-keystore", rogue.path, "-storepass", "changeit", "-file", File(keys, "rogue.cer").path)
        tool("jarsigner", "-keystore", rogue.path, "-storepass", "changeit", module(bundled, "Rogue_Signed").path, "rogue")

        val config = PluginFolders.config(root)
        val infantryKeys = OfficialTrust.infantryKeys(root, bundled)
        // The test pins its per-run Infantry cert in place of the binary's pinned fingerprint.
        val pinned = setOf(OfficialTrust.fingerprint(infantryKeys.single { "CN=Infantry" in it.subjectX500Principal.name }))
        val candidates = OfficialTrust.check(PluginFolders.scan(root, config, bundled), infantryKeys, devTrust = false, pinned = pinned)
        val r = ModuleResolver.resolve(candidates, config, platform = "desktop")
        val byName = r.statuses.associateBy { it.name }

        assertEquals(listOf("Infantry_Signed"), r.loaded.map { it.name })
        assertEquals(Tier.OFFICIAL, byName.getValue("Infantry_Signed").tier)
        val unsigned = byName.getValue("Infantry_Unsigned")
        assertEquals(ModuleState.REFUSED, unsigned.state)
        assertTrue("not signed" in unsigned.reason.orEmpty(), unsigned.reason)
        val rogueStatus = byName.getValue("Rogue_Signed")
        assertEquals(ModuleState.REFUSED, rogueStatus.state)
        assertTrue("not the pinned Infantry key" in rogueStatus.reason.orEmpty(), rogueStatus.reason)
        // With the binary's own pin (placeholder), a trusted-keys cert alone never makes a jar official.
        val unpinned = ModuleResolver.resolve(OfficialTrust.check(PluginFolders.scan(root, config, bundled), infantryKeys, devTrust = false), config, platform = "desktop")
        assertTrue(unpinned.loaded.isEmpty(), unpinned.loaded.map { it.name }.toString())

        // devTrust (dev runs only) lets the unsigned one through.
        val dev = ModuleResolver.resolve(OfficialTrust.check(PluginFolders.scan(root, config, bundled), emptyList(), devTrust = true), config, platform = "desktop")
        assertEquals(setOf("Infantry_Signed", "Infantry_Unsigned", "Rogue_Signed"), dev.loaded.map { it.name }.toSet())
    }
}
