// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.capabilities.CapabilityCall
import com.superiorsoftllc.infantry.daemon.api.capabilities.CapabilityHostInfo
import com.superiorsoftllc.infantry.daemon.api.capabilities.CapabilityInfo
import com.superiorsoftllc.infantry.daemon.api.capabilities.CapabilityResult
import com.superiorsoftllc.infantry.daemon.api.capabilities.CapabilityTarget
import com.superiorsoftllc.infantry.plugin.api.CapabilityCallFrame
import com.superiorsoftllc.infantry.plugin.api.CapabilityDeclaration
import com.superiorsoftllc.infantry.plugin.api.CapabilityEventFrame
import com.superiorsoftllc.infantry.plugin.api.CapabilityHosting
import com.superiorsoftllc.infantry.plugin.api.CapabilityResultFrame
import com.superiorsoftllc.infantry.plugin.api.CapabilityRuns
import com.superiorsoftllc.infantry.plugin.api.CapabilityWire
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * PLUGIN-BRIDGES-1 (docs/wiki/Bridges.md): the one capability registry. Generic: it knows no capability, only
 *  - the DECLARATIONS, compiled from the installed modules' module.json `capabilities` / `emits` ([install], refreshed on every
 *    load / unload) and, for a module only a client knows (a web or Android build compiles its UI halves in), from that client's
 *    [CapabilityHosting] (an installed declaration always wins);
 *  - the daemon HANDLERS the daemon halves register ([handle]);
 *  - the client HOSTS: each owner connection that announced client-side capabilities ([host]), with its last activity.
 * [call] routes one call: a daemon handler in-process, or a client host by the target rule ([CapabilityTarget]); no host = a clear
 * error at once, a silent host = an error at the timeout. Gates (policy action, grants) are the caller's ([DaemonPluginHost]).
 */
