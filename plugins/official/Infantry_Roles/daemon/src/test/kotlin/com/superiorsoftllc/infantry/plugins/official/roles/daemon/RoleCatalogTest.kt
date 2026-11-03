package com.superiorsoftllc.infantry.plugins.official.roles.daemon

import com.superiorsoftllc.infantry.protocol.RoleIds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The assembled catalog: precedence, the canonical contract holding under an empty manifest, fallback. */
class RoleCatalogTest {

    @Test
    fun bundledManifestShipsEveryCanonicalRoleWithAPersona() {
        val roles = RoleBundle.load(RoleBundle.classpathReader())
        assertEquals(RoleIds.BUILT_IN, roles.map { it.id }, "the bundled manifest must define exactly the contract ids, in order")
        roles.forEach { role ->
            assertTrue(role.persona.isNotBlank(), "role '${role.id}' has no persona")
            assertTrue(role.name.isNotBlank(), "role '${role.id}' has no name")
            assertTrue(role.color.matches(Regex("#[0-9a-f]{6}")), "role '${role.id}' colour '${role.color}' is not #rrggbb")
            assertEquals(RoleSource.BUILT_IN, role.source)
        }
    }

    @Test
    fun bundledPersonaWithCrlfReachesTheCatalogAsLf() {
        val files = mapOf(
            RoleBundle.MANIFEST_PATH to """{"roles":[{"id":"backend","persona":"backend.md"}]}""",
            "/roles/backend.md" to "You are backend.\r\nLine two.\rLine three.\r\n",
        )
        val persona = RoleBundle.load({ files[it] }).single().persona
        assertTrue('\r' !in persona, "persona still carries a CR at index ${persona.indexOf('\r')}")
        assertEquals("You are backend.\nLine two.\nLine three.\n", persona)
    }

    @Test
    fun emptyManifestStillYieldsTheCanonicalContract() {
        val catalog = RoleCatalog(builtin = emptyList())
        assertEquals(RoleIds.BUILT_IN, catalog.ids())
        val backend = assertNotNull(catalog.role("backend"))
        assertEquals(RoleSource.BUILT_IN, backend.source)
        assertEquals("Backend", backend.name)
        assertTrue(backend.color.startsWith("#"))
        assertNull(catalog.persona("backend"), "an id with no manifest entry has no persona")
    }

    @Test
    fun manifestDefinesNameColourAndPersona() {
        val manifest = listOf(Role("backend", "Backend Hero", "#123456", "You are the backend hero.", RoleSource.BUILT_IN))
        val catalog = RoleCatalog(builtin = manifest)
        val backend = assertNotNull(catalog.role("backend"))
        assertEquals("Backend Hero", backend.name)
        assertEquals("#123456", backend.color)
        assertEquals("You are the backend hero.", catalog.persona("backend"))
    }

    @Test
    fun customRoleOverridesBuiltInForThatIdOnly() {
        val builtin = listOf(Role("backend", "Backend", "#111111", "bundled", RoleSource.BUILT_IN))
        val custom = listOf(Role("backend", "Ops Backend", "#222222", "custom", RoleSource.CUSTOM))
        val catalog = RoleCatalog(builtin, custom)
        val backend = assertNotNull(catalog.role("backend"))
        assertEquals("Ops Backend", backend.name)
        assertEquals(RoleSource.CUSTOM, backend.source)
        assertEquals("custom", catalog.persona("backend"))
        // The neighbour is untouched.
        assertEquals("Frontend", assertNotNull(catalog.role("frontend")).name)
    }

    @Test
    fun extraIdsAppearAfterTheCanonicalSetSorted() {
        val catalog = RoleCatalog(
            builtin = listOf(Role("zeta", "Zeta", "#333333", "", RoleSource.BUILT_IN)),
            custom = listOf(Role("alpha", "Alpha", "#444444", "", RoleSource.CUSTOM)),
        )
        assertEquals(RoleIds.BUILT_IN + listOf("alpha", "zeta"), catalog.ids())
    }

    @Test
    fun renderedAlwaysPaintsEvenForAnUnknownId() {
        val catalog = RoleCatalog()
        val unknown = catalog.rendered("brand-new-role")
        assertEquals("brand-new-role", unknown.id)
        assertEquals("Brand New Role", unknown.name)
        assertEquals(RoleSource.GENERATED, unknown.source)
        assertTrue(unknown.color.startsWith("#"))
        assertNull(catalog.role("brand-new-role"), "rendered() must not add the id to the catalog")
    }

    @Test
    fun prettifyHandlesSeparators() {
        assertEquals("Chief Of Staff", RoleCatalog.prettify("chief-of-staff"))
        assertEquals("Snake Case", RoleCatalog.prettify("snake_case"))
        assertEquals("Bot", RoleCatalog.prettify("bot"))
    }
}
