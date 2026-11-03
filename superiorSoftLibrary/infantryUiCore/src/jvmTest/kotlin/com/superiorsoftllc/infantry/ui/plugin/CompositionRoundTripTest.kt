// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import androidx.compose.runtime.Composable
import com.superiorsoftllc.infantry.plugin.api.BlockTypeDeclaration
import com.superiorsoftllc.infantry.plugin.api.DaemonCapabilities
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.InstallSettings
import com.superiorsoftllc.infantry.plugin.host.IhqInstaller
import com.superiorsoftllc.infantry.plugin.host.IhqResult
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.BlockTypes
import com.superiorsoftllc.infantry.ui.block.layout.LayoutExport
import com.superiorsoftllc.infantry.ui.block.layout.RowBlock
import com.superiorsoftllc.infantry.ui.plugin.config.NodeConfigApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** NS3: export a section -> one signed composition .ihq with its type module nested -> install in a fresh root -> same tree. */
class CompositionRoundTripTest {
    private val tmp = Files.createTempDirectory("ns3").toFile()
    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private object NoDaemon : NodeConfigApi {
        override suspend fun seed(entries: List<NodeConfigEntry>) = Result.success(emptyList<NodeConfigEntry>())
        override suspend fun put(entry: NodeConfigEntry) = Result.success(emptyList<NodeConfigEntry>())
    }

    private class Pane(id: String, children: List<Block>) : Block("acme.pane", id, children = children) {
        @Composable override fun Render(scope: BlockScope) {}
        override fun withChildren(children: List<Block>) = Pane(id, children)
    }
    private class Card(id: String) : Block("acme.card", id) {
        @Composable override fun Render(scope: BlockScope) {}
    }

    private val manifest = ModuleManifest(
        "Acme_Widgets", "1.0.0", author = "Acme", license = "NOASSERTION", entry = "acme.Entry", jar = "lib/Acme_Widgets.jar",
        blockTypes = listOf(BlockTypeDeclaration("acme.pane", "Pane", takesChildren = true), BlockTypeDeclaration("acme.card", "Card", template = true)),
        daemon = DaemonCapabilities(needs = listOf("tickets.read"), wants = listOf("tickets.start")),
    )
    private val widgets = registerModule(manifest, Tier.CORE, NoPluginServices, object : UiPlugin {
        override fun register(registrar: UiRegistrar) {
            registrar.blockType("acme.pane") { id, c -> Pane(id, c) }
            registrar.blockType("acme.card") { id, _ -> Card(id) }
        }
    })

    private fun sig(b: Block, types: BlockTypes): String =
        "${types.typeOf(b)}:${b.id}" + if (b.children.isEmpty()) "" else b.children.joinToString(",", "[", "]") { sig(it, types) }

    @Test fun `export a section, install it in a fresh root, and the resolved tree is equal`() {
        val section = Pane("acme.board", listOf(RowBlock("acme.row", children = listOf(Card("acme.card.one")))))
        val types = BlockTypes.of(listOf(Triple(widgets.manifest.name, widgets.manifest.blockTypes, widgets.blockTypes)))

        // 1. Export (pure): the page, the dependency, the daemon needs.
        val result = LayoutExport.export(section, types, mapOf(manifest.name to manifest), config = { emptyMap() }, packaged = { true })
        assertTrue(result.ok, result.refusals.joinToString())
        assertEquals(listOf("Acme_Widgets" to "1.0.0"), result.dependencies)
        assertEquals(DaemonCapabilities(listOf("tickets.read"), listOf("tickets.start")), result.daemon)
        val card = ((((result.page!!["root"] as JsonObject)["children"] as JsonArray)[0] as JsonObject)["children"] as JsonArray)[0] as JsonObject
        assertEquals(JsonPrimitive(true), card["template"])

        // 2. The published type package (unsigned here) and the composition, signed with a fresh export key.
        val dep = File(tmp, "Acme_Widgets.ihq")
        ZipOutputStream(dep.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("module.json")); z.write(com.superiorsoftllc.infantry.plugin.host.PluginJson.encodeToString(ModuleManifest.serializer(), manifest).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("lib/Acme_Widgets.jar")); z.write(ByteArray(64)); z.closeEntry()
        }
        val out = File(tmp, "Board.ihq")
        CompositionExport.write(out, "Board", "1.0.0", "infantry.shell.center", result.page!!, listOf(Triple("Acme_Widgets", "1.0.0", dep)), result.daemon, ExportKey.loadOrCreate(File(tmp, "key")))

        // 3. Install into a fresh root: the nested package first (its own tier), then the composition (community, export key).
        val root = File(tmp, "plugins").also { it.mkdirs() }
        val installed = IhqInstaller.install(out, root, settings = InstallSettings(allowUnsigned = true))
        assertIs<IhqResult.Installed>(installed, (installed as? IhqResult.Refused)?.message)
        assertEquals(Tier.COMMUNITY, installed.tier)
        assertEquals(listOf("Acme_Widgets"), installed.deps.map { it.name })
        assertTrue(File(installed.dir, "layout/board.json").isFile)
        assertTrue(!File(installed.dir, "deps").exists())
        val scanned = PluginFolders.scan(root, PluginFolders.config(root)).associateBy { it.folderName }
        assertEquals(setOf("Acme_Widgets", "Board"), scanned.keys)

        // 4. fills[].layout instantiates through the registry in the center slot of a fresh shell.
        val composition = DesktopPlugins.composition(scanned.getValue("Board").manifest!!, Tier.COMMUNITY, installed.dir)
        val f = TabFactory(listOf(widgets, composition), allowOverride = { listOf("infantry.shell.center") }, configApi = NoDaemon)
        assertEquals(emptyList(), f.problems.filter { "Board" in it })
        val center = f.baseRoot.let { r -> generateSequence(listOf(r)) { l -> l.flatMap { it.children }.takeIf { it.isNotEmpty() } }.flatten().first { it.id == "infantry.shell.center" } }
        assertEquals(sig(section, f.blockTypes), sig(center.children.single(), f.blockTypes))
    }
}
