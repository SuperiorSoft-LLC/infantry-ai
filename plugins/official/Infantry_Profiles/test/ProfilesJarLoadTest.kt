package com.superiorsoftllc.infantry.plugins.official.profiles

import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.plugin.host.OfficialTrust
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import com.superiorsoftllc.infantry.ui.plugin.DesktopPlugins
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RUNTIME-12: the built Infantry_Profiles.jar, signed by a throwaway key in the install's trusted-keys (as RUNTIME-3..11), loads
 * at run time with the Profiles tab as it was when compiled into the shell. Without the jar the app starts with no Profiles tab.
 */
class ProfilesJarLoadTest {
    private val tmp = Files.createTempDirectory("infantry-profiles").toFile()

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun tool(name: String, vararg args: String) {
        val exe = File(File(System.getProperty("java.home"), "bin"), name + if (File.separatorChar == '\\') ".exe" else "").path
        val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "$name failed: $out" }
    }

    @Test
    fun `the signed official jar loads at run time with the Profiles tab`() {
        val bundled = File(tmp, "install/modules")
        val state = File(tmp, "state")
        val env = mapOf(PluginFolders.BUNDLED_ENV to bundled.path)::get
        // The test pins its throwaway Infantry cert in place of the binary's pinned fingerprint (as OfficialTrustTest does).
        var pinned = OfficialTrust.PINNED_FINGERPRINTS
        fun load() = DesktopPlugins.load(state, env = env, property = { null }, compiled = emptyList(), print = {}, pinned = pinned)

        load().use {
            assertNull(it.factory.find(PROFILES_TAB_ID), "no jar, no Profiles tab")
        }

        val module = File(bundled, "official/Infantry_Profiles").apply { mkdirs() }
        File(System.getProperty("infantryProfiles.moduleJson")).copyTo(File(module, "module.json"))
        val jar = File(System.getProperty("infantryProfiles.jar")).copyTo(File(module, "build/libs/Infantry_Profiles.jar"))
        val ks = File(tmp, "infantry.p12")
        tool("keytool", "-genkeypair", "-alias", "infantry", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Infantry", "-validity", "30",
            "-keystore", ks.path, "-storepass", "changeit", "-storetype", "PKCS12")
        tool("keytool", "-exportcert", "-alias", "infantry", "-keystore", ks.path, "-storepass", "changeit",
            "-file", File(bundled, "trusted-keys").apply { mkdirs() }.resolve("infantry.cer").path)
        tool("jarsigner", "-keystore", ks.path, "-storepass", "changeit", jar.path, "infantry")
        pinned = setOf(OfficialTrust.fingerprint(File(bundled, "trusted-keys/infantry.cer").inputStream().use { c ->
            java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(c) as java.security.cert.X509Certificate
        }))

        val started = System.nanoTime()
        val loaded = load()
        val ms = (System.nanoTime() - started) / 1_000_000
        loaded.use {
            val f = it.factory
            val status = f.statuses.single { s -> s.name == "Infantry_Profiles" }
            assertEquals(ModuleState.LOADED, status.state, "${status.reason} ${it.log}")
            assertEquals(Tier.OFFICIAL, status.tier)
            assertEquals(emptyList(), f.problems)
            val tab = f.find(PROFILES_TAB_ID)
            assertEquals("Infantry_Profiles", tab?.module)
            assertEquals("center-tab-PROFILES", tab?.testTag)
            assertTrue(f.tabs(TabContainers.CENTER).any { t -> t.id == PROFILES_TAB_ID })
        }
        println("[RUNTIME-12] Infantry_Profiles load (signature check + class load + register): $ms ms (budget 500 ms)")
    }
}
