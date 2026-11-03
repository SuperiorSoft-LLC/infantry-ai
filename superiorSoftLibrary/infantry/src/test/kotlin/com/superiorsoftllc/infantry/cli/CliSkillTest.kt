// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.SkillSave
import com.superiorsoftllc.infantry.persistence.SkillStore
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P10-K14 (docs/INFANTRY-PHASE10-KNOWLEDGE.md section 6): `infantry skill list` and `infantry skill show <name|id>` over a
 * REAL daemon and the REAL [Cli.run] (the [CliTraitTest] pattern). `list` leaves `attachedOnly` skills out; `show` takes
 * an exact name or an id (contract 1.56.0's `skill_get`); an unknown one prints the daemon's refusal and exits 1; a usage
 * slip exits 2 before any socket opens.
 */
class CliSkillTest {
    private val fixtures = mutableListOf<DaemonFixture>()

    private fun launch(): DaemonFixture =
        DaemonFixture.launch(stateDir = Files.createTempDirectory("infantry-skill-cli-test").toFile())
            .also {
                fixtures += it
                it.client.await(Envelope.READY)
            }

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
    }

    private data class Result(val exit: Int, val out: String, val err: String)

    private fun DaemonFixture.cli(vararg args: String): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf(*args, "--port", port.toString()), out, err, getenv = { null }, input = { StringReader("") })
        return Result(exit, out.toString(), err.toString())
    }

    private fun usage(vararg args: String): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        // Port 1: nothing listens there, so a usage error that slipped past validation would say "not running".
        val exit = Cli.run(arrayOf(*args, "--port", "1"), out, err, getenv = { null }, input = { StringReader("") })
        return Result(exit, out.toString(), err.toString())
    }

    private fun DaemonFixture.skill(name: String, description: String, body: String, attachedOnly: Boolean = false) =
        SkillStore(daemon.store.skills).save(SkillSave(name, description, body, listOf("qa"), attachedOnly))

    @Test
    fun listPrintsNameAndDescriptionLinesWithoutAttachedOnlySkills() {
        val f = launch()
        assertEquals("No skills.\n", f.cli("skill", "list").out.replace("\r\n", "\n"))

        f.skill("Review", "Use when reviewing", "look hard")
        f.skill("Deploy", "", "ship it")
        f.skill("Secret playbook", "Only for the release agent", "hidden body", attachedOnly = true)

        val list = f.cli("skill", "list")
        assertEquals(0, list.exit, list.err)
        assertEquals(listOf("Deploy", "Review: Use when reviewing"), list.out.lines().filter { it.isNotBlank() })
        assertTrue("Secret playbook" !in list.out, "attachedOnly skills are absent from the list: ${list.out}")
    }

    @Test
    fun showByNameAndById() {
        val f = launch()
        val saved = f.skill("Code Review", "Use when reviewing", "line one\nline two")

        val byName = f.cli("skill", "show", "Code Review")
        assertEquals(0, byName.exit, byName.err)
        assertTrue("ID: ${saved.id}" in byName.out, byName.out)
        assertTrue("NAME: Code Review" in byName.out, byName.out)
        assertTrue("DESCRIPTION: Use when reviewing" in byName.out, byName.out)
        assertTrue("TAGS: qa" in byName.out, byName.out)
        assertTrue(byName.out.replace("\r\n", "\n").trimEnd().endsWith("line one\nline two"), byName.out)

        // An unquoted multi-word name is the same name.
        assertEquals(byName.out, f.cli("skill", "show", "Code", "Review").out)

        val byId = f.cli("skill", "show", saved.id)
        assertEquals(0, byId.exit, byId.err)
        assertEquals(byName.out, byId.out)

        // `show` still opens an attachedOnly skill (it is hidden from the list, not unreachable).
        val hidden = f.skill("Hidden", "d", "b", attachedOnly = true)
        val shown = f.cli("skill", "show", "Hidden")
        assertEquals(0, shown.exit, shown.err)
        assertTrue("ID: ${hidden.id}" in shown.out && "ATTACHED ONLY: yes" in shown.out, shown.out)
    }

    @Test
    fun anUnknownSkillIsRefused() {
        val f = launch()
        f.skill("Review", "d", "b")
        val unknown = f.cli("skill", "show", "Nope")
        assertEquals(1, unknown.exit, unknown.out)
        assertTrue("skill_get refused: unknown skill 'Nope'" in unknown.err, unknown.err)
        assertEquals("", unknown.out)
    }

    @Test
    fun usageSlipsExit2BeforeConnecting() {
        listOf(
            arrayOf("skill") to "skill needs a subcommand",
            arrayOf("skill", "show") to "skill show requires a skill name or id",
            arrayOf("skill", "show", " ") to "skill show requires a skill name or id",
            arrayOf("skill", "list", "extra") to "skill list takes no arguments",
            arrayOf("skill", "delete", "x") to "skill needs a subcommand",
        ).forEach { (args, text) ->
            val r = usage(*args)
            assertEquals(2, r.exit, "${args.toList()}: ${r.err}")
            assertTrue(text in r.err, "${args.toList()}: ${r.err}")
        }
    }
}
