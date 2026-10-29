package com.superiorsoftllc.agenthq.ui.plugin.config

import com.superiorsoftllc.agenthq.plugin.api.ConfigDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ConfigManifest
import com.superiorsoftllc.agenthq.plugin.api.ConfigSetBy
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.NodeConfigEntry
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CONFIG-3 (docs/CONFIG-MANIFEST-DESIGN.md section 2): the compiled manifest is canonical, hashed, published and read back. */
class ConfigManifestsTest {
    private fun accent(default: String) = ConfigDeclaration(
        "infantry.shell.theme", "Theme", listOf(ConfigFieldDeclaration("accent", "select", default = JsonPrimitive(default), options = listOf("RED", "TEAL"))),
        tab = "Appearance", order = 10, title = "Theme",
    )

    private fun module(name: String, vararg config: ConfigDeclaration) =
        AgentHqModule(ModuleManifest(name = name, version = "1.0.0", config = config.toList()), tier = Tier.CORE)

    private fun factory(vararg extra: AgentHqModule) = TabFactory(CoreModules.all + extra)

    /** A fake daemon: node_config rows plus one manifest row per host, replaced on a put as the real one does. */
    private class FakeApi : NodeConfigApi {
        val rows = LinkedHashMap<Pair<String, String>, NodeConfigEntry>()
        val manifests = HashMap<String, ConfigManifest>()
        var puts = 0
        override suspend fun seed(entries: List<NodeConfigEntry>) = Result.success(rows.values.toList())
        override suspend fun put(entry: NodeConfigEntry): Result<List<NodeConfigEntry>> {
            if (entry.value == null) rows.remove(entry.nodeId to entry.key) else rows[entry.nodeId to entry.key] = entry
            return Result.success(rows.values.toList())
        }
        override suspend fun manifest(host: String) = Result.success(manifests[host])
        override suspend fun putManifest(manifest: ConfigManifest): Result<ConfigManifest?> { puts++; manifests[manifest.host] = manifest; return Result.success(manifest) }
    }

    @Test
    fun `sha256 matches the standard vectors`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))
    }

    @Test
    fun `the hash is stable and moves with a default or with a plugin`() {
        val a = ConfigManifests.compile(factory(module("Acme_Look", accent("RED"))).configTree, "desktop", emptyList())
        val again = ConfigManifests.compile(factory(module("Acme_Look", accent("RED"))).configTree, "desktop", emptyList())
        assertEquals(a.hash, again.hash)
        assertEquals(a.json, again.json)
        assertEquals(64, a.hash.length)
        // A changed default is a new hash; so is a removed plugin (no jar, no field).
        assertNotEquals(a.hash, ConfigManifests.compile(factory(module("Acme_Look", accent("TEAL"))).configTree, "desktop", emptyList()).hash)
        val bare = ConfigManifests.compile(factory().configTree, "desktop", emptyList())
        assertNotEquals(a.hash, bare.hash)
        assertTrue("infantry.shell.theme" in a.json && "infantry.shell.theme" !in bare.json)
        // Keys are sorted: the canonical text does not depend on the order fields were written in.
        assertTrue(a.json.indexOf("\"declaration\"") < a.json.indexOf("\"module\""), a.json)
        assertEquals(listOf("Acme_Look"), ConfigManifests.parse(a)!!.map { it.module }.filter { it == "Acme_Look" })
    }

    @Test
    fun `load publishes the manifest once, keeps the user's override over a new default, and drops a removed plugin's fields`() = runBlocking {
        val api = FakeApi()
        val v1 = factory(module("Acme_Look", accent("RED"))).configTree
        val state1 = NodeConfigState(v1, api, "desktop")
        state1.load()
        assertEquals(1, api.puts)
        assertEquals(listOf("RED"), state1.snapshot!!.single { it.module == "Acme_Look" }.declaration.fields.map { (it.default as JsonPrimitive).content })
        // The same tree again: the hash matches, nothing is put.
        NodeConfigState(v1, api, "desktop").load()
        assertEquals(1, api.puts)

        // The user picks BLUE-ish: an override row. A plugin update changes the default to TEAL.
        state1.set("infantry.shell.theme", "accent", JsonPrimitive("RED"), ConfigSetBy.USER)
        val v2 = factory(module("Acme_Look", accent("TEAL"))).configTree
        val state2 = NodeConfigState(v2, api, "desktop")
        state2.load()
        assertEquals(2, api.puts)
        assertEquals("RED", (ResolvedConfig(state2).values("infantry.shell.theme").string("accent")), "the user's override is kept")
        state2.set("infantry.shell.theme", "accent", null)
        assertEquals("TEAL", ResolvedConfig(state2).values("infantry.shell.theme").string("accent"), "an un-overridden key takes the new default")

        // The plugin is removed: the next snapshot has no such declaration; no fallback tab, field or default.
        val state3 = NodeConfigState(factory().configTree, api, "desktop")
        state3.load()
        assertNull(state3.snapshot!!.firstOrNull { it.declaration.node == "infantry.shell.theme" })
        assertNull(ResolvedConfig(state3).values("infantry.shell.theme").string("accent"))
    }

    @Test
    fun `without a daemon snapshot the local tree's declarations are used`() = runBlocking {
        val tree = factory(module("Acme_Look", accent("RED"))).configTree
        val offline = NodeConfigState(tree, object : NodeConfigApi {
            override suspend fun seed(entries: List<NodeConfigEntry>) = Result.success(emptyList<NodeConfigEntry>())
            override suspend fun put(entry: NodeConfigEntry) = Result.success(emptyList<NodeConfigEntry>())
        })
        offline.load()
        assertNull(offline.snapshot)
        assertTrue(offline.declarations.any { it.module == "Acme_Look" && it.declaration.tab == "Appearance" })
    }
}
