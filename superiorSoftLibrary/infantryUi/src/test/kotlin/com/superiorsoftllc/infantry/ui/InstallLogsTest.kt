// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * PKG-3: the installed profile writes its stdout/stderr to `<stateDir>/logs/infantry.log`, rotating to at most
 * five files; dev never redirects. Every test restores the real streams, whatever happens.
 */
class InstallLogsTest {
    private val stateDir: File = Files.createTempDirectory("infantry-logs-test").toFile()
    private val realOut = System.out
    private val realErr = System.err
    private val realHandler = Thread.getDefaultUncaughtExceptionHandler()

    @AfterTest fun restore() {
        System.setOut(realOut)
        System.setErr(realErr)
        Thread.setDefaultUncaughtExceptionHandler(realHandler)
        stateDir.deleteRecursively()
    }

    private fun logFiles(dir: File): List<String> = dir.listFiles().orEmpty().map { it.name }.sorted()

    @Test fun theInstalledProfileRoutesStdoutAndStderrToTheLogFileUnderTheStateDir() {
        val logs = LaunchProfile.installLogs(installed = true, stateDir = stateDir)!!
        try {
            println("hello-out")
            System.err.println("hello-err")
        } finally {
            logs.close()
        }
        val file = File(stateDir, "logs/infantry.log")
        assertEquals(file.absoluteFile, InstallLogs.file(logs.dir, 0))
        val text = file.readText()
        assertTrue("hello-out" in text, text)
        assertTrue("hello-err" in text, text)
        assertSame(realOut, System.out, "close() puts the console back")
    }

    @Test fun devDoesNotRedirect() {
        assertNull(LaunchProfile.installLogs(installed = false, stateDir = stateDir))
        assertSame(realOut, System.out)
        assertSame(realErr, System.err)
        assertTrue(!File(stateDir, "logs").exists(), "dev writes no log folder")
    }

    @Test fun eachLaunchStartsANewFileAndRotationKeepsAtMostFive() {
        repeat(8) { launch ->
            InstallLogs.install(stateDir).use { println("launch-$launch") }
        }
        val dir = InstallLogs.dir(stateDir)
        assertEquals(listOf("infantry.1.log", "infantry.2.log", "infantry.3.log", "infantry.4.log", "infantry.log"), logFiles(dir))
        assertTrue("launch-7" in File(dir, "infantry.log").readText(), "the current file is the last launch")
        assertTrue("launch-6" in File(dir, "infantry.1.log").readText())
        assertTrue("launch-3" in File(dir, "infantry.4.log").readText(), "the oldest kept is four launches back")
    }

    @Test fun aFileThatReachesMaxBytesRollsMidRunAndStillKeepsAtMostFive() {
        InstallLogs.install(stateDir, maxBytes = 200).use {
            repeat(50) { i -> println("line-$i " + "x".repeat(40)) }
        }
        val dir = InstallLogs.dir(stateDir)
        assertEquals(InstallLogs.MAX_FILES, logFiles(dir).size, logFiles(dir).toString())
        dir.listFiles()!!.forEach { assertTrue(it.length() <= 200, "${it.name} is ${it.length()} bytes") }
        assertTrue("line-49" in File(dir, "infantry.log").readText())
    }

    @Test fun anUncaughtExceptionWritesItsStackTraceToTheLog() {
        val logs = InstallLogs.install(stateDir)
        try {
            val thread = Thread { throw IllegalStateException("boom-pkg3") }
            thread.start()
            thread.join()
        } finally {
            logs.close()
        }
        val text = File(InstallLogs.dir(stateDir), "infantry.log").readText()
        assertTrue("[infantry-crash]" in text, text)
        assertTrue("IllegalStateException: boom-pkg3" in text, text)
        assertTrue("InstallLogsTest" in text, "the stack trace is written, not just the message")
    }
}
