package com.superiorsoftllc.agenthq.daemon.plugins

import com.superiorsoftllc.agenthq.settings.LimitRegistry
import com.superiorsoftllc.agenthq.daemon.api.AdmissionHook
import com.superiorsoftllc.agenthq.daemon.api.Caller
import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.DaemonPlugin
import com.superiorsoftllc.agenthq.daemon.api.DaemonTool
import com.superiorsoftllc.agenthq.daemon.api.Events
import com.superiorsoftllc.agenthq.daemon.api.board.TicketEvent
import com.superiorsoftllc.agenthq.daemon.api.Frame
import com.superiorsoftllc.agenthq.daemon.api.FrameGateHook
import com.superiorsoftllc.agenthq.daemon.api.Frames
import com.superiorsoftllc.agenthq.daemon.api.Hooks
import com.superiorsoftllc.agenthq.daemon.api.HttpCall
import com.superiorsoftllc.agenthq.daemon.api.HttpGateHook
import com.superiorsoftllc.agenthq.daemon.api.HttpReply
import com.superiorsoftllc.agenthq.daemon.api.MailGateHook
import com.superiorsoftllc.agenthq.daemon.api.Policy
import com.superiorsoftllc.agenthq.daemon.api.PresetProviderHook
import com.superiorsoftllc.agenthq.daemon.api.Projects
import com.superiorsoftllc.agenthq.daemon.api.ReadyHook
import com.superiorsoftllc.agenthq.daemon.api.Routes
import com.superiorsoftllc.agenthq.daemon.api.SessionHook
import com.superiorsoftllc.agenthq.daemon.api.SessionInfo
import com.superiorsoftllc.agenthq.daemon.api.Sessions
import com.superiorsoftllc.agenthq.daemon.api.SpawnGateHook
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import com.superiorsoftllc.agenthq.daemon.api.ToolResult
import com.superiorsoftllc.agenthq.daemon.api.Tools
import com.superiorsoftllc.agenthq.daemon.api.TurnHook
import com.superiorsoftllc.agenthq.plugin.api.DaemonCapabilities
import com.superiorsoftllc.agenthq.plugin.api.ModuleSetting
import com.superiorsoftllc.agenthq.plugin.api.ModulesConfig
import com.superiorsoftllc.agenthq.plugin.api.PolicyPreset
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.ModuleCandidate
import com.superiorsoftllc.agenthq.plugin.host.ModuleStatus
import com.superiorsoftllc.agenthq.plugin.host.OfficialTrust
import com.superiorsoftllc.agenthq.plugin.host.Resolution
import com.superiorsoftllc.agenthq.plugin.host.SemVer
import com.superiorsoftllc.agenthq.policy.ActionCatalog
import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
) : AutoCloseable {

    /** One loaded daemon half. */
    inner class Loaded(
        val id: String,
        val tier: Tier,
        val declared: DaemonCapabilities,
        val setting: ModuleSetting,
        val guard: PluginGuard,
        val loader: URLClassLoader,
    ) {
        @Volatile var plugin: DaemonPlugin? = null
        val community: Boolean get() = tier == Tier.COMMUNITY
        val callTimeoutMs: Long = declared.limits.callTimeoutMs ?: services.limit(LimitRegistry.PLUGIN_CALL_TIMEOUT_MS)
        val toolTimeoutMs: Long = declared.limits.toolTimeoutMs ?: services.limit(LimitRegistry.PLUGIN_TOOL_TIMEOUT_MS)
        val routeRate: Int = declared.limits.routeRequestsPerMinute ?: services.limit(LimitRegistry.PLUGIN_ROUTE_RATE).toInt()
        val hookKinds = mutableSetOf<String>()
        val closeables = CopyOnWriteArrayList<AutoCloseable>()
        @Volatile var routeWindowStart = 0L
        @Volatile var routeCount = 0

        fun <T> call(what: String, timeoutMs: Long = callTimeoutMs, block: () -> T): Result<T> = guard.call(what, timeoutMs, block)
    }

    private class Registered<T>(val owner: Loaded, val value: T)
    private class ToolEntry(val owner: Loaded, val tool: DaemonTool, val action: ActionCatalog.Action)
    private class FrameEntry(val owner: Loaded, val action: ActionCatalog.Action, val ownerOnly: Boolean, val handler: (Frame, Caller) -> Frame?)
    private class RouteEntry(val owner: Loaded, val method: String, val path: String, val public: Boolean, val handler: (HttpCall) -> HttpReply)

    private val loaded = CopyOnWriteArrayList<Loaded>()
    private val statuses = ConcurrentHashMap<String, String>()
    private val toolEntries = ConcurrentHashMap<String, ToolEntry>()
    private val frameHandlers = ConcurrentHashMap<String, FrameEntry>()
    private val routeEntries = ConcurrentHashMap<String, RouteEntry>()
    private val frameGates = CopyOnWriteArrayList<Registered<FrameGateHook>>()
    private val orchestratorBindings = CopyOnWriteArrayList<Pair<String, com.superiorsoftllc.agenthq.daemon.api.orchestrator.OrchestratorBinding>>()
    private val httpGates = CopyOnWriteArrayList<Registered<HttpGateHook>>()
    private val admissions = CopyOnWriteArrayList<Registered<AdmissionHook>>()
    private val mailGates = CopyOnWriteArrayList<Registered<MailGateHook>>()
    private val spawnGates = CopyOnWriteArrayList<Registered<SpawnGateHook>>()
    private val readyHooks = CopyOnWriteArrayList<Registered<ReadyHook>>()
    private val turnHooks = CopyOnWriteArrayList<Registered<TurnHook>>()
    private val sessionHooks = CopyOnWriteArrayList<Registered<SessionHook>>()
    private val presetHooks = CopyOnWriteArrayList<Registered<PresetProviderHook>>()
    private val eventListeners = CopyOnWriteArrayList<Triple<Loaded, String, (Frame) -> Unit>>()
    private val seatClaimants = CopyOnWriteArrayList<Loaded>()
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
            if (g.owner.guard.disabled) { if (path == "/health") continue; return failClosed(g.owner, "http gate") }
            val r = g.owner.call("httpGate($method $path)") { g.value.refusal(method, path, webAsset) }
            if (r.isFailure) { if (path == "/health") continue; return failClosed(g.owner, "http gate") }
            r.getOrNull()?.let { return it }
        }
        return null
    }

    /** The guest bearer tokens admitted now (official/core admission hooks only); fail open (none). */
    /** ORCH-1: the orchestrator bindings plugins offered, for the daemon to register on its hub. */
    fun orchestratorBindings(): List<Pair<String, com.superiorsoftllc.agenthq.daemon.api.orchestrator.OrchestratorBinding>> = orchestratorBindings.toList()

    fun guestTokens(): List<String> = admissions.flatMap { a ->
        if (a.owner.guard.disabled) emptyList() else a.owner.call("admission") { a.value.guestTokens() }.getOrDefault(emptyList())
    }

    fun mailRefusal(from: String, to: String, allowCrossProject: Boolean): String? {
        for (g in mailGates) {
            if (g.owner.guard.disabled) return failClosed(g.owner, "mail gate")
            val r = g.owner.call("mailGate") { g.value.refusal(from, to, allowCrossProject) }
            if (r.isFailure) return failClosed(g.owner, "mail gate")
            r.getOrNull()?.let { return it }
        }
        return null
    }

    fun spawnRefusal(role: String?, projectId: String?): String? {
        for (g in spawnGates) {
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
        PLUGIN_TOOL_CALL -> toolName(envelope.data)?.let { toolEntries[it]?.action }
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

    /** `plugin_tools`: every healthy plugin tool, as MCP `tools/list` entries. */
    fun toolDescriptors(): JsonArray = JsonArray(
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
                if (e.tool.localFiles.isNotEmpty()) put(com.superiorsoftllc.agenthq.daemon.api.LocalFiles.DESCRIPTOR_KEY, JsonArray(e.tool.localFiles.map { JsonPrimitive(it) }))
            })
        },
    )

    /**
     * `plugin_tool_call`: runs tool [name] with [arguments] as [caller]; [request] dispatches a frame on the caller's own
     * connection and waits for its answer. The policy has already gated the call by the tool's action.
     */
    fun callTool(name: String, arguments: JsonObject, caller: Caller, client: JsonObject = JsonObject(emptyMap()), request: (Envelope, Long) -> Envelope): ToolResult {
        val entry = toolEntries[name] ?: return ToolResult("Unknown tool: $name", isError = true)
        if (entry.owner.guard.disabled) return ToolResult("$name is unavailable: plugin ${entry.owner.id} is disabled", isError = true)
        val call = object : ToolCall {
            override val arguments = arguments
            override val caller = caller
            override val clientSessionId = (client["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            override val clientRole = (client["role"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            override val clientProjectId = (client["projectId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame =
                request(Envelope(type, data, sessionId), timeoutMs).toFrame()
        }
        return entry.owner.call("tool($name)", toolTimeoutMs(entry.owner, entry.tool)) { entry.tool.call(call) }
            .getOrElse { ToolResult("$name failed: ${it.message ?: it::class.simpleName}", isError = true) }
    }

    // ---- routes -----------------------------------------------------------------------------------------------------

    /** The absolute routes plugins answer (`METHOD path`), registered on the server once. */
    fun topLevelRoutes(): List<Pair<String, String>> = routeEntries.values.filter { it.path.startsWith("/") && !it.path.startsWith(PLUGIN_PREFIX) }
        .map { it.method to it.path }.distinct()

    /** True when `[method] [path]` is a plugin's declared public route (answered without the bearer token). */
    fun isPublicRoute(method: String, path: String): Boolean = routeEntries[key(method, path)]?.let { it.public && !it.owner.guard.disabled } == true

    /** The answer of a plugin route, or null when no plugin answers it (a 404 for the caller). */
    fun answerRoute(call: HttpCall): HttpReply? {
        val entry = routeEntries[key(call.method, call.path)] ?: return null
        if (entry.owner.guard.disabled) return HttpReply(503, "plugin ${entry.owner.id} is disabled")
        val owner = entry.owner
        synchronized(owner) {
            val now = System.currentTimeMillis()
            if (now - owner.routeWindowStart > 60_000) { owner.routeWindowStart = now; owner.routeCount = 0 }
            if (++owner.routeCount > owner.routeRate && owner.routeRate > 0) return HttpReply(429, "plugin ${owner.id}: more than ${owner.routeRate} requests a minute")
        }
        return owner.call("route(${call.method} ${call.path})") { entry.handler(call) }
            .getOrElse { HttpReply(500, "plugin ${owner.id} failed: ${it.message ?: it::class.simpleName}") }
    }

    override fun close() {
        for (p in loaded.reversed()) {
            p.closeables.forEach { runCatching { it.close() } }
            if (!p.guard.disabled) p.call("stop", services.limit(LimitRegistry.PLUGIN_STOP_TIMEOUT_MS)) { p.plugin?.stop() }
            p.guard.shutdown()
            runCatching { p.loader.close() }
        }
        observerQueue.shutdownNow()
    }

    // ---- loading ----------------------------------------------------------------------------------------------------

    private fun failClosed(owner: Loaded, what: String): String = "refused: plugin ${owner.id}'s $what failed (fail closed)"

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
        val guard = PluginGuard(id, loader, cpu, log, onDisabled = { unregister(p) })
        p = Loaded(id, status.tier, d, setting, guard, loader)
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
        private val d = p.declared
        override val moduleId = p.id
        override val tier = p.tier.name.lowercase()
        override val apiVersion = DaemonApi.VERSION
        override val stateDir: File by lazy { File(this@DaemonPluginHost.stateDir, "plugin-data/${p.id}").apply { mkdirs() } }

        override fun log(line: String) = this@DaemonPluginHost.log("[plugin ${p.id}] $line")
        override fun config(key: String): String? = services.config(p.id, key)
        override fun env(name: String): String? = if (name in d.env) services.env(name) else { log("env $name is not declared in daemon.env: refused"); null }
        override fun secret(name: String): String? = if (name in d.secrets) services.secret(name) else { log("secret $name is not declared in daemon.secrets: refused"); null }

        override fun schedule(name: String, periodMs: Long, job: () -> Unit): AutoCloseable {
            val period = periodMs.coerceAtLeast(1_000)
            val timer = java.util.Timer("plugin-${p.id}-$name", true)
            timer.scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() { if (p.guard.disabled) cancel() else p.call("schedule($name)") { job() } }
            }, period, period)
            return AutoCloseable { timer.cancel() }.also { p.closeables += it }
        }

        override val projects: Projects get() = services.projects
        override val sessions: Sessions get() = services.sessions
        override val policy: Policy = object : Policy {
            override fun active() = services.policy().active()
            override fun onChange(listener: (PolicyPreset) -> Unit): AutoCloseable =
                services.policy().onChange { preset -> if (!p.guard.disabled) p.call("policy.onChange", services.limit(LimitRegistry.PLUGIN_START_TIMEOUT_MS)) { listener(preset) } }.also { p.closeables += it }
            override fun actionOf(frame: Frame) = services.policy().actionOf(frame)
            override fun refusal(frame: Frame) = services.policy().refusal(frame)
            override fun refusalText(type: String) = services.policy().refusalText(type)
            override fun reportSeating(error: String?) = services.policy().reportSeating(error)
            override fun claimSeats() { declare("seats"); if (p !in seatClaimants) seatClaimants += p }
        }
        override val events: Events = object : Events {
            override fun on(type: String, listener: (Frame) -> Unit): AutoCloseable {
                val entry = Triple(p, type, listener)
                eventListeners += entry
                return AutoCloseable { eventListeners -= entry }.also { p.closeables += it }
            }

            // DP-4b: the typed ticket events. A community plugin needs tickets.read (a throw here disables the plugin, as a bad declaration does).
            override fun onTicket(consumer: String, listener: (TicketEvent) -> Unit): AutoCloseable {
                require(consumer.isNotBlank()) { "a ticket event consumer needs a name" }
                return granted.ticketEvents { services.ticketEvents }.subscribe("plugin:${p.id}:$consumer") { e ->
                    if (!p.guard.disabled) p.call("events.onTicket($consumer)") { listener(e) }.getOrThrow()
                }.also { p.closeables += it }
            }
        }
        override val tools: Tools = object : Tools {
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
                check(ActionCatalog.forFrame(type) == null && type !in CORE_FRAMES) { "frame '$type' is a core frame" }
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
        private val granted = GrantedBoard({ services.tickets }, { services.epics }, { services.board }) { grant -> !p.community || grant in p.setting.allow }
        override val tickets get() = granted.ticketsApi
        override val epics get() = granted.epicsApi
        override val board get() = granted.boardApi
        // RUNTIME-LOAD-5: the sessions read model behind this plugin's grants (community: default deny).
        override val sessionReads: com.superiorsoftllc.agenthq.daemon.api.sessions.SessionReads =
            GrantedSessionReads({ services.sessionReads }) { grant -> !p.community || grant in p.setting.allow }
        // AGENT-REGISTRY-1: the agent registry, scoped to this plugin (its own declared ids; enable/disable trusted tiers only).
        override val agents: com.superiorsoftllc.agenthq.daemon.api.agents.Agents = object : com.superiorsoftllc.agenthq.daemon.api.agents.Agents {
            private val registry get() = services.agentRegistry
            override fun list() = registry?.list().orEmpty()
            override fun get(id: String) = registry?.get(id)
            override fun contribute(definition: com.superiorsoftllc.agenthq.plugin.api.AgentDefinition): AutoCloseable =
                (registry ?: error("this daemon has no agent registry")).contribute(p.id, p.community, definition).also { p.closeables += it }
            override fun setEnabled(id: String, enabled: Boolean): com.superiorsoftllc.agenthq.daemon.api.agents.AgentEntry? {
                check(!p.community) { "a community plugin may not enable or disable agents" }
                return (registry ?: error("this daemon has no agent registry")).setEnabled(id, enabled)
            }
        }
        override val hooks: Hooks = object : Hooks {
            override fun frameGate(hook: FrameGateHook) { declare("frameGate"); frameGates += Registered(p, hook) }
            override fun httpGate(hook: HttpGateHook) { declare("httpGate"); httpGates += Registered(p, hook) }
            override fun admission(hook: AdmissionHook) { declare("admission"); check(!p.community) { "community modules may not admit tokens" }; admissions += Registered(p, hook) }
            override fun mailGate(hook: MailGateHook) { declare("mailGate"); mailGates += Registered(p, hook) }
            override fun spawnGate(hook: SpawnGateHook) { declare("spawnGate"); spawnGates += Registered(p, hook) }
            override fun ready(hook: ReadyHook) { declare("ready"); readyHooks += Registered(p, hook) }
            override fun turns(hook: TurnHook) { declare("turns"); turnHooks += Registered(p, hook) }
            override fun sessions(hook: SessionHook) { declare("sessions"); sessionHooks += Registered(p, hook) }
            override fun orchestratorBinding(providerId: String, binding: com.superiorsoftllc.agenthq.daemon.api.orchestrator.OrchestratorBinding) { declare("orchestratorBinding"); orchestratorBindings += providerId to binding }
            override fun policyProvider(hook: PresetProviderHook) { declare("policyProvider"); presetHooks += Registered(p, hook) }
        }

        private fun declare(kind: String) {
            check(kind in d.hooks) { "hook '$kind' is not declared in daemon.hooks" }
            p.hookKinds += kind
        }
    }

    companion object {
        const val PLUGIN_TOOLS = "plugin_tools"
        const val PLUGIN_TOOL_CALL = "plugin_tool_call"
        const val PLUGIN_TOOL_RESULT = "plugin_tool_result"
        const val PLUGIN_PREFIX = "/plugins/"
        val HOOK_KINDS = setOf("frameGate", "httpGate", "admission", "mailGate", "spawnGate", "ready", "turns", "sessions", "policyProvider", "orchestratorBinding", "seats")
        private val METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH")
        private val TOOL_NAME = Regex("^[a-z][a-z0-9_]{2,63}$")
        private val READY_KEY = Regex("^[a-zA-Z][a-zA-Z0-9_]{0,31}$")
        /** Frames core handles before its catalog (never a plugin's). */
        private val CORE_FRAMES = setOf(PLUGIN_TOOLS, PLUGIN_TOOL_CALL, PLUGIN_TOOL_RESULT, "replay_request", "ready", "error")
        private val CORE_ROUTES = setOf("/health", "/ws", "/plugins", "/attention", "/")

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
            return host
        }

        private fun Envelope.toFrame() = Frame(type, data, sessionId, requestId)
        private fun Frame.toFrame() = this
    }
}
