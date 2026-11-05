// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SCRIPTING-NCDFE-1: a daemon half runs on its own class loader over [DaemonApiClassLoader], which links only the exposed
 * packages. A daemon half that names any other app class must carry it in its own jar (a `shaded(project(...))` in its
 * build), or the first call that touches it throws NoClassDefFoundError and the host marks the whole plugin failed. That is
 * what killed Infantry_Scripting: `infantry_script_list` called core's `DisplayZone`, compiled fine, failed at run time.
 * And core's `DisplayZone` is never right in a plugin even when shaded: the copy never sees the `display.timeZone` setting.
 */
class PluginClassPathGuardTest {
    private val root: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "plugins/official").isDirectory && File(dir, "superiorSoftLibrary").isDirectory) return@lazy dir
            dir = dir.parentFile
        }
        error("repository root not found")
    }

    private val pkg = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
    /** An app type a source names: `com.superiorsoftllc.infantry.<lower segments>.<Type>`, by import or fully qualified. */
    private val ref = Regex("""\bcom\.superiorsoftllc\.infantry\.((?:[a-z_][\w]*\.)*)([A-Z]\w*)""")
    private val shadedProject = Regex("""shaded\(\s*project\(\s*"([^"]+)"\s*\)""")

    /** Comments name classes too (KDoc links); only code links. Block comments, then line comments (not `://` in strings). */
    private fun code(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), " ").replace(Regex("""(?m)(^|[^:"])//.*$"""), "$1")

    /**
     * Names that link with no class at run time: a `const val` is inlined by the compiler, so the class is never loaded.
     * Each entry says why; add one only for a constant.
     */
    private val inlined = setOf(
        "com.superiorsoftllc.infantry.protocol.EventVocabulary", // Infantry_Updater reads EventVocabulary.VERSION, a const val
    )

    /** A `shaded(project(...))` path as a source folder: `:a:b` -> `a/b`, `:daemon-plugin-<tier>-<Module>` -> its daemon half. */
    private fun sourcesOf(path: String): File {
        val half = Regex("""^:(daemon|harness)-plugin-([^-]+)-(.+)$""").find(path)
        return if (half != null) File(root, "plugins/${half.groupValues[2]}/${half.groupValues[3]}/${half.groupValues[1]}/src/main")
        else File(root, path.trimStart(':').replace(':', '/') + "/src/main")
    }

    private fun kt(dir: File): List<File> = if (!dir.isDirectory) emptyList() else dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun packages(files: List<File>): Set<String> = files.mapNotNull { f -> pkg.find(f.readText())?.groupValues?.get(1) }.toSet()

    private fun daemonHalves(): List<File> = File(root, "plugins").listFiles().orEmpty().filter { it.isDirectory }
        .flatMap { tier -> tier.listFiles().orEmpty().filter { File(it, "daemon/src/main").isDirectory }.map { File(it, "daemon") } }

    @Test fun `every app class a daemon half names is exposed by the plugin class loader or shaded into its jar`() {
        val halves = daemonHalves()
        assertTrue(halves.size > 3, "no daemon halves found under ${root.path}/plugins")
        val offenders = halves.flatMap { half ->
            val sources = kt(File(half, "src/main"))
            val build = File(half, "build.gradle.kts").takeIf { it.isFile }?.readText().orEmpty()
            val shaded = shadedProject.findAll(build).map { it.groupValues[1] }
                .map { sourcesOf(it) }.flatMap { kt(it) }.toList()
            val linkable = packages(sources) + packages(shaded)
            sources.flatMap { f ->
                ref.findAll(code(f.readText())).mapNotNull { m ->
                    val p = "com.superiorsoftllc.infantry." + m.groupValues[1].trimEnd('.')
                    val name = "$p.${m.groupValues[2]}"
                    if (DaemonApiClassLoader.exposed(name) || p in linkable || name in inlined) null else "${half.parentFile.name}/${f.name}: $name"
                }.toList()
            }
        }.distinct()
        assertTrue(offenders.isEmpty(), "daemon halves name app classes their class loader cannot link (use the DaemonApi, or shade the module): $offenders")
    }

    @Test fun `no daemon half uses core's DisplayZone (a plugin formats times with DaemonApi displayStamp)`() {
        val offenders = daemonHalves().flatMap { kt(File(it, "src/main")) }
            .filter { Regex("""\bcom\.superiorsoftllc\.infantry\.DisplayZone\b""").containsMatchIn(code(it.readText())) }.map { it.name }
        assertTrue(offenders.isEmpty(), "DisplayZone in a daemon half (never sees display.timeZone; not linkable unshaded): $offenders")
    }
}
