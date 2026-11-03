// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin.config

import com.superiorsoftllc.infantry.plugin.api.InjectDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockInjection
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.NodeRegistry
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** P1 step 2 (docs/BLOCKS-LAYOUT-DESIGN.md 6s E): enable/disable and reorder, stored per node and respected by the factory. */
class ChildrenControlTest {
    private val strip = "infantry.shell.center.chat.tabs"

    /** Stands in for the daemon: keeps what it is given, so a [NodeConfigState.set] reads back at once. */
    private fun TabFactory.offline() = NodeConfigState(configTree, object : NodeConfigApi {
        val rows = LinkedHashMap<Pair<String, String>, NodeConfigEntry>()
        override suspend fun seed(entries: List<NodeConfigEntry>) = Result.success(rows.values.toList())
        override suspend fun put(entry: NodeConfigEntry): Result<List<NodeConfigEntry>> {
            if (entry.value == null) rows.remove(entry.nodeId to entry.key) else rows[entry.nodeId to entry.key] = entry
            return Result.success(rows.values.toList())
        }
    })

    /** RUNTIME-14: the core modules draw no center tab any more; two official ones (their jars on desktop) give the strip its tabs. */
    private val centerTabs = arrayOf(
        com.superiorsoftllc.infantry.plugins.official.roles.InfantryRoles,
        com.superiorsoftllc.infantry.plugins.official.pods.InfantryPods,
    )

    private fun factoryWith(vararg extra: InfantryModule): Pair<TabFactory, NodeConfigState> {
        val f = TabFactory(CoreModules.all + extra)
        // The factory's own state talks to PluginDaemon; the test gives it rows through replace().
        return f to f.config
    }

    private suspend fun NodeConfigState.write(other: NodeConfigState, block: suspend (NodeConfigState) -> Unit) {
        block(other)
        replace(other.stored.values.flatMap { it.values })
    }

    @Test
    fun `a disabled tab leaves its strip and the registry lists it disabled, and the home tab cannot be disabled`() = runBlocking {
        val (f, state) = factoryWith(*centerTabs)
        val declared = f.declaredTabs(TabContainers.CENTER).map { it.id }
        assertTrue(declared.size >= 2, declared.toString())
        val home = declared.first()
        val victim = declared[1]
        val scratch = f.offline()
        state.write(scratch) { ChildrenControl.setEnabled(it, strip, victim, enabled = false) }
        assertFalse(victim in f.tabs(TabContainers.CENTER).map { it.id })
        assertTrue(f.isDisabled(victim))
        assertEquals(home, f.home(TabContainers.CENTER))
        val host = assertNotNull(NodeRegistry.host(f, strip))
        assertEquals(false, host.children.first { it.id == victim }.enabled)
        assertEquals("Home tab", ChildrenControl.refusal(f, host, host.children.first { it.id == home }))
        assertNull(ChildrenControl.refusal(f, host, host.children.first { it.id == victim }))
        // The home tab stays even if a stored list names it.
        state.write(scratch) { ChildrenControl.setEnabled(it, strip, home, enabled = false) }
        assertEquals(home, f.tabs(TabContainers.CENTER).first().id)
        // Back on.
        state.write(scratch) { ChildrenControl.setEnabled(it, strip, victim, enabled = true) }
        assertTrue(victim in f.tabs(TabContainers.CENTER).map { it.id })
    }

    @Test
    fun `a reorder is stored as the whole order and the strip draws in it`() = runBlocking {
        val (f, state) = factoryWith(*centerTabs)
        val declared = f.declaredTabs(TabContainers.CENTER).map { it.id }
        val scratch = f.offline()
        val last = declared.last()
        state.write(scratch) { ChildrenControl.move(it, strip, declared, last, -1) }
        val expected = declared.toMutableList().apply { add(lastIndex - 1, removeAt(lastIndex)) }
        assertEquals(expected, f.tabs(TabContainers.CENTER).map { it.id })
        assertEquals(expected, ChildrenControl.order(state, strip))
        assertEquals(expected, NodeRegistry.host(f, strip)!!.children.filter { it.loaded }.map { it.id })
        assertTrue(ChildrenControl.reorderable(NodeRegistry.host(f, strip)!!))
    }

    @Test
    fun `a disabled injection is not drawn, core children are not editable, and an untouched tree is the same object`() = runBlocking {
        val manifest = ModuleManifest(name = "Acme_Inject", version = "1.0.0", inject = listOf(InjectDeclaration(into = "infantry.shell.footer", name = "hello")))
        val acme = InfantryModule(manifest, tier = Tier.CORE, inject = mapOf("hello" to BlockInjection { }))
        val (f, state) = factoryWith(acme)
        val before = f.shellRoot
        assertSame(before, f.shellRoot)
        fun Block.find(id: String): Block? = if (this.id == id) this else children.firstNotNullOfOrNull { it.find(id) }
        assertNotNull(f.shellRoot.find("Acme_Inject.hello"))
        val host = assertNotNull(NodeRegistry.host(f, "infantry.shell.footer"))
        val core = host.children.firstOrNull { it.id != "Acme_Inject.hello" }
        if (core != null) assertEquals("Core tree", ChildrenControl.refusal(f, host, core))
        assertNull(ChildrenControl.refusal(f, host, host.children.first { it.id == "Acme_Inject.hello" }))
        state.write(f.offline()) { ChildrenControl.setEnabled(it, "infantry.shell.footer", "Acme_Inject.hello", enabled = false) }
        assertNull(f.shellRoot.find("Acme_Inject.hello"))
        assertNotNull(f.resolvedShellRoot.find("Acme_Inject.hello"))
        assertEquals(false, NodeRegistry.host(f, "infantry.shell.footer")!!.children.first { it.id == "Acme_Inject.hello" }.enabled)
    }

    @Test
    fun `a community module's child is editable only where modules json grants it the node`() {
        val manifest = ModuleManifest(name = "Acme_Comm", version = "1.0.0", inject = listOf(InjectDeclaration(into = "infantry.shell.footer", name = "c")))
        val acme = InfantryModule(manifest, tier = Tier.COMMUNITY, inject = mapOf("c" to BlockInjection { }))
        val f = TabFactory(CoreModules.all + acme, allowInject = { if (it == "Acme_Comm") listOf("infantry.shell.footer") else emptyList() })
        val host = NodeRegistry.host(f, "infantry.shell.footer")!!
        assertNull(ChildrenControl.refusal(f, host, host.children.first { it.id == "Acme_Comm.c" }))
    }
}
