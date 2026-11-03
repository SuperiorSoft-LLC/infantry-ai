package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.DependencyDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ModuleSetting
import com.superiorsoftllc.infantry.plugin.api.ModulesConfig
import com.superiorsoftllc.infantry.plugin.api.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** PLUGINS-2: dependencies at load, at enable time and at disable time. */
class DependencyGraphTest {
    /** `needs` entries are `Id` or `Id@range`. */
    private fun module(name: String, version: String = "1.0.0", vararg needs: String, tier: Tier = Tier.COMMUNITY, sequence: List<String> = emptyList()) =
        ModuleCandidate(
            name, tier, "/p/${tier.name.lowercase()}/$name",
            ModuleManifest(
                name, version, author = "Acme", license = "NOASSERTION", sequence = sequence,
                dependencies = needs.map { n -> n.split("@").let { DependencyDeclaration(it[0], it.getOrElse(1) { "*" }) } },
            ),
        )

    private fun core(name: String, vararg needs: String) = module(name, "1.0.0", *needs, tier = Tier.CORE)

    private fun config(vararg on: String, off: List<String> = emptyList()) =
        ModulesConfig(modules = on.associateWith { ModuleSetting(enabled = true) } + off.associateWith { ModuleSetting(enabled = false) })

    private fun refused(c: ModuleChange): ModuleChange.Refused = assertIs<ModuleChange.Refused>(c, c.message)
    private fun applied(c: ModuleChange): ModuleChange.Applied = assertIs<ModuleChange.Applied>(c, c.message)

    @Test
    fun `missing dependency is refused at load and at enable`() {
        val mods = listOf(module("Acme_Needy", "1.0.0", "Acme_Missing@>=1.2 <2"))
        val r = ModuleResolver.resolve(mods, config("Acme_Needy"))
        assertEquals("missing dependency Acme_Missing >=1.2 <2", r.status("Acme_Needy")?.reason)
        val c = refused(DependencyGraph.of(mods, ModulesConfig()).enable("Acme_Needy", ModulesConfig()))
        assertEquals("Cannot enable Acme_Needy: Acme_Needy needs Acme_Missing >=1.2 <2, which is not installed.", c.message)
    }

    @Test
    fun `version out of range is refused at load and at enable`() {
        val mods = listOf(module("Acme_Base", "1.0.0"), module("Acme_App", "1.0.0", "Acme_Base@>=1.2 <2"))
        val cfg = config("Acme_Base", "Acme_App")
        val r = ModuleResolver.resolve(mods, cfg)
        assertEquals(ModuleState.LOADED, r.status("Acme_Base")?.state)
        assertEquals("Acme_App needs Acme_Base >=1.2 <2, but Acme_Base is 1.0.0", r.status("Acme_App")?.reason)
        val c = refused(DependencyGraph.of(mods, config("Acme_Base")).enable("Acme_App", config("Acme_Base")))
        assertEquals("Cannot enable Acme_App: Acme_App needs Acme_Base >=1.2 <2, but Acme_Base is 1.0.0.", c.message)
        // In range: loads.
        val ok = ModuleResolver.resolve(listOf(module("Acme_Base", "1.4.2"), mods[1]), cfg)
        assertEquals(listOf("Acme_Base", "Acme_App"), ok.loaded.map { it.name })
    }

    @Test
    fun `a disabled dependency refuses enabling and names what to enable first`() {
        val mods = listOf(module("Acme_Base"), module("Acme_App", "1.0.0", "Acme_Base"))
        val cfg = config(off = listOf("Acme_Base"))
        val c = refused(DependencyGraph.of(mods, cfg).enable("Acme_App", cfg))
        assertEquals(listOf("Acme_Base"), c.mustEnableFirst)
        assertEquals("Cannot enable Acme_App: it needs modules that are not enabled. Enable these first, in this order: Acme_Base (disabled).", c.message)
        // Hand-edited modules.json that enables it anyway: refused at load.
        assertEquals("dependency Acme_Base is disabled", ModuleResolver.resolve(mods, config("Acme_App", off = listOf("Acme_Base"))).status("Acme_App")?.reason)
        // Once the dependency is on, enabling works and only flips the one module.
        val on = config("Acme_Base")
        val done = applied(DependencyGraph.of(mods, on).enable("Acme_App", on))
        assertEquals(listOf("Acme_App"), done.changed)
        assertEquals(true, done.config.modules["Acme_App"]?.enabled)
        assertEquals("Acme_App enabled. Restart to load it.", done.message)
    }

