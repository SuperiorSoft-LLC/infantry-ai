package com.superiorsoftllc.infantry.plugins.official.run

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
 * RUNTIME-7: the built Infantry_Run.jar, signed by a throwaway key in the install's trusted-keys (as RUNTIME-3/4/5), loads
 * at run time: the Run tab and its own tab tree (ShellRun, `infantry.shell.run`, with its seven sections) are there as
 * they were when Infantry_AppUi registered the tree. Without the jar the app starts with no Run tab and no Run tree.
 */
class RunJarLoadTest {
    private val tmp = Files.createTempDirectory("infantry-run").toFile()

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun tool(name: String, vararg args: String) {
        val exe = File(File(System.getProperty("java.home"), "bin"), name + if (File.separatorChar == '\\') ".exe" else "").path
        val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "$name failed: $out" }
    }

    @Test
    fun `the signed official jar loads at run time with the Run tab and its tab tree`() {
        val bundled = File(tmp, "install/modules")
        val state = File(tmp, "state")
        val env = mapOf(PluginFolders.BUNDLED_ENV to bundled.path)::get
        // The test pins its throwaway Infantry cert in place of the binary's pinned fingerprint (as OfficialTrustTest does).
        var pinned = OfficialTrust.PINNED_FINGERPRINTS
        fun load() = DesktopPlugins.load(state, env = env, property = { null }, compiled = emptyList(), print = {}, pinned = pinned)

        load().use {
            assertNull(it.factory.find(RUN_TAB_ID), "no jar, no Run tab")
            assertNull(it.factory.tabTree("infantry.shell.run"), "no jar, no Run tree")
        }

        val module = File(bundled, "official/Infantry_Run").apply { mkdirs() }
        File(System.getProperty("infantryRun.moduleJson")).copyTo(File(module, "module.json"))
        val jar = File(System.getProperty("infantryRun.jar")).copyTo(File(module, "build/libs/Infantry_Run.jar"))
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
            val status = f.statuses.single { s -> s.name == "Infantry_Run" }
            assertEquals(ModuleState.LOADED, status.state, "${status.reason}\n${it.log.joinToString("\n")}")
            assertEquals(Tier.OFFICIAL, status.tier)
            assertEquals(emptyList(), f.problems)
            val tab = f.find(RUN_TAB_ID)
            assertEquals("Infantry_Run", tab?.module)
            assertEquals("center-tab-RUN", tab?.testTag)
            assertEquals("infantry.settings", tab?.parent)
            assertTrue(f.tabs(TabContainers.CENTER).any { t -> t.id == RUN_TAB_ID }, "no Settings jar: Run stays in the strip")
            assertEquals(RUN_TAB_ID, f.tabTreeTabs["infantry.shell.run"])
            assertEquals(
                listOf("empty", "toolbar", "pills", "confirm", "editor", "notices", "output").map { s -> "infantry.shell.run.$s" },
                f.tabTree("infantry.shell.run")!!.children.map { c -> c.id },
            )
        }
        println("[RUNTIME-7] Infantry_Run load (signature check + class load + register): $ms ms (budget 500 ms)")
    }
}
