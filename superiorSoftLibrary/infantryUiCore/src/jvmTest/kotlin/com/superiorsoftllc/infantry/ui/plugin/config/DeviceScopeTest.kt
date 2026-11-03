package com.superiorsoftllc.infantry.ui.plugin.config

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CONFIG-4: `scope:"device"` fields live in the host's prefs, between the parent chain and nothing in the daemon; unscoped fields do not change. */
class DeviceScopeTest {
    private val moduleJson = """{
      "name": "Dev_Mod", "version": "1.0.0", "apiVersion": 2,
      "config": [ { "node": "infantry.shell.board", "group": "Board", "fields": [
        { "key": "dark", "type": "checkbox", "default": true, "scope": "device" },
        { "key": "title", "type": "text", "default": "Board" } ] } ] }"""

    private class Prefs(val map: MutableMap<String, String> = LinkedHashMap()) : DevicePrefs {
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    private class Api : NodeConfigApi {
        val rows = LinkedHashMap<Pair<String, String>, NodeConfigEntry>()
        override suspend fun seed(entries: List<NodeConfigEntry>) = Result.success(rows.values.toList())
        override suspend fun put(entry: NodeConfigEntry): Result<List<NodeConfigEntry>> {
            if (entry.value == null) rows.remove(entry.nodeId to entry.key) else rows[entry.nodeId to entry.key] = entry
            return Result.success(rows.values.toList())
        }
    }

    private fun state(api: Api, prefs: Prefs): Pair<NodeConfigState, ResolvedConfig> {
        val manifest = Json { ignoreUnknownKeys = true }.decodeFromString(ModuleManifest.serializer(), moduleJson)
        val tree = TabFactory(CoreModules.all + InfantryModule(manifest, tier = Tier.CORE)).configTree
        val s = NodeConfigState(tree, api)
        s.attachDevice(prefs)
        return s to ResolvedConfig(s)
    }

    private val node = "infantry.shell.board"

    @Test
    fun `a device value beats the default, a daemon row for it is ignored, and null deletes`() = runBlocking {
        val api = Api(); val prefs = Prefs()
        val (s, r) = state(api, prefs)
        assertEquals(true, r.values(node).boolean("dark"))
        api.rows[node to "dark"] = NodeConfigEntry(node, "dark", JsonPrimitive(false))
        s.replace(api.rows.values.toList())
        assertEquals(true, r.values(node).boolean("dark"))      // daemon row ignored for a device field
        assertTrue(s.set(node, "dark", JsonPrimitive(false)))
        assertEquals(false, r.values(node).boolean("dark"))
        assertEquals("false", prefs.map["cfg.$node.dark"])
        assertEquals(1, api.rows.size)                           // nothing went to node_config
        assertTrue(r.values(node).overridden("dark"))
        s.set(node, "dark", null)
        assertEquals(true, r.values(node).boolean("dark"))
        assertEquals("", prefs.map["cfg.$node.dark"])
    }

    @Test
    fun `an unscoped field still saves through node_config`() = runBlocking {
        val api = Api(); val prefs = Prefs()
        val (s, r) = state(api, prefs)
        assertTrue(s.set(node, "title", JsonPrimitive("Tickets")))
        assertEquals("Tickets", r.values(node).string("title"))
        assertEquals("user", api.rows.getValue(node to "title").setBy)
        assertTrue(prefs.map.keys.none { it.endsWith(".title") })
    }

    @Test
    fun `the device value survives a restart`() = runBlocking {
        val prefs = Prefs()
        state(Api(), prefs).first.set(node, "dark", JsonPrimitive(false))
        assertEquals(false, state(Api(), prefs).second.values(node).boolean("dark"))
    }

    @Test
    fun `migration copies the old prefs once and keeps them`() {
        val prefs = Prefs(mutableMapOf("materialTheme" to "BLUE;light", "blurBehindDialogs" to "off", "chatMarkdownEngine" to "x", "reasoningPlacement" to "INLINE", "progressStaleMin" to "7"))
        DeviceScope.migrate(prefs)
        val t = "infantry.shell.theme"
        assertEquals("\"BLUE\"", prefs.map["cfg.$t.accent"])
        assertEquals("false", prefs.map["cfg.$t.dark"])
        assertEquals("false", prefs.map["cfg.$t.blur"])
        assertEquals("7", prefs.map["cfg.infantry.shell.board.progressStale"])
        assertEquals("BLUE;light", prefs.map["materialTheme"])
        assertEquals("off", prefs.map["blurBehindDialogs"])
        prefs.map["materialTheme"] = "RED;dark"; prefs.map.remove("cfg.$t.accent")
        DeviceScope.migrate(prefs)                                // marker: does not run again
        assertNull(prefs.map["cfg.$t.accent"])
    }
}
