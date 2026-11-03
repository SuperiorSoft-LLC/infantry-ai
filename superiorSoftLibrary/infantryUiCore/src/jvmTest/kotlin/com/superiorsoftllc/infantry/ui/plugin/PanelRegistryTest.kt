package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.PanelDeclaration
import com.superiorsoftllc.infantry.plugin.api.TabDeclaration
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.plugins.official.InfantryAgents
import com.superiorsoftllc.infantry.plugins.official.git.InfantryGit
import com.superiorsoftllc.infantry.plugins.official.attention.InfantryAttention
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PLUGINS-3: the side panels' sections come from modules, bound to module.json `panels` like tabs are to `tabs`. */
class PanelRegistryTest {
    private fun section(id: String, panel: String = Panels.LEFT, order: Int = 200, fill: Boolean = false) =
        PanelProvider(id = id, panel = panel, order = order, testTag = "tag-$id", fill = fill) {}

    private fun acme(
        panels: List<PanelProvider>,
        declared: List<PanelDeclaration> = panels.map { PanelDeclaration(it.id, it.panel) },
        tabs: List<TabProvider> = emptyList(),
    ) = InfantryModule(
        ModuleManifest(
            "Acme_Hello", "1.0.0", author = "Acme", license = "NOASSERTION", panels = declared,
            tabs = tabs.map { TabDeclaration(it.id, it.container) },
        ),
        tabs, tier = Tier.COMMUNITY, panels = panels,
    )

    @Test
    fun `the core left panel is the agents section from Infantry_Agents and the right panel has no section`() {
        val f = TabFactory(CoreModules.all + InfantryAgents) // RUNTIME-4: Infantry_Agents is official (its jar on desktop)
        assertEquals(emptyList(), f.problems)
        assertEquals(listOf("infantry.agents"), f.panels(Panels.LEFT).map { it.id })
        assertEquals("Infantry_Agents", f.panels(Panels.LEFT).single().module)
        assertTrue(f.panels(Panels.LEFT).single().fill, "the agents section takes the panel's free height")
        assertEquals(emptyList(), f.panels(Panels.RIGHT))
    }

    @Test
    fun `a module's section lands in its panel by order and names the module`() {
        val f = TabFactory(CoreModules.all + InfantryAgents + acme(listOf(section("acme.top", order = 10), section("acme.below", order = 900))))
        assertEquals(listOf("acme.top", "infantry.agents", "acme.below"), f.panels(Panels.LEFT).map { it.id })
        assertEquals("Acme_Hello", f.panels(Panels.LEFT).first().module)
        assertEquals(Tier.COMMUNITY, f.panelModuleOf("acme.top")?.tier)
    }

    @Test
    fun `an undeclared section, one on another panel or on an unknown panel is refused`() {
        val f = TabFactory(
            CoreModules.all + InfantryAgents + acme(
                listOf(section("acme.sneak"), section("acme.moved", Panels.RIGHT), section("acme.nowhere", "bottom")),
                declared = listOf(PanelDeclaration("acme.moved", Panels.LEFT), PanelDeclaration("acme.nowhere", "bottom")),
            ),
        )
        assertEquals(listOf("infantry.agents"), f.panels(Panels.LEFT).map { it.id })
        assertEquals(emptyList(), f.panels(Panels.RIGHT))
        assertEquals(emptyList(), f.panels("bottom"))
        assertEquals(3, f.problems.size, f.problems.toString())
        assertTrue(f.problems.any { "acme.sneak" in it && "not declared" in it })
        assertTrue(f.problems.any { "acme.moved" in it && "declared in left" in it })
        assertTrue(f.problems.any { "acme.nowhere" in it && "unknown panel" in it })
    }

    @Test
    fun `a second section with a taken id is skipped and reported`() {
        val f = TabFactory(CoreModules.all + InfantryAgents + acme(listOf(section("infantry.agents"))))
        assertEquals("Infantry_Agents", f.panels(Panels.LEFT).single().module)
        assertTrue(f.problems.single().let { "Acme_Hello" in it && "infantry.agents" in it && "already" in it })
    }

    @Test
    fun `a module's right panel tab joins the right strip by order like a main tab`() {
        val icon = InfantryGit.tabs.single().icon
        val side = TabProvider(id = "acme.side", container = TabContainers.RIGHT, order = 35, title = { "Hello" }, icon = icon, testTag = "right-tab-ACME") {}
        val f = TabFactory(CoreModules.all + InfantryAttention + acme(emptyList(), tabs = listOf(side))) // RUNTIME-13: Attention is official
        val right = f.tabs(TabContainers.RIGHT).map { it.id }
        assertEquals("acme.side", right[right.indexOf("infantry.attention") + 1], "order 35 sits between Attention (30) and Usage (40)")
        assertEquals(Tier.COMMUNITY, f.moduleOf("acme.side")?.tier, "so the right panel draws the Community badge on it")
    }

    @Test
    fun `module json panels parse and default to no panels`() {
        val json = Json { ignoreUnknownKeys = true }
        val m = json.decodeFromString<ModuleManifest>("""{"name":"A_B","version":"1.0.0","panels":[{"id":"a.side","panel":"right"}]}""")
        assertEquals(listOf(PanelDeclaration("a.side", "right")), m.panels)
        assertEquals(emptyList(), json.decodeFromString<ModuleManifest>("""{"name":"A_B","version":"1.0.0"}""").panels)
    }
}
