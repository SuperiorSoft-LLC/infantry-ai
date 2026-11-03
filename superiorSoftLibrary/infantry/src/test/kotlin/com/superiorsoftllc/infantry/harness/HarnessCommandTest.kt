// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.runner.ScriptedRunner
import com.superiorsoftllc.infantry.runner.StubScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CP-4 slice (a): a harness declares its command catalog, and the pure rules that read and filter it. */
class HarnessCommandTest {

    @Test
    fun aHarnessThatDeclaresNothingOffersNoCommands() {
        val bare = object : Harness {
            override val id = "bare"
            override val contractVersion = HARNESS_CONTRACT_VERSION
            override val capabilities = emptySet<HarnessCapability>()
            override fun events(request: HarnessRequest) = kotlinx.coroutines.flow.emptyFlow<HarnessEvent>()
        }
        assertEquals(emptyList(), bare.commands)
    }


    @Test
    fun onlyANativeCommandCarriesNativeTextAndNamesAreBareWords() {
        assertFailsWith<IllegalArgumentException> { HarnessCommand("x", "d", CommandAction.SET_MODEL, native = "/x") }
        assertFailsWith<IllegalArgumentException> { HarnessCommand("x", "d", CommandAction.NATIVE) }
        assertFailsWith<IllegalArgumentException> { HarnessCommand("/x", "d", CommandAction.SET_MODEL) }
        assertFailsWith<IllegalArgumentException> { HarnessCommand("two words", "d", CommandAction.SET_MODEL) }
    }

    @Test
    fun aLeadingSlashMakesTheWholeInputACommand() {
        assertTrue(CommandLines.isCommand("/model x"))
        assertTrue(CommandLines.isCommand("   /compact"))
        assertFalse(CommandLines.isCommand("read /usr/bin"))
        assertFalse(CommandLines.isCommand("hello"))
        assertEquals(CommandLine("model", "a b"), CommandLines.parse("/model   a b "))
    }

    @Test
    fun theCatalogIsFilteredByNamePrefixThenByValuePrefix() {
        val catalog = StandardCommands.catalog(efforts = com.superiorsoftllc.infantry.session.EffortLevels.ids)
        assertEquals(listOf("model", "effort", "compact"), CommandLines.complete(catalog, "").map { it.name })
        assertEquals(listOf("effort"), CommandLines.complete(catalog, "/E").map { it.name })
        assertEquals(emptyList(), CommandLines.complete(catalog, "zzz"))
        assertTrue(CommandLines.complete(catalog, "e").single().values.isEmpty(), "names first: no values until a command is named")
        assertEquals(listOf("xhigh"), CommandLines.complete(catalog, "effort x").single().values)
        assertEquals(listOf("high", "xhigh").sorted(), CommandLines.complete(catalog, "effort ").single().values.filter { "high" in it }.sorted())
        assertEquals(emptyList(), CommandLines.complete(catalog, "nope x"), "values of an unknown command: nothing")
    }
}
