package com.superiorsoftllc.infantry.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The desktop module is only the Window host: config/env/file access belongs to the core's JVM platform host. */
class DesktopShellBoundaryTest {
    private val main = File("src/main/kotlin/com/superiorsoftllc/infantry/ui/Main.kt").readText()

    @Test fun desktopMainOwnsOnlyTheWindow() {
        assertTrue("Window(" in main && "application" in main, "Main.kt must still be the Window/application entry")
    }

    @Test fun desktopMainDoesNotReadEnvironmentOrFilesDirectly() {
        for (banned in listOf("System.getenv", "java.io.File", "File(", "INFANTRY_")) {
            assertTrue(banned !in main, "Main.kt must not contain '$banned' (platform host owns it)")
        }
    }

    @Test fun desktopMainHostsTheSharedShell() {
        assertTrue("InfantryShell(" in main, "Main.kt must delegate to the shared InfantryShell")
    }
}
