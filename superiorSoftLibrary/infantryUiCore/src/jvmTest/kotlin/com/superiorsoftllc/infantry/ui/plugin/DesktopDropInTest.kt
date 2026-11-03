// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import com.superiorsoftllc.infantry.ui.block.layout.GroupBlock
import com.superiorsoftllc.infantry.ui.shell.DropCheck
import com.superiorsoftllc.infantry.ui.shell.NodeAsk
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S6 (drop-in path): the desktop drop-in over a real plugins root: inspect installs nothing, Install writes the owner's
 * per-node grants, safe mode loads no community module and can remove one, the lockdown refuses, and a core bundle
 * (a ModuleSource entry, as the app UI's .ihq has) takes part as if compiled in.
 */
class DesktopDropInTest {
    private val state = Files.createTempDirectory("drop-in-state").toFile()
    private val root = File(state, "plugins").also { it.mkdirs() }

    @AfterTest fun cleanup() { state.deleteRecursively() }

    /** An unsigned community package (install.json allows unsigned, so no key is needed here; signing is IhqInstallerTest's). */
    private fun communityPackage(name: String = "Acme_Panel"): File {
        File(root, "install.json").writeText("""{"allowUnsigned":true}""")
        val manifest = """{"name":"$name","version":"1.2.0","author":"Acme","license":"NOASSERTION","entry":"x.Y","jar":"lib/$name.jar",
            "shell":[{"node":"infantry.shell.left","name":"left"},{"node":"infantry.shell.connect","name":"connect"}],
            "inject":[{"into":"infantry.shell.frame","name":"bar"}]}"""
        val f = File(state, "$name.ihq")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("module.json")); z.write(manifest.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("lib/$name.jar")); z.write(ByteArray(64)); z.closeEntry()
        }
        return f
    }

    private fun dropIn(lockdown: Boolean = false, safeMode: Boolean = false) = DesktopDropIn(root, lockdown, safeMode)

    @Test fun `inspect lists what the file asks for and installs nothing`() {
        val check = assertIs<DropCheck.Ready>(dropIn().inspect(communityPackage().path))
        val p = check.preview
        assertEquals("Acme_Panel", p.name)
        assertEquals("1.2.0", p.version)
        assertFalse(p.core)
        assertNull(p.signer)
        assertEquals(
            listOf(
                NodeAsk("infantry.shell.left", NodeAsk.Kind.OVERRIDE, locked = false),
                NodeAsk("infantry.shell.connect", NodeAsk.Kind.OVERRIDE, locked = true),
                NodeAsk("infantry.shell.frame", NodeAsk.Kind.INJECT, locked = false),
            ),
            p.asks,
        )
        assertFalse(File(root, "community/Acme_Panel").exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("_install-") })
    }

    @Test fun `Install writes exactly the granted nodes per kind, never a locked one, and turns the module on`() {
        val d = dropIn()
        val p = assertIs<DropCheck.Ready>(d.inspect(communityPackage().path)).preview
        assertNull(d.install(p, setOf("infantry.shell.left", "infantry.shell.connect")))
        assertTrue(File(root, "community/Acme_Panel/module.json").isFile)
        val s = PluginFolders.config(root).setting("Acme_Panel")
        assertEquals(true, s.enabled)
        assertEquals(listOf("infantry.shell.left"), s.allowOverride, "the locked connect node is never written")
        assertEquals(emptyList(), s.allowInject, "the frame injection was not ticked")
    }

    @Test fun `a file that is not a ihq, or is refused, says why`() {
        assertIs<DropCheck.Refused>(dropIn().inspect(File(state, "notes.txt").apply { writeText("x") }.path))
        File(root, "install.json").writeText("{}")
        val unsigned = communityPackage().also { File(root, "install.json").writeText("{}") }
        val r = assertIs<DropCheck.Refused>(dropIn().inspect(unsigned.path))
        assertTrue("not signed" in r.message, r.message)
    }

    @Test fun `the lockdown refuses inspect, install and remove`() {
        val d = dropIn(lockdown = true)
        assertIs<DropCheck.Refused>(d.inspect(communityPackage().path))
        assertEquals(DesktopDropIn.LOCKED, d.remove("Acme_Panel"))
        assertFalse(File(root, "community").exists())
    }

    @Test fun `safe mode loads no community module, and removes an installed one`() {
        val d = dropIn()
        assertNull(d.install(assertIs<DropCheck.Ready>(d.inspect(communityPackage().path)).preview, emptySet()))
        val safe = DesktopPlugins.load(state, env = mapOf(PluginFolders.SAFE_MODE_ENV to "1")::get, property = { null }, compiled = emptyList(), print = {})
        assertTrue(safe.safeMode)
        val status = safe.factory.statuses.single { it.name == "Acme_Panel" }
        assertEquals(ModuleState.LOCKED, status.state)
        assertEquals(Tier.COMMUNITY, status.tier)
        // Not loaded, so its jar is not open: safe mode can delete it.
        val inSafe = dropIn(safeMode = true)
        assertEquals(listOf("Acme_Panel"), inSafe.installed())
        assertNull(inSafe.remove("Acme_Panel"))
        assertFalse(File(root, "community/Acme_Panel").exists())
        assertFalse("Acme_Panel" in PluginFolders.config(root).modules)
        assertEquals(emptyList(), inSafe.installed())
    }

    @Test fun `the property form of the flag, --safe-mode, turns safe mode on`() {
        assertTrue(PluginFolders.safeMode(env = { null }, property = mapOf(PluginFolders.SAFE_MODE_PROPERTY to "true")::get))
        assertFalse(PluginFolders.safeMode(env = { null }, property = { null }))
    }

    @Test fun `a core folder whose entry is a ModuleSource is a bundle, its modules load as if compiled in`() {
        val dir = File(root, "core/Probe_Bundle").apply { mkdirs() }
        File(dir, "module.json").writeText("""{"name":"Probe_Bundle","version":"1.0.0","entry":"${ProbeBundle::class.java.name}","jar":"lib/probe.jar","fills":["infantry.shell.center"]}""")
        ZipOutputStream(File(dir, "lib/probe.jar").apply { parentFile.mkdirs() }.outputStream()).use { it.putNextEntry(ZipEntry("x.txt")); it.closeEntry() }
        val loaded = DesktopPlugins.load(state, env = { null }, property = { null }, compiled = emptyList(), print = {})
        val f = loaded.factory
        assertEquals(ModuleState.LOADED, f.statuses.single { it.name == "Probe_Bundle" }.state)
        assertEquals(ModuleState.LOADED, f.statuses.single { it.name == "Probe_Other" }.state)
        assertTrue(f.fills("infantry.shell.center"), loaded.log.joinToString("\n"))
    }

    @Test fun `a community folder is never a bundle`() {
        val dir = File(root, "community/Probe_Bundle").apply { mkdirs() }
        File(dir, "module.json").writeText("""{"name":"Probe_Bundle","version":"1.0.0","author":"x","license":"x","entry":"${ProbeBundle::class.java.name}","jar":"lib/probe.jar"}""")
        ZipOutputStream(File(dir, "lib/probe.jar").apply { parentFile.mkdirs() }.outputStream()).use { it.putNextEntry(ZipEntry("x.txt")); it.closeEntry() }
        File(root, "modules.json").writeText("""{"modules":{"Probe_Bundle":{"enabled":true}}}""")
        val f = DesktopPlugins.load(state, env = { null }, property = { null }, compiled = emptyList(), print = {}).factory
        assertEquals(ModuleState.REFUSED, f.statuses.single { it.name == "Probe_Bundle" }.state)
        assertTrue(f.statuses.none { it.name == "Probe_Other" })
        assertFalse(f.fills("infantry.shell.center"))
    }
}

/** A bundle entry (on this test's class path, found through the jar's parent loader). */
class ProbeBundle : ModuleSource {
    override fun modules(): List<InfantryModule> = listOf(
        InfantryModule(
            ModuleManifest("Probe_Bundle", "1.0.0", author = "Probe", fills = listOf(com.superiorsoftllc.infantry.plugin.api.FillDeclaration("infantry.shell.center"))),
            fills = mapOf("infantry.shell.center" to GroupBlock("infantry.shell.center")),
        ),
        InfantryModule(ModuleManifest("Probe_Other", "1.0.0", author = "Probe")),
    )
}
