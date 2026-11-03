// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * DEMO-MERGE-1: the demo is a module set (plugins/demo/<Module>), never core. Core (this library) and the official plugins
 * stay deployment-neutral: no code line (comments are skipped) and no module.json may name the demo. Core offers the generic
 * mechanisms the demo plugs into (WritePolicy, `ready` fields, declared notices, block overrides).
 *
 * CORE-CLEAN-1: the SEC-FIX-1 ratchet is closed: zero allowed names (the drop-in lock is the generic "lockdown"). A second
 * guard keeps core from naming a concrete plugin id.
 */
class NoDemoInCoreGuardTest {
    private val demo = Regex("""(?i)\bdemo""")
    private val plugin = Regex("""\b(Infantry|Demo|Acme)_[A-Z]\w*""")

    private fun isComment(line: String): Boolean { val t = line.trimStart(); return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }

    private fun coreRoots() = listOf("src/commonMain", "src/jvmMain", "src/androidMain").map(::File).filter { it.isDirectory }

    private fun scan(root: File) = root.walkTopDown().onEnter { it.name != "build" }.filter { it.isFile && (it.extension == "kt" || it.name == "module.json") }.toList()

    private fun offenders(files: Collection<File>, rule: Regex) = files.mapNotNull { f ->
        val hits = f.readLines().withIndex().filter { !isComment(it.value) && rule.containsMatchIn(it.value) }
        if (hits.isEmpty()) null else "${f.path} (${hits.size}):\n" + hits.joinToString("\n") { "  ${it.index + 1}: ${it.value.trim()}" }
    }

    @Test
    fun `no core or official plugin code names the demo`() {
        val core = coreRoots()
        val official = File("../../plugins/official")
        assertTrue(core.isNotEmpty() && official.isDirectory, "sources not found from ${File(".").absolutePath}")
        val files = core.flatMap(::scan).toSet() + scan(official)
        assertTrue(files.size > 100, "too few files scanned (${files.size})")
        val bad = offenders(files, demo)
        if (bad.isNotEmpty()) fail("the demo belongs in plugins/demo/<Module>, not core or an official plugin:\n" + bad.joinToString("\n"))
    }

    /** CORE-CLEAN-1: core names no concrete plugin (`Infantry_*` ids are plugin directories under plugins/); the app build hands them in as data. */
    @Test
    fun `core names no concrete plugin id`() {
        val files = coreRoots().flatMap(::scan).filter { it.extension == "kt" }
        assertTrue(files.size > 100, "too few files scanned (${files.size})")
        val bad = offenders(files, plugin)
        if (bad.isNotEmpty()) fail("core must stay plugin-neutral:\n" + bad.joinToString("\n"))
    }
}
