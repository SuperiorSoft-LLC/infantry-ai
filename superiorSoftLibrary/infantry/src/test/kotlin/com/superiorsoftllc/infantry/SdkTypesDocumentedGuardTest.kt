// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * AI-FIX-7: the docs cannot fall behind the SDK. `DocsFreshnessGuardTest` catches a documented type that was removed;
 * this guard catches a public top-level type that was added to an SDK module and never named in its API page.
 *
 * - infantryPlugins -> docs/wiki/plugin-api.md
 * - infantryDaemonApi -> docs/wiki/daemon-plugin-api.md
 * - infantryHarnessApi -> docs/wiki/Harnesses.md
 *
 * A type counts as documented when its name appears as a whole word anywhere in the page. A type that is public only so
 * the modules can share it goes on [allowList] with the reason.
 */
class SdkTypesDocumentedGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "AGENTS.md").isFile }

    private val pages = mapOf(
        "infantryPlugins" to "docs/wiki/plugin-api.md",
        "infantryDaemonApi" to "docs/wiki/daemon-plugin-api.md",
        "infantryHarnessApi" to "docs/wiki/Harnesses.md",
    )

    /** Public-but-internal helpers a plugin author never needs to name. Each entry carries its reason. */
    private val allowList: Map<String, String> = emptyMap()

    private val decl = Regex(
        """^(?:(?:public|data|sealed|enum|abstract|open|annotation|value|fun|expect|actual|inline|external)\s+)*(?:class|interface|object|typealias)\s+([A-Z][A-Za-z0-9_]*)"""
    )

    private fun publicTypes(module: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        File(root, "superiorSoftLibrary/$module/src").listFiles { f -> f.isDirectory && f.name.endsWith("Main", true) || f.name == "main" }.orEmpty()
            .forEach { src ->
                src.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                    f.readLines().forEach { line ->
                        // top level only (column 0); private/internal declarations do not start with a modifier matched above
                        decl.find(line)?.let { out.putIfAbsent(it.groupValues[1], f.name) }
                    }
                }
            }
        return out
    }

    @Test
    fun everyPublicSdkTypeIsNamedInItsPage() {
        val missing = mutableListOf<String>()
        for ((module, page) in pages) {
            val text = File(root, page).readText()
            for ((type, file) in publicTypes(module)) {
                if (type in allowList) continue
                if (!Regex("""\b${Regex.escape(type)}\b""").containsMatchIn(text)) missing += "$type ($module, $file) is not named in $page"
            }
        }
        assertTrue(missing.isEmpty(), "Public SDK types missing from their API page (document them, or add to allowList with a reason):\n" + missing.joinToString("\n"))
    }

    @Test
    fun allowListEntriesStillExist() {
        val all = pages.keys.flatMap { publicTypes(it).keys }.toSet()
        val stale = allowList.keys.filter { it !in all }
        assertTrue(stale.isEmpty(), "allowList names types that no longer exist: $stale")
    }
}