    @Test
    fun `transitive resolution gives one deterministic enable order`() {
        // Acme_Top needs Acme_Mid and Acme_Alt; both need Acme_Base; nothing is enabled yet.
        val mods = listOf(
            module("Acme_Top", "1.0.0", "Acme_Mid", "Acme_Alt"),
            module("Acme_Mid", "1.0.0", "Acme_Base"),
            module("Acme_Alt", "1.0.0", "Acme_Base"),
            module("Acme_Base"),
        )
        val g = DependencyGraph.of(mods, ModulesConfig())
        val c = refused(g.enable("Acme_Top", ModulesConfig()))
        assertEquals(listOf("Acme_Base", "Acme_Alt", "Acme_Mid"), c.mustEnableFirst)
        assertTrue("in this order: Acme_Base (off until enabled), Acme_Alt (off until enabled), Acme_Mid (off until enabled)" in c.message, c.message)
        assertEquals(c.mustEnableFirst, DependencyGraph.of(mods.reversed(), ModulesConfig()).let { refused(it.enable("Acme_Top", ModulesConfig())).mustEnableFirst }, "input order does not matter")
        // Following that order succeeds step by step, and the loader then loads in a dependency-first order.
        var cfg = ModulesConfig()
        for (n in c.mustEnableFirst + "Acme_Top") cfg = applied(DependencyGraph.of(mods, cfg).enable(n, cfg)).config
        assertEquals(listOf("Acme_Base", "Acme_Alt", "Acme_Mid", "Acme_Top"), ModuleResolver.resolve(mods, cfg).loaded.map { it.name })
    }

    @Test
    fun `disabling a module that enabled modules need is refused, listing them`() {
        val mods = listOf(module("Acme_Base"), module("Acme_App", "1.0.0", "Acme_Base"), module("Acme_Off", "1.0.0", "Acme_Base"))
        val cfg = config("Acme_Base", "Acme_App", off = listOf("Acme_Off"))
        val g = DependencyGraph.of(mods, cfg)
        assertEquals(listOf("Acme_App"), g.requiredBy("Acme_Base"), "a disabled dependent does not count")
        val c = refused(g.disable("Acme_Base", cfg))
        assertEquals(listOf("Acme_App"), c.dependents)
        assertEquals("Cannot disable Acme_Base: enabled modules depend on it: Acme_App. Disable them first, or choose cascade to disable them too.", c.message)
        assertEquals("Cannot remove Acme_Base: enabled modules depend on it: Acme_App. Disable them first, or choose cascade to disable them too.", refused(g.remove("Acme_Base", cfg)).message)
        // A module nothing needs disables directly.
        assertEquals(listOf("Acme_App"), applied(g.disable("Acme_App", cfg)).changed)
    }

    @Test
    fun `cascade disables the dependents too, furthest first, and logs each`() {
        val mods = listOf(module("Acme_Base"), module("Acme_Mid", "1.0.0", "Acme_Base"), module("Acme_Top", "1.0.0", "Acme_Mid"), module("Acme_Side", "1.0.0", "Acme_Base"))
        val cfg = config("Acme_Base", "Acme_Mid", "Acme_Top", "Acme_Side")
        val c = applied(DependencyGraph.of(mods, cfg).disable("Acme_Base", cfg, cascade = true))
        assertEquals(listOf("Acme_Top", "Acme_Mid", "Acme_Side", "Acme_Base"), c.changed)
        assertTrue(c.changed.all { c.config.modules[it]?.enabled == false })
        assertEquals(3, c.log.size)
        assertTrue(c.log.all { it.startsWith("[plugins] cascade: disabled ") && "depends on Acme_Base" in it }, "${c.log}")
        assertEquals("Acme_Base disabled; cascade also disabled Acme_Top, Acme_Mid, Acme_Side. Restart to apply.", c.message)
        // Remove with cascade drops the module's entry and disables the rest.
        val rm = applied(DependencyGraph.of(mods, cfg).remove("Acme_Base", cfg, cascade = true))
        assertTrue("Acme_Base" !in rm.config.modules)
        assertEquals(false, rm.config.modules["Acme_Mid"]?.enabled)
    }

