// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin.config

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.ConfigTab
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CONFIG guard (docs/CONFIG-MANIFEST-DESIGN.md 0a), shared by every config phase: the OLD structure still works. An
 * old-shape module.json (no tab / order / title / description / new field types) and an old node_config row (no
 * newer keys) still parse, resolve, render (no generated card) and save exactly as before.
 */
class LegacyConfigStructureTest {
    private val oldModuleJson = """{
      "name": "Old_Board", "version": "1.0.0", "apiVersion": 2,
      "config": [ { "node": "infantry.shell.board", "group": "Board", "fields": [
        { "key": "showEmpty", "type": "checkbox", "default": true },
        { "key": "title", "type": "text", "label": "Title", "default": "Board" },
        { "key": "columns", "type": "multiSelect", "options": ["todo", "done"], "default": ["todo"] } ] } ] }"""

    private val oldRowJson = """{"nodeId":"infantry.shell.board","key":"showEmpty","value":false,"setBy":"user","updatedAt":3}"""

    private class Api : NodeConfigApi {
        val rows = LinkedHashMap<Pair<String, String>, NodeConfigEntry>()
        override suspend fun seed(entries: List<NodeConfigEntry>) = Result.success(rows.values.toList())
        override suspend fun put(entry: NodeConfigEntry): Result<List<NodeConfigEntry>> {
            if (entry.value == null) rows.remove(entry.nodeId to entry.key) else rows[entry.nodeId to entry.key] = entry
            return Result.success(rows.values.toList())
        }
    }

    @Test
    fun `an old module json and an old node_config row load, resolve, render and save as before`() = runBlocking {
        val manifest = Json { ignoreUnknownKeys = true }.decodeFromString(ModuleManifest.serializer(), oldModuleJson)
        val decl = manifest.config.single()
        assertNull(decl.tab); assertNull(decl.title); assertNull(decl.applies); assertEquals(emptyList(), decl.keywords)
        assertEquals("Board", decl.group)

        val factory = TabFactory(CoreModules.all + InfantryModule(manifest, tier = Tier.CORE))
        assertEquals(emptyList(), factory.configTree.problems)
        assertEquals("Old_Board", factory.configTree.field("infantry.shell.board", "showEmpty")?.module)

        // Old row, resolved over the old default; the user's false wins, an untouched key keeps its default.
        val api = Api()
        val state = NodeConfigState(factory.configTree, api)
        val resolved = ResolvedConfig(state)
        assertEquals(true, resolved.values("infantry.shell.board").boolean("showEmpty"))
        state.replace(listOf(Json.decodeFromString(NodeConfigEntry.serializer(), oldRowJson)))
        assertEquals(false, resolved.values("infantry.shell.board").boolean("showEmpty"))
        assertEquals("Board", resolved.values("infantry.shell.board").string("title"))
        assertEquals(listOf("todo"), resolved.values("infantry.shell.board").strings("columns"))

        // Saves as before: a user row through PUT, the same wire shape.
        state.load()
        assertTrue(state.set("infantry.shell.board", "title", JsonPrimitive("Tickets")))
        assertEquals("Tickets", resolved.values("infantry.shell.board").string("title"))
        assertEquals("user", api.rows.getValue("infantry.shell.board" to "title").setBy)

        // Renders as before: no declaration with a tab, so the dialog generates no card and the Plugins view lists it.
        assertTrue(generatedCards(factory, ConfigTab.APPEARANCE).isEmpty())
        assertTrue(factory.configTree.groups.contains("Board"))
    }
}
