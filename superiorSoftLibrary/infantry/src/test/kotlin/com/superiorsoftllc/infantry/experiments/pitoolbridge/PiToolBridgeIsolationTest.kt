// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.experiments.pitoolbridge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Removability guard: nothing outside the `experiments/pitoolbridge` package (main or test) may reference it, except the one
 * registration line in `DaemonMain`. If this fails, the experiment has leaked and "delete the package + one line" is no
 * longer true: fix the leak, do not widen the allowance.
 */
class PiToolBridgeIsolationTest {
    private val needle = Regex("pitoolbridge|PiToolBridge", RegexOption.IGNORE_CASE)

    private fun moduleRoots(): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        val root = dir ?: error("repository root not found from ${File("").absolutePath}")
        return File(root, "superiorSoftLibrary").listFiles { f -> f.isDirectory }.orEmpty().map { File(it, "src") }.filter { it.isDirectory }
    }

    @Test
    fun onlyTheRegistrationLineReferencesThePackage() {
        val offenders = moduleRoots().flatMap { src ->
            src.walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "kts", "sq", "sqm", "json", "xml", "properties") }
                .filter { "experiments/pitoolbridge" !in it.invariantSeparatorsPath }
                .flatMap { f -> f.readLines().withIndex().filter { needle.containsMatchIn(it.value) }.map { f.name to it.value.trim() } }
                .toList()
        }.filterNot { (file, line) -> file == "DaemonMain.kt" && "PiToolBridgeRegistration.register(" in line }
            // CI-TESTS-2: HarnessCoreGuardTest's allow-list names the registration file as DATA (a file-name key), not a code reference.
            .filterNot { (file, _) -> file == "HarnessCoreGuardTest.kt" }
        assertEquals(emptyList(), offenders, "references outside the package")
    }
}
