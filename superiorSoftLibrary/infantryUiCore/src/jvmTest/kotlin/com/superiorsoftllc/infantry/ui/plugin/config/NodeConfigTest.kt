package com.superiorsoftllc.infantry.ui.plugin.config

import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigSetBy
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1 step 2 (docs/BLOCKS-LAYOUT-DESIGN.md 6s B, C): the merged tree, its seed, and resolution child -> parent -> root -> default. */
class NodeConfigTest {
    private fun field(key: String, type: String, default: Any?) = ConfigFieldDeclaration(
        key, type,
        default = when (default) {
            null -> null
            is String -> JsonPrimitive(default)
            is Number -> JsonPrimitive(default)
            is Boolean -> JsonPrimitive(default)
            is List<*> -> JsonArray(default.map { JsonPrimitive(it as String) })
            else -> error("type")
        },
    )

    private fun module(name: String, tier: Tier, vararg config: ConfigDeclaration) =
        InfantryModule(ModuleManifest(name = name, version = "1.0.0", config = config.toList()), tier = tier)

    private val board = ConfigDeclaration(
        "infantry.shell.board", "Board",
        listOf(field("showEmpty", "checkbox", true), field("title", "text", "Board"), field("columns", "multiSelect", listOf("todo", "done"))),
    )

    /** A fake daemon: answers what it stores; seeding never overwrites. */
    private class FakeApi : NodeConfigApi {
        val rows = LinkedHashMap<Pair<String, String>, NodeConfigEntry>()
        var fail: String? = null
        override suspend fun seed(entries: List<NodeConfigEntry>): Result<List<NodeConfigEntry>> {
            fail?.let { return Result.failure(IllegalStateException(it)) }
            for (e in entries) rows.putIfAbsent(e.nodeId to e.key, e.copy(setBy = ConfigSetBy.SEED))
            return Result.success(rows.values.toList())
        }
        override suspend fun put(entry: NodeConfigEntry): Result<List<NodeConfigEntry>> {
            fail?.let { return Result.failure(IllegalStateException(it)) }
            if (entry.value == null) rows.remove(entry.nodeId to entry.key) else rows[entry.nodeId to entry.key] = entry
            return Result.success(rows.values.toList())
        }
    }

    // RUNTIME-15: the board overlay (infantry.shell.board) is the official Infantry_Board's, as the desktop loads its jar.
    private fun factory(vararg extra: InfantryModule) = TabFactory(CoreModules.all + com.superiorsoftllc.infantry.plugins.official.board.InfantryBoard + extra)

    @Test
    fun `the hierarchy is the block tree's and a tab sits under its strip`() {
        // RUNTIME-14: the last core center tab (Roles) is official now; its module is added as the desktop loads its jar.
        val roles = com.superiorsoftllc.infantry.plugins.official.roles.InfantryRoles
        val tree = factory(roles).configTree
        assertEquals("infantry.shell.board", tree.parents["infantry.shell.board.pane"])
        assertEquals(listOf("infantry.shell.board.pane", "infantry.shell.board"), tree.chain("infantry.shell.board.pane").take(2))
        assertEquals("infantry.shell.center.chat.tabs", tree.parents[(CoreModules.all + roles).flatMap { it.tabs }.first { it.container == "center" && it.parent == null }.id])
    }

