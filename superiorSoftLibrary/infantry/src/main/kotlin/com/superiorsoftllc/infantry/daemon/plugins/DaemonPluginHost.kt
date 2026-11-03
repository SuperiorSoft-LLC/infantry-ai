// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.settings.LimitRegistry
import com.superiorsoftllc.infantry.daemon.api.AdmissionHook
import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.Events
import com.superiorsoftllc.infantry.daemon.api.board.TicketEvent
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.FrameGateHook
import com.superiorsoftllc.infantry.daemon.api.Frames
import com.superiorsoftllc.infantry.daemon.api.Hooks
import com.superiorsoftllc.infantry.daemon.api.HttpCall
import com.superiorsoftllc.infantry.daemon.api.HttpGateHook
import com.superiorsoftllc.infantry.daemon.api.HttpReply
import com.superiorsoftllc.infantry.daemon.api.MailGateHook
import com.superiorsoftllc.infantry.daemon.api.IncomingMailHook
import com.superiorsoftllc.infantry.daemon.api.IncomingMail
import com.superiorsoftllc.infantry.daemon.api.MailVerdict
import com.superiorsoftllc.infantry.daemon.api.Policy
import com.superiorsoftllc.infantry.daemon.api.PresetProviderHook
import com.superiorsoftllc.infantry.daemon.api.Projects
import com.superiorsoftllc.infantry.daemon.api.ReadyHook
import com.superiorsoftllc.infantry.daemon.api.Routes
import com.superiorsoftllc.infantry.daemon.api.SessionHook
import com.superiorsoftllc.infantry.daemon.api.SessionInfo
import com.superiorsoftllc.infantry.daemon.api.Sessions
import com.superiorsoftllc.infantry.daemon.api.SpawnGateHook
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolInfo
import com.superiorsoftllc.infantry.daemon.api.ToolInvoker
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.Tools
import com.superiorsoftllc.infantry.daemon.api.TurnHook
import com.superiorsoftllc.infantry.plugin.api.DaemonCapabilities
import com.superiorsoftllc.infantry.plugin.api.ModuleSetting
import com.superiorsoftllc.infantry.plugin.api.ModulesConfig
import com.superiorsoftllc.infantry.plugin.api.PolicyPreset
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.ModuleCandidate
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.plugin.host.ModuleStatus
import com.superiorsoftllc.infantry.daemon.plugins.manager.HotOutcome
import com.superiorsoftllc.infantry.daemon.plugins.manager.PluginHotApply
import com.superiorsoftllc.infantry.plugin.host.OfficialTrust
import com.superiorsoftllc.infantry.plugin.host.Resolution
import com.superiorsoftllc.infantry.plugin.host.SemVer
import com.superiorsoftllc.infantry.policy.ActionCatalog
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import com.superiorsoftllc.infantry.daemon.api.NamedEvent
import java.io.File
import java.net.URLClassLoader
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md): the daemon's plugin MECHANISM. It loads the daemon half of every module the
 * resolver let through ([Resolution.loaded]) whose module.json names a `daemon.entry`, each in its own class loader below
 * [DaemonApiClassLoader], hands it a [DaemonApi] scoped to its declarations and grants, and holds what it registered: tools,
 * frames, routes and hooks. Every call into a plugin goes through its [PluginGuard]. Core asks this host at its seams (the
 * frame loop, the access policy, the mail routes, the session door, `ready`); with no plugin loaded every answer is the
 * neutral one, so a daemon with no daemon plugins behaves exactly as before.
 */
