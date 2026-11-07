// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import java.io.File
import java.net.URLClassLoader
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/**
 * HARNESS-PLUGIN-1 (docs/HARNESS-PLUGINS.md, step H1): discovers harness plugins in jars OUTSIDE the daemon class path.
 * Each jar gets its own [URLClassLoader] below [parent], so two harness jars never see each other's classes, and only
 * the `META-INF/services/com.superiorsoftllc.infantry.harness.HarnessPlugin` entries of THAT jar count: a plugin the
 * parent already serves is [ServiceLoaderPluginSource]'s, never found twice.
 *
 * Same seam as [ServiceLoaderPluginSource]: the registry still does the version handshake and the atomic commit. A jar
 * that cannot be opened, lists no plugin or lists a broken one becomes a failing [PluginCandidate] labelled with the jar's
 * name, so it surfaces as a plugin failure and never hides the other jars. [jars] is read on every [candidates] call.
 *
 * HARNESS-PLUGIN-3 H3: with a [bridge], the jar's harness API 2.x plugins (`META-INF/services/` + the API's
 * [com.superiorsoftllc.infantry.harness.api.HarnessPlugin]) are found too, each handed to [bridge] (an [ApiHarnessPluginBridge]
 * with the module's host). The daemon loads module jars this way, below a [HarnessApiClassLoader] (H4: `HarnessModules`).
 */
class JarHarnessPluginSource(
    private val jars: () -> List<File>,
    private val parent: ClassLoader = HarnessPlugin::class.java.classLoader,
    private val bridge: ((com.superiorsoftllc.infantry.harness.api.HarnessPlugin) -> HarnessPlugin)? = null,
) : HarnessPluginSource, AutoCloseable {
    private val loaders = java.util.concurrent.CopyOnWriteArrayList<URLClassLoader>()

    /** HARNESS-PLUGIN-3 H4: close every class loader this source opened (releases the jar files; a module unload does this). */
    override fun close() {
        loaders.forEach { runCatching { it.close() } }
        loaders.clear()
    }

    override fun candidates(): List<PluginCandidate> = jars().flatMap(::candidatesOf)

    private fun candidatesOf(jar: File): List<PluginCandidate> {
        val label = "jar:${jar.name}"
        if (!jar.isFile) return listOf(failing(label, IllegalStateException("${jar.path} is not a file")))
        return try {
            // PLUGIN-UPDATE-LOCK-1: a copy of the jar is read, so the module folder can be updated while the harness runs.
            val loader = com.superiorsoftllc.infantry.plugin.host.ShadowJarClassLoader("harness-${jar.name}", jar.parentFile, listOf(jar), parent).also { loaders += it }
            val found = ServiceLoader.load(HarnessPlugin::class.java, loader).stream()
                .filter { it.type().classLoader === loader }
                .map { provider -> PluginCandidate("$label:${provider.type().name}") { provider.get() } }
                .toList() + apiCandidates(label, loader)
            found.ifEmpty { listOf(failing(label, IllegalStateException("${jar.name} lists no HarnessPlugin in META-INF/services"))) }
        } catch (e: ServiceConfigurationError) {
            listOf(failing(label, e))
        } catch (e: Exception) {
            listOf(failing(label, e))
        }
    }

    private fun apiCandidates(label: String, loader: ClassLoader): List<PluginCandidate> {
        val wrap = bridge ?: return emptyList()
        return ServiceLoader.load(com.superiorsoftllc.infantry.harness.api.HarnessPlugin::class.java, loader).stream()
            .filter { it.type().classLoader === loader }
            .map { provider -> PluginCandidate("$label:${provider.type().name}") { wrap(provider.get()) } }
            .toList()
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
