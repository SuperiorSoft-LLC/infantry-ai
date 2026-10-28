package com.superiorsoftllc.agenthq.ui.plugin

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** NS10: "Try in a fresh shell" prepares a temp app dir with the export and starts the shell-only classpath on it. */
class FreshShellTest {
    private val tmp = Files.createTempDirectory("ns10").toFile()
    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    @Test
    fun preparesTheAppDirAndBuildsTheShellOnlyCommand() {
        val exported = File(tmp, "exports/Pane.ihq").apply { parentFile.mkdirs(); writeText("ihq") }
        val dir = FreshShell.prepare(File(tmp, "state"), exported, "Pane", now = 42)

        assertEquals(File(tmp, "state/fresh-shells/try.pane.42"), dir)
        assertTrue(File(dir, "plugins/core").isDirectory)
        assertEquals("ihq", File(dir, "Pane.ihq").readText())
        assertTrue("\"appId\": \"try.pane.42\"" in File(dir, "app.json").readText())

        val sep = File.pathSeparator
        val cmd = FreshShell.command(
            "/jdk", listOf("/l/agentHqUi.jar", "/l/agenthqAppUi.jar", "/l/Infantry_Board.jar", "/l/agenthqUiCore-jvm.jar").joinToString(sep),
            listOf("-Xmx1g", "-Djpackage.app-path=x", "-XX:MaxMetaspaceSize=256m"), dir, windows = false,
        )
        assertEquals(
            listOf(File("/jdk/bin/java").path, "-Xmx1g", "-XX:MaxMetaspaceSize=256m", "-cp", "/l/agentHqUi.jar$sep/l/agenthqUiCore-jvm.jar", FreshShell.MAIN_CLASS, "--app", dir.absolutePath),
            cmd,
        )
    }
}
