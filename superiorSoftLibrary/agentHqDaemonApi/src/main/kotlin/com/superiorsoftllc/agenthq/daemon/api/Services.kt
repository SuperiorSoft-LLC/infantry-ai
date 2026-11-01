package com.superiorsoftllc.agenthq.daemon.api

import kotlin.reflect.KClass

/**
 * AR-5 (DaemonApi 1.16.0, additive; docs/DAEMON-CORE.md section 8): the typed optional service registry. A plugin PUBLISHES an
 * implementation of an interface declared in an API module; any module FINDS it by that interface. A missing provider is null:
 * the caller owns its fail mode.
 */
interface ServicesApi {
    /**
     * Publish [impl] as the provider of [type]. Official / core tier, or a community module holding the grant `services.provide`;
     * anything else throws [IllegalStateException]. One provider per type: a second `provide` of the same type throws.
     * Closing the returned handle (or unloading the plugin) removes the provider.
     */
    fun <T : Any> provide(type: KClass<T>, impl: T): AutoCloseable

    /** The provider of [type], or null when none is loaded. Any module may look up. */
    fun <T : Any> find(type: KClass<T>): T?

    /**
     * Publish a string endpoint under [id] (1.25.0, additive): a contract two plugins share without sharing classes (each plugin has its own
     * class loader), so a plugin can declare an extension point as an id prefix and other plugins contribute under it. Same tier rule and
     * one-provider-per-id rule as [provide]; closing the handle (or unloading the plugin) removes it.
     */
    fun publishEndpoint(id: String, endpoint: ServiceEndpoint): AutoCloseable = error("this daemon has no endpoint registry")

    /** Every endpoint whose id starts with [prefix], by id (empty when none). Any module may look up. */
    fun endpoints(prefix: String): Map<String, ServiceEndpoint> = emptyMap()
}

/** A named call across plugins: operation, request and answer are plain strings (caller and provider agree the format). */
fun interface ServiceEndpoint {
    fun call(operation: String, request: String): String
}

/** A daemon without a registry: nothing can be published, nothing is found. */
object NoServices : ServicesApi {
    override fun <T : Any> provide(type: KClass<T>, impl: T): AutoCloseable = error("this daemon has no service registry")
    override fun <T : Any> find(type: KClass<T>): T? = null
    override fun publishEndpoint(id: String, endpoint: ServiceEndpoint): AutoCloseable = error("this daemon has no service registry")
}