    @Test
    fun `a cycle is refused at load and at enable`() {
        val mods = listOf(module("Acme_A", "1.0.0", "Acme_B"), module("Acme_B", "1.0.0", "Acme_A"), module("Acme_C"))
        val cfg = config("Acme_A", "Acme_B", "Acme_C")
        val r = ModuleResolver.resolve(mods, cfg)
        assertEquals(listOf("Acme_C"), r.loaded.map { it.name })
        assertTrue(r.status("Acme_A")!!.reason!!.startsWith("dependency cycle: Acme_A -> Acme_B -> Acme_A"))
        val c = refused(DependencyGraph.of(mods, config("Acme_B")).enable("Acme_A", config("Acme_B")))
        assertEquals("Cannot enable Acme_A: dependency cycle: Acme_A -> Acme_B -> Acme_A.", c.message)
    }

    @Test
    fun `a community dependency under a core module is refused at load and at enable`() {
        val mods = listOf(core("Infantry_A", "Acme_B"), module("Acme_B"))
        val cfg = config("Acme_B")
        assertEquals("core module Infantry_A cannot depend on community module Acme_B", ModuleResolver.resolve(mods, cfg).status("Infantry_A")?.reason)
        val off = config("Acme_B", off = listOf("Infantry_A"))
        val c = refused(DependencyGraph.of(mods, off).enable("Infantry_A", off))
        assertEquals("Cannot enable Infantry_A: core module Infantry_A cannot depend on community module Acme_B.", c.message)
        // Core on core is fine, and community on core too.
        val fine = listOf(core("Infantry_A", "Infantry_B"), core("Infantry_B"), module("Acme_C", "1.0.0", "Infantry_A"))
        assertEquals(listOf("Infantry_B", "Infantry_A", "Acme_C"), ModuleResolver.resolve(fine, config("Acme_C")).loaded.map { it.name })
    }

    @Test
    fun `sequence only orders, it does not require`() {
        val mods = listOf(module("Acme_A", sequence = listOf("Acme_Z", "Acme_Absent")), module("Acme_Z"))
        val r = ModuleResolver.resolve(mods, config("Acme_A", "Acme_Z"))
        assertEquals(listOf("Acme_Z", "Acme_A"), r.loaded.map { it.name })
        assertEquals(listOf("Acme_A"), ModuleResolver.resolve(mods, config("Acme_A")).loaded.map { it.name }, "an absent or off sequence name is ignored")
    }

    @Test
    fun `needs and required-by read a finished load`() {
        val mods = listOf(core("Infantry_Cli"), module("Acme_Hello", "1.0.0", "Infantry_Cli@>=1.0 <2"))
        val g = DependencyGraph.of(ModuleResolver.resolve(mods, config("Acme_Hello")).statuses)
        assertEquals(listOf("Infantry_Cli >=1.0 <2"), g.needs("Acme_Hello").map { DependencyGraph.label(it) })
        assertEquals(listOf("Acme_Hello"), g.requiredBy("Infantry_Cli"))
        assertEquals(emptyList(), DependencyGraph.of(ModuleResolver.resolve(mods).statuses).requiredBy("Infantry_Cli"), "off modules do not require")
    }

    @Test
    fun `an invalid range is refused with the range quoted`() {
        val mods = listOf(module("Acme_Base"), module("Acme_App", "1.0.0", "Acme_Base@>=one"))
        assertEquals("Acme_App declares an invalid version range '>=one' for Acme_Base", ModuleResolver.resolve(mods, config("Acme_Base", "Acme_App")).status("Acme_App")?.reason)
    }
}
