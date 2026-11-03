// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin.config

import com.superiorsoftllc.infantry.ui.shell.ConfigContext
import com.superiorsoftllc.infantry.ui.shell.ConfigValues
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.infantry.plugin.api.ConfigSetBy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.reflect.KClass

/**
 * Keys that belong to the node they are stored on and are never inherited: the children control (6s E), which says
 * what a container draws, not a setting its children share, and bookkeeping keys (`<field>@known`, [ImplementationFields]).
 */
fun isOwnOnlyKey(key: String): Boolean = key.startsWith("children.") || '@' in key

/**
 * P1 step 2 (docs/BLOCKS-LAYOUT-DESIGN.md 6s C): the core's [ConfigContext]. A key resolves at the node, then its
 * parent, up to the root (the first stored value wins), then the nearest declaration's default; unset is null, never an
 * error. Reads [state]'s composition state, so a block that reads a value recomposes when it changes; the resolution of
 * a node is computed once per change of the stored copy, not per frame.
 */
class ResolvedConfig(private val state: NodeConfigState, private val implementations: Implementations = Implementations.NONE) : ConfigContext {
    private var cacheOf: Any? = null
    private var deviceOf: Any? = null
    private val cache = HashMap<String, Map<String, JsonElement>>()

    override fun values(nodeId: String): ConfigValues {
        val values = resolved(nodeId)
        val chain = state.tree.chain(nodeId)
        val set = values.keys.filterTo(HashSet()) { k ->
            chain.withIndex().any { (i, n) ->
                (i == 0 || !isOwnOnlyKey(k)) && (state.device[n]?.containsKey(k) == true || state.stored[n]?.get(k)?.let { it.setBy == ConfigSetBy.USER && it.value.let { v -> v != null && v !is JsonNull } } == true)
            }
        }
        return JsonValues(values, set)
    }

    /** The JSON [key] resolves to for [nodeId], or null when unset (the configuration view's editors read it). */
    fun json(nodeId: String, key: String): JsonElement? = state.daemonJson(nodeId, key) ?: resolved(nodeId)[key]

    private fun resolved(nodeId: String): Map<String, JsonElement> {
        val stored = state.stored
        val device = state.device
        if (cacheOf !== stored || deviceOf !== device) { cache.clear(); cacheOf = stored; deviceOf = device }
        return cache.getOrPut(nodeId) { resolve(nodeId, stored) }
    }

    /** The key [key] resolves to for [nodeId], as JSON, and the node that answered it (null = the declared default). */
    fun origin(nodeId: String, key: String): String? = state.tree.chain(nodeId)
        .firstOrNull { n -> (n == nodeId || !isOwnOnlyKey(key)) && state.stored[n]?.get(key)?.value.let { it != null && it !is JsonNull } }

    override fun <T : Any> implementation(type: KClass<T>, key: String): T? = implementations.find(type, key)

    private fun resolve(node: String, stored: Map<String, Map<String, com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry>>): Map<String, JsonElement> {
        val chain = state.tree.chain(node)
        val out = LinkedHashMap<String, JsonElement>()
        // CONFIG-4: a device field takes its value from this device's layer (nearest node first), never from node_config.
        for ((i, n) in chain.withIndex()) for ((k, v) in state.device[n].orEmpty()) {
            if (i > 0 && isOwnOnlyKey(k)) continue
            if (k !in out) out[k] = v
        }
        for ((i, n) in chain.withIndex()) {
            for ((k, e) in stored[n].orEmpty()) {
                if (i > 0 && isOwnOnlyKey(k)) continue
                if (state.isDevice(n, k)) continue
                val v = e.value ?: continue
                if (v !is JsonNull) if (k !in out) out[k] = v
            }
        }
        for ((i, n) in chain.withIndex()) for (f in state.tree.fields[n].orEmpty()) {
            if (i > 0 && isOwnOnlyKey(f.key)) continue
            f.field.default?.takeIf { it !is JsonNull }?.let { if (f.key !in out) out[f.key] = it }
        }
        // 6s D: a stored implementation key whose module is gone resolves to the field's default (the view says so).
        for (k in out.keys.toList()) {
            val f = state.tree.field(node, k)?.field ?: continue
            val registry = f.registry ?: continue
            if (f.type != ConfigFieldTypes.IMPLEMENTATION) continue
            val key = (out[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (key == null || !implementations.has(registry, key)) {
                val d = (f.default as? JsonPrimitive)?.takeIf { it.isString && implementations.has(registry, it.content) }
                if (d != null) out[k] = d else out.remove(k)
            }
        }
        return out
    }
}

/** Typed reads of one node's resolved JSON; a value of another type is null, never coerced. */
internal class JsonValues(private val values: Map<String, JsonElement>, private val userSet: Set<String> = emptySet()) : ConfigValues {
    override fun overridden(key: String): Boolean = key in userSet
    override fun json(key: String): JsonElement? = values[key]
    override val keys: Set<String> get() = values.keys
    private fun prim(key: String) = values[key] as? JsonPrimitive
    override fun string(key: String): String? = prim(key)?.takeIf { it.isString }?.content
    override fun int(key: String): Int? = prim(key)?.takeIf { !it.isString }?.intOrNull
    override fun double(key: String): Double? = prim(key)?.takeIf { !it.isString }?.doubleOrNull
    override fun boolean(key: String): Boolean? = prim(key)?.takeIf { !it.isString }?.booleanOrNull
    override fun classes(slot: String, key: String): List<String>? =
        ((values[key] as? kotlinx.serialization.json.JsonObject)?.get(slot) as? JsonPrimitive)?.takeIf { it.isString }?.content?.split(' ')?.filter { it.isNotEmpty() }
    override fun obj(key: String): ConfigValues? = (values[key] as? kotlinx.serialization.json.JsonObject)?.let { JsonValues(it) }
    override fun list(key: String): List<ConfigValues>? = (values[key] as? JsonArray)?.map { (it as? kotlinx.serialization.json.JsonObject)?.let { o -> JsonValues(o) } ?: return null }
    override fun strings(key: String): List<String>? = (values[key] as? JsonArray)?.let { a ->
        a.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
    }
}

/**
 * P1 step 2 (6s D): what modules registered for implementation fields, registry -> key -> value, in load order. The
 * JSON stores a key only; this is the one place a key becomes code.
 */
class Implementations(val registries: Map<String, Map<String, RegisteredImplementation>>, val problems: List<String> = emptyList()) {
    /** The keys registered in [registry], in registration order. */
    fun keys(registry: String): List<String> = registries[registry]?.keys?.toList().orEmpty()

    fun has(registry: String, key: String): Boolean = registries[registry]?.containsKey(key) == true

    /** What is registered under [key] of [registry], or null. */
    fun get(registry: String, key: String): Any? = registries[registry]?.get(key)?.value

    /** Who registered [key] of [registry], or null. */
    fun moduleOf(registry: String, key: String): String? = registries[registry]?.get(key)?.module

    fun <T : Any> find(type: KClass<T>, key: String): T? {
        for (r in registries.values) {
            val v = r[key]?.value ?: continue
            @Suppress("UNCHECKED_CAST")
            if (type.isInstance(v)) return v as T
        }
        return null
    }

    companion object {
        val NONE = Implementations(emptyMap())
    }
}

/** One registered implementation: [module] put [value] under [key] of [registry]. */
class RegisteredImplementation(val registry: String, val key: String, val module: String, val value: Any)
