// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * LICENSE-APPLY-1: every source file carries the SPDX id the license map gives its path. The rule lives in
 * `scripts/license-paths.txt` (first prefix wins, default Apache-2.0); `scripts/license-headers.sh` writes the headers.
 * A new file without a header, or with the wrong one, fails here and names the file. DRAFT until the public launch.
 */
class LicenseMapGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "AGENTS.md").isFile }

    private val skipDirs = setOf("build", ".git", ".gradle", ".worktrees", "node_modules", ".idea", "out", "generated")
    private val extensions = setOf("kt", "kts", "sh", "ps1", "mjs")

    private val rules: List<Pair<String, String>> = File(root, "scripts/license-paths.txt").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.substringBefore('\t') to it.substringAfter('\t').trim() }

    private fun expected(path: String): String = rules.firstOrNull { path.startsWith(it.first) }?.second ?: "Apache-2.0"

    @Test
    fun everySourceFileHasTheSpdxIdOfItsPath() {
        val bad = mutableListOf<String>()
        var seen = 0
        root.walkTopDown().onEnter { it.name !in skipDirs }.filter { it.isFile && it.extension in extensions }.forEach { f ->
            val path = f.relativeTo(root).path.replace(File.separatorChar, '/')
            if (path.startsWith("gradle/wrapper/")) return@forEach
            seen++
            val head = f.useLines { it.take(6).toList() }
            val id = head.firstNotNullOfOrNull { Regex("""SPDX-License-Identifier:\s*(\S+)""").find(it)?.groupValues?.get(1) }
            val want = expected(path)
            if (id != want) bad += "$path: has ${id ?: "no header"}, map says $want"
            else if (head.none { "Copyright (c) SuperiorSoft LLC" in it }) bad += "$path: no copyright line"
        }
        assertTrue(seen > 0, "no source files found under $root")
        assertTrue(bad.isEmpty(), "Run scripts/license-headers.sh. ${bad.size} file(s) off the license map:\n" + bad.take(40).joinToString("\n"))
    }

    @Test
    fun licenseTextsExist() {
        for (n in listOf("LICENSES/MPL-2.0.txt", "LICENSES/Apache-2.0.txt")) assertTrue(File(root, n).length() > 10_000, "$n missing")
    }
}
