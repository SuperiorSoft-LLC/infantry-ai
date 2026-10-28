package com.superiorsoftllc.agenthq.plugins.official.git

import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.OfficialTrust
import com.superiorsoftllc.agenthq.plugin.host.PluginFolders
import com.superiorsoftllc.agenthq.ui.plugin.DesktopPlugins
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RUNTIME-6: the built Infantry_Git.jar, signed by a throwaway key in the install's trusted-keys (as RUNTIME-3/4/5), loads
 * at run time: the Git tab and its own tab tree (ShellGit, `infantry.shell.git`, with its seven sections) are there as
 * they were when Infantry_AppUi registered the tree. Without the jar the app starts with no Git tab and no Git tree.
 */
class GitJarLoadTest {
    private val tmp = Files.createTempDirectory("infantry-git").toFile()

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun tool(name: String, vararg args: String) {
        val exe = File(File(System.getProperty("java.home"), "bin"), name + if (File.separatorChar == '\\') ".exe" else "").path
        val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "$name failed: $out" }
    }

    @Test
    fun `the signed official jar loads at run time with the Git tab and its tab tree`() {
        val bundled = File(tmp, "install/modules")
        val state = File(tmp, "state")
        val env = mapOf(PluginFolders.BUNDLED_ENV to bundled.path)::get
        // The test pins its throwaway Infantry cert in place of the binary's pinned fingerprint (as OfficialTrustTest does).
        var pinned = OfficialTrust.PINNED_FINGERPRINTS
        fun load() = DesktopPlugins.load(state, env = env, property = { null }, compiled = emptyList(), print = {}, pinned = pinned)

        load().use {
            assertNull(it.factory.find(GIT_TAB_ID), "no jar, no Git tab")
            assertNull(it.factory.tabTree("infantry.shell.git"), "no jar, no Git tree")
        }

        val module = File(bundled, "official/Infantry_Git").apply { mkdirs() }
        File(System.getProperty("infantryGit.moduleJson")).copyTo(File(module, "module.json"))
        val jar = File(System.getProperty("infantryGit.jar")).copyTo(File(module, "build/libs/Infantry_Git.jar"))
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
            val status = f.statuses.single { s -> s.name == "Infantry_Git" }
            assertEquals(ModuleState.LOADED, status.state, "${status.reason}\n${it.log.joinToString("\n")}")
            assertEquals(Tier.OFFICIAL, status.tier)
            assertEquals(emptyList(), f.problems)
            val tab = f.find(GIT_TAB_ID)
            assertEquals("Infantry_Git", tab?.module)
            assertEquals("center-tab-GIT", tab?.testTag)
            assertEquals("infantry.settings", tab?.parent)
            assertTrue(f.tabs(TabContainers.CENTER).any { t -> t.id == GIT_TAB_ID }, "no Settings jar: Git stays in the strip")
            assertEquals(GIT_TAB_ID, f.tabTreeTabs["infantry.shell.git"])
            assertEquals(
                listOf("base", "directory", "tree", "diff", "branches", "pipeline", "history").map { s -> "infantry.shell.git.$s" },
                f.tabTree("infantry.shell.git")!!.children.map { c -> c.id },
            )
        }
        println("[RUNTIME-6] Infantry_Git load (signature check + class load + register): $ms ms (budget 500 ms)")
    }
}
