// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * AR-5: the daemon's typed service registry (docs/DAEMON-CORE.md section 8). Core queries it directly; a plugin reaches it
 * through [com.superiorsoftllc.infantry.daemon.api.ServicesApi]. One provider per interface; a missing provider is null.
 */
class ServiceRegistry {
    /**
     * One provider: [impl] for a plain one, [factory] for a caller-scoped one (TRIG-API-1), whose instances are cached per caller
     * module id in [perCaller].
     */
    private class Entry(val owner: String, val impl: Any?, val factory: ((String) -> Any)? = null) {
        val perCaller = ConcurrentHashMap<String, Any>()

        /** Drops [caller]'s instance (only if it is still [instance]) and closes it when it is closeable. */
        fun evict(caller: String, instance: Any) {
            if (perCaller.remove(caller, instance)) (instance as? AutoCloseable)?.let { runCatching { it.close() } }
        }

        fun evictAll() = perCaller.entries.toList().forEach { (caller, instance) -> evict(caller, instance) }
    }
    private val entries = ConcurrentHashMap<KClass<*>, Entry>()

    private fun register(type: KClass<*>, e: Entry): AutoCloseable {
        check(entries.putIfAbsent(type, e) == null) { "a provider of ${type.qualifiedName} is already registered by ${entries[type]?.owner}" }
        return AutoCloseable { if (entries.remove(type, e)) e.evictAll() }
    }

    /** Registers [impl] for [type] on behalf of [owner]; the handle removes it (only if it is still this provider). */
    fun <T : Any> provide(owner: String, type: KClass<T>, impl: T): AutoCloseable = register(type, Entry(owner, impl))

    /**
     * TRIG-API-1: registers a caller-scoped provider of [type] on behalf of [owner]: [factory] gets the finding module's id, once per
     * caller (cached). The handle removes the provider and drops (closes) every cached instance.
     */
    fun <T : Any> provideScoped(owner: String, type: KClass<T>, factory: (callerModuleId: String) -> T): AutoCloseable =
        register(type, Entry(owner, null, factory))

    /**
     * The provider of [type] as [caller] sees it, or null. A plain provider is the same object for everyone; a scoped one is the
     * caller's own instance, created on its first lookup, when [onCreated] receives the handle that drops it again (the module host
     * closes it when [caller] unloads).
     */
    fun <T : Any> find(type: KClass<T>, caller: String = CORE_CALLER, onCreated: (AutoCloseable) -> Unit = {}): T? {
        val e = entries[type] ?: return null
        val factory = e.factory ?: return e.impl?.let { type.javaObjectType.cast(it) }
        var created = false
        val instance = e.perCaller.computeIfAbsent(caller) { created = true; factory(it) }
        if (created) onCreated(AutoCloseable { e.evict(caller, instance) })
        return type.javaObjectType.cast(instance)
    }

    private val endpoints = ConcurrentHashMap<String, Entry>()

    fun publishEndpoint(owner: String, id: String, endpoint: com.superiorsoftllc.infantry.daemon.api.ServiceEndpoint): AutoCloseable {
        require(id.isNotBlank()) { "an endpoint id must not be blank" }
        val e = Entry(owner, endpoint)
        check(endpoints.putIfAbsent(id, e) == null) { "an endpoint $id is already published by ${endpoints[id]?.owner}" }
        return AutoCloseable { endpoints.remove(id, e) }
    }

    fun endpoints(prefix: String): Map<String, com.superiorsoftllc.infantry.daemon.api.ServiceEndpoint> =
        endpoints.filterKeys { it.startsWith(prefix) }.mapValues { it.value.impl as com.superiorsoftllc.infantry.daemon.api.ServiceEndpoint }.toSortedMap()

    /** Who provides [type] (diagnostics), or null. */
    fun ownerOf(type: KClass<*>): String? = entries[type]?.owner

    companion object {
        /** The caller id a scoped provider sees when the daemon itself looks it up. */
        const val CORE_CALLER = "core"
    }
}

/**
 * One module's view of the [ServiceRegistry]: [mayProvide] is the tier / grant check (official and core: always true; community:
 * holds `services.provide`), [track] receives each provider's handle so unloading the module removes its providers.
 */
class ScopedServices(
    private val registry: ServiceRegistry,
    private val owner: String,
    private val mayProvide: () -> Boolean,
    private val track: (AutoCloseable) -> Unit,
) : com.superiorsoftllc.infantry.daemon.api.ServicesApi {
    override fun <T : Any> provide(type: KClass<T>, impl: T): AutoCloseable {
        check(mayProvide()) { "a community plugin needs the grant ${GRANT} to publish a service" }
        return registry.provide(owner, type, impl).also(track)
    }
    /** A scoped provider sees [owner] as the caller; the cached instance is dropped when this module unloads. */
    override fun <T : Any> find(type: KClass<T>): T? = registry.find(type, owner, track)
    override fun <T : Any> provideScoped(type: KClass<T>, factory: (callerModuleId: String) -> T): AutoCloseable {
        check(mayProvide()) { "a community plugin needs the grant ${GRANT} to publish a service" }
        return registry.provideScoped(owner, type, factory).also(track)
    }
    override fun publishEndpoint(id: String, endpoint: com.superiorsoftllc.infantry.daemon.api.ServiceEndpoint): AutoCloseable {
        check(mayProvide()) { "a community plugin needs the grant ${GRANT} to publish a service" }
        return registry.publishEndpoint(owner, id, endpoint).also(track)
    }
    override fun endpoints(prefix: String) = registry.endpoints(prefix)

    companion object { const val GRANT = "services.provide" }
}
