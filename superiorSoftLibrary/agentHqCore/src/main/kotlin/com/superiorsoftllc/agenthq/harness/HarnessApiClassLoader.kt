package com.superiorsoftllc.agenthq.harness

import java.net.URL
import java.util.Collections
import java.util.Enumeration

/**
 * HARNESS-PLUGIN-3 H3 (docs/HARNESS-PLUGINS.md section 3.1): the parent of every harness plugin jar's class loader, the
 * twin of [com.superiorsoftllc.agenthq.daemon.plugins.DaemonApiClassLoader]. A harness plugin sees the JDK, the Kotlin and
 * kotlinx runtimes and the harness API (`harness.api.*`), and NOTHING else of the daemon: not the 1.x harness types, not the
 * runner, not the daemon API. Every other daemon class is a [ClassNotFoundException], every other resource absent.
 *
 * This is an API boundary, not a sandbox: plugin code runs in the daemon's JVM with the daemon's rights (it can use
 * `java.lang.ProcessBuilder` or `java.io.File` directly). What keeps an untrusted harness out is the trust rules
 * (bundled / signed official / granted community) applied BEFORE its jar is opened.
 */
class HarnessApiClassLoader(private val daemon: ClassLoader) : ClassLoader("harness-api", getPlatformClassLoader()) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            runCatching { return parent.loadClass(name) }
            if (!exposed(name)) throw ClassNotFoundException("$name is not part of the harness plugin API")
            return daemon.loadClass(name)
        }
    }

    override fun getResource(name: String): URL? =
        parent.getResource(name) ?: if (exposedResource(name)) daemon.getResource(name) else null

    override fun getResources(name: String): Enumeration<URL> =
        if (exposedResource(name)) daemon.getResources(name) else parent.getResources(name) ?: Collections.emptyEnumeration()

    /** A services file is a resource named after the interface; only the API's own may be seen (and a jar's own, below this loader). */
    private fun exposedResource(name: String): Boolean =
        !name.startsWith("META-INF/services/") && exposed(name.replace('/', '.'))

    companion object {
        /** The packages a harness plugin may load from the daemon's class path. */
        val EXPOSED_PREFIXES: List<String> = listOf(
            "kotlin.",
            "kotlinx.serialization.",
            "kotlinx.coroutines.",
            "com.superiorsoftllc.agenthq.harness.api.",
        )

        fun exposed(name: String): Boolean = EXPOSED_PREFIXES.any { name.startsWith(it) }
    }
}
