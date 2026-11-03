package com.superiorsoftllc.infantry.daemon.plugins

import java.net.URL
import java.util.Collections
import java.util.Enumeration

/**
 * DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md section 2): the parent of every daemon plugin's class loader. A plugin sees the
 * JDK, the Kotlin and kotlinx runtimes, the daemon API and the plugin SDK's data model, and NOTHING else of the daemon:
 * every other class name is a [ClassNotFoundException], every other resource absent. Each plugin's own jar sits on its
 * own [java.net.URLClassLoader] below this one, so two plugins never see each other's classes either.
 */
class DaemonApiClassLoader(private val daemon: ClassLoader) : ClassLoader("daemon-api", getPlatformClassLoader()) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            // The platform loader (the parent) holds java.*, javax.*, jdk.*: tried first, no daemon class is there.
            runCatching { return parent.loadClass(name) }
            if (!exposed(name)) throw ClassNotFoundException("$name is not part of the daemon plugin API")
            return daemon.loadClass(name)
        }
    }

    override fun getResource(name: String): URL? =
        parent.getResource(name) ?: if (exposedResource(name)) daemon.getResource(name) else null

    override fun getResources(name: String): Enumeration<URL> =
        if (exposedResource(name)) daemon.getResources(name) else parent.getResources(name) ?: Collections.emptyEnumeration()

    private fun exposedResource(name: String): Boolean = exposed(name.replace('/', '.'))

    companion object {
        /** The packages a plugin may load from the daemon's class path. */
        val EXPOSED_PREFIXES: List<String> = listOf(
            "kotlin.",
            "kotlinx.serialization.",
            "kotlinx.coroutines.",
            "com.superiorsoftllc.infantry.daemon.api.",
            "com.superiorsoftllc.infantry.plugin.api.",
        )

        fun exposed(name: String): Boolean = EXPOSED_PREFIXES.any { name.startsWith(it) }
    }
}
