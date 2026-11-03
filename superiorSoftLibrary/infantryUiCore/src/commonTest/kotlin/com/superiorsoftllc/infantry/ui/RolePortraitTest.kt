// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.RolePortrait
import com.superiorsoftllc.infantry.ui.model.rolePortraitFor
import kotlin.test.Test
import kotlin.test.assertEquals

/** M2-1: role id -> portrait is pure, total, and covers the daemon's whole role catalog. */
class RolePortraitTest {
    /** infantry `RoleIds.BUILT_IN` / `roles/index.json`, copied: this module may not depend on the daemon. */
    private val catalog = mapOf(
        "architect" to RolePortrait.ARCHITECT,
        "backend" to RolePortrait.BACKEND,
        "frontend" to RolePortrait.FRONTEND,
        "fullstack" to RolePortrait.FULLSTACK,
        "qa" to RolePortrait.QA,
        "devops" to RolePortrait.DEVOPS,
        "security" to RolePortrait.SECURITY,
        "mobile" to RolePortrait.MOBILE,
        "pm" to RolePortrait.PM,
        "marketing" to RolePortrait.MARKETING,
        "version-control" to RolePortrait.GIT,
        "seo" to RolePortrait.SEO,
        "localization" to RolePortrait.LOCALIZATION,
        "ideation" to RolePortrait.BRAINSTORMER,
        "automation" to RolePortrait.CUSTOM,
        "assistant" to RolePortrait.ASSISTANT,
        "custom" to RolePortrait.CUSTOM,
    )

    /** ORIGIN-SCRUB-1a: the ids a daemon older than contract 1.90.0 still sends keep their portraits. */
    private val retired = mapOf(
        "git" to RolePortrait.GIT,
        "i18n" to RolePortrait.LOCALIZATION,
        "brainstormer" to RolePortrait.BRAINSTORMER,
        "bot" to RolePortrait.CUSTOM,
        "chief-of-staff" to RolePortrait.ASSISTANT,
    )

    @Test fun anOlderDaemonsRetiredRoleIdsKeepTheirPortraits() {
        retired.forEach { (id, portrait) -> assertEquals(portrait, rolePortraitFor(id), "retired role id '$id'") }
    }

    @Test fun everyCatalogRoleIdMapsToItsPortrait() {
        catalog.forEach { (id, portrait) -> assertEquals(portrait, rolePortraitFor(id), "role id '$id'") }
    }

    @Test fun theTicketsShortIdsAndObviousAliasesResolve() {
        assertEquals(RolePortrait.PM, rolePortraitFor("project-manager"))
        assertEquals(RolePortrait.PM, rolePortraitFor("Project Manager"))
        assertEquals(RolePortrait.ASSISTANT, rolePortraitFor("assistant"))
        assertEquals(RolePortrait.LOCALIZATION, rolePortraitFor("localization"))
        assertEquals(RolePortrait.FULLSTACK, rolePortraitFor("full_stack"))
        assertEquals(RolePortrait.QA, rolePortraitFor(" QA "))
    }

    @Test fun unknownCustomOrMissingRolesGetTheCustomAgentPortrait() {
        listOf(null, "", "   ", "data-wrangler", "my-custom-role", "1").forEach {
            assertEquals(RolePortrait.CUSTOM, rolePortraitFor(it), "role id '$it'")
        }
        assertEquals("16_custom_agent", rolePortraitFor("nope").key)
    }

    @Test fun everyPortraitIsReachableAndKeysAreTheShippedFileNames() {
        val reachable = catalog.values.toSet() + rolePortraitFor("assistant")
        assertEquals(RolePortrait.entries.toSet(), reachable)
        assertEquals(16, RolePortrait.entries.map { it.key }.toSet().size)
        assertEquals("drawable/09_project_manager.png", RolePortrait.PM.resourcePath)
    }
}
