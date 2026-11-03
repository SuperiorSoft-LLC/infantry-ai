// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.runner.PipedProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessSpec
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BUG-G2, end to end on a real OS process: with [InfantryBin.childEnv] a shell the session starts finds
 * `infantry` BY NAME. Windows: `cmd /c infantry` runs `infantry.bat` (PATHEXT), and when Git Bash is on PATH
 * `bash -c infantry` runs the extensionless POSIX launcher (what opencode's bash tool does). POSIX: `sh -c`.
 */
class InfantryBinShellTest {
    private val windows = System.getProperty("os.name").startsWith("Windows")

    private fun run(command: List<String>, env: Map<String, String>): Pair<Int, List<String>> {
        val out = CopyOnWriteArrayList<String>()
        val proc = PipedProcessRunner().start(ProcessSpec(command, env = env), object : ProcessListener {
            override fun onStdout(line: String) { out += line }
            override fun onStderr(line: String) { out += "ERR $line" }
            override fun onExit(exitCode: Int) = Unit
        })
        proc.closeInput()
        val code = proc.awaitExit()
        Thread.sleep(100) // let the drain threads flush the last line
        return code to out.toList()
    }

    /** Git Bash itself, never WSL's System32 `bash.exe` (a different OS that would not see this PATH). */
    private fun gitBash(): File? =
        listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramW6432"))
            .map { File(it, "Git/bin/bash.exe") }.firstOrNull { it.isFile }

    @Test
    fun aSessionShellResolvesInfantryByName() {
        val bin = Files.createTempDirectory("infantry-bin").toFile()
        try {
            File(bin, "infantry").apply { writeText("#!/bin/sh\necho infantry-posix-ok\n"); setExecutable(true) }
            File(bin, "infantry.bat").writeText("@echo infantry-bat-ok\r\n")
            val env = InfantryBin.childEnv(bin)

            if (windows) {
                val (code, out) = run(listOf("cmd", "/c", "infantry"), env)
                assertEquals(0, code, "cmd could not run infantry: $out")
                assertTrue("infantry-bat-ok" in out, "cmd /c infantry must run infantry.bat: $out")
                gitBash()?.let { bash ->
                    val (bashCode, bashOut) = run(listOf(bash.path, "-c", "infantry"), env)
                    assertEquals(0, bashCode, "Git Bash could not run infantry: $bashOut")
                    assertTrue("infantry-posix-ok" in bashOut, "bash -c infantry must run the POSIX launcher: $bashOut")
                }
            } else {
                val (code, out) = run(listOf("sh", "-c", "infantry"), env)
                assertEquals(0, code, "sh could not run infantry: $out")
                assertTrue("infantry-posix-ok" in out, "$out")
            }
        } finally {
            bin.deleteRecursively()
        }
    }
}
