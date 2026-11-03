package com.superiorsoftllc.infantry.ui.plugin.config

import androidx.compose.runtime.Composable
import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.ConfigEntry
import com.superiorsoftllc.infantry.ui.ConfigTab
import com.superiorsoftllc.infantry.ui.configSectionNode
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.NoPluginServices
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.registerModule
import com.superiorsoftllc.infantry.ui.visibleConfigTabs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CONFIG-5 (docs/CONFIG-MANIFEST-DESIGN.md section 4): the plan the Settings dialog is generated from. */
class ConfigPlanTest {
    private object NoDaemon : NodeConfigApi {
        override suspend fun seed(entries: List<NodeConfigEntry>) = Result.success(emptyList<NodeConfigEntry>())
        override suspend fun put(entry: NodeConfigEntry) = Result.success(emptyList<NodeConfigEntry>())
    }

    private fun form(tab: String, group: String, order: Int = 0, keywords: List<String> = emptyList(), vararg fields: ConfigFieldDeclaration) =
        ModuleDeclaration("Acme", ConfigDeclaration("acme.$group", group, fields.toList().ifEmpty { listOf(ConfigFieldDeclaration("k", "text")) }, tab = tab, order = order, keywords = keywords))

    @Test fun `tabs are core's default order first, then any other tab by first declaration`() {
        val plan = ConfigPlan.of(
            listOf(form("Zeta", "z"), form("Acme", "a"), form("General", "g"), form("Appearance", "p")),
            present = emptySet(), blockIds = emptySet(),
        )
        assertEquals(listOf("Appearance", "General", "Zeta", "Acme"), plan.tabs)
    }

    @Test fun `a tab with nothing to show is hidden, and a block card needs its fill`() {
        val block = ModuleDeclaration("Acme", ConfigDeclaration("acme.panel", "Panel", tab = "Acme", block = "acme.panel"))
        assertTrue(ConfigPlan.of(listOf(block), emptySet(), emptySet()).tabs.isEmpty())
        assertEquals(listOf("Acme"), ConfigPlan.of(listOf(block), emptySet(), setOf("acme.panel")).tabs)
        // A trust section the host lacks (no pair callback) is no card, so its Devices tab is hidden.
        val pair = ModuleDeclaration("core", ConfigDeclaration(configSectionNode(ConfigEntry.PAIR), "Pair", tab = "Devices", block = configSectionNode(ConfigEntry.PAIR), locked = true))
        assertTrue(ConfigPlan.of(listOf(pair), emptySet(), emptySet()).tabs.isEmpty())
        assertEquals(listOf("Devices"), ConfigPlan.of(listOf(pair), setOf(ConfigEntry.PAIR), emptySet()).tabs)
    }

    @Test fun `a block card is the hand-written section, so that section is not drawn twice`() {
        val about = ModuleDeclaration("core", ConfigDeclaration(configSectionNode(ConfigEntry.ABOUT), "About", tab = "About", block = configSectionNode(ConfigEntry.ABOUT), locked = true))
        val plan = ConfigPlan.of(listOf(about), setOf(ConfigEntry.ABOUT, ConfigEntry.UPDATES), emptySet())
        assertEquals(listOf(ConfigEntry.ABOUT), plan.cards.mapNotNull { it.entry })
        assertEquals(listOf(ConfigEntry.UPDATES), plan.uncovered)
        assertEquals("About", plan.tabOf(ConfigEntry.UPDATES))
    }

    @Test fun `a moved section shows only through its owner's declaration`() {
        val owned = ConfigPlan.MOVED.filter { it != ConfigEntry.THEME && it != ConfigEntry.BLUR && it != ConfigEntry.TAB_GROUPS }
        val decls = owned.map { e -> ModuleDeclaration("Owner", ConfigDeclaration(configSectionNode(e), e.title, tab = e.tab.label, block = configSectionNode(e))) }
        val present = ConfigPlan.MOVED.toSet()
        // With every owner there, nothing is left over; remove one owner and only its section goes.
        assertTrue(ConfigPlan.of(decls, present, emptySet()).uncovered.none { it in owned })
        val without = ConfigPlan.of(decls.filterNot { it.declaration.block == configSectionNode(ConfigEntry.MARKDOWN_ENGINE) }, present, emptySet())
        assertEquals(listOf(ConfigEntry.MARKDOWN_ENGINE), owned.filter { e -> without.tabOf(e) == null || without.cards.none { it.entry == e } })
        assertTrue(ConfigPlan.MOVED.all { it !in without.uncovered })
    }