class DaemonPluginHost private constructor(
    private val services: DaemonServices,
    private val stateDir: File,
    private val log: (String) -> Unit,
) : AutoCloseable, PluginHotApply {

    /** One loaded daemon half. */
    inner class Loaded(
        val id: String,
        val tier: Tier,
        val declared: DaemonCapabilities,
        val setting: ModuleSetting,
        val guard: PluginGuard,
        val loader: URLClassLoader,
        /** PLUGIN-HOTLOAD-1: the module ids this one depends on (its manifest's `dependencies`), so an unload finds its dependents. */
        val dependencies: List<String> = emptyList(),
    ) {
        @Volatile var plugin: DaemonPlugin? = null
        /** PLUGIN-HOTLOAD-1: set when an unload began: a failure from here on is not announced, and its gates no longer apply. */
        @Volatile var unloading = false
        val community: Boolean get() = tier == Tier.COMMUNITY
        val callTimeoutMs: Long = declared.limits.callTimeoutMs ?: services.limit(LimitRegistry.PLUGIN_CALL_TIMEOUT_MS)
        val toolTimeoutMs: Long = declared.limits.toolTimeoutMs ?: services.limit(LimitRegistry.PLUGIN_TOOL_TIMEOUT_MS)
        val routeRate: Int = declared.limits.routeRequestsPerMinute ?: services.limit(LimitRegistry.PLUGIN_ROUTE_RATE).toInt()
        val hookKinds = mutableSetOf<String>()
        val closeables = CopyOnWriteArrayList<AutoCloseable>()
        @Volatile var routeWindowStart = 0L
        @Volatile var routeCount = 0

        fun <T> call(what: String, timeoutMs: Long = callTimeoutMs, block: () -> T): Result<T> = guard.call(what, timeoutMs, block)
        suspend fun <T> callSuspending(what: String, timeoutMs: Long = callTimeoutMs, block: () -> T): Result<T> = guard.callSuspending(what, timeoutMs, block)
    }

    private class Registered<T>(val owner: Loaded, val value: T)
    private class ToolEntry(val owner: Loaded, val tool: DaemonTool, val action: ActionCatalog.Action)
    private class FrameEntry(val owner: Loaded, val action: ActionCatalog.Action, val ownerOnly: Boolean, val handler: (Frame, Caller) -> Frame?)
    private class RouteEntry(val owner: Loaded, val method: String, val path: String, val public: Boolean, val handler: (HttpCall) -> HttpReply) {
        /** Non-null for a template path: its segments, a `{name}` one binds a path param. */
        val template: List<String>? = if ('{' in path) path.trim('/').split('/') else null
    }

    private val loaded = CopyOnWriteArrayList<Loaded>()
    /** AR-5: the typed service registry; core callers query it here, plugins through [DaemonApi.services]. */
    val serviceRegistry = ServiceRegistry()
    private val statuses = ConcurrentHashMap<String, String>()
    private val toolEntries = ConcurrentHashMap<String, ToolEntry>()
    private val frameHandlers = ConcurrentHashMap<String, FrameEntry>()
    private val routeEntries = ConcurrentHashMap<String, RouteEntry>()
    private val frameGates = CopyOnWriteArrayList<Registered<FrameGateHook>>()
    private val orchestratorBindings = CopyOnWriteArrayList<Registered<Pair<String, com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorBinding>>>()
    private val approvalRuleHooks = CopyOnWriteArrayList<Registered<com.superiorsoftllc.infantry.daemon.api.ApprovalRulesHook>>()
    private val httpGates = CopyOnWriteArrayList<Registered<HttpGateHook>>()
    private val admissions = CopyOnWriteArrayList<Registered<AdmissionHook>>()
    private val mailGates = CopyOnWriteArrayList<Registered<MailGateHook>>()
    private val incomingMailHooks = CopyOnWriteArrayList<Registered<IncomingMailHook>>()
    private val spawnGates = CopyOnWriteArrayList<Registered<SpawnGateHook>>()
    private val connectedHooks = CopyOnWriteArrayList<Registered<com.superiorsoftllc.infantry.daemon.api.ConnectedHook>>()
    private val statusHooks = CopyOnWriteArrayList<Registered<com.superiorsoftllc.infantry.daemon.api.StatusHook>>()
    private val readyHooks = CopyOnWriteArrayList<Registered<ReadyHook>>()
    private val turnHooks = CopyOnWriteArrayList<Registered<TurnHook>>()
    private val sessionHooks = CopyOnWriteArrayList<Registered<SessionHook>>()
    private val presetHooks = CopyOnWriteArrayList<Registered<PresetProviderHook>>()
    private val eventListeners = CopyOnWriteArrayList<Triple<Loaded, String, (Frame) -> Unit>>()
    private val seatClaimants = CopyOnWriteArrayList<Loaded>()
    /** SCRIPT-3: the generic named-event bus (plugins publish / subscribe through `api.events`; core and agents publish through [publishEvent]). */
    val namedEvents = NamedEventBus(executor = { r -> observerQueue.execute(r) })
    /** Observers run off the caller's thread, in order, so a slow observer never holds a frame or a session event. */
    private val observerQueue = Executors.newSingleThreadExecutor { r -> Thread(r, "daemon-plugin-observers").apply { isDaemon = true } }

    // ---- what core asks ---------------------------------------------------------------------------------------------

    /** `module id -> loaded | refused: why | failed: why`, for `GET /plugins`. */
    fun status(module: String): String? = statuses[module]?.let { s -> loaded.firstOrNull { it.id == module }?.guard?.disabledReason?.let { "failed: $it" } ?: s }

    /** The tool names a module registered. */
    fun toolsOf(module: String): List<String> = toolEntries.values.filter { it.owner.id == module }.map { it.tool.name }.sorted()

    val hasPlugins: Boolean get() = loaded.isNotEmpty()

    /** Every frame gate, in load order: the first refusal wins. A failed or disabled gate refuses (fail closed) all but the owner. */
    fun frameRefusal(envelope: Envelope, caller: Caller): String? {
        val frame = envelope.toFrame()
        for (g in frameGates) {
            if (g.owner.unloading) continue // PLUGIN-HOTLOAD-1: a gate being removed on purpose is no failure
            if (g.owner.guard.disabled) {
                if (caller.owner) continue
                return failClosed(g.owner, "frame gate")
            }
            val r = g.owner.call("frameGate(${envelope.type})") { g.value.refusal(frame, caller) }
            if (r.isFailure) { if (caller.owner) continue; return failClosed(g.owner, "frame gate") }
            r.getOrNull()?.let { return it }
        }
        return null
    }

    /** Every HTTP gate; fail closed (except `/health`, which a supervisor must always reach). */
    fun httpRefusal(method: String, path: String, webAsset: Boolean): String? {
        for (g in httpGates) {
            if (g.owner.unloading) continue
            if (g.owner.guard.disabled) { if (path == "/health") continue; return failClosed(g.owner, "http gate") }
            val r = g.owner.call("httpGate($method $path)") { g.value.refusal(method, path, webAsset) }
            if (r.isFailure) { if (path == "/health") continue; return failClosed(g.owner, "http gate") }
            r.getOrNull()?.let { return it }
        }
        return null
    }

    /** The guest bearer tokens admitted now (official/core admission hooks only); fail open (none). */
    /** ORCH-1: the orchestrator bindings plugins offered, for the daemon to register on its hub. */
    fun orchestratorBindings(): List<Pair<String, com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorBinding>> = orchestratorBindings.map { it.value }

    /** DP-10b: the approval-rule store a plugin offered (the first one), else a port with no rules. Fail open. */
    val approvalRules: com.superiorsoftllc.infantry.daemon.api.ApprovalRulesHook = object : com.superiorsoftllc.infantry.daemon.api.ApprovalRulesHook {
        override fun match(projectId: String?, tool: String, input: String) =
            approvalRuleHooks.firstOrNull()?.let { r -> runCatching { r.value.match(projectId, tool, input) }.getOrNull() }
        override fun remember(projectId: String?, tool: String, input: String): Boolean =
            approvalRuleHooks.firstOrNull()?.let { r -> runCatching { r.value.remember(projectId, tool, input) }.getOrDefault(false) } ?: false
    }

    fun guestTokens(): List<String> = admissions.flatMap { a ->
        if (a.owner.guard.disabled) emptyList() else a.owner.call("admission") { a.value.guestTokens() }.getOrDefault(emptyList())
    }

    fun mailRefusal(from: String, to: String, allowCrossProject: Boolean): String? {
        for (g in mailGates) {
            if (g.owner.unloading) continue
            if (g.owner.guard.disabled) return failClosed(g.owner, "mail gate")
            val r = g.owner.call("mailGate") { g.value.refusal(from, to, allowCrossProject) }
            if (r.isFailure) return failClosed(g.owner, "mail gate")
            r.getOrNull()?.let { return it }
        }
        return null
    }

    /** MAIL-HOOK-1: the first hook verdict that handles or rewrites the mail. Fail open: a disabled, throwing or timed-out hook reads as no verdict. */
    fun incomingMail(mail: IncomingMail): MailVerdict? {
        for (h in incomingMailHooks) {
            if (h.owner.guard.disabled) continue
            val r = h.owner.call("incomingMail") { h.value.incoming(mail) }
            r.exceptionOrNull()?.let { ex -> runCatching { h.value.lost(mail.id, "${ex.javaClass.simpleName}: ${ex.message?.take(160)}") } }
            val v = r.getOrNull()
            if (v != null && (v.handled || v.text != null)) return v
        }
        return null
    }

    fun spawnRefusal(role: String?, projectId: String?): String? {
        for (g in spawnGates) {
            if (g.owner.unloading) continue
            if (g.owner.guard.disabled) return failClosed(g.owner, "spawn gate")
            val r = g.owner.call("spawnGate") { g.value.refusal(role, projectId) }
            if (r.isFailure) return failClosed(g.owner, "spawn gate")
            r.getOrNull()?.let { return it }
        }
        return null
    }

    /** The `ready` keys the plugins add (fail open); a key core already sends is never overwritten by the caller. */
    fun readyFields(): Map<String, String> = buildMap {
        for (h in readyHooks) if (!h.owner.guard.disabled) h.owner.call("ready") { h.value.fields() }.getOrNull()?.forEach { (k, v) ->
            if (READY_KEY.matches(k) && v.none { it.isWhitespace() }) putIfAbsent(k, v)
        }
    }

    /** TRIG-2a: the fields plugins add to health/status (fail open); keys follow the ready-key rule, a repeated key keeps the first. */
    fun statusFields(): Map<String, String> = buildMap {
        for (h in statusHooks) if (!h.owner.guard.disabled) h.owner.call("status") { h.value.fields() }.getOrNull()?.forEach { (k, v) ->
            if (READY_KEY.matches(k)) putIfAbsent(k, v)
        }
    }

    /** ATTN-2a: the frames the plugins replay to one newly connected client (fail open), in plugin order. */
    fun connectedFrames(sessionId: String?): List<Frame> = connectedHooks.flatMap { h ->
        if (h.owner.guard.disabled) emptyList() else h.owner.call("connected") { h.value.frames(sessionId) }.getOrNull().orEmpty()
    }

    /** The first pinned notice a plugin gives, or null. */
    fun notice(): String? = readyHooks.firstNotNullOfOrNull { h ->
        if (h.owner.guard.disabled) null else h.owner.call("ready.notice") { h.value.notice() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun turnAccepted(envelope: Envelope, caller: Caller) {
        if (turnHooks.isEmpty()) return
        val frame = envelope.toFrame()
        for (h in turnHooks) if (!h.owner.guard.disabled) h.owner.call("turns") { h.value.accepted(frame, caller) }
    }

    fun sessionChanged(kind: String, session: SessionInfo) {
        if (sessionHooks.isEmpty()) return
        observerQueue.execute { for (h in sessionHooks) if (!h.owner.guard.disabled) h.owner.call("sessions") { h.value.changed(kind, session) } }
    }

    /** A frame the daemon broadcast to its clients, to the plugins' event listeners (off the broadcasting thread). */
    fun broadcast(envelope: Envelope) {
        if (eventListeners.isEmpty()) return
        val frame = envelope.toFrame()
        observerQueue.execute {
            for ((owner, type, listener) in eventListeners) {
                if (!owner.guard.disabled && (type == "*" || type == frame.type)) owner.call("event(${frame.type})") { listener(frame) }
            }
        }
    }

    /** The plugins' policy presets (POLICY-1's provider seam); fail open. */
    fun presets(): List<PolicyPreset> = presetHooks.flatMap { h ->
        if (h.owner.guard.disabled) emptyList() else h.owner.call("policyProvider") { h.value.presets() }.getOrDefault(emptyList())
    }

    /** True when a loaded, healthy plugin implements a preset's `seats`. */
    val seatsClaimed: Boolean get() = seatClaimants.any { !it.guard.disabled }

    /** The action of a plugin-defined frame, or of `plugin_tool_call` naming a plugin tool; null = not a plugin's. */
    fun actionFor(envelope: Envelope): ActionCatalog.Action? = when (envelope.type) {
        PLUGIN_TOOL_CALL -> toolName(envelope.data)?.let { if (it == EVENT_EMIT_TOOL) EVENT_EMIT_ACTION else if (it == TESTS_TOOL) TESTS_ACTION else toolEntries[it]?.action }
        else -> frameHandlers[envelope.type]?.action
    }

    /** Every plugin frame's catalog entry (merged into the `policy` frame's catalog). */
    fun frameActions(): Map<String, ActionCatalog.Action> = frameHandlers.mapValues { it.value.action }

    fun handlesFrame(type: String): Boolean = frameHandlers.containsKey(type)

    /** Owner-only plugin frames (core/official may declare them). */
    fun ownerOnly(type: String): Boolean = frameHandlers[type]?.ownerOnly == true

    /** The answer of the plugin frame [envelope]; an error envelope when the plugin failed. */
    fun answerFrame(envelope: Envelope, caller: Caller): Envelope? {
        val entry = frameHandlers[envelope.type] ?: return Envelope(Envelope.ERROR, "unknown frame type '${envelope.type}'")
        val r = entry.owner.call("frame(${envelope.type})") { entry.handler(envelope.toFrame(), caller) }
        return r.fold(
            onSuccess = { it?.let { f -> Envelope(f.type, f.data, f.sessionId) } },
            onFailure = { Envelope(Envelope.ERROR, "${envelope.type} failed: plugin ${entry.owner.id} ${it.message ?: it::class.simpleName}") },
        )
    }

    // ---- tools ------------------------------------------------------------------------------------------------------

    /** The wall clock one call of [tool] gets: its own declared timeoutMs capped at limits.plugins.toolMaxTimeoutMs, else the module's. */
    private fun toolTimeoutMs(owner: Loaded, tool: DaemonTool): Long =
        tool.timeoutMs?.takeIf { it > 0 }?.coerceAtMost(services.limit(LimitRegistry.PLUGIN_TOOL_MAX_TIMEOUT_MS)) ?: owner.toolTimeoutMs

    /**
     * SCRIPT-3: `infantry_event_emit`, the one built-in tool: any role publishes a named event (name, optional JSON payload, optional
     * causeId / depth). The source is `session:<id>` of the calling MCP process, else `cli`.
     */
    private fun emitTool(arguments: JsonObject, client: JsonObject): ToolResult {
        val name = (arguments["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val payload = arguments["payload"]?.let { if (it is JsonPrimitive && it.isString) it.content else it.toString() } ?: "{}"
        val causeId = (arguments["causeId"] as? JsonPrimitive)?.contentOrNull
        val depth = (arguments["depth"] as? JsonPrimitive)?.intOrNull ?: 0
        val sid = (client["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        return try {
            val e = publishEvent(name, payload, sid?.let { "session:$it" } ?: "cli", causeId, depth)
            ToolResult("emitted ${e.name} id=${e.id} cause=${e.causeId} depth=${e.depth}")
        } catch (ex: IllegalArgumentException) {
            ToolResult("$EVENT_EMIT_TOOL refused: ${ex.message}", isError = true)
        }
    }

    /** Core's way to publish a named event (the daemon's own happenings, the emit tool). */
    fun publishEvent(name: String, payload: String = "{}", source: String = "daemon", causeId: String? = null, depth: Int = 0): NamedEvent =
        namedEvents.publish(name, payload, source, causeId, depth)

    // ---- FULLSUITE-2: the background full-suite runner ------------------------------------------------------------

    private val testRun: com.superiorsoftllc.infantry.testrun.TestRunService by lazy { buildTestRun() }
    private var testSchedule: java.util.concurrent.ScheduledExecutorService? = null

    private fun buildTestRun(): com.superiorsoftllc.infantry.testrun.TestRunService {
        val handle = services.storageHandle
        val ledgers = if (handle != null) com.superiorsoftllc.infantry.testrun.SqlLedgerStore(LocalPluginStorage(handle, "testrun"))
        else com.superiorsoftllc.infantry.testrun.MemoryLedgerStore()
        val owner = Caller(owner = true)
        fun sink(projectId: String) = com.superiorsoftllc.infantry.testrun.ServiceFailureSink(
            createTicket = { title, desc ->
                when (val r = services.tickets.create(com.superiorsoftllc.infantry.daemon.api.board.TicketDraft(title = title, description = desc, projectId = projectId, status = "todo"), owner)) {
                    is com.superiorsoftllc.infantry.daemon.api.board.BoardResult.Ok -> r.value.id
                    is com.superiorsoftllc.infantry.daemon.api.board.BoardResult.Refused -> error(r.text)
                    is com.superiorsoftllc.infantry.daemon.api.board.BoardResult.Unavailable -> error(r.reason)
                }
            },
            commentTicket = { id, text ->
                val args = JsonObject(mapOf("ticketId" to JsonPrimitive(id), "kind" to JsonPrimitive("note"), "body" to JsonPrimitive(text)))
                callTool("infantry_ticket_comment", args, owner) { _, _ -> error("no connection") }
            },
            notifyPm = { text -> services.sessionNotifier.notifyRole(projectId, "pm", "New failing test", text) },
        )
        return com.superiorsoftllc.infantry.testrun.TestRunService(
            ledgers = ledgers,
            data = { services.projectData(it) },
            envFor = { pid ->
                services.projects.list().firstOrNull { it.id == pid }?.directory?.takeIf { it.isNotBlank() }
                    ?.let { com.superiorsoftllc.infantry.testrun.GitSuiteEnv(File(it), File(stateDir, "testrun/$pid")) }
            },
            exec = com.superiorsoftllc.infantry.testrun.ProcessSuiteExec(),
            sinkFor = ::sink,
            onStatus = { pid, line -> services.broadcast("tests_status", "$pid	$line") },
        )
    }

    /** The optional schedule (`tests.scheduleMinutes` project data, off by default): checked every few minutes. */
    fun startTestSchedule(everyMinutes: Long = 5) {
        if (testSchedule != null) return
        testSchedule = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "full-suite-schedule").apply { isDaemon = true } }.also { ex ->
            ex.scheduleWithFixedDelay({ runCatching { testRun.tick(services.projects.list().map { it.id }) } }, everyMinutes, everyMinutes, java.util.concurrent.TimeUnit.MINUTES)
        }
    }

    /** `infantry_tests`: `run` starts the background full suite of the caller's project, `status` reads the last result. Open to every role. */
    private fun testsTool(arguments: JsonObject, client: JsonObject): ToolResult {
        val action = (arguments["action"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val pid = (arguments["projectId"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: (client["projectId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult("$TESTS_TOOL needs a project (pass projectId)", isError = true)
        return when (action) {
            "run" -> ToolResult(runCatching { testRun.run(pid) }.getOrElse { return ToolResult("$TESTS_TOOL refused: ${it.message}", isError = true) })
            "status" -> ToolResult(testRun.status(pid))
            "stop" -> ToolResult(testRun.stop(pid))
            else -> ToolResult("usage: infantry tests run|status|stop [--project <id>]", isError = true)
        }
    }

    private val testsDescriptor = JsonObject(mapOf(
        "name" to JsonPrimitive(TESTS_TOOL),
        "description" to JsonPrimitive("Background full-suite runner: action run starts the project's configured suite (tests.* project data) without blocking; status shows the last result; stop ends the run in flight. New failures become tickets."),
        "inputSchema" to Json.parseToJsonElement("""{"type":"object","properties":{"action":{"type":"string","enum":["run","status","stop"]},"projectId":{"type":"string","description":"Default: the caller's project"}},"required":["action"]}"""),
        "module" to JsonPrimitive("core"),
        "action" to JsonPrimitive(TESTS_ACTION.id),
        "timeoutMs" to JsonPrimitive(30_000),
    ))

    private val emitDescriptor = JsonObject(mapOf(
        "name" to JsonPrimitive(EVENT_EMIT_TOOL),
        "description" to JsonPrimitive("Publish a named event (dotted lower-case name, e.g. merge.done) with an optional JSON object payload; plugins subscribed to the name or its prefix react. Open to every role."),
        "inputSchema" to Json.parseToJsonElement("""{"type":"object","properties":{"name":{"type":"string","description":"Dotted event name, e.g. merge.done"},"payload":{"type":"string","description":"A JSON object as text (default {})"},"causeId":{"type":"string","description":"Id of the event that caused this one (default: itself)"},"depth":{"type":"integer","description":"Hops from the root event (default 0)"}},"required":["name"]}"""),
        "module" to JsonPrimitive("core"),
        "action" to JsonPrimitive(EVENT_EMIT_ACTION.id),
        "timeoutMs" to JsonPrimitive(10_000),
    ))

    /** `plugin_tools`: every healthy plugin tool, as MCP `tools/list` entries. */
    fun toolDescriptors(): JsonArray = JsonArray(listOf(emitDescriptor, testsDescriptor) +
        toolEntries.values.filter { !it.owner.guard.disabled }.sortedBy { it.tool.name }.map { e ->
            JsonObject(buildMap {
                put("name", JsonPrimitive(e.tool.name))
                put("description", JsonPrimitive(e.tool.description))
                put("inputSchema", e.tool.inputSchema)
                if (e.tool.annotations.isNotEmpty()) put("annotations", e.tool.annotations)
                put("module", JsonPrimitive(e.owner.id))
                put("action", JsonPrimitive(e.action.id))
                put("timeoutMs", JsonPrimitive(toolTimeoutMs(e.owner, e.tool)))
                // RUNTIME-LOAD-4 (contract 1.147.0): the arguments the client sends file bytes for (daemon.api.LocalFiles).
                if (e.tool.localFiles.isNotEmpty()) put(com.superiorsoftllc.infantry.daemon.api.LocalFiles.DESCRIPTOR_KEY, JsonArray(e.tool.localFiles.map { JsonPrimitive(it) }))
            })
        },
    )

    /**
     * `plugin_tool_call`: runs tool [name] with [arguments] as [caller]; [request] dispatches a frame on the caller's own
     * connection and waits for its answer. The policy has already gated the call by the tool's action.
     */
    fun callTool(name: String, arguments: JsonObject, caller: Caller, client: JsonObject = JsonObject(emptyMap()), request: (Envelope, Long) -> Envelope): ToolResult =
        kotlinx.coroutines.runBlocking { callToolSuspending(name, arguments, caller, client, request) }

    /** BOARD-STALL-2: [callTool] for the daemon's request path: suspends while the plugin works, holding no Dispatchers.IO thread. */
    suspend fun callToolSuspending(name: String, arguments: JsonObject, caller: Caller, client: JsonObject = JsonObject(emptyMap()), request: (Envelope, Long) -> Envelope): ToolResult {
        if (com.superiorsoftllc.infantry.cli.mcp.LegacyToolNames.canonical(name) == EVENT_EMIT_TOOL) return emitTool(arguments, client)
        if (com.superiorsoftllc.infantry.cli.mcp.LegacyToolNames.canonical(name) == TESTS_TOOL) return testsTool(arguments, client)
        val entry = (toolEntries[name] ?: toolEntries[com.superiorsoftllc.infantry.cli.mcp.LegacyToolNames.canonical(name)]) ?: return ToolResult("Unknown tool: $name", isError = true)
        if (entry.owner.guard.disabled) return ToolResult("$name is unavailable: plugin ${entry.owner.id} is disabled", isError = true)
        val call = object : ToolCall {
            override val arguments = arguments
            override val caller = caller
            override val clientSessionId = (client["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            override val clientRole = (client["role"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            override val clientProjectId = (client["projectId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            override val clientNarrowing = narrowingOf(client)
            override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame =
                request(Envelope(type, data, sessionId), timeoutMs).toFrame()
        }
        if (entry.owner.id == "Infantry_Board" || entry.owner.id == "Board") log("[plugins] board tool call: $name caller=${caller.owner} session=${call.clientSessionId ?: "-"} role=${call.clientRole ?: "-"}")
        return entry.owner.callSuspending("tool($name)", toolTimeoutMs(entry.owner, entry.tool)) { entry.tool.call(call) }
            .getOrElse { ToolResult("$name failed: ${it.message ?: it::class.simpleName}", isError = true) }
    }

    /**
     * SCRIPT-4: a tool call made by the daemon for an agent (a script's action): the same two gates a `plugin_tool_call` frame
     * passes (the active policy preset's action refusal, then the plugin frame gates), then [callTool]. A tool that wants a frame on a
     * connection finds none and answers an error.
     */
    /** SCRIPT-HOOK-3: how a daemon-run tool call gets the frames its tool sends; set by the daemon once it is listening. */
    @Volatile var loopback: LoopbackFrames? = null

    fun invokeTool(name: String, arguments: JsonObject, by: ToolInvoker): ToolResult {
        val data = JsonObject(mapOf("name" to JsonPrimitive(name), "arguments" to arguments)).toString()
        val envelope = Envelope(PLUGIN_TOOL_CALL, data, by.sessionId)
        services.policy().refusal(envelope.toFrame())?.let { return ToolResult(it, isError = true) }
        frameRefusal(envelope, by.caller)?.let { return ToolResult(it, isError = true) }
        val client = JsonObject(listOfNotNull(
            by.sessionId?.let { "sessionId" to JsonPrimitive(it) }, by.role?.let { "role" to JsonPrimitive(it) }, by.projectId?.let { "projectId" to JsonPrimitive(it) },
        ).toMap())
        return callTool(name, arguments, by.caller, client) { r, timeoutMs ->
            val lb = loopback ?: return@callTool Envelope(Envelope.ERROR, "${r.type} is not available to ${by.label.ifBlank { "a daemon-run call" }}")
            lb.request(r, by.caller.owner, timeoutMs)
        }
    }

    // ---- routes -----------------------------------------------------------------------------------------------------

    /** The absolute routes plugins answer (`METHOD path`), registered on the server once. */
    fun topLevelRoutes(): List<Pair<String, String>> = routeEntries.values.filter { it.path.startsWith("/") && !it.path.startsWith(PLUGIN_PREFIX) }
        .map { it.method to it.path }.distinct()

    /** True when `[method] [path]` is a plugin's declared public route (answered without the bearer token). */
    fun isPublicRoute(method: String, path: String): Boolean = findRoute(method, path)?.first?.let { it.public && !it.owner.guard.disabled } == true

    /** TRIG-2a: the exact route, else the first template route whose segments match; with the bound path params. */
    private fun findRoute(method: String, path: String): Pair<RouteEntry, Map<String, String>>? {
        routeEntries[key(method, path)]?.let { return it to emptyMap() }
        val m = method.uppercase()
        val parts = path.trim('/').split('/')
        for (e in routeEntries.values) {
            val t = e.template ?: continue
            if (e.method != m) continue
            matchTemplate(t, parts)?.let { return e to it }
        }
        return null
    }

    /** The answer of a plugin route, or null when no plugin answers it (a 404 for the caller). */
    fun answerRoute(call: HttpCall): HttpReply? {
        val (entry, pathParams) = findRoute(call.method, call.path) ?: return null
        if (entry.owner.guard.disabled) return HttpReply(503, "plugin ${entry.owner.id} is disabled")
        val owner = entry.owner
        synchronized(owner) {
            val now = System.currentTimeMillis()
            if (now - owner.routeWindowStart > 60_000) { owner.routeWindowStart = now; owner.routeCount = 0 }
            if (++owner.routeCount > owner.routeRate && owner.routeRate > 0) return HttpReply(429, "plugin ${owner.id}: more than ${owner.routeRate} requests a minute")
        }
        return owner.call("route(${call.method} ${call.path})") { entry.handler(if (pathParams.isEmpty()) call else call.copy(pathParams = pathParams)) }
            .getOrElse { HttpReply(500, "plugin ${owner.id} failed: ${it.message ?: it::class.simpleName}") }
    }

    // ---- PLUGIN-HOTLOAD-1: load, unload and reload ONE daemon half while the host runs ------------------------------

    /** What a live load reads: the plugins root as it is NOW (re-resolved), its grants file, and the trust that boot used. */
    class HotSource(val resolution: Resolution, val config: ModulesConfig, val trust: Trust)

    /** Set by the daemon once it is up: re-reads the plugins root and the loading rules, so [load] sees what was just installed. Null = no live load. */
    @Volatile var hotSource: (() -> HotSource)? = null

    /**
     * Serialises load / unload / reload (one at a time). Readers never take it: every registry is a concurrent map or a
     * copy-on-write list, a removal is one atomic step per registry, and a request that already holds an entry runs to its end
     * through the owner's guard (see [retire]).
     */
    private val lifecycle = Any()

    /** True when the daemon half of [module] is in the host (healthy or failed). */
    fun isLoaded(module: String): Boolean = loaded.any { it.id == module }

    private inline fun withSource(block: (HotSource) -> HotOutcome): HotOutcome {
        val src = hotSource ?: return HotOutcome(false, "this host cannot load plugins live (no plugin source set)")
        val fresh = runCatching { src() }.getOrElse { return HotOutcome(false, "could not re-read the plugins root: ${it.message}") }
        return block(fresh)
    }

    /** Starts the daemon half of [id], resolving it against the loaded set as boot does. Dependencies must already be loaded. */
    override fun load(id: String): HotOutcome = withSource { load(id, it) }
    override fun unload(id: String): HotOutcome = synchronized(lifecycle) { unloadLocked(id) }
    override fun reload(id: String): HotOutcome = withSource { reload(id, it) }

    fun load(id: String, source: HotSource): HotOutcome = synchronized(lifecycle) { loadLocked(id, source) }

    /** Unload then load of [id], then every module that had to leave with it, in dependency order. */
    fun reload(id: String, source: HotSource): HotOutcome = synchronized(lifecycle) {
        val out = unloadLocked(id)
        val self = loadLocked(id, source)
        val back = if (self.ok) out.unloaded.filter { it != id }.reversed().map { loadLocked(it, source) } else emptyList()
        val failed = back.filter { !it.ok }
        HotOutcome(
            self.ok && failed.isEmpty(),
            buildString {
                append(self.message)
                val again = back.flatMap { it.loaded }
                if (again.isNotEmpty()) append("; dependents loaded again: ${again.joinToString()}")
                failed.forEach { append("; ${it.message}") }
            },
            unloaded = out.unloaded, loaded = self.loaded + back.flatMap { it.loaded }, notes = (out.notes + self.notes + back.flatMap { it.notes }).distinct(),
        )
    }

    private fun loadLocked(id: String, src: HotSource): HotOutcome {
        loaded.firstOrNull { it.id == id }?.let { existing ->
            if (!existing.guard.disabled) return HotOutcome(true, "$id is already loaded")
            unloadLocked(id) // a failed one: take it out first (frees its class loader), then start it afresh
        }
        val s = src.resolution.status(id) ?: return HotOutcome(false, "no module '$id' in the plugins root")
        if (s.state != ModuleState.LOADED) return HotOutcome(false, "$id does not load: ${s.state.name.lowercase()}${s.reason?.let { " ($it)" }.orEmpty()}")
        val m = s.manifest ?: return HotOutcome(false, "$id has no readable module.json")
        val daemon = m.daemon
        val notes = mutableListOf<String>()
        if (m.harness != null) notes += "the harness half of $id loads at the next daemon start (harness halves are start-only)"
        if (daemon?.entry.isNullOrBlank()) return HotOutcome(true, "$id has no daemon half; nothing to load", notes = notes)
        for (dep in m.dependencies) {
            val ds = src.resolution.status(dep.id)
            if (ds?.manifest?.daemon?.entry?.isNotBlank() == true && loaded.none { it.id == dep.id && !it.guard.disabled }) {
                return HotOutcome(false, "$id depends on ${dep.id}, whose daemon half is not loaded; load ${dep.id} first", notes = notes)
            }
        }
        if (daemon != null && "orchestratorBinding" in daemon.hooks) notes += "an orchestrator binding of $id is offered to the hub at daemon start only"
        runCatching { load(s, src.config, src.trust) }
            .onFailure { statuses[id] = "refused: ${it.message}"; log("[plugins] daemon half of $id refused: ${it.message}") }
        // A start that failed leaves a disabled plugin behind: free its class loader at once (the status keeps the reason).
        loaded.firstOrNull { it.id == id }?.takeIf { it.guard.disabled }?.let { failed -> retire(failed); loaded.remove(failed) }
        val status = statuses[id] ?: "refused"
        return if (status == "loaded") HotOutcome(true, "loaded $id: ${toolsOf(id).size} tool(s)", loaded = listOf(id), notes = notes)
        else HotOutcome(false, "$id did not load ($status)", notes = notes)
    }

    private fun unloadLocked(id: String): HotOutcome {
        val target = loaded.firstOrNull { it.id == id } ?: return HotOutcome(true, "$id has no loaded daemon half")
        // A dependent cannot run without it: they leave first (reverse load order), and are reported.
        val gone = mutableSetOf(id)
        var grew = true
        while (grew) {
            grew = false
            for (p in loaded) if (p.id !in gone && p.dependencies.any { it in gone }) { gone += p.id; grew = true }
        }
        val leaving = loaded.reversed().filter { it.id in gone && it !== target } + target
        val notes = leaving.filter { o -> orchestratorBindings.any { it.owner === o } }.map { "the orchestrator binding of ${it.id} stays on the hub until the next daemon start" }
        for (p in leaving) {
            retire(p)
            loaded.remove(p)
            statuses.remove(p.id)
        }
        val names = leaving.map { it.id }
        log("[plugins] unloaded daemon half of ${names.joinToString()}")
        val dependents = names.filter { it != id }
        return HotOutcome(true, "unloaded $id" + if (dependents.isEmpty()) "" else " (and its dependents ${dependents.joinToString()})", unloaded = names, notes = notes)
    }

    /**
     * Takes one plugin out of service: its registrations go first (a new request no longer finds it), then its background work,
     * then `stop` under the stop timeout, then its guard drains (a call in flight finishes, or is interrupted at the stop
     * timeout, and a failure caused by leaving is not a plugin failure), then its class loader closes so the jar can be deleted.
     */
    private fun retire(p: Loaded) {
        p.unloading = true
        dropRegistrations(p)
        p.closeables.forEach { runCatching { it.close() } }
        val stopMs = services.limit(LimitRegistry.PLUGIN_STOP_TIMEOUT_MS)
        if (!p.guard.disabled && p.plugin != null) {
            p.call("stop", stopMs) { p.plugin?.stop() }.onFailure { log("[plugins] ${p.id}: stop failed while unloading: ${it.message}") }
        }
        p.guard.retire(stopMs)
        runCatching { p.loader.close() }.onFailure { log("[plugins] ${p.id}: could not close its class loader: ${it.message}") }
    }

    /** EVERYTHING [p] registered, public routes and gates included (a planned unload, unlike a failure, removes its gates). */
    private fun dropRegistrations(p: Loaded) {
        toolEntries.values.removeIf { it.owner === p }
        frameHandlers.values.removeIf { it.owner === p }
        routeEntries.values.removeIf { it.owner === p }
        frameGates.removeIf { it.owner === p }
        approvalRuleHooks.removeIf { it.owner === p }
        httpGates.removeIf { it.owner === p }
        admissions.removeIf { it.owner === p }
        mailGates.removeIf { it.owner === p }
        incomingMailHooks.removeIf { it.owner === p }
        spawnGates.removeIf { it.owner === p }
        connectedHooks.removeIf { it.owner === p }
        statusHooks.removeIf { it.owner === p }
        readyHooks.removeIf { it.owner === p }
        turnHooks.removeIf { it.owner === p }
        sessionHooks.removeIf { it.owner === p }
        presetHooks.removeIf { it.owner === p }
        orchestratorBindings.removeIf { it.owner === p }
        eventListeners.removeIf { it.first === p }
        seatClaimants.remove(p)
    }

    override fun close() = synchronized(lifecycle) { closeAll() }

    private fun closeAll() {
        for (p in loaded.reversed()) {
            p.closeables.forEach { runCatching { it.close() } }
            if (!p.guard.disabled) p.call("stop", services.limit(LimitRegistry.PLUGIN_STOP_TIMEOUT_MS)) { p.plugin?.stop() }
            p.guard.shutdown()
            runCatching { p.loader.close() }
        }
        observerQueue.shutdownNow()
        testSchedule?.shutdownNow()
    }

    // ---- loading ----------------------------------------------------------------------------------------------------

    private fun failClosed(owner: Loaded, what: String): String = "refused: plugin ${owner.id}'s $what failed (fail closed)"

    /** BOARD-DOWN-1: a plugin marked failed is mailed to every live pm, so an outage is seen at once (generic, any plugin). */
    private fun announceFailure(id: String, why: String) {
        observerQueue.execute {
            runCatching {
                val subject = "Daemon plugin $id FAILED"
                val body = "Daemon plugin $id was marked failed and unregistered: $why. Its tools and frames are gone until the daemon restarts."
                services.projects.list().forEach { services.sessionNotifier.notifyRole(it.id, "pm", subject, body) }
            }.onFailure { log("[plugins] could not announce failure of $id: ${it.message}") }
        }
    }

    private fun announceTrip(id: String, why: String) = announceNotice("Daemon plugin $id cooling down", "Daemon plugin $id timed out repeatedly ($why). Its calls are refused for a short cooldown, then it is probed and comes back by itself; no restart needed.")

    private fun announceRecovered(id: String) = announceNotice("Daemon plugin $id recovered", "Daemon plugin $id answered a probe call and is back in service.")

    private fun announceNotice(subject: String, body: String) {
        observerQueue.execute {
            runCatching { services.projects.list().forEach { services.sessionNotifier.notifyRole(it.id, "pm", subject, body) } }
                .onFailure { log("[plugins] could not announce '$subject': ${it.message}") }
        }
    }

    private fun unregister(p: Loaded) {
        toolEntries.entries.removeIf { it.value.owner === p }
        frameHandlers.entries.removeIf { it.value.owner === p }
        routeEntries.entries.removeIf { it.value.owner === p && !it.value.public }
        // Gates stay registered: a disabled gate is fail closed. Observers and providers are skipped while disabled.
        p.closeables.forEach { runCatching { it.close() } }
    }

    private fun load(status: ModuleStatus, config: ModulesConfig, trust: Trust) {
        val m = status.manifest ?: return
        val d = m.daemon ?: return
        val entry = d.entry?.takeIf { it.isNotBlank() } ?: return
        val id = m.name
        fun refuse(why: String) { statuses[id] = "refused: $why"; log("[plugins] daemon half of $id refused: $why") }
        val setting = config.setting(id)
        val dir = File(status.dir ?: return refuse("no folder"))
        refusal(status.tier, d, setting, id)?.let { return refuse(it) }
        val jarPath = d.jar?.takeIf { it.isNotBlank() } ?: return refuse("daemon.entry without daemon.jar")
        val jar = File(dir, jarPath)
        if (!jar.canonicalPath.startsWith(dir.canonicalPath + File.separator)) return refuse("daemon.jar $jarPath is outside the module folder")
        // PACKAGE-1: a declared half whose jar is not there (a build that did not ship it, or a dev tree not built yet).
        if (!jar.isFile) return refuse("jar missing: daemon.jar $jarPath is not in ${dir.path} (build it: :daemon-plugin-<folder>-$id:jar; a package ships it)")
        if (status.tier == Tier.OFFICIAL && !trust.trusts(dir)) {
            OfficialTrust.refusal(ModuleCandidate(dir.name, status.tier, dir.absolutePath, m), jarPath, trust.keys)?.let { return refuse("official daemon jar refused: $it") }
        }
        val loader = URLClassLoader("plugin-$id", arrayOf(jar.toURI().toURL()), DaemonApiClassLoader(DaemonPluginHost::class.java.classLoader))
        val cpu = d.limits.cpuMsPerCall ?: maxOf(d.limits.callTimeoutMs ?: services.limit(LimitRegistry.PLUGIN_CALL_TIMEOUT_MS), d.limits.toolTimeoutMs ?: services.limit(LimitRegistry.PLUGIN_TOOL_TIMEOUT_MS))
        lateinit var p: Loaded
        val guard = PluginGuard(id, loader, cpu, log, hangCeilingMs = services.limit(LimitRegistry.PLUGIN_HANG_CEILING_MS), poolSize = services.limit(LimitRegistry.PLUGIN_POOL_SIZE).toInt(), queueLimit = services.limit(LimitRegistry.PLUGIN_QUEUE_LIMIT).toInt(), timeoutStrikes = services.limit(LimitRegistry.PLUGIN_GUARD_STRIKES).toInt(), cooldownMs = services.limit(LimitRegistry.PLUGIN_GUARD_COOLDOWN_MS), onTripped = { why -> announceTrip(id, why) }, onRecovered = { announceRecovered(id) }, onDisabled = { why -> if (!p.unloading) { unregister(p); announceFailure(id, why) } })
        p = Loaded(id, status.tier, d, setting, guard, loader, m.dependencies.map { it.id })
        loaded += p
        val api = Api(p)
        val started = p.call("start", services.limit(LimitRegistry.PLUGIN_START_TIMEOUT_MS)) {
            val plugin = loader.loadClass(entry).getDeclaredConstructor().newInstance() as? DaemonPlugin
                ?: throw IllegalStateException("$entry does not implement DaemonPlugin")
            p.plugin = plugin
            plugin.start(api)
        }
        if (started.isFailure) {
            statuses[id] = "failed: ${guard.disabledReason ?: started.exceptionOrNull()?.message}"
            return
        }
        statuses[id] = "loaded"
        log("[plugins] daemon half of $id (${status.tier.name.lowercase()}) loaded: ${toolsOf(id).size} tool(s), hooks ${p.hookKinds.sorted()}")
    }

    /** Why the declarations of [id] may not load for [tier] and its grants; null = they may. */
    private fun refusal(tier: Tier, d: DaemonCapabilities, setting: ModuleSetting, id: String): String? {
        d.apiAtLeast?.let { need ->
            val wanted = SemVer.parseOrNull(need) ?: return "daemon.apiAtLeast '$need' is not a version"
            if (wanted > SemVer.parse(DaemonApi.VERSION)) return "needs daemon API $need, this daemon has ${DaemonApi.VERSION}"
        }
        d.hooks.firstOrNull { it !in HOOK_KINDS }?.let { return "unknown hook kind '$it' (known: ${HOOK_KINDS.joinToString()})" }
        d.tools.firstOrNull { !TOOL_NAME.matches(it.name) }?.let { return "tool name '${it.name}' must match ${TOOL_NAME.pattern}" }
        if (tier != Tier.COMMUNITY) return null
        if (d.storage.adopt.isNotEmpty()) return "a community module may not adopt core tables (daemon.storage.adopt)"
        val allow = setting.allow.toSet()
        if ("daemon.code" !in allow) return "community daemon code needs the 'daemon.code' grant in modules.json"
        if (d.routes.isNotEmpty() || d.publicRoutes.isNotEmpty()) return "a community module may not declare top-level or public routes"
        if ("admission" in d.hooks) return "a community module may not admit tokens"
        d.frames.firstOrNull { it.ownerOnly }?.let { return "a community module may not declare an owner-only frame (${it.type})" }
        val vendor = id.substringBefore('_').lowercase() + "_"
        d.tools.firstOrNull { !it.name.startsWith(vendor) }?.let { return "community tool '${it.name}' must start with '$vendor'" }
        val needed = buildList {
            if (d.tools.isNotEmpty()) add("daemon.tool")
            if (d.frames.isNotEmpty()) add("daemon.frame")
            d.hooks.forEach { add("daemon.hook.$it") }
            d.env.forEach { add("daemon.env:$it") }
            d.secrets.forEach { add("daemon.secret:$it") }
        }
        needed.firstOrNull { it !in allow }?.let { return "missing grant '$it' in modules.json" }
        return null
    }

    /** Which official folders are trusted without the signature check. */
    class Trust(val keys: List<X509Certificate>, val devTrust: Boolean, val bundledRoot: File?) {
        fun trusts(dir: File): Boolean = devTrust ||
            bundledRoot?.let { dir.canonicalPath.startsWith(it.canonicalPath + File.separator) } == true
    }

    // ---- the API one plugin sees ------------------------------------------------------------------------------------

    private inner class Api(private val p: Loaded) : DaemonApi {
        private val hostServices = this@DaemonPluginHost.services
        private val d = p.declared
        override val moduleId = p.id
        override val tier = p.tier.name.lowercase()
        override val apiVersion = DaemonApi.VERSION
        override val stateDir: File by lazy { File(this@DaemonPluginHost.stateDir, "plugin-data/${p.id}").apply { mkdirs() } }

        override fun log(line: String) = this@DaemonPluginHost.log("[plugin ${p.id}] $line")
        override fun config(key: String): String? = hostServices.config(p.id, key)
        override fun env(name: String): String? = if (name in d.env) hostServices.env(name) else { log("env $name is not declared in daemon.env: refused"); null }
        override fun secret(name: String): String? = if (name in d.secrets) hostServices.secret(name) else { log("secret $name is not declared in daemon.secrets: refused"); null }

        override fun schedule(name: String, periodMs: Long, job: () -> Unit): AutoCloseable {
            val period = periodMs.coerceAtLeast(1_000)
            val timer = java.util.Timer("plugin-${p.id}-$name", true)
            timer.scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() { if (p.guard.disabled) cancel() else p.call("schedule($name)") { job() } }
            }, period, period)
            return AutoCloseable { timer.cancel() }.also { p.closeables += it }
        }

        override val projects: Projects get() = hostServices.projects
        override val sessions: Sessions get() = hostServices.sessions
        override val policy: Policy = object : Policy {
            override fun active() = hostServices.policy().active()
            override fun onChange(listener: (PolicyPreset) -> Unit): AutoCloseable =
                hostServices.policy().onChange { preset -> if (!p.guard.disabled) p.call("policy.onChange", hostServices.limit(LimitRegistry.PLUGIN_START_TIMEOUT_MS)) { listener(preset) } }.also { p.closeables += it }
            override fun actionOf(frame: Frame) = hostServices.policy().actionOf(frame)
            override fun refusal(frame: Frame) = hostServices.policy().refusal(frame)
            override fun refusalText(type: String) = hostServices.policy().refusalText(type)
            override fun reportSeating(error: String?) = hostServices.policy().reportSeating(error)
            override fun claimSeats() { declare("seats"); if (p !in seatClaimants) seatClaimants += p }
        }
        override val events: Events = object : Events {
            override fun on(type: String, listener: (Frame) -> Unit): AutoCloseable {
                val entry = Triple(p, type, listener)
                eventListeners += entry
                return AutoCloseable { eventListeners -= entry }.also { p.closeables += it }
            }

            // DP-7d (1.18.0): a session killed or removed; the plugin's session-scoped data goes with it.
            override fun onSessionRemoved(listener: (String) -> Unit): AutoCloseable =
                hostServices.onSessionRemoved { id -> if (!p.guard.disabled) p.call("events.onSessionRemoved") { listener(id) } }.also { p.closeables += it }

            // SCRIPT-3 (1.26.0): generic named events. A listener runs under the plugin's limits; a disabled plugin hears nothing.
            override fun publish(name: String, payload: String, causeId: String?, depth: Int): NamedEvent =
                namedEvents.publish(name, payload, p.id, causeId, depth)
            override fun subscribe(pattern: String, listener: (NamedEvent) -> Unit): AutoCloseable =
                namedEvents.subscribe(pattern) { e -> if (!p.guard.disabled) p.call("events.subscribe($pattern)") { listener(e) } }.also { p.closeables += it }

            // ATTN-2a (1.24.0): a frame to every connected client; official / core only.
            override fun broadcast(type: String, data: String) {
                check(!p.community) { "community modules may not broadcast to clients" }
                hostServices.broadcast(type, data)
            }

            // DP-4b: the typed ticket events. A community plugin needs tickets.read (a throw here disables the plugin, as a bad declaration does).
            override fun onTicket(consumer: String, listener: (TicketEvent) -> Unit): AutoCloseable {
                require(consumer.isNotBlank()) { "a ticket event consumer needs a name" }
                return granted.ticketEvents { hostServices.ticketEvents }.subscribe("plugin:${p.id}:$consumer") { e ->
                    if (!p.guard.disabled) p.call("events.onTicket($consumer)") { listener(e) }.getOrThrow()
                }.also { p.closeables += it }
            }
        }
        override val tools: Tools = object : Tools {
            override fun catalog(): List<ToolInfo> = toolDescriptors().mapNotNull { it as? JsonObject }.map { d ->
                ToolInfo(
                    (d["name"] as JsonPrimitive).content, (d["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    d["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
                    ((d["annotations"] as? JsonObject)?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull == true,
                )
            }

            override fun invoke(name: String, arguments: JsonObject, `as`: ToolInvoker): ToolResult {
                check(!p.community) { "community modules may not invoke tools" }
                return invokeTool(name, arguments, `as`)
            }

            override fun register(tool: DaemonTool) {
                val decl = d.tools.firstOrNull { it.name == tool.name } ?: error("tool '${tool.name}' is not declared in daemon.tools")
                check(toolEntries[tool.name] == null) { "tool '${tool.name}' is already registered by ${toolEntries[tool.name]?.owner?.id}" }
                // A core or official tool may share a core action of the same kind (a tool moved out of core keeps its gating);
                // a community tool never borrows one, so a preset that allows a core action never lets an unknown tool through.
                ActionCatalog.forId(decl.action)?.let { core ->
                    check(!p.community) { "tool action '${decl.action}' is a core action id" }
                    check(core.kind == decl.kind) { "tool action '${decl.action}' is core's  action, declared " }
                }
                toolEntries[tool.name] = ToolEntry(p, tool, ActionCatalog.Action(decl.action, decl.kind, listOf(PLUGIN_TOOL_CALL)))
            }
        }
        override val frames: Frames = object : Frames {
            override fun handle(type: String, handler: (Frame, Caller) -> Frame?) {
                val decl = d.frames.firstOrNull { it.type == type } ?: error("frame '$type' is not declared in daemon.frames")
                // DP-5h-3e-b: the board plugin answers frames the core catalog still names for policy: an OFFICIAL plugin may register one only under that same action.
                val core = ActionCatalog.forFrame(type)
                check(type !in CORE_FRAMES && (core == null || (!p.community && core.id == decl.action && core.kind == decl.kind))) { "frame '$type' is a core frame" }
                check(frameHandlers[type] == null) { "frame '$type' is already answered by ${frameHandlers[type]?.owner?.id}" }
                frameHandlers[type] = FrameEntry(p, ActionCatalog.Action(decl.action, decl.kind, listOf(type)), decl.ownerOnly, handler)
            }
        }
        override val routes: Routes = object : Routes {
            override fun handle(method: String, path: String, handler: (HttpCall) -> HttpReply) {
                val m = method.trim().uppercase()
                require(m in METHODS) { "unknown HTTP method '$method'" }
                val full: String
                if (path.startsWith("/")) {
                    check("$m $path" in d.routes) { "route '$m $path' is not declared in daemon.routes" }
                    check(path !in CORE_ROUTES && !path.startsWith(PLUGIN_PREFIX)) { "route $path belongs to core" }
                    full = path
                } else {
                    check(!p.community || "daemon.route" in p.setting.allow) { "missing grant 'daemon.route' in modules.json" }
                    require(path.isNotBlank() && ".." !in path) { "bad plugin route '$path'" }
                    full = "$PLUGIN_PREFIX${p.id}/${path.trimStart('/')}"
                }
                val k = key(m, full)
                check(routeEntries[k] == null) { "route $m $full is already answered by ${routeEntries[k]?.owner?.id}" }
                routeEntries[k] = RouteEntry(p, m, full, "$m $full" in d.publicRoutes, handler)
            }
        }
        // DP-4a: the board API behind this plugin's grants (community: default deny; bundled / official: trusted).
        private val granted = GrantedBoard({ hostServices.tickets }, { hostServices.epics }, { hostServices.board }) { grant -> !p.community || grant in p.setting.allow }
        override val tickets get() = granted.ticketsApi
        override val epics get() = granted.epicsApi
        override val board get() = granted.boardApi
        // RUNTIME-LOAD-5: the sessions read model behind this plugin's grants (community: default deny).
        override val sessionReads: com.superiorsoftllc.infantry.daemon.api.sessions.SessionReads =
            GrantedSessionReads({ hostServices.sessionReads }) { grant -> !p.community || grant in p.setting.allow }
        // DP-5h-3a: the board runtime for bundled / official plugins only (a community plugin builds no board).
        override val boardRuntime: com.superiorsoftllc.infantry.daemon.api.board.BoardRuntime
            get() = if (p.community) com.superiorsoftllc.infantry.daemon.api.board.NoBoardRuntime else hostServices.boardRuntime
        // RUNTIME-LOAD-6: the host spawn service behind this plugin's grants (community: `sessions.spawn`, default deny).
        override val spawns: com.superiorsoftllc.infantry.daemon.api.spawns.Spawns =
            GrantedSpawns({ hostServices.spawns }) { grant -> !p.community || grant in p.setting.allow }
        // STACK-NEUTRAL-2: the host process service (official / core; community needs `daemon.processes` and never kills), the
        // per-launch env seam and the settings reader. A listener / contributor ends with the plugin (closeables).
        override val processes: com.superiorsoftllc.infantry.daemon.api.processes.Processes by lazy {
            val host = hostServices.processHost
            if (host == null || (p.community && "daemon.processes" !in p.setting.allow)) com.superiorsoftllc.infantry.daemon.api.processes.NoProcesses
            else {
                val view = host.view(p.id, mayKill = !p.community)
                object : com.superiorsoftllc.infantry.daemon.api.processes.Processes by view {
                    override fun onSessionClosed(listener: (com.superiorsoftllc.infantry.daemon.api.processes.SessionClosed) -> Unit) =
                        view.onSessionClosed { c -> if (!p.guard.disabled) p.call("processes.onSessionClosed") { listener(c) } }.also { p.closeables += it }
                    // Not a closeable: the plugin is stopped (with the other background closers) BEFORE the sessions are shut down, and
                    // the last-moment sweep of what they left must still reach it afterwards.
                    override fun onDaemonShutdown(listener: () -> Unit) =
                        view.onDaemonShutdown { if (!p.guard.disabled) listener() }
                    override fun serve(handler: com.superiorsoftllc.infantry.daemon.api.processes.ProcessesHandler) =
                        view.serve(handler).also { p.closeables += it }
                }
            }
        }
        override val launchEnv: com.superiorsoftllc.infantry.daemon.api.processes.LaunchEnv = object : com.superiorsoftllc.infantry.daemon.api.processes.LaunchEnv {
            override fun contribute(name: String, contributor: (com.superiorsoftllc.infantry.daemon.api.processes.LaunchInfo) -> Map<String, String>): AutoCloseable {
                check(!p.community || "daemon.processes" in p.setting.allow) { "missing grant 'daemon.processes' in modules.json" }
                val host = hostServices.launchEnvHost ?: return AutoCloseable {}
                return host.contribute(p.id, name) { info -> if (p.guard.disabled) emptyMap() else contributor(info) }.also { p.closeables += it }
            }
        }
        override val settings: com.superiorsoftllc.infantry.daemon.api.processes.PluginSettings get() = hostServices.pluginSettings
        // PACE-TREND-1: the plan meters, read-only; a community plugin needs the grant `usage.read` (default deny: reads nothing).
        override val meters: com.superiorsoftllc.infantry.daemon.api.usage.Meters =
            GrantedMeters({ hostServices.meters }) { grant -> !p.community || grant in p.setting.allow }
        // DP-7e: the mail port; a community plugin needs the grant `sessions.notify`.
        override val sessionNotifier: com.superiorsoftllc.infantry.daemon.api.sessions.SessionNotifier =
            GrantedSessionNotifier({ hostServices.sessionNotifier }) { grant -> !p.community || grant in p.setting.allow }
        // DAEMON-STORAGE-1: plugin-owned tables; adopt is honoured for official / bundled only (a community adopt is refused at load).
        override val storage: com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage by lazy {
            val h = hostServices.storageHandle
            if (h == null) com.superiorsoftllc.infantry.daemon.api.storage.NoPluginStorage
            else LocalPluginStorage(h, p.id, if (p.community) emptyList() else p.declared.storage.adopt) {
                !p.community || LocalPluginStorage.GRANT in p.setting.allow
            }
        }
        // DP-10a: the project file surface; official / core, or a community module with the grant `files`.
        override fun files(projectId: String): com.superiorsoftllc.infantry.daemon.api.files.ProjectFiles {
            check(!p.community || "files" in p.setting.allow) { "missing grant 'files' in modules.json" }
            val id = projectId.trim()
            require(id.isNotEmpty()) { "expects a project id" }
            val project = hostServices.projects.list().firstOrNull { it.id == id } ?: throw IllegalArgumentException("no project '${id.take(80)}'")
            val dir = project.directory.orEmpty()
            require(dir.isNotBlank()) { "project '${project.id}' has no directory to store uploads in" }
            require(java.io.File(dir).isDirectory) { "project '${project.id}' directory does not exist: $dir" }
            return com.superiorsoftllc.infantry.daemon.api.files.DirectoryProjectFiles(java.io.File(dir))
        }
        // PLUGIN-MCP-1: read-only plugin manager view; official / core, or a community module with the grant `plugins.read`.
        override val plugins: com.superiorsoftllc.infantry.daemon.api.plugins.PluginCatalogReader get() {
            check(!p.community || "plugins.read" in p.setting.allow) { "missing grant 'plugins.read' in modules.json" }
            return hostServices.plugins
        }
        // CMD-1: the run surface; official / core, or a community module with the grant `runs`.
        override val runs: com.superiorsoftllc.infantry.daemon.api.runs.Runs get() {
            check(!p.community || "runs" in p.setting.allow) { "missing grant 'runs' in modules.json" }
            return hostServices.runs
        }
        // AR-5: typed optional hostServices. Publishing: official / core, or the grant `hostServices.provide`; lookup is open. Unload closes the handle.
        override val services: com.superiorsoftllc.infantry.daemon.api.ServicesApi =
            ScopedServices(serviceRegistry, p.id, { !p.community || ScopedServices.GRANT in p.setting.allow }) { p.closeables += it }
        // AGENT-REGISTRY-1: the agent registry, scoped to this plugin (its own declared ids; enable/disable trusted tiers only).
        override val agents: com.superiorsoftllc.infantry.daemon.api.agents.Agents = object : com.superiorsoftllc.infantry.daemon.api.agents.Agents {
            private val registry get() = hostServices.agentRegistry
            override fun list() = registry?.list().orEmpty()
            override fun get(id: String) = registry?.get(id)
            override fun contribute(definition: com.superiorsoftllc.infantry.plugin.api.AgentDefinition): AutoCloseable =
                (registry ?: error("this daemon has no agent registry")).contribute(p.id, p.community, definition).also { p.closeables += it }
            override fun setEnabled(id: String, enabled: Boolean): com.superiorsoftllc.infantry.daemon.api.agents.AgentEntry? {
                check(!p.community) { "a community plugin may not enable or disable agents" }
                return (registry ?: error("this daemon has no agent registry")).setEnabled(id, enabled)
            }
        }
        override val hooks: Hooks = object : Hooks {
            override fun frameGate(hook: FrameGateHook) { declare("frameGate"); frameGates += Registered(p, hook) }
            override fun approvalRules(hook: com.superiorsoftllc.infantry.daemon.api.ApprovalRulesHook) { declare("approvalRules"); check(!p.community) { "community modules may not own approval rules" }; approvalRuleHooks += Registered(p, hook) }
            override fun httpGate(hook: HttpGateHook) { declare("httpGate"); httpGates += Registered(p, hook) }
            override fun admission(hook: AdmissionHook) { declare("admission"); check(!p.community) { "community modules may not admit tokens" }; admissions += Registered(p, hook) }
            override fun mailGate(hook: MailGateHook) { declare("mailGate"); mailGates += Registered(p, hook) }
            override fun incomingMail(hook: IncomingMailHook) { declare("incomingMail"); incomingMailHooks += Registered(p, hook) }
            override fun spawnGate(hook: SpawnGateHook) { declare("spawnGate"); spawnGates += Registered(p, hook) }
            override fun connected(hook: com.superiorsoftllc.infantry.daemon.api.ConnectedHook) { declare("connected"); check(!p.community) { "community modules may not replay frames to clients" }; connectedHooks += Registered(p, hook) }
            override fun status(hook: com.superiorsoftllc.infantry.daemon.api.StatusHook) { check(!p.community) { "status hooks are official / core only" }; declare("status"); statusHooks += Registered(p, hook) }
            override fun ready(hook: ReadyHook) { declare("ready"); readyHooks += Registered(p, hook) }
            override fun turns(hook: TurnHook) { declare("turns"); turnHooks += Registered(p, hook) }
            override fun sessions(hook: SessionHook) { declare("sessions"); sessionHooks += Registered(p, hook) }
            override fun orchestratorBinding(providerId: String, binding: com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorBinding) { declare("orchestratorBinding"); orchestratorBindings += Registered(p, providerId to binding) }
            override fun policyProvider(hook: PresetProviderHook) { declare("policyProvider"); presetHooks += Registered(p, hook) }
        }

        private fun declare(kind: String) {
            check(kind in d.hooks) { "hook '$kind' is not declared in daemon.hooks" }
            p.hookKinds += kind
        }
    }

    companion object {
        const val PLUGIN_TOOLS = "plugin_tools"
        /** SCRIPT-3: the built-in emit tool and its policy action (kind write; open to every role). */
        const val EVENT_EMIT_TOOL = "infantry_event_emit"
        val EVENT_EMIT_ACTION = ActionCatalog.Action("events.emit", "write", listOf("plugin_tool_call"))
        /** FULLSUITE-2: the built-in full-suite runner tool and its policy action. */
        const val TESTS_TOOL = "infantry_tests"
        val TESTS_ACTION = ActionCatalog.Action("tests.run", "write", listOf("plugin_tool_call"))

        const val PLUGIN_TOOL_CALL = "plugin_tool_call"

        /** RUNTIME-LOAD-7: the client's `maxDepth` / `maxSpawns` / `depth` (whole numbers >= 0) and `clientKey`; null when it sent none. */
        internal fun narrowingOf(client: JsonObject): com.superiorsoftllc.infantry.daemon.api.spawns.SpawnNarrowing? {
            fun count(key: String) = (client[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()?.takeIf { it >= 0 }
            val key = (client["clientKey"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
            val n = com.superiorsoftllc.infantry.daemon.api.spawns.SpawnNarrowing(count("maxDepth"), count("maxSpawns"), count("depth"), key)
            return n.takeIf { it != com.superiorsoftllc.infantry.daemon.api.spawns.SpawnNarrowing.NONE }
        }
        const val PLUGIN_TOOL_RESULT = "plugin_tool_result"
        const val PLUGIN_PREFIX = "/plugins/"
        /** TRIG-2a: binds a template's `{name}` segments against a path's segments; null when it does not match. */
        internal fun matchTemplate(template: List<String>, parts: List<String>): Map<String, String>? {
            if (template.size != parts.size) return null
            val params = HashMap<String, String>()
            for (i in template.indices) {
                val s = template[i]
                if (s.length > 2 && s.startsWith("{") && s.endsWith("}")) { if (parts[i].isEmpty()) return null; params[s.substring(1, s.length - 1)] = parts[i] }
                else if (s != parts[i]) return null
            }
            return params
        }
        val HOOK_KINDS = setOf("frameGate", "connected", "httpGate", "admission", "mailGate", "incomingMail", "spawnGate", "ready", "turns", "sessions", "policyProvider", "orchestratorBinding", "seats", "approvalRules", "status")
        private val METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH")
        private val TOOL_NAME = Regex("^[a-z][a-z0-9_]{2,63}$")
        private val READY_KEY = Regex("^[a-zA-Z][a-zA-Z0-9_]{0,31}$")
        /** Frames core handles before its catalog (never a plugin's). */
        private val CORE_FRAMES = setOf(PLUGIN_TOOLS, PLUGIN_TOOL_CALL, PLUGIN_TOOL_RESULT, "replay_request", "ready", "error")
        private val CORE_ROUTES = setOf("/health", "/ws", "/plugins", "/")

        private fun key(method: String, path: String) = "${method.uppercase()} $path"

        /** The tool a `plugin_tool_call` payload names, or null. */
        fun toolName(data: String): String? = runCatching {
            ((kotlinx.serialization.json.Json.parseToJsonElement(data) as JsonObject)["name"] as JsonPrimitive).content
        }.getOrNull()

        /** No daemon plugin: every seam answers neutrally. */
        fun empty(): DaemonPluginHost = DaemonPluginHost(DaemonServices.NONE, File("."), {})

        /**
         * Loads the daemon half of every module [resolution] loaded. A module that fails a rule, its trust check or its
         * start is logged and left out (never fatal); the rest load.
         */
        fun load(
            resolution: Resolution,
            config: ModulesConfig,
            trust: Trust,
            services: DaemonServices,
            stateDir: File,
            log: (String) -> Unit = { System.err.println(it) },
        ): DaemonPluginHost {
            val host = DaemonPluginHost(services, stateDir, log)
            for (s in resolution.loaded) runCatching { host.load(s, config, trust) }
                .onFailure { host.statuses[s.name] = "refused: ${it.message}"; log("[plugins] daemon half of ${s.name} refused: ${it.message}") }
            host.startTestSchedule()
            return host
        }

        private fun Envelope.toFrame() = Frame(type, data, sessionId, requestId)
        private fun Frame.toFrame() = this
    }
}
