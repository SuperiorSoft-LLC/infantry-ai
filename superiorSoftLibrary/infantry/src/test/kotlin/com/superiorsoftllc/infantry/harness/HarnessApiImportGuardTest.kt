// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.daemon.TestDaemonPlugins
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HARNESS-PLUGIN-3 H3: the harness API (`infantryHarnessApi`) is what community harness jars compile against, so its ABI is
 * exactly its own sources: it names no other infantry package, depends on no project and no library, and the class loader
 * that serves it to plugins exposes that package and nothing else of the daemon.
 */
class HarnessApiImportGuardTest {
    private val module = File(TestDaemonPlugins.repo, "superiorSoftLibrary/infantryHarnessApi")

    @Test fun `no api source imports or names a daemon package`() {
        val files = File(module, "src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size >= 2, "harness api sources not found under $module")
        val foreign = Regex("""com\.superiorsoftllc\.infantry\.(?!harness\.api\b)""")
        val hits = files.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> foreign.containsMatchIn(l) }.map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" }
        }
        assertTrue(hits.isEmpty(), "the harness api names daemon code:\n${hits.joinToString("\n")}")
        // A CRLF checkout (core.autocrlf) ends the package line with \r\n.
        files.forEach { f -> assertTrue(f.readText().replace("\r\n", "\n").contains("package com.superiorsoftllc.infantry.harness.api\n"), "${f.name} is outside harness.api") }
    }

    @Test fun `the api module depends on nothing`() {
        val gradle = File(module, "build.gradle.kts").readLines().map { it.trim() }.filter { !it.startsWith("//") }
        assertTrue(gradle.none { "project(" in it }, "the harness api must not depend on a project")
        assertTrue(gradle.none { it.startsWith("dependencies") }, "the harness api must not declare dependencies (its ABI is its own sources)")
    }

    @Test fun `the plugin class loader exposes the runtimes and the api only`() {
        assertEquals(
            listOf("kotlin.", "kotlinx.serialization.", "kotlinx.coroutines.", "com.superiorsoftllc.infantry.harness.api."),
            HarnessApiClassLoader.EXPOSED_PREFIXES,
        )
    }
}
