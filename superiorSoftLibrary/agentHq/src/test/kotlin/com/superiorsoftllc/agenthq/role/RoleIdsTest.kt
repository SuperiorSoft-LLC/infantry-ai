package com.superiorsoftllc.agenthq.role

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The canonical id set is a contract: assert it verbatim, so an "improvement" fails loudly. */
class RoleIdsTest {

    @Test
    fun canonicalIdsAreThePublishedSetInOrder() {
        assertEquals(
            listOf(
                "architect", "backend", "frontend", "fullstack", "qa", "devops", "security",
                "mobile", "pm", "marketing", "version-control", "seo", "localization", "ideation", "automation",
                "assistant",
            ),
            RoleIds.CANONICAL,
        )
    }

    @Test
    fun builtInAddsTheCustomMarker() {
        assertEquals(17, RoleIds.BUILT_IN.size)
        assertEquals(RoleIds.CANONICAL + "custom", RoleIds.BUILT_IN)
        assertTrue(RoleIds.isCanonical("qa"))
        assertFalse(RoleIds.isCanonical("custom"))
        assertFalse(RoleIds.isCanonical("backend-2"))
    }

    /** ORIGIN-SCRUB-1a: the retired ids map to their replacements, and never come back as canonical. */
    @Test
    fun retiredIdsNormalizeToTheirReplacements() {
        assertEquals(
            mapOf(
                "git" to "version-control", "i18n" to "localization", "brainstormer" to "ideation",
                "bot" to "automation", "chief-of-staff" to "assistant",
            ),
            RoleIds.RETIRED,
        )
        RoleIds.RETIRED.forEach { (old, new) ->
            assertEquals(new, RoleIds.normalize(old))
            assertFalse(RoleIds.isCanonical(old), "retired '$old' is published again")
            assertTrue(RoleIds.isCanonical(new), "replacement '$new' is not canonical")
            assertEquals(listOf(old), RoleIds.retiredNamesOf(new))
        }
        assertEquals("backend", RoleIds.normalize("backend"))
        assertEquals("my-role", RoleIds.normalize("my-role"))
    }

    @Test
    fun idPatternMatchesTheProfileRoleShape() {
        assertTrue(RoleIds.isValid("backend"))
        assertTrue(RoleIds.isValid("chief-of-staff"))
        assertTrue(RoleIds.isValid("a"))
        assertFalse(RoleIds.isValid("Backend"))
        assertFalse(RoleIds.isValid("2backend"))
        assertFalse(RoleIds.isValid("-backend"))
        assertFalse(RoleIds.isValid(""))
        assertFalse(RoleIds.isValid("a".repeat(33)))
    }
}