    @Test
    fun `a value resolves at the node, then its parent, then the declared default, another type is null`() = runBlocking {
        val f = factory(module("Acme_Board", Tier.CORE, board))
        val api = FakeApi()
        val state = NodeConfigState(f.configTree, api)
        val config = ResolvedConfig(state)
        // Before any load: the declared defaults, inherited by a child.
        assertEquals(true, config.values("infantry.shell.board.pane").boolean("showEmpty"))
        state.load()
        assertEquals(NodeConfigLoad.LOADED, state.load)
        assertEquals(setOf("showEmpty", "title", "columns"), api.rows.keys.filter { it.first == "infantry.shell.board" }.map { it.second }.toSet())
        // Parent's stored value reaches the child; the child's own wins over it.
        state.set("infantry.shell.board", "title", JsonPrimitive("Tickets"))
        assertEquals("Tickets", config.values("infantry.shell.board.pane").string("title"))
        state.set("infantry.shell.board.pane", "title", JsonPrimitive("Pane"))
        assertEquals("Pane", config.values("infantry.shell.board.pane").string("title"))
        assertEquals("Tickets", config.values("infantry.shell.board").string("title"))
        // Cleared: back to the parent's.
        state.set("infantry.shell.board.pane", "title", null)
        assertEquals("Tickets", config.values("infantry.shell.board.pane").string("title"))
        // Typed, never coerced.
        assertNull(config.values("infantry.shell.board").int("title"))
        assertNull(config.values("infantry.shell.board").string("showEmpty"))
        assertEquals(listOf("todo", "done"), config.values("infantry.shell.board.pane").strings("columns"))
        // Unset is null, not an error; an unrelated node sees nothing.
        assertNull(config.values("infantry.shell.board").string("nope"))
        assertTrue(config.values("infantry.shell.footer").keys.isEmpty())
    }

    @Test
    fun `seeding never overwrites what the daemon has, and a failure keeps the defaults with a line`() = runBlocking {
        val f = factory(module("Acme_Board", Tier.CORE, board))
        val api = FakeApi()
        api.rows["infantry.shell.board" to "showEmpty"] = NodeConfigEntry("infantry.shell.board", "showEmpty", JsonPrimitive(false), ConfigSetBy.USER)
        val state = NodeConfigState(f.configTree, api)
        state.load()
        assertEquals(false, ResolvedConfig(state).values("infantry.shell.board").boolean("showEmpty"))
        assertEquals(ConfigSetBy.USER, state.own("infantry.shell.board", "showEmpty")?.setBy)

        val down = FakeApi().apply { fail = "could not reach the daemon" }
        val offline = NodeConfigState(f.configTree, down)
        offline.load()
        assertEquals(NodeConfigLoad.FAILED, offline.load)
        assertEquals("could not reach the daemon", offline.error)
        assertEquals(true, ResolvedConfig(offline).values("infantry.shell.board").boolean("showEmpty"))
    }

    @Test
    fun `a second declaration of a key and a community module's stranger node are refused with a line`() {
        val again = ConfigDeclaration("infantry.shell.board", "Board", listOf(field("showEmpty", "checkbox", false)))
        val stranger = ConfigDeclaration("infantry.shell.footer", "Footer", listOf(field("x", "text", "y")))
        val own = ConfigDeclaration("Acme_Comm.thing", "Acme", listOf(field("x", "gauge", "red")))
        val tree = factory(module("Acme_Board", Tier.CORE, board), module("Acme_Two", Tier.CORE, again), module("Acme_Comm", Tier.COMMUNITY, stranger, own)).configTree
        assertEquals("Acme_Board", tree.field("infantry.shell.board", "showEmpty")?.module)
        assertTrue(tree.problems.any { "Acme_Two: config key showEmpty on infantry.shell.board is already declared by Acme_Board" in it }, tree.problems.toString())
        assertTrue(tree.problems.any { "Acme_Comm: config for infantry.shell.footer refused" in it }, tree.problems.toString())
        assertNull(tree.fields["infantry.shell.footer"])
        // An unknown type is kept (shown read-only) with a line.
        assertEquals("gauge", tree.field("Acme_Comm.thing", "x")?.field?.type)
        assertTrue(tree.problems.any { "unknown type gauge" in it })
    }

    @Test
    fun `a community module declares on a node modules json grants it`() {
        val granted = ConfigDeclaration("infantry.shell.footer", "Footer", listOf(field("x", "text", "y")))
        val tree = TabFactory(CoreModules.all + module("Acme_Comm", Tier.COMMUNITY, granted), allowInject = { if (it == "Acme_Comm") listOf("infantry.shell.footer") else emptyList() }).configTree
        assertEquals("Acme_Comm", tree.field("infantry.shell.footer", "x")?.module)
    }
}
