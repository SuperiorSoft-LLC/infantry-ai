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

/** PLUGIN-TIER-1: Core / Official / Community comes from the proved publisher, never from a string in module.json. */
class TierLabelTest {
    private val tmp = Files.createTempDirectory("tierlabel-test").toFile()
    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    @Test fun `label rule`() {
        assertEquals("Core", TierLabel.of(Tier.OFFICIAL, bundled = true, publisherVerified = false))
        assertEquals("Core", TierLabel.of(Tier.CORE, false, false))
        assertEquals("Official", TierLabel.of(Tier.OFFICIAL, bundled = false, publisherVerified = false))
        assertEquals("Official", TierLabel.of(Tier.COMMUNITY, false, publisherVerified = true))
        assertEquals("Community", TierLabel.of(Tier.COMMUNITY, false, false))
    }

    private fun tool(name: String, vararg args: String) {
        val exe = File(File(System.getProperty("java.home"), "bin"), name + if (File.separatorChar == '\\') ".exe" else "").path
        val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "$name failed: $out" }
    }

    private fun module(base: File, name: String, claim: String): File {
        val json = """{"name":"$name","version":"1.0.0","tier":"$claim","author":"SuperiorSoft","entry":"x.Y","jar":"$name.jar"}"""
        val dir = File(base, "community/$name").also { it.mkdirs() }
        File(dir, "module.json").writeText(json)
        val jar = File(dir, "$name.jar")
        ZipOutputStream(jar.outputStream()).use { z ->
            mapOf("module.json" to json.toByteArray(), "x/Y.class" to ByteArray(64) { it.toByte() }).forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return jar
    }

    @Test fun `signed installed plugin is Official, forged or third party is Community`() {
        val root = File(tmp, "plugins").also { it.mkdirs() }
        val bundled = File(tmp, "install/modules")
        val ks = File(tmp, "infantry.p12")
        tool("keytool", "-genkeypair", "-alias", "infantry", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Infantry", "-validity", "30", "-keystore", ks.path, "-storepass", "changeit", "-storetype", "PKCS12")
        val keys = File(bundled, "trusted-keys").also { it.mkdirs() }
        tool("keytool", "-exportcert", "-alias", "infantry", "-keystore", ks.path, "-storepass", "changeit", "-file", File(keys, "infantry.cer").path)
        tool("jarsigner", "-keystore", ks.path, "-storepass", "changeit", module(root, "Hosting_Signed", "official").path, "infantry")
        module(root, "Forged_Official", "official") // claims official and SuperiorSoft, unsigned
        module(root, "Third_Party", "community")
        val infantryKeys = OfficialTrust.infantryKeys(root, bundled)
        val pinned = setOf(OfficialTrust.fingerprint(infantryKeys.single()))
        val config = PluginFolders.config(root)
        val marked = OfficialTrust.markPublishers(PluginFolders.scan(root, config, bundled), infantryKeys, pinned)
        val byName = ModuleResolver.resolve(marked, config, platform = "desktop").statuses.associateBy { it.name }
        assertEquals("Official", byName.getValue("Hosting_Signed").tierLabel)
        assertEquals("Community", byName.getValue("Forged_Official").tierLabel)
        assertEquals("Community", byName.getValue("Third_Party").tierLabel)
        // The real binary pin (placeholder): a trusted-keys cert alone proves nothing.
        val unpinned = OfficialTrust.markPublishers(PluginFolders.scan(root, config, bundled), infantryKeys)
        assertEquals("Community", ModuleResolver.resolve(unpinned, config, platform = "desktop").statuses.first { it.name == "Hosting_Signed" }.tierLabel)
    }
}
