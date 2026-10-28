package com.superiorsoftllc.agenthq.plugins.official

import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.PluginFolders
import com.superiorsoftllc.agenthq.ui.plugin.DesktopPlugins
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * RUNTIME-3: the built Infantry_Cli.jar, signed by a key in the install's trusted-keys (a throwaway one per run, as
 * RUNTIME-2's OfficialTrustTest; no key is pinned), loads at run time from the bundled official folder and the CLI tab
 * appears, first in the centre strip. Without the jar the app starts and the tab is simply absent.
 */
class CliJarLoadTest {
    private val tmp = Files.createTempDirectory("infantry-cli").toFile()

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun tool(name: String, vararg args: String) {
        val exe = File(File(System.getProperty("java.home"), "bin"), name + if (File.separatorChar == '\\') ".exe" else "").path
        val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "$name failed: $out" }
    }

    @Test
    fun `the signed official jar loads at run time and the CLI tab appears`() {
        val bundled = File(tmp, "install/modules")
        val state = File(tmp, "state")
        val env = mapOf(PluginFolders.BUNDLED_ENV to bundled.path)::get
        fun load() = DesktopPlugins.load(state, env = env, property = { null }, compiled = emptyList(), print = {})

        // No jar: the app starts, without the CLI tab.
        load().use { assertNull(it.factory.find("infantry.cli"), "no jar, no CLI tab") }

        val module = File(bundled, "official/Infantry_Cli").apply { mkdirs() }
        File(System.getProperty("infantryCli.moduleJson")).copyTo(File(module, "module.json"))
        val jar = File(System.getProperty("infantryCli.jar")).copyTo(File(module, "build/libs/Infantry_Cli.jar"))
        val ks = File(tmp, "infantry.p12")
        tool("keytool", "-genkeypair", "-alias", "infantry", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Infantry", "-validity", "30",
            "-keystore", ks.path, "-storepass", "changeit", "-storetype", "PKCS12")
        tool("keytool", "-exportcert", "-alias", "infantry", "-keystore", ks.path, "-storepass", "changeit",
            "-file", File(bundled, "trusted-keys").apply { mkdirs() }.resolve("infantry.cer").path)
        tool("jarsigner", "-keystore", ks.path, "-storepass", "changeit", jar.path, "infantry")

        val started = System.nanoTime()
        val loaded = load()
        val ms = (System.nanoTime() - started) / 1_000_000
        loaded.use {
            val f = it.factory
            val status = f.statuses.single { s -> s.name == "Infantry_Cli" }
            assertEquals(ModuleState.LOADED, status.state, "${status.reason}\n${it.log.joinToString("\n")}")
            assertEquals(Tier.OFFICIAL, status.tier)
            assertEquals("Infantry_Cli", f.find("infantry.cli")?.module)
            assertEquals("center-tab-OUTPUT", f.tabs(TabContainers.CENTER).first().testTag)
            assertEquals("infantry.cli", f.byShortcut(1)?.id)
        }
        println("[RUNTIME-3] Infantry_Cli load (signature check + class load + register): $ms ms (budget 500 ms)")
    }
}
