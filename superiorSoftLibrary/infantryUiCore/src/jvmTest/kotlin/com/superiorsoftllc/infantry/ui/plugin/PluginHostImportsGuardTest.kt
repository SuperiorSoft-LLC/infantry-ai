// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.Tier
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PLUGIN-LOADER-UIPAIR-1 (ticket 73e9c106): every host `com.superiorsoftllc.infantry.ui.*` name a plugin's runtime sources
 * (`src/`, not `androidSrc/` or `test/`) reference must load through the filtered [PluginClassLoader] of the tier its folder gets
 * (`ModulesConfig.DEFAULT_FOLDERS`: `official` is OFFICIAL, `community` is COMMUNITY; `examples` and `_template` are starters a
 * developer copies into `community`, so COMMUNITY too). A hidden host class is a NoClassDefFoundError on first use; inside
 * composition that kills the recomposer and freezes the window (5fd5ba18 was `ui.progress`). The fix is never to drop the name
 * from this scan: either the plugin stops naming the class, or the class is plugin-safe and the loader allows it.
 *
 * A name is checked when it is a host class on this test's class path: an imported or fully qualified class (nested `A.B` is
 * `A$B`), or a top-level function / property, which lives in its file's `<File>Kt` class. A `const val` is inlined by the
 * compiler and never loaded, so it is skipped. Names the host does not define (the plugin's own, or another module's) are skipped.
 * Static, not run in a window: an unused import is flagged too (remove it; it costs nothing).
 */
class PluginHostImportsGuardTest {
    private val repo = File("../..")
    private val hostSources = listOf("commonMain", "jvmMain").map { File("src/$it/kotlin") }
    private val ref = Regex("""com\.superiorsoftllc\.infantry\.ui(?:\.[A-Za-z_][A-Za-z0-9_]*)+""")

    @Test
    fun `every host ui name a plugin references loads through its tier's filtered loader`() {
        val folders = mapOf("official" to Tier.OFFICIAL, "community" to Tier.COMMUNITY, "examples" to Tier.COMMUNITY, "_template" to Tier.COMMUNITY)
        val tmp = Files.createTempDirectory("plugin-imports").toFile()
        val failures = mutableListOf<String>()
        var checked = 0
        for ((folder, tier) in folders) {
            val root = File(repo, "plugins/$folder")
            val sources = root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.relativeTo(root).invariantSeparatorsPath.split('/').getOrNull(1) == "src" }
                .toList()
            if (folder == "official") assertTrue(sources.isNotEmpty(), "official plugin sources not found under ${root.absolutePath}")
            PluginClassLoader(tmp, File(tmp, "empty.jar"), javaClass.classLoader, tier).use { loader ->
                for (file in sources) for (name in ref.findAll(file.readText()).map { it.value }.toSet()) {
                    val cls = hostClass(name) ?: continue
                    checked++
                    val ok = runCatching { loader.loadClass(cls) }.isSuccess
                    if (!ok) failures += "${file.relativeTo(repo).invariantSeparatorsPath}: $name ($cls) is hidden from a $tier plugin"
                }
            }
        }
        assertTrue(checked > 0, "no host ui reference found in plugin sources: the scan is broken")
        assertTrue(failures.isEmpty(), "plugins reference host classes their loader hides (NoClassDefFoundError on first use):\n" + failures.joinToString("\n"))
    }

    /** The host class [name] resolves to, or null when the host defines no such class or top-level declaration. */
    private fun hostClass(name: String): String? {
        val parts = name.split('.')
        val firstUpper = parts.indexOfFirst { it.first().isUpperCase() }
        if (firstUpper >= 0) {
            val cls = parts.take(firstUpper + 1).joinToString(".") + parts.drop(firstUpper + 1).takeWhile { it.first().isUpperCase() }.joinToString("") { "$$it" }
            return cls.takeIf { exists(it) }
        }
        val pkg = parts.dropLast(1).joinToString(".")
        val member = parts.last()
        val decl = Regex("""^(?:(?:public|internal)\s+)?(?:inline\s+)?(const\s+)?(?:fun|val|var)\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_.<>?, ]+\.)?${Regex.escape(member)}\b""", RegexOption.MULTILINE)
        for (src in hostSources) {
            val dir = File(src, pkg.replace('.', '/'))
            for (f in dir.listFiles { f -> f.isFile && f.extension == "kt" }.orEmpty()) {
                val text = f.readText()
                val m = decl.find(text) ?: continue
                if (m.groupValues[1].isNotEmpty()) return null // const val: inlined, never loaded
                val jvmName = Regex("""@file:JvmName\("([^"]+)"\)""").find(text)?.groupValues?.get(1) ?: (f.nameWithoutExtension + "Kt")
                return "$pkg.$jvmName".takeIf { exists(it) }
            }
        }
        return null
    }

    private fun exists(cls: String) = runCatching { Class.forName(cls, false, javaClass.classLoader) }.isSuccess
}
