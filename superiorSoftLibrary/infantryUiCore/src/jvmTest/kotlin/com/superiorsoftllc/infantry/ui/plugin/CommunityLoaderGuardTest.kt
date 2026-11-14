// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.Tier
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CARD-LIVE-2: every official plugin's UI jar, loaded through the COMMUNITY [PluginClassLoader], must not name a host
 * `ui.*` class that loader hides. The catalog installs plugins at the community tier; a hidden host class is a
 * NoClassDefFoundError at first draw (Infantry_Mascot: avatar seam, Infantry_News: Palette / ClickablesKt), invisible when the
 * same plugin runs as OFFICIAL. A plugin that really needs OFFICIAL-only host classes (shell, controller) is on [OFFICIAL_ONLY]
 * with its reason: it can never be catalog-installed, so it is exempt. Anything else fails: either the plugin stops naming the
 * class, or the class is plugin-safe and the loader allows it (as in CARD-LIVE-1). Does not run in a window: static, over the
 * class files' constant pools (method bodies included).
 */
class CommunityLoaderGuardTest {
    private val hostRef = Regex("com/superiorsoftllc/infantry/ui/[A-Za-z0-9_/$]*[A-Za-z0-9_]")

    /** Plugins that need host classes only the OFFICIAL tier sees (not catalog-installable). */
    private val OFFICIAL_ONLY = setOf(
        // Shell / tab plugins bundled with the app: they draw with ui.shell and the kit and are never catalog-installed.
        "Infantry_Agents", "Infantry_Alerts", "Infantry_Appearance", "Infantry_Approvals", "Infantry_Assistant", "Infantry_Attention", "Infantry_Board", "Infantry_Changes", "Infantry_Chat", "Infantry_ChatFeed", "Infantry_Epics", "Infantry_Git", "Infantry_Header", "Infantry_Notes", "Infantry_Notify", "Infantry_Pace", "Infantry_Pods", "Infantry_Processes", "Infantry_Profiles", "Infantry_Prompts", "Infantry_Review", "Infantry_Roles", "Infantry_Run", "Infantry_Runners", "Infantry_Settings", "Infantry_Skills", "Infantry_Stats", "Infantry_Triggers", "Infantry_Updater", "Infantry_Usage",
    )

    @Test
    fun `no official plugin names a host class the community loader hides`() {
        val dirs = (System.getProperty("official.module.dirs") ?: error("official.module.dirs not set: run through Gradle jvmTest"))
            .split(File.pathSeparator).map(::File)
        val tmp = Files.createTempDirectory("community-guard").toFile()
        val failures = mutableListOf<String>()
        var checked = 0
        try {
            for (src in dirs) {
                val jar = File(src, "build/libs/${src.name}.jar")
                assertTrue(jar.isFile, "${src.name}: ${jar.path} not built")
                val own: Set<String>
                val refs: List<Pair<String, String>>
                JarFile(jar).use { j ->
                    val names = j.entries().toList().map { it.name }.filter { it.endsWith(".class") && !it.startsWith("META-INF/") }
                    own = names.map { it.removeSuffix(".class") }.toSet()
                    refs = names.flatMap { n -> hostRef.findAll(String(j.getInputStream(j.getJarEntry(n)).readBytes(), Charsets.ISO_8859_1)).map { it.value to n } }
                }
                val hidden = mutableListOf<String>()
                PluginClassLoader(File(tmp, src.name).also { it.mkdirs() }, jar, javaClass.classLoader, Tier.COMMUNITY).use { loader ->
                    for ((ref, from) in refs.filter { it.first !in own }.distinctBy { it.first }) {
                        val cls = ref.replace('/', '.')
                        if (runCatching { Class.forName(cls, false, javaClass.classLoader) }.isFailure) continue // not a host class
                        checked++
                        if (runCatching { loader.loadClass(cls) }.isFailure) hidden += "$cls (in $from)"
                    }
                }
                if (hidden.isNotEmpty() && src.name !in OFFICIAL_ONLY) failures += "${src.name}: ${hidden.joinToString("; ")}"
                if (hidden.isEmpty() && src.name in OFFICIAL_ONLY) failures += "${src.name}: is on OFFICIAL_ONLY but names no hidden class; remove it from the list"
            }
        } finally { tmp.deleteRecursively() }
        assertTrue(checked > 0, "no host ui reference found in any official jar: the scan is broken")
        assertTrue(failures.isEmpty(), "plugins name host classes the COMMUNITY loader hides (NoClassDefFoundError once catalog-installed):\n" + failures.joinToString("\n"))
    }
}
