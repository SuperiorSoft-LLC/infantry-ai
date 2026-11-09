// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.install

import com.superiorsoftllc.infantry.cli.CliPathCli
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CLI-PATH-1: `infantry install-cli` / `uninstall-cli` end to end, through the same entry point the CLI calls.
 *
 * `INFANTRY_CLI_PATH_FILE` sends the write to a temp file, so this test never reads or writes a real PATH
 * ([CliPathCli.STORE_ENV]).
 */
class CliPathCliTest {
    private val temp: File = File(System.getProperty("java.io.tmpdir"), "infantry-install-cli-" + System.nanoTime()).apply { mkdirs() }
    private val pathFile = File(temp, "path.txt")
    private val launcher: File = File(temp, "app-resources").apply {
        mkdirs()
        File(this, "infantry.cmd").writeText("@echo off\r\njava -cp \"%~dp0..\\*\" ${CliPath.MAIN_CLASS} %*\r\n")
    }

    private val env: (String) -> String? = { name -> if (name == CliPathCli.STORE_ENV) pathFile.path else null }

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun run(command: String, args: List<String> = emptyList(), dir: String? = launcher.path, dryRun: Boolean = false): Triple<Int, String, String> {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = CliPathCli.run(command, args, dir, dryRun, out, err, env)
        return Triple(code, out.toString(), err.toString())
    }

    @Test
    fun `install-cli puts the launcher folder on the PATH and says to open a new terminal`() {
        val (code, out, err) = run("install-cli")

        assertEquals(0, code, err)
        assertTrue(out.contains("added ${launcher.absolutePath}"), out)
        assertTrue(out.contains(CliPath.NEW_TERMINAL_NOTE), out)
        assertTrue(out.contains("on ${pathFile.path}"), out)
        assertEquals(listOf(launcher.absolutePath), CliPath.entries(pathFile.readText()))
    }

    @Test
    fun `a second install-cli changes nothing`() {
        run("install-cli")
        val before = pathFile.readText()

        val (code, out, _) = run("install-cli")

        assertEquals(0, code)
        assertTrue(out.contains("already on ${pathFile.path}: nothing to change"), out)
        assertFalse(out.contains(CliPath.NEW_TERMINAL_NOTE), "nothing was written, so no new terminal is needed")
        assertEquals(before, pathFile.readText())
    }

    @Test
    fun `uninstall-cli takes the entry off again`() {
        run("install-cli")

        val (code, out, _) = run("uninstall-cli")

        assertEquals(0, code)
        assertTrue(out.contains("removed ${launcher.absolutePath}"), out)
        assertTrue(CliPath.entries(pathFile.readText()).isEmpty(), pathFile.readText())
    }

    @Test
    fun `a dry run reports what it would write and writes nothing`() {
        val (code, out, _) = run("install-cli", dryRun = true)

        assertEquals(0, code)
        assertTrue(out.contains("would write ${pathFile.path}"), out)
        assertFalse(pathFile.exists(), "a dry run writes no file")
    }

    @Test
    fun `a folder that holds no launcher is refused`() {
        val empty = File(temp, "empty").apply { mkdirs() }

        val (code, _, err) = run("install-cli", dir = empty.path)

        assertEquals(1, code)
        assertTrue(err.contains("holds no Infantry AI launcher"), err)
    }

    @Test
    fun `an argument is a usage error`() {
        val (code, _, err) = run("install-cli", args = listOf("extra"))

        assertEquals(2, code)
        assertTrue(err.contains("takes no arguments"), err)
    }
}
