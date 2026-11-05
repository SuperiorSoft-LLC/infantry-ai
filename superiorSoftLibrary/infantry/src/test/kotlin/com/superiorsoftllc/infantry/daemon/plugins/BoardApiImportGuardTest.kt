// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * DP-4a. Rule: the plugin-facing board API (`infantryDaemonApi`, package `...daemon.api.board`) imports and names no
 * class of the daemon. A plugin compiles against `infantryDaemonApi` and nothing else.
 * Why: a daemon class in the API would pull the whole daemon onto every plugin's compile path and break the SDK line.
 * Fix a failure: the listed file imports or names a `com.superiorsoftllc.infantry.<daemon package>` type. Move that
 * type behind an API type in `infantryDaemonApi` (or into `daemon.api`/`plugin.api`) and have the daemon implement it.
 * Do not add the package to the allow-list to make it pass.
 */
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
        assertTrue(offenders.isEmpty(), "api imports from the daemon (fix: move the type behind an infantryDaemonApi type): $offenders")
        val board = files.filter { it.path.replace('\\', '/').contains("/api/board/") }
        assertTrue(board.isNotEmpty())
        val foreign = Regex("com\\.superiorsoftllc\\.infantry\\.(?!daemon\\.api|plugin\\.api)")
        board.forEach { f -> assertTrue(!foreign.containsMatchIn(f.readText()), "${f.name} names a daemon package") }
    }
}
