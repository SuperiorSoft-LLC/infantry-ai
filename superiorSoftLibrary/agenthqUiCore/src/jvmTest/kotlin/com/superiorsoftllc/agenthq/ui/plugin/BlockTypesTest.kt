package com.superiorsoftllc.agenthq.ui.plugin

import com.superiorsoftllc.agenthq.plugin.api.BlockTypeDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.NodeConfigEntry
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.ui.block.layout.CoreBlockTypes
import com.superiorsoftllc.agenthq.ui.block.layout.RowBlock
import com.superiorsoftllc.agenthq.ui.block.layout.SpacerBlock
import com.superiorsoftllc.agenthq.ui.plugin.config.NodeConfigApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** NS1 / PB1 (docs/BLOCKS-LAYOUT-DESIGN.md 6t.2, 6t.5): the block type registry and the page JSON. */
class BlockTypesTest {
    private object NoDaemon : NodeConfigApi {
        override suspend fun seed(entries: List<NodeConfigEntry>) = Result.success(emptyList<NodeConfigEntry>())
        override suspend fun put(entry: NodeConfigEntry) = Result.success(emptyList<NodeConfigEntry>())
    }

    @Test fun `a module's declared block type registers beside core's layout primitives and the tree renders as the page JSON`() {
        val manifest = ModuleManifest(
            "Acme_Board", "1.2.0", author = "Acme", license = "NOASSERTION",
            blockTypes = listOf(BlockTypeDeclaration("acme.gap", "Gap")),
        )
        val module = registerModule(manifest, Tier.CORE, NoPluginServices, object : UiPlugin {
            override fun register(registrar: UiRegistrar) = registrar.blockType("acme.gap") { id, _ -> SpacerBlock(id) }
        })
        val f = TabFactory(listOf(module), configApi = NoDaemon)

        assertEquals(
            listOf(CoreBlockTypes.GROUP, CoreBlockTypes.ROW, CoreBlockTypes.COLUMN, CoreBlockTypes.STACK, CoreBlockTypes.SPACER, "acme.gap"),
            f.blockTypes.types.keys.toList(),
        )
        assertEquals("Acme_Board", f.blockTypes["acme.gap"]!!.module)
        assertEquals(emptyList(), f.problems)

        val gap = f.blockTypes.create("acme.gap", "user.gap.1")!!
        val row = assertIs<RowBlock>(f.blockTypes.create(CoreBlockTypes.ROW, "user.row.1", listOf(gap)))
        val page = f.pageJson(row)
        assertEquals(JsonPrimitive(1), page["schema"])
        assertEquals(
            JsonArray(listOf(JsonObject(mapOf("name" to JsonPrimitive("Acme_Board"), "version" to JsonPrimitive("1.2.0"))))),
            page["modules"],
        )
        val root = page["root"] as JsonObject
        assertEquals(JsonPrimitive(CoreBlockTypes.ROW), root["blockType"])
        assertEquals(JsonPrimitive("user.row.1"), root["id"])
        assertEquals(JsonPrimitive("Wrap"), (root["config"] as JsonObject)["size"])
        val child = (root["children"] as JsonArray).single() as JsonObject
        // The instance is a SpacerBlock, so its own blockType is the primitive's alias; the registry names it.
        assertEquals(JsonPrimitive(CoreBlockTypes.SPACER), child["blockType"])
        assertEquals(JsonPrimitive("user.gap.1"), child["id"])
    }
}
