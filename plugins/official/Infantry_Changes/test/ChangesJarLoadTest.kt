// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.changes

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
 * RUNTIME-8: the built Infantry_Changes.jar, signed by a throwaway key in the install's trusted-keys (as RUNTIME-3..7), loads
 * at run time: the right-hand Changes tab and its own tab tree (ShellChanges, `infantry.shell.changes`, with its twelve
 * sections) are there as they were when Infantry_AppUi registered the tree. Without the jar the app starts with no Changes
 * tab and no Changes tree.
 */
class ChangesJarLoadTest {
    private val tmp = Files.createTempDirectory("infantry-changes").toFile()

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun tool(name: String, vararg args: String) {
        val exe = File(File(System.getProperty("java.home"), "bin"), name + if (File.separatorChar == '\\') ".exe" else "").path
        val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "$name failed: $out" }
    }

    @Test
    fun `the signed official jar loads at run time with the Changes tab and its tab tree`() {
        val bundled = File(tmp, "install/modules")
        val state = File(tmp, "state")
        val env = mapOf(PluginFolders.BUNDLED_ENV to bundled.path)::get
        // The test pins its throwaway Infantry cert in place of the binary's pinned fingerprint (as OfficialTrustTest does).
        var pinned = OfficialTrust.PINNED_FINGERPRINTS
        fun load() = DesktopPlugins.load(state, env = env, property = { null }, compiled = emptyList(), print = {}, pinned = pinned)

        load().use {
            assertNull(it.factory.find(CHANGES_TAB_ID), "no jar, no Changes tab")
            assertNull(it.factory.tabTree("infantry.shell.changes"), "no jar, no Changes tree")
        }

        val module = File(bundled, "official/Infantry_Changes").apply { mkdirs() }
        File(System.getProperty("infantryChanges.moduleJson")).copyTo(File(module, "module.json"))
        val jar = File(System.getProperty("infantryChanges.jar")).copyTo(File(module, "build/libs/Infantry_Changes.jar"))
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
            val status = f.statuses.single { s -> s.name == "Infantry_Changes" }
            assertEquals(ModuleState.LOADED, status.state, "${status.reason}\n${it.log.joinToString("\n")}")
            assertEquals(Tier.OFFICIAL, status.tier)
            assertEquals("Infantry_Changes", f.fillModules["infantry.shell.diff"])
            assertEquals(emptyList(), f.problems)
            val tab = f.find(CHANGES_TAB_ID)
            assertEquals("Infantry_Changes", tab?.module)
            assertEquals("right-tab-CHANGES", tab?.testTag)
            assertEquals(null, tab?.parent)
            assertEquals(3, tab?.shortcut)
            assertTrue(f.tabs(TabContainers.RIGHT).any { t -> t.id == CHANGES_TAB_ID }, "the Changes tab is in the right strip")
            assertEquals(CHANGES_TAB_ID, f.tabTreeTabs["infantry.shell.changes"])
            assertEquals(
                listOf("empty", "title", "unsupported", "notGit", "worktree", "header", "mode", "branch", "hint", "files", "branchFiles", "history").map { s -> "infantry.shell.changes.$s" },
                f.tabTree("infantry.shell.changes")!!.children.map { c -> c.id },
            )
        }
        println("[RUNTIME-8] Infantry_Changes load (signature check + class load + register): $ms ms (budget 500 ms)")
    }
}
