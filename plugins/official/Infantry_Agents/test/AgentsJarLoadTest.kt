package com.superiorsoftllc.infantry.plugins.official

import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.plugin.host.OfficialTrust
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import com.superiorsoftllc.infantry.ui.plugin.DesktopPlugins
import com.superiorsoftllc.infantry.ui.plugin.Panels
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RUNTIME-4: the built Infantry_Agents.jar, signed by a key in the install's trusted-keys (a throwaway one per run, as
 * RUNTIME-2's OfficialTrustTest, which the test pins in place of the binary's fingerprint), loads at run time from the bundled official folder and
 * the agents section appears in the left panel. Without the jar the app starts and the section is simply absent.
 */
class AgentsJarLoadTest {
    private val tmp = Files.createTempDirectory("infantry-agents").toFile()

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun tool(name: String, vararg args: String) {
        val exe = File(File(System.getProperty("java.home"), "bin"), name + if (File.separatorChar == '\\') ".exe" else "").path
        val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "$name failed: $out" }
    }

    @Test
    fun `the signed official jar loads at run time and the agents section appears`() {
        val bundled = File(tmp, "install/modules")
        val state = File(tmp, "state")
        val env = mapOf(PluginFolders.BUNDLED_ENV to bundled.path)::get
        // The test pins its throwaway Infantry cert in place of the binary's pinned fingerprint (as OfficialTrustTest does).
        var pinned = OfficialTrust.PINNED_FINGERPRINTS
        fun load() = DesktopPlugins.load(state, env = env, property = { null }, compiled = emptyList(), print = {}, pinned = pinned)

        // No jar: the app starts, without the agents section.
        load().use { assertTrue(it.factory.panels(Panels.LEFT).none { p -> p.id == "infantry.agents" }, "no jar, no agents section") }

        val module = File(bundled, "official/Infantry_Agents").apply { mkdirs() }
        File(System.getProperty("infantryAgents.moduleJson")).copyTo(File(module, "module.json"))
        val jar = File(System.getProperty("infantryAgents.jar")).copyTo(File(module, "build/libs/Infantry_Agents.jar"))
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
            val status = f.statuses.single { s -> s.name == "Infantry_Agents" }
            assertEquals(ModuleState.LOADED, status.state, "${status.reason}\n${it.log.joinToString("\n")}")
            assertEquals(Tier.OFFICIAL, status.tier)
            val section = f.panels(Panels.LEFT).single { p -> p.id == "infantry.agents" }
            assertEquals("Infantry_Agents", section.module)
            assertEquals("left-panel-agents", section.testTag)
            assertTrue(section.fill, "the agents section takes the panel's free height")
        }
        println("[RUNTIME-4] Infantry_Agents load (signature check + class load + register): $ms ms (budget 500 ms)")
    }
}
