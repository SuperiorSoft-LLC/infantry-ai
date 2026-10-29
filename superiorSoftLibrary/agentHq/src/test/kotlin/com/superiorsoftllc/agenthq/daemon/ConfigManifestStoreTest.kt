package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.db.AgentHQDatabaseFactory
import com.superiorsoftllc.agenthq.plugin.api.ConfigManifest
import com.superiorsoftllc.agenthq.plugin.api.ConfigManifestSource
import com.superiorsoftllc.agenthq.plugin.api.ConfigSetBy
import com.superiorsoftllc.agenthq.plugin.api.NodeConfigEntry
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** CONFIG-3: the compiled manifest is one row per host, replaced on a put; user overrides survive a swap. */
class ConfigManifestStoreTest {
    private val path = Files.createTempDirectory("config-manifest").resolve("agenthq.db").toString()

    private fun manifest(hash: String, accentDefault: String?, node: String = "infantry.shell.theme"): ConfigManifest {
        val field = if (accentDefault == null) """{"key":"accent"}""" else """{"default":"$accentDefault","key":"accent"}"""
        return ConfigManifest("desktop", hash, """[{"declaration":{"fields":[$field],"node":"$node"},"module":"Infantry_Appearance"}]""",
            listOf(ConfigManifestSource("Infantry_Appearance", "1.0.0", "m1")))
    }

    @Test
    fun `a put replaces the host's row and a read returns it, with the sources`() {
        AgentHQDatabaseFactory.open(path).use { h ->
            val store = ConfigManifestStore(h.database, NodeConfigStore(h.database)) { 9L }
            assertNull(store.get("desktop"))
            store.put(manifest("h1", "RED"))
            store.put(manifest("h2", "TEAL"))
            store.put(manifest("c1", "RED").copy(host = "compiled"))
            val row = store.get("desktop")!!
            assertEquals("h2", row.hash)
            assertEquals(9L, row.compiledAt)
            assertEquals(listOf(ConfigManifestSource("Infantry_Appearance", "1.0.0", "m1")), row.sources)
            assertEquals("c1", store.get("compiled")!!.hash)
        }
        AgentHQDatabaseFactory.open(path).use { h -> assertEquals("h2", ConfigManifestStore(h.database, NodeConfigStore(h.database)).get("desktop")!!.hash) }
    }

    @Test
    fun `a swap keeps the user's override, drops a default copy, and a removed plugin's fields leave the row`() {
        AgentHQDatabaseFactory.open(path).use { h ->
            val nodeConfig = NodeConfigStore(h.database)
            val store = ConfigManifestStore(h.database, nodeConfig)
            nodeConfig.put(NodeConfigEntry("infantry.shell.theme", "accent", JsonPrimitive("BLUE"), ConfigSetBy.USER))
            nodeConfig.seed(listOf(NodeConfigEntry("infantry.shell.theme", "other", JsonPrimitive("x"))))
            store.put(manifest("h1", "RED"))
            // A plugin update changes the default; the override is another table and is not touched.
            store.put(manifest("h2", "TEAL"))
            assertEquals(JsonPrimitive("BLUE"), nodeConfig.all().single { it.key == "accent" }.value)
            assertEquals(ConfigSetBy.USER, nodeConfig.all().single { it.key == "accent" }.setBy)
            // The plugin is removed: the new manifest has no such node; the fields are gone from the snapshot, the override is inert.
            store.put(manifest("h3", null, node = "infantry.shell.other"))
            assertEquals(false, "infantry.shell.theme" in store.get("desktop")!!.json)
            assertEquals(JsonPrimitive("BLUE"), nodeConfig.all().single { it.key == "accent" }.value)
        }
    }

    @Test
    fun `the manifest shape is checked`() {
        assertNull(ConfigManifestStore.defaultsOf("""{"not":"a list"}"""))
        assertNull(ConfigManifestStore.defaultsOf("""[{"module":"x"}]"""))
        assertEquals(mapOf("n" to mapOf("k" to JsonPrimitive(1))), ConfigManifestStore.defaultsOf("""[{"declaration":{"node":"n","fields":[{"key":"k","default":1},{"key":"nodef"}]},"module":"m"}]"""))
    }
}
