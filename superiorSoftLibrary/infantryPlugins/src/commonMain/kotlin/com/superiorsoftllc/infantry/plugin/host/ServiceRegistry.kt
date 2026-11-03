// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.DaemonAccess
import com.superiorsoftllc.infantry.plugin.api.DaemonReply
import com.superiorsoftllc.infantry.plugin.api.EventBus
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ModulesConfig
import com.superiorsoftllc.infantry.plugin.api.PluginEvent
import com.superiorsoftllc.infantry.plugin.api.PluginServices
import com.superiorsoftllc.infantry.plugin.api.ServiceKey
import com.superiorsoftllc.infantry.plugin.api.Services
import com.superiorsoftllc.infantry.plugin.api.Tier

/**
 * PLUGINS-1: the host's service registry, the extension point for platform services. A platform registers a factory
 * per service (built per module, so storage and settings are that module's own); [forModule] hands a module only the
 * services the gate lets through.
 */
class ServiceRegistry(private val config: ModulesConfig = ModulesConfig(), private val log: (String) -> Unit = {}) {
    private val factories = LinkedHashMap<String, (ModuleStatus) -> Any>()

    fun <T : Any> provide(key: ServiceKey<T>, factory: (ModuleStatus) -> T) {
        factories[key.id] = factory
    }

    fun provided(): Set<String> = factories.keys

    fun forModule(module: ModuleStatus): PluginServices = object : PluginServices {
        private val built = HashMap<String, Any>()
        override val module = module.name

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: ServiceKey<T>): T? {
            val manifest = module.manifest ?: return null
            val refusal = ServiceGate.refusal(key.id, manifest, module.tier, config)
            if (refusal != null) {
                log("[plugins] ${module.name}: service ${key.id} refused: $refusal")
                return null
            }
            val factory = factories[key.id] ?: return null
            return built.getOrPut(key.id) { factory(module) } as T
        }
    }
}

/** The service rules: declared in module.json, not denied by the owner, and for a community module, allowed. */
object ServiceGate {
    fun refusal(id: String, manifest: ModuleManifest, tier: Tier, config: ModulesConfig): String? {
        val setting = config.setting(manifest.name)
        return when {
            id !in manifest.services -> "not declared in module.json"
            id in setting.deny -> "denied in modules.json"
            tier == Tier.COMMUNITY && id !in setting.allow -> "a community module needs it allowed in modules.json"
            else -> null
        }
    }
}

/**
 * CONFIG-FIELDS-3 (G8): who may read another node's config. A core or official module reads any node (no change today). A
 * community module reads its own nodes (`<name>` and `<name>.*`, plus the ids it declares) freely and any other node only
 * with the `config.read` service: declared in module.json `services` and allowed in modules.json (default deny).
 */
object ConfigReadGate {
    const val SERVICE = "config.read"

    fun allows(module: String, manifest: ModuleManifest?, tier: Tier, config: ModulesConfig, node: String): Boolean = when {
        !config.configReadGate || tier != Tier.COMMUNITY -> true
        node == module || node.startsWith("$module.") || manifest?.config?.any { it.node == node } == true -> true
        else -> manifest != null && ServiceGate.refusal(SERVICE, manifest, tier, config) == null
    }
}

/** The daemon rule: a route must be declared, and under the lockdown also allowed by the lock. Null = may call. */
object RouteGate {
    fun refusal(route: String, declared: List<String>, demoLock: Boolean, config: ModulesConfig): String? = when {
        route !in declared -> "route '$route' is not declared in module.json"
        demoLock && route !in config.demoLock.allowRoutes -> "route '$route' is not allowed under the lockdown"
        else -> null
    }

    /** Wraps a platform's raw daemon call in the rule for one module. */
    fun gated(manifest: ModuleManifest, demoLock: Boolean, config: ModulesConfig, send: suspend (route: String, body: String?) -> DaemonReply) =
        DaemonAccess { route, body ->
            refusal(route, manifest.routes, demoLock, config)?.let { DaemonReply.Refused(it) } ?: send(route, body)
        }
}

/** A plain in-process event bus. Observers run on the publisher's thread; one that throws is skipped, not fatal. */
class SimpleEventBus(private val log: (String) -> Unit = {}) : EventBus {
    private val observers = mutableListOf<Pair<String, (PluginEvent) -> Unit>>()

    override fun publish(event: PluginEvent) {
        val now = observers.filter { it.first == event.name }
        for ((_, o) in now) runCatching { o(event) }.onFailure { log("[plugins] observer of ${event.name} failed: ${it.message}") }
    }

    override fun subscribe(name: String, observer: (PluginEvent) -> Unit): () -> Unit {
        val entry = name to observer
        observers += entry
        return { observers.remove(entry) }
    }

    /** S7: every observer is dropped (the module set that subscribed them is unloaded). Later publishes reach nobody. */
    fun clear() { observers.clear() }
}

/** One module's view of the bus: it may publish and observe only the events it declared. */
class GatedEventBus(private val bus: EventBus, private val module: String, private val declared: List<String>, private val log: (String) -> Unit = {}) : EventBus {
    override fun publish(event: PluginEvent) {
        if (event.name in declared) bus.publish(event) else log("[plugins] $module: publish ${event.name} refused: not declared")
    }

    override fun subscribe(name: String, observer: (PluginEvent) -> Unit): () -> Unit {
        if (name !in declared) {
            log("[plugins] $module: observe $name refused: not declared")
            return {}
        }
        return bus.subscribe(name, observer)
    }
}

/** Registers the services every platform has: the event bus (each module sees only its declared events). */
fun ServiceRegistry.provideEvents(bus: EventBus, log: (String) -> Unit = {}) =
    provide(Services.EVENTS) { s -> GatedEventBus(bus, s.name, s.manifest?.events.orEmpty(), log) }
