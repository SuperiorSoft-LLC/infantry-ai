package com.superiorsoftllc.infantry.ui.plugin.config

import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigSetBy
import com.superiorsoftllc.infantry.plugin.api.ImplementationDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.shell.implementation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1 step 2 (docs/BLOCKS-LAYOUT-DESIGN.md 6s D): implementation fields store registry keys; the tab sorter is the real one. */
class ImplementationFieldsTest {
    private val strip = "infantry.shell.center.chat.tabs"

    /** One daemon shared by several factories (a restart with another module installed). */
    private class Daemon : NodeConfigApi {
        val rows = LinkedHashMap<Pair<String, String>, NodeConfigEntry>()
        override suspend fun seed(entries: List<NodeConfigEntry>): Result<List<NodeConfigEntry>> {
            for (e in entries) rows.putIfAbsent(e.nodeId to e.key, e.copy(setBy = ConfigSetBy.SEED))
            return Result.success(rows.values.toList())
        }
        override suspend fun put(entry: NodeConfigEntry): Result<List<NodeConfigEntry>> {
            if (entry.value == null) rows.remove(entry.nodeId to entry.key) else rows[entry.nodeId to entry.key] = entry
            return Result.success(rows.values.toList())
        }
    }

    private val reverse = TabSorter { it.reversed() }

    private fun sorterModule(name: String, key: String, declared: Boolean = true) = InfantryModule(
        ModuleManifest(name = name, version = "1.0.0", implementations = if (declared) listOf(ImplementationDeclaration(TabSorters.REGISTRY, key)) else emptyList()),
        tier = Tier.CORE, implementations = mapOf(TabSorters.REGISTRY to mapOf(key to reverse)),
    )

    @Test
    fun `the strip's sorter is a stored key, byTitle sorts the strip, and an unknown key falls back to the default`() = runBlocking {
        val daemon = Daemon()
        val f = TabFactory(CoreModules.all, configApi = daemon)
        f.config.load()
        val declared = f.declaredTabs(TabContainers.CENTER)
        assertEquals(TabSorters.BY_ORDER, f.resolvedConfig.values(strip).string("sorter"))
        assertEquals(declared.map { it.id }, f.tabs(TabContainers.CENTER).map { it.id })
        f.config.set(strip, "sorter", JsonPrimitive(TabSorters.BY_TITLE))
        assertEquals(declared.sortedWith(compareBy({ TabSorters.label(it).lowercase() }, { it.id })).map { it.id }, f.tabs(TabContainers.CENTER).map { it.id })
        // Only the key is stored, never a class.
        assertEquals(JsonPrimitive(TabSorters.BY_TITLE), daemon.rows[strip to "sorter"]?.value)
        // A key no module registers (its module is gone) resolves to the default.
        f.config.set(strip, "sorter", JsonPrimitive("Gone_Module.sorter"))
        assertEquals(TabSorters.BY_ORDER, f.resolvedConfig.values(strip).string("sorter"))
        assertEquals(declared.map { it.id }, f.tabs(TabContainers.CENTER).map { it.id })
        // ShellContext.config hands out what is registered under a key.
        assertNotNull(f.resolvedConfig.implementation<TabSorter>(TabSorters.BY_TITLE))
        assertNull(f.resolvedConfig.implementation<TabSorter>("nope"))
    }

    @Test
    fun `a module's declared implementation is offered, an undeclared one is refused with a line`() {
        val f = TabFactory(CoreModules.all + sorterModule("Acme_Sort", "reverse") + sorterModule("Acme_Sly", "sly", declared = false))
        assertEquals(listOf(TabSorters.BY_ORDER, TabSorters.BY_TITLE, "reverse"), f.implementations.keys(TabSorters.REGISTRY))
        assertEquals("Acme_Sort", f.implementations.moduleOf(TabSorters.REGISTRY, "reverse"))
        assertTrue(f.implementations.problems.any { "Acme_Sly: implementation tabs.sorter/sly is not declared" in it }, f.implementations.problems.toString())
    }

    private val autoNode = "Acme_Host.panel"
    private fun host() = InfantryModule(
        ModuleManifest(
            name = "Acme_Host", version = "1.0.0",
            config = listOf(ConfigDeclaration(autoNode, "Acme", listOf(ConfigFieldDeclaration("sorter", "implementation", registry = TabSorters.REGISTRY, default = JsonPrimitive(TabSorters.BY_ORDER), exposed = true, autoSelectNew = true)))),
        ),
        tier = Tier.CORE,
    )

    @Test
    fun `a newly installed key becomes the selection only while the user never picked one`() = runBlocking {
        val daemon = Daemon()
        // First start: what is registered is recorded, nothing is selected.
        TabFactory(CoreModules.all + host(), configApi = daemon).config.load()
        assertEquals(JsonPrimitive(TabSorters.BY_ORDER), daemon.rows[autoNode to "sorter"]?.value)
        // Next start with a sorter module installed: it becomes the selection.
        val second = TabFactory(CoreModules.all + host() + sorterModule("Acme_Sort", "reverse"), configApi = daemon)
        second.config.load()
        assertEquals("reverse", second.resolvedConfig.values(autoNode).string("sorter"))
        // The user picks one; a later install does not override it.
        second.config.set(autoNode, "sorter", JsonPrimitive(TabSorters.BY_TITLE))
        val third = TabFactory(CoreModules.all + host() + sorterModule("Acme_Sort", "reverse") + sorterModule("Acme_More", "more"), configApi = daemon)
        third.config.load()
        assertEquals(TabSorters.BY_TITLE, third.resolvedConfig.values(autoNode).string("sorter"))
        // The bookkeeping key is the node's own, never inherited or shown as a value of a child.
        assertNull(third.resolvedConfig.values("$autoNode.child").string(ImplementationFields.knownKey("sorter")))
    }

    @Test
    fun `the core strips' sorter is exposed and does not auto-select`() {
        val f = TabFactory(CoreModules.all)
        val field = assertNotNull(f.configTree.field(strip, "sorter")).field
        assertTrue(field.exposed)
        assertEquals(false, field.autoSelectNew)
        assertEquals("Tabs", f.configTree.field(strip, "sorter")?.group)
        assertTrue(f.configTree.problems.isEmpty(), f.configTree.problems.toString())
    }
}
