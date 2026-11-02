package com.superiorsoftllc.agenthq.daemon

import java.util.concurrent.ConcurrentHashMap

/** DAEMON-CORE-2: the core components whose implementation is chosen by the setting `core.<key>.provider` (docs/DAEMON-CORE.md). */
enum class CoreComponent(val key: String) {
    SESSION_MANAGER("sessionManager"),
    MAILBOX("mailbox"),
    ORCHESTRATOR_LINK("orchestratorLink"),
    STORE("store");

    val setting: String get() = "core.$key.provider"
}

/** A provider id the setting names but nothing offers; the daemon refuses to start (never falls back silently). */
class UnknownComponentProviderException(message: String) : IllegalStateException(message)

/**
 * The offers the resolver chooses from: a plugin (or a test) offers an implementation of a component under a provider id. It is
 * published once in the AR-5 service registry (`ServiceRegistry.provide(owner, ComponentProviders::class, ...)`) and read by
 * [ComponentBindings]. `local` is never offered here: it is the built-in the daemon constructs itself.
 */
class ComponentProviders {
    private val offers = ConcurrentHashMap<Pair<CoreComponent, String>, Pair<String, Any>>()

    fun offer(owner: String, component: CoreComponent, providerId: String, impl: Any): AutoCloseable {
        require(providerId.isNotBlank() && providerId != ComponentBindings.LOCAL) { "'${ComponentBindings.LOCAL}' is the built-in provider" }
        val key = component to providerId
        val value = owner to impl
        check(offers.putIfAbsent(key, value) == null) { "provider '$providerId' of ${component.key} is already offered by ${offers[key]?.first}" }
        return AutoCloseable { offers.remove(key, value) }
    }

    fun find(component: CoreComponent, providerId: String): Any? = offers[component to providerId]?.second
}

/**
 * Resolves each core component by its `core.<component>.provider` setting (default `local`). Absent offers are looked up through
 * [providers] (null when no plugin offered anything). An unknown provider id throws [UnknownComponentProviderException] naming the
 * setting, the id and the known ids; nothing is substituted.
 */
class ComponentBindings(
    private val setting: (String) -> String?,
    private val providers: () -> ComponentProviders?,
) {
    fun providerId(component: CoreComponent): String = setting(component.setting)?.trim()?.takeIf { it.isNotEmpty() } ?: LOCAL

    /** Fails fast for every component, so a typo is refused at start before anything is constructed. */
    fun verifyAll() { CoreComponent.entries.forEach { check(it) } }

    private fun check(component: CoreComponent): Any? {
        val id = providerId(component)
        if (id == LOCAL) return null
        return providers()?.find(component, id)
            ?: throw UnknownComponentProviderException("${component.setting} = '$id' names no registered provider (only '$LOCAL' is built in); fix or clear the setting")
    }

    /** The provider's implementation as [T], or [local] built by the caller when the setting is `local`. */
    fun <T : Any> resolve(component: CoreComponent, type: Class<T>, local: () -> T): T {
        val offered = check(component) ?: return local()
        return type.cast(offered) ?: error("unreachable")
    }

    companion object { const val LOCAL = "local" }
}
