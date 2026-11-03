// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A client connection the daemon pushes view changes to; identity is the object. */
class ViewConnection(val send: (Envelope) -> Unit)

/**
 * DV-1b (docs/DECL-UI-DESIGN.md 2.3-2.4): who watches which view. A subscription is `(connection, module, view, canonical
 * params)`; `changed` pushes `plugin_view_changed` to the subscribed connections only, coalesced per target inside a window.
 * Opaque to what a view shows: the daemon never reads a page.
 */
internal class ViewSubscriptions(private val coalesceMs: () -> Long, private val maxPerConnection: () -> Long) {
    data class Key(val module: String, val view: String, val params: String)

    private val subs = LinkedHashMap<ViewConnection, LinkedHashSet<Key>>()
    private val listers = LinkedHashSet<ViewConnection>()
    private val revs = ConcurrentHashMap<Key, AtomicLong>()
    private val lastPush = HashMap<String, Long>()
    private val pending = HashSet<String>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "view-push").also { it.isDaemon = true } }

    fun keyOf(module: String, view: String, params: JsonObject) = Key(module, view, canonical(params).toString())

    fun rev(key: Key): Long = revs.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()

    @Synchronized fun noteLister(conn: ViewConnection) { listers += conn }

    @Synchronized fun subscribe(conn: ViewConnection, key: Key) {
        val set = subs.getOrPut(conn) { LinkedHashSet() }
        set.remove(key); set.add(key) // newest last
        val max = maxPerConnection().coerceAtLeast(1)
        while (set.size > max) set.remove(set.first())
    }

    @Synchronized fun close(conn: ViewConnection, key: Key) {
        subs[conn]?.let { it.remove(key); if (it.isEmpty()) subs.remove(conn) }
    }

    @Synchronized fun disconnect(conn: ViewConnection) { subs.remove(conn); listers.remove(conn) }

    @Synchronized fun count(conn: ViewConnection): Int = subs[conn]?.size ?: 0

    /** Pushes `plugin_view_changed {module, view, params?, rev}` to the subscribers of the target; [params] null = every params. */
    fun changed(module: String, view: String, params: JsonObject?) {
        val target = "$module/$view/${params?.let { canonical(it).toString() } ?: "*"}"
        val wait: Long
        synchronized(this) {
            if (target in pending) return
            val now = System.currentTimeMillis()
            wait = (lastPush[target] ?: Long.MIN_VALUE).let { if (it == Long.MIN_VALUE) 0L else it + coalesceMs() - now }
            if (wait <= 0) lastPush[target] = now else pending += target
        }
        if (wait <= 0) fire(module, view, params, target)
        else runCatching {
            scheduler.schedule({
                synchronized(this) { pending -= target; lastPush[target] = System.currentTimeMillis() }
                fire(module, view, params, target)
            }, wait, TimeUnit.MILLISECONDS)
        }
    }

    private fun fire(module: String, view: String, params: JsonObject?, target: String) {
        val canon = params?.let { canonical(it).toString() }
        val sends = mutableListOf<Pair<ViewConnection, Envelope>>()
        synchronized(this) {
            for ((conn, set) in subs) for (k in set) {
                if (k.module != module || k.view != view || (canon != null && k.params != canon)) continue
                val body = JsonObject(mapOf(
                    "module" to JsonPrimitive(module), "view" to JsonPrimitive(view),
                    "params" to Json.parseToJsonElement(k.params), "rev" to JsonPrimitive(rev(k)),
                ))
                sends += conn to Envelope(DaemonPluginHost.PLUGIN_VIEW_CHANGED, body.toString())
            }
        }
        sends.forEach { (c, e) -> runCatching { c.send(e) } }
    }

    /**
     * A module was loaded, unloaded or reloaded: every connection with a subscription or that listed views gets
     * `plugin_view_changed {module, view:"*"}` (so its list refreshes); with [drop] the module's subscriptions end.
     */
    fun moduleChanged(module: String, drop: Boolean) {
        val targets: List<ViewConnection>
        synchronized(this) {
            targets = (subs.keys + listers).distinct()
            if (drop) {
                subs.values.forEach { set -> set.removeIf { it.module == module } }
                subs.values.removeIf { it.isEmpty() }
                revs.keys.removeIf { it.module == module }
            }
        }
        val e = Envelope(DaemonPluginHost.PLUGIN_VIEW_CHANGED, JsonObject(mapOf("module" to JsonPrimitive(module), "view" to JsonPrimitive("*"))).toString())
        targets.forEach { c -> runCatching { c.send(e) } }
    }

    fun shutdown() { scheduler.shutdownNow() }

    companion object {
        /** Object keys sorted, recursively, so equal params give equal text. */
        fun canonical(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
            is JsonArray -> JsonArray(e.map { canonical(it) })
            is JsonNull, is JsonPrimitive -> e
        }
    }
}