class CapabilityRegistry(
    /** The wait when a capability declares none. */
    private val defaultTimeoutMs: () -> Long,
    /** The ceiling for a capability's own timeoutMs. */
    private val maxTimeoutMs: () -> Long,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One capability as declared by [module]. */
    class Declared(val module: String, val decl: CapabilityDeclaration)

    /** A daemon half's handler: [run] executes one call under its plugin's guard within the given ms. */
    class DaemonHandler(val module: String, val run: (CapabilityCall, Long) -> Result<JsonElement>)

    /** One client connection hosting client-side capabilities. [send] puts one frame on that connection; false = not sent. */
    inner class Host(val id: String, val send: (type: String, data: String) -> Boolean) {
        @Volatile var hosting: CapabilityHosting = CapabilityHosting()
        @Volatile var lastActiveAt: Long = clock()
        fun hosts(capability: String): Boolean = hosting.modules.any { m -> m.capabilities.any { it.id == capability && it.runs == CapabilityRuns.CLIENT } }
        fun info() = CapabilityHostInfo(id, hosting.platform, hosting.label, lastActiveAt)
    }

    @Volatile private var installed: Map<String, Declared> = emptyMap()
    @Volatile private var installedEmits: Map<String, Set<String>> = emptyMap()
    private val handlers = ConcurrentHashMap<String, DaemonHandler>()
    private val hosts = ConcurrentHashMap<String, Host>()
    private val byConnection = ConcurrentHashMap<Any, Host>()
    private class Pending(val hostId: String, val future: CompletableFuture<CapabilityResultFrame>)
    private val pending = ConcurrentHashMap<String, Pending>()

    // ---- declarations -------------------------------------------------------------------------------------------------

    /**
     * Replaces the installed declarations with those of [modules] (load order: the first declaration of an id wins). Returns the
     * problems (a bad id, an unknown `runs`, a duplicate), each also logged; a refused declaration is left out.
     */
    fun install(modules: List<Pair<String, ModuleManifest>>): List<String> {
        val problems = mutableListOf<String>()
        val next = LinkedHashMap<String, Declared>()
        val emits = LinkedHashMap<String, Set<String>>()
        for ((module, m) in modules) {
            for (c in m.capabilities) {
                val bad = validate(c) ?: next[c.id]?.let { "already declared by ${it.module}" }
                if (bad != null) problems += "$module: capability '${c.id}' refused: $bad" else next[c.id] = Declared(module, c)
            }
            val names = m.emits.map { it.event.trim() }.filter { it.isNotEmpty() }
            names.filter { !CapabilityRuns.ID.matches(it) }.forEach { problems += "$module: emits '$it' refused: an event name is dotted lower case" }
            emits[module] = names.filter { CapabilityRuns.ID.matches(it) }.toSet()
        }
        installed = next
        installedEmits = emits
        problems.forEach { log("[capabilities] $it") }
        return problems
    }

    /** Drops what [module] declared (its plugin was unloaded or removed) and its daemon handlers. */
    fun dropModule(module: String) {
        installed = installed.filterValues { it.module != module }
        installedEmits = installedEmits - module
        handlers.values.removeIf { it.module == module }
    }

    /** The declaration of [id]: the installed one, else one a connected client hosts. */
    fun declaration(id: String): Declared? = installed[id] ?: hosts.values.firstNotNullOfOrNull { h ->
        h.hosting.modules.firstNotNullOfOrNull { m -> m.capabilities.firstOrNull { it.id == id && it.runs == CapabilityRuns.CLIENT }?.let { Declared(m.module, it) } }
    }

    /** True when [module] declares the event [name] in module.json `emits` (installed, or a client's hosted copy). */
    fun emits(module: String, name: String): Boolean =
        installedEmits[module]?.contains(name) == true ||
            hosts.values.any { h -> h.hosting.modules.any { it.module == module && it.emits.any { e -> e.event == name } } }

    // ---- daemon handlers ----------------------------------------------------------------------------------------------

    /** Registers [module]'s handler of [id]; throws [IllegalStateException] unless [module] declares [id] with `runs: daemon`. */
    fun handle(module: String, id: String, run: (CapabilityCall, Long) -> Result<JsonElement>): AutoCloseable {
        val d = installed[id]
        check(d != null && d.module == module) { "capability '$id' is not declared in $module's module.json capabilities" }
        check(d.decl.runs == CapabilityRuns.DAEMON) { "capability '$id' is declared runs: ${d.decl.runs}; only a daemon-side one has a daemon handler" }
        val h = DaemonHandler(module, run)
        check(handlers.putIfAbsent(id, h) == null) { "capability '$id' already has a handler" }
        return AutoCloseable { handlers.remove(id, h) }
    }

    // ---- client hosts -------------------------------------------------------------------------------------------------

    /**
     * The client on [connection] hosts [hosting] now (replacing what it hosted). Only declarations that are well formed and not an
     * installed id of another module are kept. Returns the host id.
     */
    fun host(connection: Any, hosting: CapabilityHosting, send: (String, String) -> Boolean): String {
        val host = byConnection.computeIfAbsent(connection) {
            val id = "${hosting.platform.ifBlank { "client" }.lowercase().filter { c -> c.isLetterOrDigit() }.take(12)}-${UUID.randomUUID().toString().take(8)}"
            Host(id, send).also { hosts[id] = it }
        }
        val kept = hosting.modules.map { m ->
            m.copy(capabilities = m.capabilities.filter { c ->
                val bad = validate(c) ?: if (c.runs != CapabilityRuns.CLIENT) "a client hosts only runs: client" else
                    installed[c.id]?.takeIf { it.module != m.module }?.let { "already declared by ${it.module}" }
                if (bad != null) log("[capabilities] client ${host.id}: ${m.module} capability '${c.id}' ignored: $bad")
                bad == null
            })
        }
        host.hosting = hosting.copy(modules = kept)
        host.lastActiveAt = clock()
        return host.id
    }

    /** The client on [connection] sent a frame: its activity time (the default target is the most recently active desktop). */
    fun touch(connection: Any) { byConnection[connection]?.lastActiveAt = clock() }

    /** The connection closed: its host leaves, and every call waiting on it fails at once. */
    fun disconnected(connection: Any) {
        val host = byConnection.remove(connection) ?: return
        hosts.remove(host.id)
        pending.entries.filter { it.value.hostId == host.id }.forEach { (callId, p) ->
            pending.remove(callId)
            p.future.complete(CapabilityResultFrame(callId, ok = false, error = "host ${host.id} disconnected"))
        }
    }

    /** A client's answer (frame [CapabilityWire.RESULT]); one for an unknown call or from another host is ignored. */
    fun result(connection: Any, frame: CapabilityResultFrame) {
        val host = byConnection[connection] ?: return
        val p = pending[frame.callId] ?: return
        if (p.hostId != host.id) return
        pending.remove(frame.callId)
        p.future.complete(frame)
    }

    /** The module and event of a client's [CapabilityWire.EVENT] frame, when [connection] hosts that module and it declares the event; else the refusal. */
    fun clientEvent(connection: Any, frame: CapabilityEventFrame): Result<Pair<String, String>> {
        val host = byConnection[connection] ?: return Result.failure(IllegalStateException("this connection hosts no module (send ${CapabilityWire.HOST} first)"))
        if (host.hosting.modules.none { it.module == frame.module }) return Result.failure(IllegalStateException("this connection does not host ${frame.module}"))
        if (!emits(frame.module, frame.event)) return Result.failure(IllegalStateException("${frame.module} does not declare the event '${frame.event}' in module.json emits"))
        return Result.success(frame.module to frame.event)
    }

    fun hostsNow(): List<CapabilityHostInfo> = hosts.values.map { it.info() }.sortedBy { it.id }

    // ---- listing and calls --------------------------------------------------------------------------------------------

    fun list(): List<CapabilityInfo> {
        val all = LinkedHashMap<String, Declared>(installed)
        for (h in hosts.values) for (m in h.hosting.modules) for (c in m.capabilities) all.putIfAbsent(c.id, Declared(m.module, c))
        return all.values.sortedBy { it.decl.id }.map { d ->
            val c = d.decl
            val clientHosts = if (c.runs == CapabilityRuns.CLIENT) hosts.values.filter { it.hosts(c.id) }.map { it.info() }.sortedByDescending { it.lastActiveAt } else emptyList()
            CapabilityInfo(
                id = c.id, module = d.module, description = c.description, runs = c.runs, params = c.params, result = c.result,
                action = c.actionId, grant = c.grant, hosts = clientHosts,
                available = if (c.runs == CapabilityRuns.DAEMON) handlers[c.id]?.module == d.module else clientHosts.isNotEmpty(),
            )
        }
    }

    /** The wait of one call of [decl]: its own timeoutMs capped at the ceiling, else the default. */
    fun timeoutOf(decl: CapabilityDeclaration): Long = decl.timeoutMs?.takeIf { it > 0 }?.coerceAtMost(maxTimeoutMs()) ?: defaultTimeoutMs()

    /** Runs [id] for [caller] (gates already passed). Never throws: a refusal, a missing host, a failure or a timeout is [CapabilityResult.Failed]. */
    fun call(id: String, args: JsonObject, target: String?, caller: Caller, source: String): CapabilityResult {
        val d = declaration(id) ?: return CapabilityResult.Failed("unknown capability '$id' (infantry_capability_list lists the installed ones)")
        val timeoutMs = timeoutOf(d.decl)
        val call = CapabilityCall(id, args, caller, source)
        if (d.decl.runs == CapabilityRuns.DAEMON) {
            val h = handlers[id]?.takeIf { it.module == d.module }
                ?: return CapabilityResult.Failed("capability '$id' has no daemon handler: the daemon half of ${d.module} is not loaded")
            return h.run(call, timeoutMs).fold(
                onSuccess = { CapabilityResult.Ok(it, "daemon:${d.module}") },
                onFailure = { CapabilityResult.Failed("$id failed: ${it.message ?: it::class.simpleName}") },
            )
        }
        val candidates = hosts.values.filter { it.hosts(id) }
        if (candidates.isEmpty()) return CapabilityResult.Failed("capability '$id' has no host: no connected client has ${d.module} loaded (open the app with ${d.module} installed)")
        val t = target?.trim().orEmpty()
        return when {
            t.equals(CapabilityTarget.ALL, ignoreCase = true) -> {
                val sent = candidates.map { h -> h to dispatch(h, id, args) }
                val deadline = clock() + timeoutMs
                val rows = sent.map { (h, f) ->
                    val r = await(h, f, (deadline - clock()).coerceAtLeast(1))
                    JsonObject(buildMap {
                        put("host", JsonPrimitive(h.id))
                        put("ok", JsonPrimitive(r.ok))
                        if (r.ok) r.result?.let { put("result", it) } else put("error", JsonPrimitive(r.error ?: "failed"))
                    })
                }
                CapabilityResult.Ok(JsonArray(rows), CapabilityTarget.ALL)
            }
            else -> {
                val host = if (t.isEmpty() || t.equals(CapabilityTarget.DEFAULT, ignoreCase = true)) pickDefault(candidates)
                else candidates.firstOrNull { it.id == t } ?: return CapabilityResult.Failed(
                    "capability '$id': host '$t' is not connected or does not host it (hosts: ${candidates.joinToString { it.id }})",
                )
                val r = await(host, dispatch(host, id, args), timeoutMs)
                if (r.ok) CapabilityResult.Ok(r.result ?: JsonObject(emptyMap()), host.id) else CapabilityResult.Failed("$id on ${host.id}: ${r.error ?: "failed"}")
            }
        }
    }

    /** The default target: the most recently active desktop host, else the most recently active one. */
    private fun pickDefault(candidates: List<Host>): Host =
        candidates.filter { it.hosting.platform.equals("desktop", ignoreCase = true) }.maxByOrNull { it.lastActiveAt }
            ?: candidates.maxBy { it.lastActiveAt }

    private fun dispatch(host: Host, id: String, args: JsonObject): Pair<String, CompletableFuture<CapabilityResultFrame>> {
        val callId = UUID.randomUUID().toString()
        val f = CompletableFuture<CapabilityResultFrame>()
        pending[callId] = Pending(host.id, f)
        if (!host.send(CapabilityWire.CALL, CapabilityWire.encode(CapabilityCallFrame(callId, id, args)))) {
            pending.remove(callId)
            f.complete(CapabilityResultFrame(callId, ok = false, error = "could not send to host ${host.id}"))
        }
        return callId to f
    }

    private fun await(host: Host, sent: Pair<String, CompletableFuture<CapabilityResultFrame>>, timeoutMs: Long): CapabilityResultFrame {
        val (callId, f) = sent
        return try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending.remove(callId)
            CapabilityResultFrame(callId, ok = false, error = "host ${host.id} did not answer within ${timeoutMs} ms")
        } catch (e: InterruptedException) {
            pending.remove(callId)
            Thread.currentThread().interrupt()
            CapabilityResultFrame(callId, ok = false, error = "interrupted")
        }
    }

    companion object {
        /** Why [c] is malformed, or null. */
        fun validate(c: CapabilityDeclaration): String? = when {
            !CapabilityRuns.ID.matches(c.id) -> "an id is dotted lower case, two segments at least (vendor.name)"
            c.runs !in CapabilityRuns.ALL -> "runs must be one of ${CapabilityRuns.ALL.joinToString()}"
            c.kind !in setOf("read", "write", "destructive", "spawn") -> "kind must be read, write, destructive or spawn"
            else -> null
        }
    }
}
