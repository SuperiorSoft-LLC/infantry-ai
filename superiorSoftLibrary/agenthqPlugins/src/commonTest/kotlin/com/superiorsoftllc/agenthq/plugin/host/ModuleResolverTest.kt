package com.superiorsoftllc.agenthq.plugin.host

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ModuleSetting
import com.superiorsoftllc.agenthq.plugin.api.ModulesConfig
import com.superiorsoftllc.agenthq.plugin.api.PreferenceDeclaration
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.plugin.api.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PLUGINS-1: the loading rules, one by one. */
class ModuleResolverTest {
    private fun core(name: String, vararg sequence: String, tabs: List<String> = emptyList()) =
        ModuleCandidate(name, Tier.CORE, "/p/core/$name", ModuleManifest(name, "1.0.0", author = "Infantry AI", sequence = sequence.toList(), tabs = tabs.map { TabDeclaration(it, "center") }))

    private fun community(
        name: String, vararg sequence: String, author: String? = "Acme", license: String? = "NOASSERTION",
        tabs: List<String> = emptyList(), preferences: List<PreferenceDeclaration> = emptyList(), tier: String? = null,
    ) = ModuleCandidate(
        name, Tier.COMMUNITY, "/p/community/$name",
        ModuleManifest(name, "1.0.0", author = author, license = license, tier = tier, sequence = sequence.toList(), tabs = tabs.map { TabDeclaration(it, "center") }, preferences = preferences),
    )

    private fun enabled(vararg names: String, allowOverride: List<String> = emptyList()) =
        ModulesConfig(modules = names.associateWith { ModuleSetting(enabled = true, allowOverride = allowOverride) })

    private fun Resolution.state(name: String) = status(name)?.state
    private fun Resolution.reason(name: String) = status(name)?.reason.orEmpty()

    @Test
    fun `load order follows sequence, core before community, then name`() {
        val r = ModuleResolver.resolve(
            listOf(community("Acme_B", "Infantry_Z"), core("Infantry_Z", "Infantry_A"), core("Infantry_A"), community("Acme_A")),
            enabled("Acme_A", "Acme_B"),
        )
        assertEquals(listOf("Infantry_A", "Infantry_Z", "Acme_A", "Acme_B"), r.loaded.map { it.name })
    }

    @Test
    fun `invalid json, bad name and folder mismatch are refused with the reason`() {
        val r = ModuleResolver.resolve(
            listOf(
                ModuleCandidate("Acme_Broken", Tier.COMMUNITY, "/p/community/Acme_Broken", null, "Unexpected JSON token at offset 12"),
                ModuleCandidate("hello", Tier.COMMUNITY, "/p/community/hello", ModuleManifest("hello", "1", author = "a", license = "l")),
                ModuleCandidate("Acme_Folder", Tier.COMMUNITY, "/p/community/Acme_Folder", ModuleManifest("Acme_Other", "1", author = "a", license = "l")),
                core("Infantry_A"),
            ),
        )
        assertEquals(ModuleState.REFUSED, r.state("Acme_Broken"))
        assertTrue("invalid module.json" in r.reason("Acme_Broken"))
        assertTrue("not Vendor_Module" in r.reason("hello"))
        assertTrue("must match" in r.reason("Acme_Other"))
        assertEquals(ModuleState.LOADED, r.state("Infantry_A"), "a broken module never stops the others")
        assertTrue(r.log.any { it.startsWith("[plugins] Acme_Broken (community): refused - invalid module.json") })
    }

    @Test
    fun `a missing dependency refuses the module and everything that needs it`() {
        val r = ModuleResolver.resolve(listOf(community("Acme_Needy", "Acme_Missing"), community("Acme_Top", "Acme_Needy")), enabled("Acme_Needy", "Acme_Top"))
        assertEquals("missing dependency Acme_Missing", r.reason("Acme_Needy"))
        assertEquals(ModuleState.REFUSED, r.state("Acme_Top"))
        assertTrue("Acme_Needy" in r.reason("Acme_Top"))
    }

    @Test
    fun `a cycle refuses every module on it and leaves the rest`() {
        val r = ModuleResolver.resolve(listOf(core("Infantry_A", "Infantry_B"), core("Infantry_B", "Infantry_A"), core("Infantry_C")))
        assertEquals(listOf("Infantry_C"), r.loaded.map { it.name })
        assertTrue(r.reason("Infantry_A").startsWith("dependency cycle: Infantry_A -> Infantry_B -> Infantry_A"))
        assertEquals(ModuleState.REFUSED, r.state("Infantry_B"))
    }

    @Test
    fun `a wrong api version is refused`() {
        val c = core("Infantry_A").let { it.copy(manifest = it.manifest!!.copy(apiVersion = 99)) }
        assertTrue("plugin API 99" in ModuleResolver.resolve(listOf(c)).reason("Infantry_A"))
    }

