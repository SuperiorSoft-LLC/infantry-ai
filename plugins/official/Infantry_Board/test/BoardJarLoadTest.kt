package com.superiorsoftllc.agenthq.plugins.official.board

import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.OfficialTrust
import com.superiorsoftllc.agenthq.plugin.host.PluginFolders
import com.superiorsoftllc.agenthq.ui.plugin.DesktopPlugins
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RUNTIME-15: the built Infantry_Board.jar, signed by a throwaway key in the install's trusted-keys (as RUNTIME-3..14), loads
 * at run time with the board overlay fill and the board's 14 block types as they were when compiled into the shell. Without
 * the jar the app starts with no board.
 */
class BoardJarLoadTest {
    private val tmp = Files.createTempDirectory("infantry-board").toFile()

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun tool(name: String, vararg args: String) {
        val exe = File(File(System.getProperty("java.home"), "bin"), name + if (File.separatorChar == '\\') ".exe" else "").path
        val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "$name failed: $out" }
    }

    @Test
    fun `the signed official jar loads at run time with the board`() {
        val bundled = File(tmp, "install/modules")
        val state = File(tmp, "state")
        val env = mapOf(PluginFolders.BUNDLED_ENV to bundled.path)::get
        // The test pins its throwaway Infantry cert in place of the binary's pinned fingerprint (as OfficialTrustTest does).
        var pinned = OfficialTrust.PINNED_FINGERPRINTS
        fun load() = DesktopPlugins.load(state, env = env, property = { null }, compiled = emptyList(), print = {}, pinned = pinned)

        load().use {
            assertNull(it.factory.fillModules[BOARD_FILL_ID], "no jar, no board")
            assertNull(it.factory.blockTypes[BoardTypes.PANE], "no jar, no board block types")
        }

        val module = File(bundled, "official/Infantry_Board").apply { mkdirs() }
        File(System.getProperty("infantryBoard.moduleJson")).copyTo(File(module, "module.json"))
        val jar = File(System.getProperty("infantryBoard.jar")).copyTo(File(module, "build/libs/Infantry_Board.jar"))
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
            val status = f.statuses.single { s -> s.name == "Infantry_Board" }
            assertEquals(ModuleState.LOADED, status.state, "${status.reason} ${it.log}")
            assertEquals(Tier.OFFICIAL, status.tier)
            assertEquals(emptyList(), f.problems)
            assertEquals("Infantry_Board", f.fillModules[BOARD_FILL_ID], "the jar fills the board overlay itself")
            assertTrue(BoardTypes.all.keys.all { t -> f.blockTypes[t] != null }, "the jar registers the board's block types")
            assertEquals(emptyList(), f.blockTypes.problems)
        }
        println("[RUNTIME-15] Infantry_Board load (signature check + class load + register): $ms ms (budget 500 ms)")
    }
}
