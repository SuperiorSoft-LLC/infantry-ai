// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** DP-4a: the plugin-facing board API names no daemon class (a plugin sees `infantryDaemonApi` and nothing else). */
class BoardApiImportGuardTest {
    private fun apiDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, "infantryDaemonApi/src/main/kotlin").takeIf { it.isDirectory }?.let { return it }
            File(dir, "superiorSoftLibrary/infantryDaemonApi/src/main/kotlin").takeIf { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        error("infantryDaemonApi sources not found")
    }

    @Test fun `no api type imports or names a class of the daemon`() {
        val allowed = listOf("com.superiorsoftllc.infantry.daemon.api.", "com.superiorsoftllc.infantry.plugin.api.")
        val files = apiDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty())
        val offenders = files.flatMap { f ->
            f.readLines().filter { it.trimStart().startsWith("import com.superiorsoftllc.infantry.") }
                .filter { line -> allowed.none { line.contains("import $it") } }.map { "${f.name}: $it" }
        }
        assertTrue(offenders.isEmpty(), "api imports from the daemon: $offenders")
        val board = files.filter { it.path.replace('\\', '/').contains("/api/board/") }
        assertTrue(board.isNotEmpty())
        val foreign = Regex("com\\.superiorsoftllc\\.infantry\\.(?!daemon\\.api|plugin\\.api)")
        board.forEach { f -> assertTrue(!foreign.containsMatchIn(f.readText()), "${f.name} names a daemon package") }
    }
}
