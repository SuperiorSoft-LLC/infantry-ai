package com.superiorsoftllc.agenthq.harness.pi

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TODO-PROGRESS-1: the daemon-owned pi `todowrite` extension is installed into a temp dir and loaded with `-e`, next to the bridge's extras. */
class PiTodoExtensionTest {
    private val dir: File = Files.createTempDirectory("pi-todo-ext").toFile()

    @AfterTest fun reset() {
        PiLaunchExtras.args = { _, _ -> emptyList() }
        PiLaunchExtras.builtIn = { _, _ -> emptyList() }
        dir.deleteRecursively()
    }

    @Test fun `the extension registers todowrite in the opencode full-list shape`() {
        val src = PiTodoExtension.source()
        assertTrue("name: \"todowrite\"" in src)
        assertTrue("\"in_progress\"" in src && "\"completed\"" in src && "content" in src)
        assertTrue(!src.contains("import "), "no imports, so it loads from any directory")
    }

    @Test fun `launch args install the file and point -e at it, re-installing a deleted file`() {
        val args = PiTodoExtension.launchArgs(dir)(null, emptyMap())
        val file = File(dir, PiTodoExtension.FILE_NAME)
        assertEquals(listOf("-e", file.absolutePath), args)
        assertEquals(PiTodoExtension.source(), file.readText())
        file.delete()
        PiTodoExtension.launchArgs(dir)(null, emptyMap())
        assertTrue(file.isFile)
    }

    @Test fun `built-in extras and the bridge's extras both reach the argv`() {
        PiLaunchExtras.builtIn = PiTodoExtension.launchArgs(dir)
        PiLaunchExtras.args = { _, _ -> listOf("--approve") }
        assertEquals(listOf("-e", File(dir, PiTodoExtension.FILE_NAME).absolutePath, "--approve"), PiLaunchExtras.all(null, emptyMap()))
    }
}
