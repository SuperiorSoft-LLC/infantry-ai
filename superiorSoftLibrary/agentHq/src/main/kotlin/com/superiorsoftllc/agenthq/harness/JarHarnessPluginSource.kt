package com.superiorsoftllc.agenthq.harness

import java.io.File
import java.net.URLClassLoader
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/**
 * HARNESS-PLUGIN-1 (docs/HARNESS-PLUGINS.md, step H1): discovers harness plugins in jars OUTSIDE the daemon class path.
 * Each jar gets its own [URLClassLoader] below [parent], so two harness jars never see each other's classes, and only
 * the `META-INF/services/com.superiorsoftllc.agenthq.harness.HarnessPlugin` entries of THAT jar count: a plugin the
 * parent already serves is [ServiceLoaderPluginSource]'s, never found twice.
 *
 * Same seam as [ServiceLoaderPluginSource]: the registry still does the version handshake and the atomic commit. A jar
 * that cannot be opened, lists no plugin or lists a broken one becomes a failing [PluginCandidate] labelled with the jar's
 * name, so it surfaces as a plugin failure and never hides the other jars. [jars] is read on every [candidates] call.
 *
 * Not wired into the daemon yet: trust (bundled / official / community grants) and the module.json `harness` block come
 * with step H2, through the module resolver the daemon halves already use.
 */
class JarHarnessPluginSource(
    private val jars: () -> List<File>,
    private val parent: ClassLoader = HarnessPlugin::class.java.classLoader,
) : HarnessPluginSource {

    override fun candidates(): List<PluginCandidate> = jars().flatMap(::candidatesOf)

    private fun candidatesOf(jar: File): List<PluginCandidate> {
        val label = "jar:${jar.name}"
        if (!jar.isFile) return listOf(failing(label, IllegalStateException("${jar.path} is not a file")))
        return try {
            val loader = URLClassLoader("harness-${jar.name}", arrayOf(jar.toURI().toURL()), parent)
            val found = ServiceLoader.load(HarnessPlugin::class.java, loader).stream()
                .filter { it.type().classLoader === loader }
                .map { provider -> PluginCandidate("$label:${provider.type().name}") { provider.get() } }
                .toList()
            found.ifEmpty { listOf(failing(label, IllegalStateException("${jar.name} lists no HarnessPlugin in META-INF/services"))) }
        } catch (e: ServiceConfigurationError) {
            listOf(failing(label, e))
        } catch (e: Exception) {
            listOf(failing(label, e))
        }
    }

    private fun failing(label: String, cause: Throwable) = PluginCandidate(label) { throw cause }

    companion object {
        /** Every `*.jar` directly in [dir], by name; a missing folder yields none. */
        fun folder(dir: File, parent: ClassLoader = HarnessPlugin::class.java.classLoader): JarHarnessPluginSource =
            JarHarnessPluginSource(
                { dir.listFiles { f -> f.isFile && f.name.endsWith(".jar", ignoreCase = true) }?.sortedBy { it.name }.orEmpty() },
                parent,
            )
    }
}
