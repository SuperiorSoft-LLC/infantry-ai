// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.Tier
import java.io.File
import java.net.URL
import java.net.URLClassLoader

/**
 * PLUGIN-PLATFORMS-1: the class loader of ONE module (never shared between modules, closed when the module set unloads).
 * Its class path is the module's own [jar] plus every `*.jar` in the module folder's `lib/` (the plugin developer's
 * dependencies), parent the app's loader. Native files: `System.loadLibrary("foo")` called from the module's classes
 * finds `foo.dll` / `libfoo.so` / `libfoo.dylib` in `<module>/native/<os>-<arch>/` (e.g. `windows-amd64`), else
 * `<module>/native/`, else the module folder itself.
 *
 * PLUGIN-ENDPOINTS-1 (D2b): with [filtered] (community modules; OFFICIAL also sees ui.shell and the ui root package, LOADER-1) the parent shows only the plugin API, Kotlin, Compose, `java*` and the
 * plugin-facing `ui.plugin` types (not `DaemonLink`), plus the packages the plugin API's own signatures hand out (ticket-109d3f79):
 * `ui.model` (TabContext.state is an AppState, TabProvider/TabInterceptor titles take one, TabContext.diffTarget), `ui.protocol`
 * (the wire data AppState's fields hold) and `ui.block` (UiRegistrar.shell/inject/fill/tabTree/blockType take its trees). All three
 * are data and pure functions with no daemon link or credential: nothing in them holds a Controller, DaemonLink or token (model's
 * ProjectSettings only names the Controller type in a lambda signature, and Controller stays hidden). `ui.net` (Controller,
 * DaemonLink's HTTP client) and `ui.shell` stay hidden. Any other host class is a ClassNotFoundException. HONEST LIMIT: code in this JVM
 * can still use reflection, files and the environment (owner.token, INFANTRY_TOKEN); this closes the easy paths, not a determined
 * attacker. The trust boundary stays consent (off until enabled), safe mode and the demo lock.
 */
class PluginClassLoader(private val dir: File, jar: File, parent: ClassLoader, filtered: Boolean = false, official: Boolean = false) :
    URLClassLoader(urls(dir, jar), if (filtered) FilteringParent(parent, official) else parent) {
    constructor(dir: File, jar: File, parent: ClassLoader, tier: Tier) : this(dir, jar, parent, tier != Tier.CORE, tier == Tier.OFFICIAL)

    override fun findLibrary(libname: String): String? {
        val file = System.mapLibraryName(libname)
        val os = System.getProperty("os.name").lowercase().let { if ("win" in it) "windows" else if ("mac" in it) "macos" else "linux" }
        val arch = System.getProperty("os.arch").lowercase()
        return listOf(File(dir, "native/$os-$arch"), File(dir, "native"), dir).map { File(it, file) }.firstOrNull { it.isFile }?.absolutePath
            ?: super.findLibrary(libname)
    }

    /** Only what a plugin legitimately needs from the host. */
    private class FilteringParent(private val real: ClassLoader, private val official: Boolean) : ClassLoader(null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!allowed(name)) throw ClassNotFoundException(name)
            return real.loadClass(name)
        }
        override fun getResource(name: String) = if (allowed(name.removeSuffix(".class").replace('/', '.'))) real.getResource(name) else null
        private fun allowed(n: String) = HIDDEN.none { n.startsWith(it) } && (ALLOWED.any { n.startsWith(it) } || n == CONFIG_VALUES || (official && officialAllowed(n)))
    }

    private companion object {
        const val UI = "com.superiorsoftllc.infantry.ui."
        /** LOADER-1: an OFFICIAL jar also sees `ui.shell` and the classes directly in the `ui` package (ShellContext, ChatPanelState, the kit composables).
         *  e063ce77: and exactly the host types the official jars link against (checked with jdeps over all 25 jars): the shell's
         *  `ui.net.Controller` (Header, Chat, Changes, Assistant) and `ImageUploadOutcome` (Board), and `ui.sound` / `ui.voice` (Alerts;
         *  settings and UI only). The rest of `ui.net` (DaemonClient, the HTTP client) and `ui.pair` stay hidden. */
        val OFFICIAL_NET = listOf("net.CommandResult", "net.Controller", "net.ImageUploadOutcome")
        fun officialAllowed(n: String): Boolean {
            if (!n.startsWith(UI)) return false
            val r = n.removePrefix(UI)
            return r.startsWith("shell.") || r.startsWith("sound.") || r.startsWith("voice.") || '.' !in r ||
                OFFICIAL_NET.any { r == it || r.startsWith("$it$") }
        }
        /** CONFIG-7b: TabContext.config / BlockScope.config return this one `ui.shell` type; a plugin must be able to name it. Exact match, nothing else in ui.shell. */
        const val CONFIG_VALUES = "com.superiorsoftllc.infantry.ui.shell.ConfigValues"
        val ALLOWED = listOf("java.", "javax.", "kotlin.", "kotlinx.", "androidx.compose", "org.jetbrains.compose", "org.jetbrains.skiko", "com.superiorsoftllc.infantry.plugin.api.", "com.superiorsoftllc.infantry.ui.plugin.",
            "com.superiorsoftllc.infantry.ui.model.", "com.superiorsoftllc.infantry.ui.protocol.", "com.superiorsoftllc.infantry.ui.block.")
        val HIDDEN = listOf("com.superiorsoftllc.infantry.ui.plugin.DaemonLink", "com.superiorsoftllc.infantry.ui.plugin.PluginClassLoader", "com.superiorsoftllc.infantry.ui.plugin.config.DaemonNodeConfigApi", "com.superiorsoftllc.infantry.ui.plugin.JvmEndpointAccess")
        fun urls(dir: File, jar: File): Array<URL> =
            (listOf(jar) + File(dir, "lib").listFiles { f -> f.isFile && f.name.endsWith(".jar") }.orEmpty().sortedBy { it.name }).map { it.toURI().toURL() }.toTypedArray()
    }
}
