package com.superiorsoftllc.agenthq.plugins.official

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ModuleSetting
import com.superiorsoftllc.agenthq.plugin.api.ModulesConfig
import com.superiorsoftllc.agenthq.plugin.api.NodeConfigEntry
import com.superiorsoftllc.agenthq.plugin.api.PreferenceDeclaration
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.AcceptedPreference
import com.superiorsoftllc.agenthq.plugin.host.ModuleCandidate
import com.superiorsoftllc.agenthq.plugin.host.ModuleResolver
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.OfficialTrust
import com.superiorsoftllc.agenthq.plugin.host.PluginFolders
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.DesktopPlugins
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.plugin.TabGroups
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RUNTIME-5: the built Infantry_Settings.jar, signed by a throwaway key in the install's trusted-keys (as RUNTIME-3/4),
 * loads at run time; the Settings host tab and its `children.grouped` switch work as before, a setup tab goes under it
 * when grouped, and a community module may replace it. Without the jar the app starts with no Settings tab.
 */
class SettingsJarLoadTest {
    private val tmp = Files.createTempDirectory("infantry-settings").toFile()

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun tool(name: String, vararg args: String) {
        val exe = File(File(System.getProperty("java.home"), "bin"), name + if (File.separatorChar == '\\') ".exe" else "").path
        val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "$name failed: $out" }
    }

    private val icon = tabIcon("probe", { moveTo(4f, 12f); lineTo(20f, 12f) })

    /** A compiled-in setup tab that names Settings its parent, as Infantry_Git and the others do. */
    private val probe = AgentHqModule(
        ModuleManifest(name = "Infantry_Probe", version = "1.0.0", tabs = listOf(TabDeclaration("infantry.probe", TabContainers.CENTER))),
        listOf(TabProvider(id = "infantry.probe", container = TabContainers.CENTER, order = 50, title = { "Probe" }, icon = icon, testTag = "center-tab-PROBE", parent = SETTINGS_TAB_ID) {}),
    )

    @Test
    fun `the signed official jar loads at run time and the Settings tab groups its setup tabs`() {
        val bundled = File(tmp, "install/modules")
        val state = File(tmp, "state")
        val env = mapOf(PluginFolders.BUNDLED_ENV to bundled.path)::get
        // The test pins its throwaway Infantry cert in place of the binary's pinned fingerprint (as OfficialTrustTest does).
        var pinned = OfficialTrust.PINNED_FINGERPRINTS
        fun load() = DesktopPlugins.load(state, env = env, property = { null }, compiled = listOf(probe), print = {}, pinned = pinned)

        // No jar: the app starts without a Settings tab; its would-be sub-tab stays in the strip.
        load().use {
            assertNull(it.factory.find(SETTINGS_TAB_ID), "no jar, no Settings tab")
            assertTrue(it.factory.tabs(TabContainers.CENTER).any { t -> t.id == "infantry.probe" })
        }

        val module = File(bundled, "official/Infantry_Settings").apply { mkdirs() }
        File(System.getProperty("infantrySettings.moduleJson")).copyTo(File(module, "module.json"))
        val jar = File(System.getProperty("infantrySettings.jar")).copyTo(File(module, "build/libs/Infantry_Settings.jar"))
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
            val status = f.statuses.single { s -> s.name == "Infantry_Settings" }
            assertEquals(ModuleState.LOADED, status.state, "${status.reason}\n${it.log.joinToString("\n")}")
            assertEquals(Tier.OFFICIAL, status.tier)
            assertEquals("Infantry_Settings", f.find(SETTINGS_TAB_ID)?.module)
            assertEquals("center-tab-SETTINGS", f.find(SETTINGS_TAB_ID)?.testTag)
            // The configuration field it contributes: off by default (Settings hidden), on groups the setup tabs under it.
            assertEquals(listOf(TabGroups.GROUPED), status.manifest!!.config.single { c -> c.node == SETTINGS_TAB_ID }.fields.map { c -> c.key })
            assertEquals(false, f.grouped(SETTINGS_TAB_ID))
            assertTrue(f.tabs(TabContainers.CENTER).none { t -> t.id == SETTINGS_TAB_ID })
            f.config.replace(listOf(NodeConfigEntry(SETTINGS_TAB_ID, TabGroups.GROUPED, JsonPrimitive(true))))
            assertEquals(listOf(SETTINGS_TAB_ID), f.tabs(TabContainers.CENTER).map { t -> t.id })
            assertEquals(listOf("infantry.probe"), f.children(SETTINGS_TAB_ID).map { t -> t.id })
        }
        println("[RUNTIME-5] Infantry_Settings load (signature check + class load + register): $ms ms (budget 500 ms)")

        // A community module may replace the official Settings tab through a preference, no allowOverride needed.
        val acme = ModuleManifest(name = "Acme_Settings", version = "1.0.0", author = "Acme", license = "NOASSERTION", tabs = listOf(TabDeclaration("acme.settings", TabContainers.CENTER)),
            preferences = listOf(PreferenceDeclaration(SETTINGS_TAB_ID, "acme.settings")))
        val official = InfantrySettings.manifest.copy(tier = "official")
        val resolved = ModuleResolver.resolve(listOf(
            ModuleCandidate("Infantry_Settings", Tier.OFFICIAL, null, official),
            ModuleCandidate("Acme_Settings", Tier.COMMUNITY, null, acme),
        ), ModulesConfig(modules = mapOf("Acme_Settings" to ModuleSetting(enabled = true))))
        assertEquals(listOf(AcceptedPreference("Acme_Settings", SETTINGS_TAB_ID, "acme.settings")), resolved.preferences, resolved.log.joinToString("\n"))
        val replaced = TabFactory(
            listOf(AgentHqModule(official, InfantrySettings.tabs, tier = Tier.OFFICIAL),
                AgentHqModule(acme, listOf(TabProvider(id = "acme.settings", container = TabContainers.CENTER, order = 999, title = { "Mine" }, icon = icon, testTag = "acme-settings") {}), tier = Tier.COMMUNITY)),
            preferences = resolved.preferences,
        )
        assertEquals("Acme_Settings", replaced.find(SETTINGS_TAB_ID)?.module)
    }
}
