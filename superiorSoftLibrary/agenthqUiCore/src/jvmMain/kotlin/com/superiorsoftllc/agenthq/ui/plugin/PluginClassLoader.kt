package com.superiorsoftllc.agenthq.ui.plugin

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
 * PLUGIN-ENDPOINTS-1 (D2b): with [filtered] (community modules) the parent shows only the plugin API, Kotlin, Compose, `java*` and the
 * plugin-facing `ui.plugin` types (not `DaemonLink`); any other host class is a ClassNotFoundException. HONEST LIMIT: code in this JVM
 * can still use reflection, files and the environment (owner.token, AGENTHQ_TOKEN); this closes the easy paths, not a determined
 * attacker. The trust boundary stays consent (off until enabled), safe mode and the demo lock.
 */
class PluginClassLoader(private val dir: File, jar: File, parent: ClassLoader, filtered: Boolean = false) :
    URLClassLoader(urls(dir, jar), if (filtered) FilteringParent(parent) else parent) {
    override fun findLibrary(libname: String): String? {
        val file = System.mapLibraryName(libname)
        val os = System.getProperty("os.name").lowercase().let { if ("win" in it) "windows" else if ("mac" in it) "macos" else "linux" }
        val arch = System.getProperty("os.arch").lowercase()
        return listOf(File(dir, "native/$os-$arch"), File(dir, "native"), dir).map { File(it, file) }.firstOrNull { it.isFile }?.absolutePath
            ?: super.findLibrary(libname)
    }

    /** Only what a plugin legitimately needs from the host. */
    private class FilteringParent(private val real: ClassLoader) : ClassLoader(null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!allowed(name)) throw ClassNotFoundException(name)
            return real.loadClass(name)
        }
        override fun getResource(name: String) = if (allowed(name.removeSuffix(".class").replace('/', '.'))) real.getResource(name) else null
        private fun allowed(n: String) = (ALLOWED.any { n.startsWith(it) } && HIDDEN.none { n.startsWith(it) })
    }

    private companion object {
        val ALLOWED = listOf("java.", "javax.", "kotlin.", "kotlinx.", "androidx.compose", "org.jetbrains.compose", "org.jetbrains.skiko", "com.superiorsoftllc.agenthq.plugin.api.", "com.superiorsoftllc.agenthq.ui.plugin.")
        val HIDDEN = listOf("com.superiorsoftllc.agenthq.ui.plugin.DaemonLink", "com.superiorsoftllc.agenthq.ui.plugin.PluginClassLoader", "com.superiorsoftllc.agenthq.ui.plugin.config.DaemonNodeConfigApi", "com.superiorsoftllc.agenthq.ui.plugin.JvmEndpointAccess")
        fun urls(dir: File, jar: File): Array<URL> =
            (listOf(jar) + File(dir, "lib").listFiles { f -> f.isFile && f.name.endsWith(".jar") }.orEmpty().sortedBy { it.name }).map { it.toURI().toURL() }.toTypedArray()
    }
}
