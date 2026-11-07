// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LAUNCH-FIRSTRUN-1: `infantry project create` lets a headless server (no desktop New Project form) get its first
 * project from the CLI alone, over a REAL daemon and the REAL [Cli.run].
 */
class CliProjectCreateTest {

    private val fixtures = mutableListOf<DaemonFixture>()

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
        fixtures.clear()
    }

    private fun launch(): DaemonFixture =
        DaemonFixture.launch(stateDir = Files.createTempDirectory("infantry-project-create-cli-test").toFile())
            .also { fixtures += it; it.client.await(Envelope.READY) }

    private data class Result(val exit: Int, val out: String, val err: String)

    private fun cli(port: Int, vararg args: String): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf(*args, "--port", port.toString()), out, err, getenv = { null })
        return Result(exit, out.toString(), err.toString())
    }

    @Test
    fun createMakesAProjectThatFindLists() {
        val fixture = launch()
        val made = cli(fixture.port, "project", "create", "First project")
        assertEquals(0, made.exit, made.err)
        val id = Regex("created project (\\S+) \\| First project").find(made.out)?.groupValues?.get(1)
        assertTrue(id != null, made.out)
        assertTrue("next:" in made.out, "the stranger is told what to do next: ${made.out}")

        val found = cli(fixture.port, "project", "find", "first")
        assertEquals(0, found.exit, found.err)
        assertTrue("$id | First project" in found.out, found.out)
    }

    @Test
    fun blankNameIsAUsageError() {
        val fixture = launch()
        val r = cli(fixture.port, "project", "create", "  ")
        assertTrue(r.exit != 0)
        assertTrue("project create takes one non-blank name" in r.err, r.err)
        assertEquals("no project matches ''", cli(fixture.port, "project", "find").out.trim())
    }
}
