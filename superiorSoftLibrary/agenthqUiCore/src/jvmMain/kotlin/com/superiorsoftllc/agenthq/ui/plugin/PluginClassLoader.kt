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
 */
class PluginClassLoader(private val dir: File, jar: File, parent: ClassLoader) : URLClassLoader(urls(dir, jar), parent) {
    override fun findLibrary(libname: String): String? {
        val file = System.mapLibraryName(libname)
        val os = System.getProperty("os.name").lowercase().let { if ("win" in it) "windows" else if ("mac" in it) "macos" else "linux" }
        val arch = System.getProperty("os.arch").lowercase()
        return listOf(File(dir, "native/$os-$arch"), File(dir, "native"), dir).map { File(it, file) }.firstOrNull { it.isFile }?.absolutePath
            ?: super.findLibrary(libname)
    }

    private companion object {
        fun urls(dir: File, jar: File): Array<URL> =
            (listOf(jar) + File(dir, "lib").listFiles { f -> f.isFile && f.name.endsWith(".jar") }.orEmpty().sortedBy { it.name }).map { it.toURI().toURL() }.toTypedArray()
    }
}
