// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.roles.daemon

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Custom role files: the "filename is the role id" convention, front matter, and tolerant loading. */
class CustomRolesTest {

    @Test
    fun parsesFrontMatterAndPersona() {
        val role = CustomRoles.parse(
            "backend-ninja",
            """
            ---
            name: Backend Ninja
            color: #0ea5e9
            ---
            You are the backend ninja.
            Ship it.
            """.trimIndent(),
        )
        assertEquals("backend-ninja", role.id)
        assertEquals("Backend Ninja", role.name)
        assertEquals("#0ea5e9", role.color)
        assertEquals("You are the backend ninja.\nShip it.", role.persona)
        assertEquals(RoleSource.CUSTOM, role.source)
    }

    @Test
    fun bareMarkdownIsAValidRole() {
        val role = CustomRoles.parse("docs", "You write documentation.")
        assertEquals("docs", role.name, "a file with no front matter is named by its id")
        assertEquals("You write documentation.", role.persona)
        assertTrue(role.color.startsWith("#"), "the colour falls back to the palette")
    }

    @Test
    fun anUnclosedFrontMatterIsAnError() {
        assertFailsWith<IllegalArgumentException> { CustomRoles.parse("docs", "---\nname: x\nbody never delimited") }
    }

    @Test
    fun anInvalidRoleIdIsRejected() {
        assertFailsWith<IllegalArgumentException> { CustomRoles.parse("Not-Valid", "x") }
    }

    @Test
    fun loadFromReadsEveryMarkdownFileSortedAndSkipsTheBadOnes() {
        val dir = File.createTempFile("infantry-roles", "").let { it.delete(); it.mkdirs(); it }
        try {
            File(dir, "zeta.md").writeText("---\ncolor: #111111\n---\nZeta persona.")
            File(dir, "alpha.md").writeText("Alpha persona.")
            File(dir, "notes.txt").writeText("ignored: not markdown")
            File(dir, "Bad Name.md").writeText("invalid id")
            File(dir, "broken.md").writeText("---\nname: x\nno close")

            val load = CustomRoles.loadFrom(dir)
            assertEquals(listOf("alpha", "zeta"), load.roles.map { it.id }, "valid files load, sorted by name")
            assertEquals("#111111", load.roles.single { it.id == "zeta" }.color)
            assertEquals(2, load.problems.size, "the bad id and the unclosed front matter are both reported: ${load.problems}")
            assertTrue(load.problems.any { it.contains("Bad Name.md") })
            assertTrue(load.problems.any { it.contains("broken.md") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aMissingDirectoryIsEmptyNotAnError() {
        assertTrue(CustomRoles.loadFrom(null).roles.isEmpty())
        assertTrue(CustomRoles.loadFrom(File("does-not-exist-anywhere")).roles.isEmpty())
    }
}