    @Test
    fun `community is off until enabled, and needs an author and a license`() {
        val r = ModuleResolver.resolve(listOf(community("Acme_Off"), community("Acme_NoAuthor", author = null), community("Acme_NoLicense", license = " ")))
        assertEquals(ModuleState.DISABLED, r.state("Acme_Off"))
        assertTrue("off until enabled" in r.reason("Acme_Off"))
        assertEquals("community module without an author", r.reason("Acme_NoAuthor"))
        assertEquals("community module without a license", r.reason("Acme_NoLicense"))
    }

    @Test
    fun `a core module can be disabled in modules json`() {
        val r = ModuleResolver.resolve(listOf(core("Infantry_A")), ModulesConfig(modules = mapOf("Infantry_A" to ModuleSetting(enabled = false))))
        assertEquals(ModuleState.DISABLED, r.state("Infantry_A"))
        assertEquals("disabled in modules.json", r.reason("Infantry_A"))
    }

    @Test
    fun `the folder decides the tier, a claimed tier is logged and ignored`() {
        val r = ModuleResolver.resolve(listOf(community("Acme_Sneaky", tier = "core")), enabled("Acme_Sneaky"))
        assertEquals(Tier.COMMUNITY, r.status("Acme_Sneaky")!!.tier)
        assertTrue(r.log.any { "claims tier 'core', ignored" in it })
    }

    @Test
    fun `a repeated name is refused and the first keeps it`() {
        val r = ModuleResolver.resolve(listOf(core("Infantry_A"), ModuleCandidate("Infantry_A", Tier.COMMUNITY, "/p/community/Infantry_A", ModuleManifest("Infantry_A", "1", author = "x", license = "y"))))
        assertEquals(ModuleState.LOADED, r.state("Infantry_A"))
        assertTrue(r.statuses.any { it.state == ModuleState.REFUSED && "already provided by core/Infantry_A" in it.reason.orEmpty() })
    }

    @Test
    fun `a core module cannot depend on a community one`() {
        val r = ModuleResolver.resolve(listOf(core("Infantry_A", "Acme_B"), community("Acme_B")), enabled("Acme_B"))
        assertEquals("a core module cannot depend on community module Acme_B", r.reason("Infantry_A"))
    }

    @Test
    fun `the demo lock keeps every community module out and core in`() {
        val r = ModuleResolver.resolve(listOf(core("Infantry_A"), community("Acme_B")), enabled("Acme_B"), demoLock = true)
        assertEquals(ModuleState.LOADED, r.state("Infantry_A"))
        assertEquals(ModuleState.LOCKED, r.state("Acme_B"))
    }

    @Test
    fun `community cannot replace a core tab unless modules json allows that override`() {
        val git = core("Infantry_Git", tabs = listOf("infantry.git"))
        val acme = community("Acme_Hello", tabs = listOf("acme.git"), preferences = listOf(PreferenceDeclaration("infantry.git", "acme.git")))
        val refused = ModuleResolver.resolve(listOf(git, acme), enabled("Acme_Hello"))
        assertEquals(emptyList(), refused.preferences)
        assertTrue(refused.log.any { "cannot replace core infantry.git" in it })
        assertEquals(ModuleState.LOADED, refused.state("Acme_Hello"), "the module loads; only its override is refused")

        val allowed = ModuleResolver.resolve(listOf(git, acme), enabled("Acme_Hello", allowOverride = listOf("infantry.git")))
        assertEquals(listOf(AcceptedPreference("Acme_Hello", "infantry.git", "acme.git")), allowed.preferences)
    }

    @Test
    fun `community may replace community and the last loaded wins`() {
        val a = community("Acme_A", tabs = listOf("acme.a"))
        val b = community("Acme_B", tabs = listOf("acme.b"), preferences = listOf(PreferenceDeclaration("acme.a", "acme.b")))
        val c = community("Acme_C", "Acme_B", tabs = listOf("acme.c"), preferences = listOf(PreferenceDeclaration("acme.a", "acme.c")))
        val r = ModuleResolver.resolve(listOf(a, b, c), enabled("Acme_A", "Acme_B", "Acme_C"))
        assertEquals(listOf("acme.b", "acme.c"), r.preferences.map { it.with })
        assertTrue(r.log.any { "Acme_C: replaces acme.a, overriding Acme_B" in it })
    }

    @Test
    fun `a preference to a tab the module does not declare is refused`() {
        val r = ModuleResolver.resolve(
            listOf(core("Infantry_Git", tabs = listOf("infantry.git")), core("Infantry_X", preferences = listOf(PreferenceDeclaration("infantry.git", "nope")))),
        )
        assertNull(r.preferences.firstOrNull())
        assertTrue(r.log.any { "nope is not a tab it declares" in it })
    }

    private fun core(name: String, preferences: List<PreferenceDeclaration>) =
        ModuleCandidate(name, Tier.CORE, "/p/core/$name", ModuleManifest(name, "1.0.0", preferences = preferences))
}