    @Test fun `core declares the two shell-owned sections`() {
        val core = ConfigTree.parse(CoreConfig.JSON, mutableListOf(), "core")
        for (e in CoreConfig.SHELL_OWNED) assertTrue(core.any { it.block == configSectionNode(e) }, e.name)
    }

    @Test fun `search matches title, label, description and keywords`() {
        val field = ConfigFieldDeclaration("k", "text", label = "Frobnicate level", description = "How hard to wibble")
        val plan = ConfigPlan.of(listOf(form("Acme", "Widgets", keywords = listOf("sprocket"), fields = arrayOf(field))), emptySet(), emptySet())
        for (q in listOf("widgets", "sprocket", "frobnicate", "wibble", "acme", "wibble level".replace("level", "hard"))) assertEquals(1, plan.search(q).size, q)
        assertTrue(plan.search("nothing like it").isEmpty())
        assertTrue(plan.search("  ").isEmpty())
    }

    @Test fun `search also finds a hand-written section no card stands for`() {
        val plan = ConfigPlan.of(emptyList(), setOf(ConfigEntry.LOGS), emptySet())
        assertEquals(listOf(ConfigEntry.LOGS), plan.search("diagnostics").map { it.entry })
    }

    @Test fun `the ten trust sections (POLICY-1 added policy) are declared in core's config, locked, as block cards`() {
        val problems = mutableListOf<String>()
        val decls = ConfigTree.parse(CoreConfig.JSON, problems, "core")
        assertEquals(emptyList(), problems)
        val trust = decls.filter { it.block != null && it.locked }
        assertEquals(10, trust.size)
        assertTrue(trust.all { it.locked && it.tab != null && it.fields.isEmpty() })
        for (e in CoreConfig.TRUST) {
            val d = trust.single { it.block == configSectionNode(e) }
            assertEquals(e.tab.label, d.tab); assertEquals(e.title, d.title); assertEquals(e.applies, d.applies); assertEquals(e.keywords, d.keywords)
        }
        // Without a snapshot nothing changes: the hand-written tabs are the ones the host offers.
        val present = ConfigEntry.entries.toSet()
        assertEquals(ConfigTab.entries, visibleConfigTabs(present))
        // And generated from core's declarations alone, the trust tabs come out in today's order.
        val factory = TabFactory(CoreModules.all, configApi = NoDaemon)
        val plan = ConfigPlan.of(factory.configTree.declarations, present, emptySet())
        // CONFIG-6: a moved section is drawn only by its owner, so core alone has no Appearance tab and no Agents section.
        assertEquals(ConfigTab.entries.map { it.label }.filter { it != "Appearance" }, plan.tabs)
        assertEquals(CoreConfig.SHELL_OWNED + CoreConfig.TRUST, plan.cards.mapNotNull { it.entry })
    }

    @Test fun `only core may declare a locked section, and a block is placed once`() {
        val manifest = ModuleManifest(
            "Acme_Thief", "1.0.0", author = "Acme", license = "NOASSERTION",
            config = listOf(
                ConfigDeclaration("acme.sneaky", "Sneaky", tab = "Acme", block = "acme.sneaky", locked = true),
                ConfigDeclaration(configSectionNode(ConfigEntry.SECRETS), "Secrets", tab = "Secrets", block = configSectionNode(ConfigEntry.SECRETS)),
            ),
        )
        val factory = TabFactory(CoreModules.all + InfantryModule(manifest, tier = Tier.COMMUNITY), configApi = NoDaemon)
        val problems = factory.configTree.problems
        assertTrue(problems.any { "locked" in it }, problems.toString())
        assertTrue(problems.any { "already placed" in it || "neither owns" in it }, problems.toString())
        assertEquals(1, factory.configTree.declarations.count { it.declaration.block == configSectionNode(ConfigEntry.SECRETS) })
    }

    private class Stars : FieldType {
        override fun validate(field: ConfigFieldDeclaration, value: JsonElement?): String? =
            if (((value as? JsonPrimitive)?.content?.toIntOrNull() ?: 0) in 1..5) null else "1 to 5 stars"
        @Composable
        override fun Editor(field: ConfigFieldDeclaration, value: JsonElement?, enabled: Boolean, onChange: (JsonElement?) -> Unit) {}
    }

