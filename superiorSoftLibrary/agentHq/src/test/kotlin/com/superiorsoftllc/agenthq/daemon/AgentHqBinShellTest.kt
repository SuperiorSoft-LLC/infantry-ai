package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.runner.PipedProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BUG-G2, end to end on a real OS process: with [AgentHqBin.childEnv] a shell the session starts finds
 * `agenthq` BY NAME. Windows: `cmd /c agenthq` runs `agenthq.bat` (PATHEXT), and when Git Bash is on PATH
 * `bash -c agenthq` runs the extensionless POSIX launcher (what opencode's bash tool does). POSIX: `sh -c`.
 */
class AgentHqBinShellTest {
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
    fun aSessionShellResolvesAgentHqByName() {
        val bin = Files.createTempDirectory("agenthq-bin").toFile()
        try {
            File(bin, "agenthq").apply { writeText("#!/bin/sh\necho agenthq-posix-ok\n"); setExecutable(true) }
            File(bin, "agenthq.bat").writeText("@echo agenthq-bat-ok\r\n")
            val env = AgentHqBin.childEnv(bin)

            if (windows) {
                val (code, out) = run(listOf("cmd", "/c", "agenthq"), env)
                assertEquals(0, code, "cmd could not run agenthq: $out")
                assertTrue("agenthq-bat-ok" in out, "cmd /c agenthq must run agenthq.bat: $out")
                gitBash()?.let { bash ->
                    val (bashCode, bashOut) = run(listOf(bash.path, "-c", "agenthq"), env)
                    assertEquals(0, bashCode, "Git Bash could not run agenthq: $bashOut")
                    assertTrue("agenthq-posix-ok" in bashOut, "bash -c agenthq must run the POSIX launcher: $bashOut")
                }
            } else {
                val (code, out) = run(listOf("sh", "-c", "agenthq"), env)
                assertEquals(0, code, "sh could not run agenthq: $out")
                assertTrue("agenthq-posix-ok" in out, "$out")
            }
        } finally {
            bin.deleteRecursively()
        }
    }
}
