package com.superiorsoftllc.agenthq.harness

import java.util.ServiceLoader
import java.util.ServiceConfigurationError

/**
 * HARNESS 2/4 - what a third party ships. A plugin REGISTERS its own harnesses; the daemon
 * (see [HarnessRegistry]) only DISCOVERS plugins. The daemon never names a harness, so adding one
 * never touches daemon code.
 *
 * A plugin is found through a [HarnessPluginSource]; the standard JVM one is [ServiceLoaderPluginSource]
 * (list the implementing class in `META-INF/services/com.superiorsoftllc.agenthq.harness.HarnessPlugin`).
 */
interface HarnessPlugin {
    /** Stable, unique, non-blank. Unloading and failure reports address a plugin by this id. */
    val pluginId: String

    /**
     * The [HARNESS_CONTRACT_VERSION] this plugin was BUILT against. There is deliberately NO default:
     * a default getter would be evaluated by the daemon's copy of the interface at run time and would
     * always report the daemon's own version, making the version handshake pass vacuously. A plugin
     * must state it itself - `override val contractVersion = HARNESS_CONTRACT_VERSION` - so the
     * `const` is inlined when the PLUGIN is compiled.
     */
    val contractVersion: String

    /** Hand every harness this plugin provides to [registrar]. Called once, only after the version check passed. */
    fun register(registrar: HarnessRegistrar)

    /** Release whatever this plugin holds. Called once, after the plugin's harnesses were already removed. */
    fun unload() {}
}

/** Only valid for the duration of [HarnessPlugin.register]; using it later fails loudly. */
interface HarnessRegistrar {
    fun register(harness: Harness)
}

/** One plugin the daemon found. [create] is lazy so a plugin whose construction throws is still attributable by [label]. */
class PluginCandidate(val label: String, val create: () -> HarnessPlugin)

/** Where the daemon looks for plugins. Replaceable so discovery is not welded to one mechanism. */
fun interface HarnessPluginSource {
    fun candidates(): List<PluginCandidate>
}

/** Discovers plugins the standard JVM way: `META-INF/services` entries visible to [classLoader]. */
class ServiceLoaderPluginSource(
    private val classLoader: ClassLoader =
        Thread.currentThread().contextClassLoader ?: HarnessPlugin::class.java.classLoader,
) : HarnessPluginSource {
    override fun candidates(): List<PluginCandidate> {
        val found = ArrayList<PluginCandidate>()
        try {
            ServiceLoader.load(HarnessPlugin::class.java, classLoader).stream().forEach { provider ->
                found += PluginCandidate(provider.type().name) { provider.get() }
            }
        } catch (e: ServiceConfigurationError) {
            // A malformed services entry must surface as a plugin failure, not abort or vanish.
            found += PluginCandidate("ServiceLoader<HarnessPlugin>") { throw e }
        }
        return found
    }
}
