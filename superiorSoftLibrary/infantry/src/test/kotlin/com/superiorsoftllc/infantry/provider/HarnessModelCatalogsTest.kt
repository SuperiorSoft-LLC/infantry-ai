// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.provider

import com.superiorsoftllc.infantry.protocol.ProviderModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ROLE-FIRST S4: the sidecar registry's merge rules (docs/INFANTRY-ROLE-FIRST.md 5.3). */
class HarnessModelCatalogsTest {

    private val pi = HarnessModelCatalog { listOf(ProviderModels("pi", "acme", "acme", listOf("acme/one"))) }

    @Test
    fun onlyRegisteredHarnessesNotCoveredByAProviderGetRows() {
        val catalogs = HarnessModelCatalogs(mapOf("pi" to pi, "ghost" to pi))
        assertEquals(listOf("acme/one"), catalogs.rows(listOf("pi", "claude"), covered = setOf("claude")).single().models)
        assertTrue(catalogs.rows(listOf("pi"), covered = setOf("pi")).isEmpty(), "a configured provider wins")
        assertTrue(catalogs.rows(emptyList(), covered = emptySet()).isEmpty(), "an unregistered harness has no row")
    }

    @Test
    fun aFailingSourceIsANotedEmptyRowAndDoesNotHideTheOthers() {
        val catalogs = HarnessModelCatalogs(mapOf("bad" to HarnessModelCatalog { error("exploded") }, "pi" to pi))
        val rows = catalogs.rows(listOf("bad", "pi"), covered = emptySet())
        val bad = rows.single { it.provider == "bad" }
        assertTrue(bad.models.isEmpty())
        assertEquals("could not list models: exploded", bad.note)
        assertTrue(rows.any { it.provider == "pi" })
    }

    @Test
    fun aSourceCannotClaimAnotherHarnessSlot() {
        val liar = HarnessModelCatalog { listOf(ProviderModels("claude", "x", "x", listOf("x/y"))) }
        assertEquals("pi", HarnessModelCatalogs(mapOf("pi" to liar)).rows(listOf("pi"), emptySet()).single().provider)
    }


    /** PICKER-ALL-1: a registered harness with no source is one noted empty row, never no row (a bare text box). */
    @Test
    fun aHarnessWithNoSourceIsANotedEmptyRow() {
        val rows = HarnessModelCatalogs(mapOf("pi" to pi)).rows(listOf("pi", "omp"), covered = emptySet())
        val omp = rows.single { it.provider == "omp" }
        assertTrue(omp.models.isEmpty())
        assertEquals(HarnessModelCatalogs.NO_CATALOG_NOTE, omp.note)
        assertEquals(listOf("acme/one"), rows.single { it.provider == "pi" }.models)
    }
}
