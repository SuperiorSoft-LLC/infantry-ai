// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.roles.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The generated fallback's ingredients: initials and a stable palette colour. */
class RolePaletteTest {

    @Test
    fun initialsTakeUpToTwoWords() {
        assertEquals("CS", RolePalette.initials("chief-of-staff"))
        assertEquals("B", RolePalette.initials("backend"))
        assertEquals("I", RolePalette.initials("i18n"))
        assertEquals("BN", RolePalette.initials("backend_ninja"))
        assertEquals("?", RolePalette.initials("---"))
        assertEquals("?", RolePalette.initials(""))
    }

    @Test
    fun colorIsAPaletteEntryAndDeterministic() {
        val color = RolePalette.colorFor("brand-new-role")
        assertTrue(color in RolePalette.FALLBACK, "generated colours come from the palette")
        assertEquals(color, RolePalette.colorFor("brand-new-role"), "the same id is the same colour every time")
    }

    @Test
    fun thePaletteIsNotAllOneColour() {
        val distinct = (0 until 40).map { RolePalette.colorFor("role-$it") }.toSet()
        assertTrue(distinct.size > 1)
        assertNotEquals(RolePalette.colorFor("a"), RolePalette.colorFor("b"))
    }
}