    private fun starModule(declared: List<String>, id: String = "acme.stars"): InfantryModule {
        val manifest = ModuleManifest(
            "Acme_Stars", "1.0.0", author = "Acme", license = "NOASSERTION", fieldTypes = declared,
            config = listOf(ConfigDeclaration("acme.rating", "Rating", listOf(ConfigFieldDeclaration("rating", "acme.stars")), tab = "Acme")),
        )
        return registerModule(manifest, Tier.CORE, NoPluginServices, object : UiPlugin {
            override fun register(registrar: UiRegistrar) = registrar.fieldType(id, Stars())
        })
    }

    @Test fun `a registered field type is known to the tree and validates through its own rule`() {
        val factory = TabFactory(CoreModules.all + starModule(listOf("acme.stars")), configApi = NoDaemon)
        assertEquals(setOf("acme.stars"), factory.fieldTypes.ids)
        assertNotNull(factory.fieldTypes["acme.stars"])
        assertEquals(emptyList(), factory.problems.filter { "stars" in it || "unknown type" in it })
        val field = ConfigFieldDeclaration("rating", "acme.stars")
        assertNull(factory.fieldTypes.validate(field, JsonPrimitive(3)))
        assertEquals("1 to 5 stars", factory.fieldTypes.validate(field, JsonPrimitive(9)))
    }

    @Test fun `a field type the module json does not declare, or that is built in, is refused`() {
        val undeclared = TabFactory(CoreModules.all + starModule(emptyList()), configApi = NoDaemon)
        assertTrue(undeclared.fieldTypes.ids.isEmpty())
        assertTrue(undeclared.problems.any { "not declared" in it })
        val builtIn = TabFactory(CoreModules.all + starModule(listOf("text"), id = "text"), configApi = NoDaemon)
        assertTrue(builtIn.fieldTypes.ids.isEmpty())
        assertTrue(builtIn.problems.any { "built in" in it })
    }

    @Test fun `the built-in types validate their values`() {
        fun f(type: String, min: Double? = null, max: Double? = null, options: List<String> = emptyList()) = ConfigFieldDeclaration("k", type, min = min, max = max, options = options)
        val v = FieldTypes()
        assertNull(v.validate(f("number", 1.0, 5.0), JsonPrimitive(3)))
        assertEquals("At most 5.0", v.validate(f("number", 1.0, 5.0), JsonPrimitive(9)))
        assertEquals("At least 1.0", v.validate(f("number", 1.0, 5.0), JsonPrimitive(0)))
        assertEquals("Not a number", v.validate(f("number"), JsonPrimitive("x")))
        assertNull(v.validate(f("color"), JsonPrimitive("#FF8800")))
        assertEquals("Use #RRGGBB or #AARRGGBB", v.validate(f("color"), JsonPrimitive("red")))
        assertNull(v.validate(f("select", options = listOf("A", "B")), JsonPrimitive("A")))
        assertEquals("Not one of: A, B", v.validate(f("radio", options = listOf("A", "B")), JsonPrimitive("C")))
        assertNull(v.validate(f("multiSelect", options = listOf("A", "B")), JsonArray(listOf(JsonPrimitive("A")))))
        assertEquals("Not all among: A, B", v.validate(f("multiSelect", options = listOf("A", "B")), JsonArray(listOf(JsonPrimitive("C")))))
        assertNull(v.validate(f("toggle"), JsonPrimitive(true)))
        assertEquals("Not on or off", v.validate(f("toggle"), JsonPrimitive("maybe")))
    }

    @Test fun `a plugin written against the old registrar, with no fieldType call, still loads`() {
        val manifest = ModuleManifest("Old_Plugin", "1.0.0", author = "Old", license = "NOASSERTION")
        // An implementation of the interface that predates fieldType: the default method is a no-op.
        val registrar = object : UiRegistrar {
            override val services = NoPluginServices
            override fun tab(provider: com.superiorsoftllc.infantry.ui.plugin.TabProvider) {}
            override fun interceptor(name: String, interceptor: com.superiorsoftllc.infantry.ui.plugin.TabInterceptor) {}
            override fun block(name: String, block: com.superiorsoftllc.infantry.ui.plugin.SlotBlock) {}
            override fun observer(name: String, observer: (com.superiorsoftllc.infantry.plugin.api.PluginEvent) -> Unit) {}
        }
        registrar.fieldType("x.y", Stars())
        val module = registerModule(manifest, Tier.CORE, NoPluginServices, object : UiPlugin { override fun register(registrar: UiRegistrar) {} })
        val factory = TabFactory(CoreModules.all + module, configApi = NoDaemon)
        assertTrue(factory.fieldTypes.ids.isEmpty())
        assertEquals(emptyList(), factory.configTree.problems)
    }
}
