// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.testrun

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SCRIPT-HOOK-3 (TST-A, TST-02): the command's exit code is the suite's result on every OS; stop ends a run. */
class ProcessSuiteExecTest {
    private val dir = Files.createTempDirectory("pse").toFile()

    @Test fun windowsArgvHasNoStartWrapper() {
        assertEquals(listOf("cmd", "/c", "exit 1"), ProcessSuiteExec.argv("exit 1", true))
    }

    @Test fun exitCodeOfTheCommandPropagates() {
        val exec = ProcessSuiteExec()
        assertEquals(1, exec.run("exit 1", dir, 1).exitCode)
        assertEquals(0, exec.run("exit 0", dir, 1).exitCode)
        assertEquals(5, exec.run("exit 5", dir, 1).exitCode)
    }

    @Test fun stopEndsTheRunInFlight() {
        val exec = ProcessSuiteExec()
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val cmd = if (windows) "ping -n 30 127.0.0.1 > nul" else "sleep 30"
        var code = 0
        val t = Thread { code = exec.run(cmd, dir, 1).exitCode }.apply { start() }
        Thread.sleep(1500); exec.stop(); t.join(10_000)
        assertTrue(!t.isAlive && code != 0, "stopped run exits non-zero, got $code")
    }
}
