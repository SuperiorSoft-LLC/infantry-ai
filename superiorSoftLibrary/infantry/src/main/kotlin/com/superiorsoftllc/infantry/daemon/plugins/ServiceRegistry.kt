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
    private class Entry(val owner: String, val impl: Any)
    private val entries = ConcurrentHashMap<KClass<*>, Entry>()

    /** Registers [impl] for [type] on behalf of [owner]; the handle removes it (only if it is still this provider). */
    fun <T : Any> provide(owner: String, type: KClass<T>, impl: T): AutoCloseable {
        val e = Entry(owner, impl)
        check(entries.putIfAbsent(type, e) == null) { "a provider of ${type.qualifiedName} is already registered by ${entries[type]?.owner}" }
        return AutoCloseable { entries.remove(type, e) }
    }

    fun <T : Any> find(type: KClass<T>): T? = entries[type]?.impl?.let { type.javaObjectType.cast(it) }

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
    override fun <T : Any> find(type: KClass<T>): T? = registry.find(type)
    override fun publishEndpoint(id: String, endpoint: com.superiorsoftllc.infantry.daemon.api.ServiceEndpoint): AutoCloseable {
        check(mayProvide()) { "a community plugin needs the grant ${GRANT} to publish a service" }
        return registry.publishEndpoint(owner, id, endpoint).also(track)
    }
    override fun endpoints(prefix: String) = registry.endpoints(prefix)

    companion object { const val GRANT = "services.provide" }
}
