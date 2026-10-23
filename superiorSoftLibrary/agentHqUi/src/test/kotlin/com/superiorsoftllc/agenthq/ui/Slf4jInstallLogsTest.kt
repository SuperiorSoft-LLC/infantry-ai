package com.superiorsoftllc.agenthq.ui

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * BUG-INSTALL-SLF4J-1: the installed app runs the daemon in-process, so a Ktor/sqlite log line goes through SLF4J on
 * the desktop's runtime classpath. It must land in `<stateDir>/logs/agenthq.log` ([InstallLogs]); with the NOP logger
 * bound it reached no sink at all.
 */
class Slf4jInstallLogsTest {
    private val stateDir: File = Files.createTempDirectory("agenthq-slf4j-test").toFile()
    private val realOut = System.out
    private val realErr = System.err
    private val realHandler = Thread.getDefaultUncaughtExceptionHandler()

    @AfterTest fun restore() {
        System.setOut(realOut)
        System.setErr(realErr)
        Thread.setDefaultUncaughtExceptionHandler(realHandler)
        stateDir.deleteRecursively()
    }

    @Test fun anSlf4jLogLineReachesAgentHqLog() {
        val logs = LaunchProfile.installLogs(installed = true, stateDir = stateDir)!!
        try {
            LoggerFactory.getLogger("io.ktor.server.Application").info("slf4j-install-marker")
        } finally {
            logs.close()
        }
        val text = InstallLogs.file(logs.dir, 0).readText()
        assertTrue("slf4j-install-marker" in text, "the SLF4J line is in agenthq.log: '$text'")
    }
}
